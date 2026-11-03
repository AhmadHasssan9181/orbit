package webrtc

import dev.onvoid.webrtc.media.video.CustomVideoSource
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.desktop.DesktopCaptureCallback
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer
import dev.onvoid.webrtc.media.video.desktop.DesktopSource
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import livekit.StreamDiag

/**
 * Desktop capture paced by us rather than by the native capture thread.
 *
 * Capturing runs on a dedicated high-priority capture thread, while frame
 * scaling and pushing run on a pipelined push thread so WebRTC push/encoding
 * does not block the next capture deadline.
 */
internal class PacedDesktopVideoSource(
    private val sourceId: Long,
    private val sourceIsWindow: Boolean,
    private val maxFrameWidth: Int,
    private val maxFrameHeight: Int,
    frameRate: Int,
) {
    val videoSource = CustomVideoSource()

    private val frameRate = frameRate.coerceIn(1, 60)
    private val started = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private val capturedFrames = AtomicLong()
    private val pushedFrames = AtomicLong()
    private val captureRequests = AtomicLong()
    private val captureCallbacks = AtomicLong()
    private val captureFailures = AtomicLong()
    private val smallFrames = AtomicLong()
    private val wrongFormatFrames = AtomicLong()
    private val scaledDropFrames = AtomicLong()
    private val pushFailures = AtomicLong()
    private val lateScheduleCount = AtomicLong()
    private val captureCallNanos = AtomicLong()
    private val captureCallMaxNanos = AtomicLong()
    private val colorNanos = AtomicLong()
    private val colorMaxNanos = AtomicLong()
    private val pushNanos = AtomicLong()
    private val pushMaxNanos = AtomicLong()
    private val callbackGapNanos = AtomicLong()
    private val callbackGapMaxNanos = AtomicLong()
    private val lastCallbackNs = AtomicLong()
    private val lastStatsNs = AtomicLong()

    @Volatile
    private var captureThread: Thread? = null

    @Volatile
    private var pushThread: Thread? = null

    @Volatile
    private var lastFrame: VideoFrame? = null

    @Volatile
    private var failureLoggedAtNs = 0L

    private var loggedWidth = 0
    private var loggedHeight = 0

    private class FrameToPush(val frame: VideoFrame, val buffer: I420Buffer)

    private val pushQueue = ArrayBlockingQueue<FrameToPush>(1)

    private fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        if (maxFrameWidth >= width && maxFrameHeight >= height) return width to height

        var targetWidth = width
        var targetHeight = height
        if (maxFrameWidth in 1 until targetWidth) {
            val scale = maxFrameWidth.toDouble() / targetWidth
            targetWidth = maxFrameWidth
            targetHeight = (targetHeight * scale).toInt().coerceAtLeast(1)
        } else if (maxFrameHeight in 1 until targetHeight) {
            val scale = maxFrameHeight.toDouble() / targetHeight
            targetWidth = (targetWidth * scale).toInt().coerceAtLeast(1)
            targetHeight = maxFrameHeight
        }
        return targetWidth to targetHeight
    }

    private fun pushFrame(frame: VideoFrame, buffer: I420Buffer): Boolean {
        val (targetWidth, targetHeight) = targetSize(buffer.width, buffer.height)
        if (targetWidth == buffer.width && targetHeight == buffer.height) {
            videoSource.pushFrame(frame)
            pushedFrames.incrementAndGet()
            return true
        }

        val scaled = buffer.cropAndScale(
            0,
            0,
            buffer.width,
            buffer.height,
            targetWidth,
            targetHeight,
        ) as? I420Buffer ?: return false
        val scaledFrame = VideoFrame(scaled, frame.rotation, frame.timestampNs)
        try {
            videoSource.pushFrame(scaledFrame)
            pushedFrames.incrementAndGet()
            return true
        } finally {
            runCatching { scaledFrame.release() }
        }
    }

    private fun releaseLastFrame() {
        lastFrame?.let { runCatching { it.release() } }
        lastFrame = null
    }

    private fun enqueueFrame(frame: VideoFrame, buffer: I420Buffer) {
        val item = FrameToPush(frame, buffer)
        if (!pushQueue.offer(item)) {
            // Queue full: drop older unpushed frame so latency does not build up.
            pushQueue.poll()?.let {
                runCatching { it.frame.release() }
                scaledDropFrames.incrementAndGet()
            }
            if (!pushQueue.offer(item)) {
                runCatching { frame.release() }
                scaledDropFrames.incrementAndGet()
            }
        }
    }

    private fun pushLoop() {
        while (running.get()) {
            val item = try {
                pushQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                break
            }
            val pushStart = System.nanoTime()
            val pushed = try {
                pushFrame(item.frame, item.buffer)
            } catch (t: Throwable) {
                pushFailures.incrementAndGet()
                false
            } finally {
                runCatching { item.frame.release() }
            }
            val pushElapsed = System.nanoTime() - pushStart
            pushNanos.addAndGet(pushElapsed)
            updateMax(pushMaxNanos, pushElapsed)
            if (!pushed) scaledDropFrames.incrementAndGet()
        }
    }

    private val captureCallback = object : DesktopCaptureCallback {
        override fun onCaptureResult(result: DesktopCapturer.Result, frame: VideoFrame?) {
            val callbackNs = System.nanoTime()
            captureCallbacks.incrementAndGet()
            val previousCallbackNs = lastCallbackNs.getAndSet(callbackNs)
            if (previousCallbackNs != 0L) {
                val gap = callbackNs - previousCallbackNs
                callbackGapNanos.addAndGet(gap)
                updateMax(callbackGapMaxNanos, gap)
            }

            if (result != DesktopCapturer.Result.SUCCESS || frame == null) {
                captureFailures.incrementAndGet()
                runCatching { frame?.release() }
                return
            }

            try {
                if (!running.get()) return
                val buffer = frame.buffer as? I420Buffer
                if (buffer == null) {
                    wrongFormatFrames.incrementAndGet()
                    return
                }
                if (buffer.width <= 1 || buffer.height <= 1) {
                    // A minimized window reports 1x1. Reuse the last real frame.
                    smallFrames.incrementAndGet()
                    val previous = lastFrame ?: return
                    val previousBuffer = previous.buffer as? I420Buffer ?: return
                    previous.retain()
                    enqueueFrame(previous, previousBuffer)
                    return
                }

                capturedFrames.incrementAndGet()
                if (buffer.width != loggedWidth || buffer.height != loggedHeight) {
                    loggedWidth = buffer.width
                    loggedHeight = buffer.height
                    val target = targetSize(buffer.width, buffer.height)
                    StreamDiag.log(
                        "phase=captureFrame source=${buffer.width}x${buffer.height} " +
                            "target=${target.first}x${target.second} fps=$frameRate"
                    )
                }

                // Keep one reference so a later 1x1 capture can reuse this frame.
                frame.retain()
                lastFrame?.let { runCatching { it.release() } }
                lastFrame = frame

                // Hand off to push thread without blocking the capture loop
                frame.retain()
                enqueueFrame(frame, buffer)
            } catch (t: Throwable) {
                captureFailures.incrementAndGet()
                val now = System.nanoTime()
                if (now - failureLoggedAtNs >= 2_000_000_000L) {
                    failureLoggedAtNs = now
                    println("WARN [ScreenCapture] frame processing failed: ${t.message}")
                }
            } finally {
                runCatching { frame.release() }
            }
        }
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }

    fun start() {
        check(started.compareAndSet(false, true)) { "screen capture already started" }
        running.set(true)
        lastStatsNs.set(System.nanoTime())

        val pusher = Thread(
            {
                pushLoop()
            },
            "orbit-screen-push",
        ).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        pushThread = pusher
        pusher.start()

        val setup = CountDownLatch(1)
        val setupError = AtomicReference<Throwable?>()
        val thread = Thread(
            {
                var capturer: DesktopCapturer? = null
                try {
                    capturer = if (sourceIsWindow) WindowCapturer() else ScreenCapturer()
                    capturer.setMaxFrameRate(frameRate)
                    capturer.setFocusSelectedSource(true)
                    capturer.selectSource(DesktopSource("", sourceId))
                    capturer.start(captureCallback)

                    setup.countDown()
                    captureLoop(capturer)
                } catch (t: Throwable) {
                    setupError.set(t)
                    setup.countDown()
                    if (running.get()) {
                        println("WARN [ScreenCapture] capture thread stopped: ${t.message}")
                    }
                } finally {
                    running.set(false)
                    releaseLastFrame()
                    runCatching { capturer?.dispose() }
                }
            },
            "orbit-screen-capture",
        ).apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }

        captureThread = thread
        thread.start()

        if (!setup.await(5, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("screen capture did not start within 5s")
        }
        setupError.get()?.let {
            stop()
            throw IllegalStateException("screen capture failed to start: ${it.message}", it)
        }
        StreamDiag.log(
            "phase=captureStart sourceId=$sourceId window=$sourceIsWindow " +
                "fps=$frameRate maxFrame=${maxFrameWidth}x$maxFrameHeight"
        )
        println("DEBUG [ScreenCapture] started sourceId=$sourceId isWindow=$sourceIsWindow fps=$frameRate")
    }

    private fun captureLoop(capturer: DesktopCapturer) {
        val periodNs = 1_000_000_000L / frameRate
        var deadlineNs = System.nanoTime()

        while (running.get()) {
            captureRequests.incrementAndGet()
            val captureCallStart = System.nanoTime()
            try {
                capturer.captureFrame()
            } catch (t: Throwable) {
                captureFailures.incrementAndGet()
                val now = System.nanoTime()
                if (now - failureLoggedAtNs >= 2_000_000_000L) {
                    failureLoggedAtNs = now
                    println("WARN [ScreenCapture] captureFrame failed: ${t.message}")
                }
            }
            val captureCallElapsed = System.nanoTime() - captureCallStart
            captureCallNanos.addAndGet(captureCallElapsed)
            updateMax(captureCallMaxNanos, captureCallElapsed)

            reportStatsIfDue()

            deadlineNs += periodNs
            val now = System.nanoTime()
            if (deadlineNs <= now) {
                lateScheduleCount.incrementAndGet()
                // If a capture ran long, do not emit a burst of catch-up frames.
                if (now - deadlineNs >= periodNs) deadlineNs = now + periodNs
            } else {
                LockSupport.parkNanos(deadlineNs - now)
            }
        }
    }

    private fun reportStatsIfDue() {
        val now = System.nanoTime()
        val previous = lastStatsNs.get()
        if (previous != 0L && now - previous < 1_000_000_000L) return

        if (!lastStatsNs.compareAndSet(previous, now)) return
        val elapsedNs = if (previous == 0L) 1_000_000_000L else (now - previous).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0

        val requests = captureRequests.getAndSet(0)
        val callbacks = captureCallbacks.getAndSet(0)
        val captured = capturedFrames.getAndSet(0)
        val pushed = pushedFrames.getAndSet(0)
        val failures = captureFailures.getAndSet(0)
        val small = smallFrames.getAndSet(0)
        val wrongFormat = wrongFormatFrames.getAndSet(0)
        val scaleDrop = scaledDropFrames.getAndSet(0)
        val pushFail = pushFailures.getAndSet(0)
        val lateSchedule = lateScheduleCount.getAndSet(0)
        val captureCallNs = captureCallNanos.getAndSet(0)
        val captureCallMax = captureCallMaxNanos.getAndSet(0)
        val colorNs = colorNanos.getAndSet(0)
        val colorMax = colorMaxNanos.getAndSet(0)
        val pushNs = pushNanos.getAndSet(0)
        val pushMax = pushMaxNanos.getAndSet(0)
        val gapNs = callbackGapNanos.getAndSet(0)
        val gapMax = callbackGapMaxNanos.getAndSet(0)

        StreamDiag.log(
            "phase=capture targetFps=$frameRate " +
                "captureFps=${oneDecimal(captured / elapsedSec)} " +
                "pushFps=${oneDecimal(pushed / elapsedSec)} " +
                "requests=$requests callbacks=$callbacks captured=$captured pushed=$pushed " +
                "pending=${(requests - callbacks).coerceAtLeast(0)} " +
                "captureFail=$failures small=$small wrongFormat=$wrongFormat " +
                "scaleDrop=$scaleDrop pushFail=$pushFail lateSchedule=$lateSchedule " +
                "captureCallAvgMs=${millis(captureCallNs, requests)} " +
                "captureCallMaxMs=${millis(captureCallMax, 1L)} " +
                "colorAvgMs=${millis(colorNs, captured)} colorMaxMs=${millis(colorMax, 1L)} " +
                "pushAvgMs=${millis(pushNs, pushed + pushFail)} pushMaxMs=${millis(pushMax, 1L)} " +
                "callbackGapAvgMs=${millis(gapNs, (callbacks - 1).coerceAtLeast(0L))} " +
                "callbackGapMaxMs=${millis(gapMax, 1L)} " +
                "sourceId=$sourceId window=$sourceIsWindow"
        )
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun millis(nanos: Long, samples: Long): String =
        String.format(Locale.US, "%.2f", nanos / 1_000_000.0 / samples.coerceAtLeast(1L))

    fun stop() {
        if (!started.get()) return
        running.set(false)
        while (true) {
            val item = pushQueue.poll() ?: break
            runCatching { item.frame.release() }
        }
        val pusher = pushThread
        if (pusher != null && pusher !== Thread.currentThread()) {
            pusher.interrupt()
            runCatching { pusher.join(2_000) }
        }
        pushThread = null

        val thread = captureThread ?: return
        if (thread === Thread.currentThread()) return
        LockSupport.unpark(thread)
        thread.interrupt()
        runCatching { thread.join(5_000) }
            .onFailure { println("WARN [ScreenCapture] stop join failed: ${it.message}") }
        if (thread.isAlive) {
            println("WARN [ScreenCapture] capture thread still alive after stop")
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        stop()
        val thread = captureThread
        if (thread != null && thread.isAlive) {
            // Disposing the native source under a live capture callback would be
            // a use-after-free. The thread owns the capturer and will release it.
            println("WARN [ScreenCapture] source left undisposed: capture thread is still alive")
            return
        }
        runCatching { videoSource.dispose() }
        captureThread = null
    }
}
