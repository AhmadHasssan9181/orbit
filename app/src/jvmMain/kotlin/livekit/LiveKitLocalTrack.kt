package livekit

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame as WrtcVideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Screen-share frame pump for group calls: the WebRTC desktop capturer's track
 * sinks frames here and the native C++ session publishes them as a
 * SOURCE_SCREENSHARE LiveKit track (armed/disarmed by CallManager).
 */
object LiveKitLocalScreenShareTrack {
    /** Downsampled-rate copy of our own share for the local preview tile. */
    class LocalSharePreviewFrame(val buffer: ByteArray, val width: Int, val height: Int)

    val previewFrame = MutableStateFlow<LocalSharePreviewFrame?>(null)

    fun clearPreview() {
        previewFrame.value = null
    }

    private class I420Snapshot(
        val y: ByteArray, val u: ByteArray, val v: ByteArray,
        val width: Int, val height: Int,
        val strideY: Int, val strideU: Int, val strideV: Int
    )

    private val previewScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val previewChannel = Channel<I420Snapshot>(Channel.CONFLATED)

    init {
        previewScope.launch {
            for (snapshot in previewChannel) {
                // runCatching per frame: an exception escaping this loop ends
                // the only collector, and since the channel is CONFLATED later
                // snapshots would still "succeed" at trySend and be discarded
                // forever.
                val start = System.nanoTime()
                runCatching { convertI420ToRgba(snapshot) }
                    .onSuccess {
                        val elapsed = System.nanoTime() - start
                        previewFrames.incrementAndGet()
                        previewNanos.addAndGet(elapsed)
                        updateMax(previewMaxNanos, elapsed)
                        previewFrame.value = it
                        reportPreviewStatsIfDue(start, snapshot)
                    }
                    .onFailure { println("WARN [LocalSharePreview] conversion failed: ${it.message}") }
            }
        }
    }

    private var lastPreviewNs = 0L
    private var firstFrameLogged = false

    private val framesIn = AtomicLong()
    private val framesPushed = AtomicLong()
    private val pushFailures = AtomicLong()
    private val nonI420Frames = AtomicLong()
    private val packNanos = AtomicLong()
    private val packMaxNanos = AtomicLong()
    private val pushNanos = AtomicLong()
    private val pushMaxNanos = AtomicLong()
    private val frameGapNanos = AtomicLong()
    private val frameGapMaxNanos = AtomicLong()
    private val lastFrameNs = AtomicLong()
    private val lastStatsNs = AtomicLong()
    private val bytesPushed = AtomicLong()
    private val previewScheduled = AtomicLong()
    private val previewDropped = AtomicLong()
    private val previewFrames = AtomicLong()
    private val previewNanos = AtomicLong()
    private val previewMaxNanos = AtomicLong()
    private val previewLastStatsNs = AtomicLong()

