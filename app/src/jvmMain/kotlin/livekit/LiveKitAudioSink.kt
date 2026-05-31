package livekit

import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

object LiveKitAudioSink {
    private val activeLines = ConcurrentHashMap<String, SourceDataLine>()

    fun pushFrame(
        trackSid: String,
        sampleRate: Int,
        numChannels: Int,
        samplesPerChannel: Int,
        dataPtr: Long
    ) {
        val formatKey = "$sampleRate-$numChannels"
        var line = activeLines[trackSid]

        if (line == null || line.format.sampleRate.toInt() != sampleRate || line.format.channels != numChannels) {
            line?.close()
            try {
                val format = AudioFormat(sampleRate.toFloat(), 16, numChannels, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) {
                    println("ERROR [LiveKitAudioSink] Audio line not supported for format: $format")
                    return
                }
                line = AudioSystem.getLine(info) as SourceDataLine
                line.open(format)
                line.start()
                activeLines[trackSid] = line
                println("DEBUG [LiveKitAudioSink] Opened and started audio playback for track $trackSid ($formatKey)")
            } catch (e: Exception) {
                println("ERROR [LiveKitAudioSink] Failed to open audio line: ${e.message}")
                return
            }
        }

        if (line != null) {
            val lengthBytes = samplesPerChannel * numChannels * 2
            val ptr = Pointer(dataPtr)
            val buffer = ptr.getByteArray(0, lengthBytes)
            line.write(buffer, 0, buffer.size)
        }
    }

    fun removeTrack(trackSid: String) {
        activeLines.remove(trackSid)?.apply {
            stop()
            close()
        }
    }
}
