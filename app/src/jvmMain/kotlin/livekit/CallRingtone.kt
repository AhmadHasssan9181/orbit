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

    /**
     * Read by the worker thread on every 100 ms chunk, written by [setPlaying].
     *
     * It was a plain `var` with no happens-before edge between the write (inside
     * `@Synchronized`) and the read (on the worker, unsynchronized), so the loop
     * could legitimately never observe the stop and would ring until the line was
     * closed from elsewhere.
     *
     * `AtomicBoolean` rather than `@Volatile`: the value is only ever compared,
     * and an explicit atomic documents that the comparison and the flip are the
     * contract rather than an accident.
     */
    private val playing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var worker: Thread? = null

    @Synchronized
    fun setPlaying(start: Boolean) {
        if (start == playing.get()) return
        playing.set(start)
        if (start) {
            worker = thread(name = "orbit-ringtone", isDaemon = true) { runLoop() }
        } else {
            // Drop the handle as soon as we are told to stop, so the loop's
            // `worker === self` guard stops it even if the flag write is delayed.
            worker = null
        }
    }

    private fun runLoop() {
        val self = Thread.currentThread()
        var line: javax.sound.sampled.SourceDataLine? = null
        try {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            line = AudioSystem.getSourceDataLine(format)
            line.open(format, SAMPLE_RATE / 2)
            line.start()
            var phase = 0L
            while (playing.get() && worker === self) {
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
        } catch (error: Throwable) {
            println("WARN [CallRingtone] playback failed: ${error.message}")
            // Reset the flag if the DEVICE failed to open. It used to stay `true`
            // with a non-null worker, so every later `setPlaying(true)` short-
            // circuited on `start == playing` and the ringtone never sounded again
            // for the rest of the process -- one unavailable output device and the
            // feature is dead until restart.
            playing.set(false)
            synchronized(this) {
                if (worker === self) worker = null
            }
        } finally {
            runCatching { line?.stop() }
            runCatching { line?.close() }
        }
    }
}
