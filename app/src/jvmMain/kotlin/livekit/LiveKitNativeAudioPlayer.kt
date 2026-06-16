package livekit

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

object LiveKitNativeAudioPlayer : NativeMixedAudioSink {
    private val lock = Any()
    private var line: SourceDataLine? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    @Volatile private var expectedSessionId = 0L
    @Volatile private var expectedEpoch = 0L
    @Volatile private var bridge: OrbitLiveKitNativeBridge? = null
    @Volatile private var errorSink: ((NativeError) -> Unit)? = null
    @Volatile private var playbackErrorReported = false

    fun start(
        sessionId: Long,
        epoch: Long,
        nativeBridge: OrbitLiveKitNativeBridge,
        onError: (NativeError) -> Unit
    ) {
        expectedSessionId = sessionId
        expectedEpoch = epoch
        bridge = nativeBridge
        errorSink = onError
        playbackErrorReported = false
        nativeBridge.setMixedAudioSink(this)
    }

    fun stop() {
        bridge?.setMixedAudioSink(null)
        bridge = null
        errorSink = null
        expectedSessionId = 0L
        expectedEpoch = 0L
        closeLine()
    }

    override fun onMixedAudioFrame(
        sessionId: Long,
        epoch: Long,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int,
        timestampNs: Long
    ) {
        if (sessionId != expectedSessionId || epoch != expectedEpoch) return
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0 || samplesPerChannel <= 0) return

        synchronized(lock) {
            val out = ensureLine(sampleRate, channels) ?: return
            runCatching {
                out.write(pcm, 0, pcm.size)
            }.onFailure { error ->
                reportPlaybackError(
                    code = NativeErrorCode.REMOTE_AUDIO_FAILED,
                    message = error.message ?: "JVM audio playback write failed"
                )
                closeLine()
            }
        }
    }

    private fun ensureLine(sampleRate: Int, channels: Int): SourceDataLine? {
        val existing = line
        if (existing != null && currentSampleRate == sampleRate && currentChannels == channels) {
            return existing
        }

        closeLine()

        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            reportPlaybackError(
                code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                message = "Audio output line not supported for ${sampleRate}Hz/${channels}ch"
            )
            return null
        }

        return runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).also { newLine ->
                newLine.open(format)
                newLine.start()
                line = newLine
                currentSampleRate = sampleRate
                currentChannels = channels
                println("DEBUG [LiveKitNativeAudioPlayer] Playback line started")
            }
        }.getOrElse { error ->
            reportPlaybackError(
                code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                message = error.message ?: "Failed to start JVM audio playback"
            )
            null
        }
    }

    private fun reportPlaybackError(code: NativeErrorCode, message: String) {
        if (playbackErrorReported) return
        playbackErrorReported = true
        val error = NativeError(
            code = code,
            message = message,
            operation = "JVM_AUDIO_PLAYBACK",
            recoverable = true
        )
        println("ERROR [LiveKitNativeAudioPlayer] ${error.code} ${error.operation}: ${error.message}")
        errorSink?.invoke(error)
    }

    private fun closeLine() {
        synchronized(lock) {
            runCatching { line?.drain() }
            runCatching { line?.stop() }
            runCatching { line?.close() }
            line = null
            currentSampleRate = 0
            currentChannels = 0
        }
    }
}