    val sink = object : VideoTrackSink {
        override fun onVideoFrame(frame: WrtcVideoFrame) {
            // The capturer hands ownership of the native frame to the sink,
            // so it must be released on every path or the media engine leaks.
            // OnVoid WebRTC desktop capturers may deliver a generic VideoFrameBuffer
            // that requires .toI420() conversion.
            val frameStartNs = System.nanoTime()
            framesIn.incrementAndGet()
            val previousFrameNs = lastFrameNs.getAndSet(frameStartNs)
            if (previousFrameNs != 0L) {
                val gap = frameStartNs - previousFrameNs
                frameGapNanos.addAndGet(gap)
                updateMax(frameGapMaxNanos, gap)
            }

            var i420: I420Buffer? = null
            try {
                val rawBuffer = frame.buffer
                if (rawBuffer !is I420Buffer) nonI420Frames.incrementAndGet()
                val buffer = (rawBuffer as? I420Buffer)
                    ?: runCatching { rawBuffer?.toI420() }.getOrNull()
                    ?: return
                i420 = buffer

                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    StreamDiag.log(
                        "phase=sink firstFrame width=${buffer.width} height=${buffer.height} " +
                            "rotation=${frame.rotation} rawFormat=${rawBuffer?.javaClass?.simpleName}"
                    )
                }

                maybeEmitPreview(buffer)

                val packStart = System.nanoTime()
                val packed = packI420(buffer)
                val packElapsed = System.nanoTime() - packStart
                packNanos.addAndGet(packElapsed)
                updateMax(packMaxNanos, packElapsed)
                val bytes = packed.remaining()

                val pushStart = System.nanoTime()
                val pushed = LiveKitNativeMediaSession.pushScreenShareFrame(
                    buffer = packed,
                    width = buffer.width,
                    height = buffer.height,
                    timestampNs = frameStartNs,
                    rotation = frame.rotation
                )
                val pushElapsed = System.nanoTime() - pushStart
                pushNanos.addAndGet(pushElapsed)
                updateMax(pushMaxNanos, pushElapsed)

                if (pushed) {
                    framesPushed.incrementAndGet()
                    bytesPushed.addAndGet(bytes.toLong())
                    CallPathStats.onShareFramePushed(bytes)
                } else {
                    pushFailures.incrementAndGet()
                }
            } finally {
                if (i420 != null && i420 !== frame.buffer) {
                    runCatching { i420.release() }
                }
                runCatching { frame.release() }
                reportStatsIfDue(frameStartNs)
            }
        }
    }

    private fun updateMax(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (value <= current || target.compareAndSet(current, value)) return
        }
    }

    private fun reportStatsIfDue(now: Long) {
        val previous = lastStatsNs.get()
        if (previous != 0L && now - previous < 1_000_000_000L) return
        if (!lastStatsNs.compareAndSet(previous, now)) return
        val elapsedNs = if (previous == 0L) 1_000_000_000L else (now - previous).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0

        val inCount = framesIn.getAndSet(0)
        val pushed = framesPushed.getAndSet(0)
        val pushFail = pushFailures.getAndSet(0)
        val nonI420 = nonI420Frames.getAndSet(0)
        val packNs = packNanos.getAndSet(0)
        val packMax = packMaxNanos.getAndSet(0)
        val pushNs = pushNanos.getAndSet(0)
        val pushMax = pushMaxNanos.getAndSet(0)
        val gapNs = frameGapNanos.getAndSet(0)
        val gapMax = frameGapMaxNanos.getAndSet(0)
        val bytes = bytesPushed.getAndSet(0)
        val preview = previewScheduled.getAndSet(0)
        val previewDrop = previewDropped.getAndSet(0)

        StreamDiag.log(
            "phase=sink fpsIn=${oneDecimal(inCount / elapsedSec)} " +
                "fpsPushed=${oneDecimal(pushed / elapsedSec)} " +
                "framesIn=$inCount pushed=$pushed pushFail=$pushFail nonI420=$nonI420 " +
                "packAvgMs=${millis(packNs, inCount)} packMaxMs=${millis(packMax, 1L)} " +
                "pushAvgMs=${millis(pushNs, pushed + pushFail)} pushMaxMs=${millis(pushMax, 1L)} " +
                "preview=$preview previewDrop=$previewDrop bytes=$bytes " +
                "frameGapAvgMs=${millis(gapNs, (inCount - 1).coerceAtLeast(0L))} " +
                "frameGapMaxMs=${millis(gapMax, 1L)}"
        )
    }

    private fun oneDecimal(value: Double): String = String.format(Locale.US, "%.1f", value)

    private fun millis(nanos: Long, samples: Long): String =
        String.format(Locale.US, "%.2f", nanos / 1_000_000.0 / samples.coerceAtLeast(1L))

    private fun reportPreviewStatsIfDue(now: Long, snapshot: I420Snapshot) {
        val previous = previewLastStatsNs.get()
        if (previous == 0L) {
            previewLastStatsNs.compareAndSet(0L, now)
            return
        }
        if (now - previous < 5_000_000_000L) return
        if (!previewLastStatsNs.compareAndSet(previous, now)) return
        val elapsedNs = (now - previous).coerceAtLeast(1L)
        val elapsedSec = elapsedNs / 1_000_000_000.0
        val frames = previewFrames.getAndSet(0)
        val nanos = previewNanos.getAndSet(0)
        val max = previewMaxNanos.getAndSet(0)
        StreamDiag.log(
            "phase=preview previewFps=${oneDecimal(frames / elapsedSec)} frames=$frames " +
                "convAvgMs=${millis(nanos, frames)} convMaxMs=${millis(max, 1L)} " +
                "size=${snapshot.width}x${snapshot.height}"
        )
    }

    // Allows full stream frame rate (up to 60fps / 16.6ms) with 12ms min gap.
    private fun maybeEmitPreview(buffer: I420Buffer) {
        val now = System.nanoTime()
        if (now - lastPreviewNs < 12_000_000L) return
        lastPreviewNs = now
        val chromaWidth = (buffer.width + 1) / 2
        val chromaHeight = (buffer.height + 1) / 2
        val sent = previewChannel.trySend(
            I420Snapshot(
                y = copyBytes(buffer.dataY, buffer.strideY * buffer.height),
                u = copyBytes(buffer.dataU, buffer.strideU * chromaHeight),
                v = copyBytes(buffer.dataV, buffer.strideV * chromaHeight),
                width = buffer.width,
                height = buffer.height,
                strideY = buffer.strideY,
                strideU = buffer.strideU,
                strideV = buffer.strideV
            )
        )
        if (sent.isSuccess) previewScheduled.incrementAndGet() else previewDropped.incrementAndGet()
    }

    private fun copyBytes(source: ByteBuffer, byteCount: Int): ByteArray {
        val view = source.duplicate()
        view.rewind()
        // Always allocate the full requested size. This used to clamp to
        // view.remaining(), so a plane whose capacity was smaller than
        // stride*rows produced a SHORT array with no diagnostic -- and
        // convertI420ToRgba then indexed past its end, throwing
        // ArrayIndexOutOfBoundsException inside the preview collector, which
        // (SupervisorJob, so it did not kill the scope) left the "you are
        // sharing this" tile frozen on a stale frame for the rest of the
        // session with nothing able to restart it.
        val bytes = ByteArray(byteCount)
        val available = view.remaining().coerceAtMost(byteCount)
        if (available < byteCount) {
            println(
                "WARN [LocalSharePreview] plane is $available bytes, expected $byteCount; " +
                    "zero-filling the tail so conversion stays in bounds"
            )
        }
        view.get(bytes, 0, available)
        return bytes
    }

    // BT.601 limited-range YUV → RGBA, matching how remote frames are decoded.
    // Subsamples 1080p+ down by 2 (e.g. 1920x1080 -> 960x540) to keep conversion
    // under ~1.5ms and avoid allocating 8MB bitmaps at 60fps.
    private fun convertI420ToRgba(snapshot: I420Snapshot): LocalSharePreviewFrame {
        val scale = if (snapshot.width > 960) 2 else 1
        val w = snapshot.width / scale
        val h = snapshot.height / scale
        val rgba = ByteArray(w * h * 4)
        for (row in 0 until h) {
            val srcRow = row * scale
            val yRow = srcRow * snapshot.strideY
            val uRow = (srcRow / 2) * snapshot.strideU
            val vRow = (srcRow / 2) * snapshot.strideV
            var outIndex = row * w * 4
            for (column in 0 until w) {
                val srcCol = column * scale
                val y = (snapshot.y[yRow + srcCol].toInt() and 0xFF) - 16
                val u = (snapshot.u[uRow + (srcCol / 2)].toInt() and 0xFF) - 128
                val v = (snapshot.v[vRow + (srcCol / 2)].toInt() and 0xFF) - 128
                var r = (1191 * y + 1634 * v) shr 10
                var g = (1191 * y - 833 * v - 400 * u) shr 10
                var b = (1191 * y + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                rgba[outIndex] = r.toByte()
                rgba[outIndex + 1] = g.toByte()
                rgba[outIndex + 2] = b.toByte()
                rgba[outIndex + 3] = -1
                outIndex += 4
            }
        }
        return LocalSharePreviewFrame(rgba, w, h)
    }

    // The share track is single-instance and the native push memcpy's the buffer
    // before returning, so one direct buffer is reused across frames: a fresh
    // 3 MB direct allocation per frame forces Bits.reserveMemory into full GC
    // runs, which show up as UI freezes and audio underruns.
    private val packedLock = Any()
    private var packedBuffer: ByteBuffer? = null
    private var packedBytes = 0
    private var rowScratch = ByteArray(0)

    private fun packI420(buffer: I420Buffer): ByteBuffer = synchronized(packedLock) {
        val width = buffer.width
        val height = buffer.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val bytes = width * height + chromaWidth * chromaHeight * 2
        // Only reuse an exactly-sized buffer: the native side validates
        // capacity against the tight-plane size it expects.
        val existing = packedBuffer
        val packed = if (existing != null && packedBytes == bytes) {
            existing
        } else {
            ByteBuffer.allocateDirect(bytes).also {
                packedBuffer = it
                packedBytes = bytes
            }
        }
        val maxColumns = maxOf(width, chromaWidth)
        if (rowScratch.size < maxColumns) {
            rowScratch = ByteArray(maxColumns)
        }
        packed.clear()
        copyPlane(buffer.dataY, packed, height, width, buffer.strideY)
        copyPlane(buffer.dataU, packed, chromaHeight, chromaWidth, buffer.strideU)
        copyPlane(buffer.dataV, packed, chromaHeight, chromaWidth, buffer.strideV)
        packed.flip()
        packed
    }

    private fun copyPlane(source: ByteBuffer, destination: ByteBuffer, rows: Int, columns: Int, stride: Int) {
        val scratch = rowScratch
        // Hoisted: the old code duplicated the buffer inside the loop, i.e. once
        // per row (2160 allocations for a 1080p frame). One duplicate, mutated
        // per row. Reads capacity, not limit, so an already-positioned buffer is
        // handled the same as a fresh one.
        val rowView = source.duplicate()
        for (row in 0 until rows) {
            val rowStart = row * stride
            // Clamp rather than throw. position(rowStart + columns) with a
            // short plane throws IllegalArgumentException, and this function has
            // no catch on the capture path, so a padded/mismatched I420 plane
            // turned into a hard camera failure instead of a dropped frame.
            val from = rowStart.coerceIn(0, rowView.capacity())
            val to = (rowStart + columns).coerceIn(from, rowView.capacity())
            val count = to - from
            if (count < columns) {
                java.util.Arrays.fill(scratch, 0, columns, 0)
            }
            rowView.position(from)
            rowView.limit(to)
            rowView.get(scratch, 0, count)
            destination.put(scratch, 0, columns)
        }
    }
}
