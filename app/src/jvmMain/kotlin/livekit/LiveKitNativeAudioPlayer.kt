package livekit

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

object LiveKitNativeAudioPlayer : LiveKitNativeAudioBridge.RemoteAudioSink {
    private val lock = Any()
    private var line: SourceDataLine? = null
    private var currentSampleRate = 0
    private var currentChannels = 0

    fun start() {
        LiveKitNativeAudioBridge.nativeSetRemoteAudioSink(this)
    }

    fun stop() {
        LiveKitNativeAudioBridge.nativeSetRemoteAudioSink(null)
        closeLine()
    }

    override fun onAudioFrame(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int
    ) {
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0 || samplesPerChannel <= 0) return

        synchronized(lock) {
            val out = ensureLine(sampleRate, channels) ?: return
            out.write(pcm, 0, pcm.size)
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
            println("ERROR [LiveKitNativeAudioPlayer] Audio output line not supported")
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
            println("ERROR [LiveKitNativeAudioPlayer] Failed to start playback: ${error.message}")
            null
        }
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
