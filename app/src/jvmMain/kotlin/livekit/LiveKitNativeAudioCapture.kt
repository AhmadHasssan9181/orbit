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

object LiveKitNativeAudioCapture {
    private const val DEFAULT_SAMPLE_RATE = 48000
    private const val DEFAULT_CHANNELS = 1
    private const val FRAME_MS = 10

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null
    @Volatile private var activeLine: TargetDataLine? = null
    @Volatile private var started = false
    @Volatile private var muted = false

    fun start(sampleRate: Int = DEFAULT_SAMPLE_RATE, channels: Int = DEFAULT_CHANNELS): Boolean {
        if (started) return true

        if (!LiveKitNativeAudioBridge.nativePublishAudio(sampleRate, channels, FRAME_MS)) {
            println("ERROR [LiveKitNativeAudioCapture] Failed to publish native C++ audio source")
            return false
        }

        started = true
        captureJob = scope.launch {
            runCaptureLoop(sampleRate, channels)
        }
        return true
    }

    fun stop() {
        started = false
        activeLine?.let { line ->
            runCatching { line.stop() }
            runCatching { line.close() }
        }
        captureJob?.cancel()
        captureJob = null
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    suspend fun stopAndJoin() {
        val job = captureJob
        stop()
        job?.cancelAndJoin()
    }

    private suspend fun runCaptureLoop(sampleRate: Int, channels: Int) {
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            println("ERROR [LiveKitNativeAudioCapture] Audio line not supported")
            started = false
            return
        }

        val frameBytes = (sampleRate * FRAME_MS / 1000) * channels * Short.SIZE_BYTES
        val heapBuffer = ByteArray(frameBytes)
        val directBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.nativeOrder())

        val line = AudioSystem.getLine(info) as TargetDataLine
        activeLine = line
        try {
            line.open(format)
            line.start()
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop started")

            while (started && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val read = line.read(heapBuffer, 0, heapBuffer.size)
                if (read <= 0) continue
                if (read % (Short.SIZE_BYTES * channels) != 0) continue
                val samplesPerChannel = read / (Short.SIZE_BYTES * channels)
                if (muted) {
                    heapBuffer.fill(0, 0, read)
                }

                directBuffer.clear()
                directBuffer.put(heapBuffer, 0, read)
                directBuffer.flip()

                LiveKitNativeAudioBridge.nativePushAudioFrame(
                    directBuffer,
                    read,
                    sampleRate,
                    channels,
                    samplesPerChannel
                )
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
