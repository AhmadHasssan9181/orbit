package livekit

import com.sun.jna.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlinx.coroutines.*

object LiveKitAudioSink {
    private class TrackPlayer(val trackSid: String, val sampleRate: Int, val numChannels: Int) {
        val queue = LinkedBlockingQueue<ByteArray>(100)
        var line: SourceDataLine? = null
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        var job: Job? = null

        fun start() {
            try {
                val format = AudioFormat(sampleRate.toFloat(), 16, numChannels, true, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) {
                    println("ERROR [LiveKitAudioSink] Audio line not supported for format: $format")
                    return
                }
                line = (AudioSystem.getLine(info) as SourceDataLine).also {
                    val bufferSize = sampleRate * numChannels * 2 / 4 // 250ms buffer
                    it.open(format, bufferSize)
                    it.start()
                }
                println("DEBUG [LiveKitAudioSink] Opened and started audio playback for track $trackSid ($sampleRate-$numChannels)")
                job = scope.launch {
                    while (isActive) {
                        val pcm = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                        val out = line ?: continue
                        runCatching { out.write(pcm, 0, pcm.size) }
                    }
                }
            } catch (e: Exception) {
                println("ERROR [LiveKitAudioSink] Failed to start audio player: ${e.message}")
            }
        }

        fun stop() {
            job?.cancel()
            job = null
            queue.clear()
            runCatching { line?.stop() }
            runCatching { line?.close() }
            line = null
            scope.cancel()
        }
    }

    private val activePlayers = ConcurrentHashMap<String, TrackPlayer>()

    fun pushFrame(
        trackSid: String,
        sampleRate: Int,
        numChannels: Int,
        samplesPerChannel: Int,
        dataPtr: Long
    ) {
        var player = activePlayers[trackSid]
        if (player == null || player.sampleRate != sampleRate || player.numChannels != numChannels) {
            player?.stop()
            player = TrackPlayer(trackSid, sampleRate, numChannels).also { it.start() }
            activePlayers[trackSid] = player
        }

        if (player.line != null) {
            val lengthBytes = samplesPerChannel * numChannels * 2
            val ptr = Pointer(dataPtr)
            val buffer = ptr.getByteArray(0, lengthBytes)
            if (!player.queue.offer(buffer)) {
                player.queue.poll()
                player.queue.offer(buffer)
            }
        }
    }

    fun removeTrack(trackSid: String) {
        activePlayers.remove(trackSid)?.stop()
    }
}
