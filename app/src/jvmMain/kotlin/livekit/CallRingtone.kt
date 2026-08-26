package livekit

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural incoming-call ringtone — classic 440+480 Hz dual tone with a
 * 2s-on / 2s-off cadence, synthesized in-process (no bundled asset). Owns a
 * dedicated SourceDataLine so it never contends with the soundboard monitor
 * or native call playback.
 */
object CallRingtone {
    private const val SAMPLE_RATE = 48000
    private const val TONE_SAMPLES = SAMPLE_RATE * 2L
    private const val CYCLE_SAMPLES = SAMPLE_RATE * 4L
    private const val FADE_SAMPLES = SAMPLE_RATE / 125L

    private var playing = false
    private var worker: Thread? = null

    @Synchronized
    fun setPlaying(start: Boolean) {
        if (start == playing) return
        playing = start
        if (start) {
            worker = thread(name = "orbit-ringtone", isDaemon = true) { runLoop() }
        }
    }

    private fun runLoop() {
        val self = Thread.currentThread()
        try {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, SAMPLE_RATE / 2)
            line.start()
            var phase = 0L
            while (playing && worker === self) {
                val chunkSamples = SAMPLE_RATE / 10
                val bytes = ByteArray(chunkSamples * 2)
                for (i in 0 until chunkSamples) {
                    val cyclePos = phase % CYCLE_SAMPLES
                    var sample = 0.0
                    if (cyclePos < TONE_SAMPLES) {
                        val t = phase.toDouble() / SAMPLE_RATE
                        val fade = min(
                            min(cyclePos, TONE_SAMPLES - cyclePos),
                            FADE_SAMPLES
                        ).toDouble() / FADE_SAMPLES
                        sample = (sin(2.0 * PI * 440.0 * t) + sin(2.0 * PI * 480.0 * t)) * 0.22 * fade
                    }
                    phase++
                    val value = (sample.coerceIn(-1.0, 1.0) * 32767.0).toInt()
                    bytes[i * 2] = (value and 0xFF).toByte()
                    bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                }
                line.write(bytes, 0, bytes.size)
            }
            line.stop()
            line.close()
        } catch (error: Throwable) {
            println("WARN [CallRingtone] playback failed: ${error.message}")
        }
    }
}
