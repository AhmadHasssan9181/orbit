package livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object LiveKitNativeAudioCapture {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val FRAME_MS = 10
    const val SAMPLES_PER_CHANNEL = 480
    const val FRAME_BYTES = 960

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    @Volatile private var activeLine: TargetDataLine? = null
    @Volatile private var started = false
    @Volatile private var muted = false
    @Volatile private var sessionId = 0L
    @Volatile private var epoch = 0L
    @Volatile private var bridge: OrbitLiveKitNativeBridge? = null
    @Volatile private var failureSink: ((NativeError) -> Unit)? = null

    fun start(
        sessionId: Long,
        epoch: Long,
        nativeBridge: OrbitLiveKitNativeBridge,
        onFailure: (NativeError) -> Unit
    ): Boolean {
        if (started) return true

        LiveKitNativeAudioBridge.resetFrameDropLog()
        if (!LiveKitNativeAudioBridge.acceptingFrames) {
            println("ERROR [LiveKitNativeAudioCapture] Native C++ audio is not accepting frames")
            return false
        }

        this.sessionId = sessionId
        this.epoch = epoch
        bridge = nativeBridge
        failureSink = onFailure
        started = true
        captureJob = scope.launch {
            runCaptureLoop()
        }
        return true
    }

    private fun requestStop(): Job? {
        val job = captureJob
        started = false
        activeLine?.let { line ->
            runCatching { line.stop() }
            runCatching { line.close() }
        }
        job?.cancel()
        captureJob = null
        sessionId = 0L
        epoch = 0L
        bridge = null
        failureSink = null
        return job
    }

    fun stop() {
        requestStop()
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    fun stopAndJoin() {
        val job = requestStop()
        if (job != null) {
            runBlocking {
                job.cancelAndJoin()
            }
        }
    }

    private suspend fun runCaptureLoop() {
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            println("ERROR [LiveKitNativeAudioCapture] Audio line not supported")
            started = false
            return
        }

        val heapBuffer = ByteArray(FRAME_BYTES)
        val directBuffer = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var consecutivePushFailures = 0

        val line = AudioSystem.getLine(info) as TargetDataLine
        activeLine = line
        try {
            line.open(format)
            line.start()
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop started")

            while (started && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val read = line.read(heapBuffer, 0, heapBuffer.size)
                if (read != FRAME_BYTES) continue
                if (muted) {
                    heapBuffer.fill(0, 0, read)
                }

                if (!LiveKitNativeAudioBridge.acceptingFrames) {
                    continue
                }

                directBuffer.clear()
                directBuffer.put(heapBuffer, 0, read)
                directBuffer.flip()

                val result = bridge?.pushAudioFrame(
                    sessionId,
                    epoch,
                    directBuffer,
                    SAMPLE_RATE,
                    CHANNELS,
                    SAMPLES_PER_CHANNEL,
                    System.nanoTime()
                )
                if (result == null || result.ok) {
                    consecutivePushFailures = 0
                    continue
                }

                consecutivePushFailures += 1
                if (consecutivePushFailures >= 3) {
                    val error = result.error ?: NativeError(
                        code = NativeErrorCode.AUDIO_CAPTURE_FAILED,
                        message = "Native audio frame push failed",
                        operation = "nativePushAudioFrame",
                        recoverable = true
                    )
                    failureSink?.invoke(error)
                    break
                }
            }
        } catch (error: Throwable) {
            if (started) {
                println("ERROR [LiveKitNativeAudioCapture] Capture failed: ${error.message}")
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            if (activeLine === line) {
                activeLine = null
            }
            started = false
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop stopped")
        }
    }
}
