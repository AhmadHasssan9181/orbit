package livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Soundboard engine. Renders short synthesized effects to 48 kHz mono float PCM,
 * plays them locally through the default speaker output, and queues them on a
 * mix bus that [LiveKitNativeAudioCapture] folds into the outgoing mic frames —
 * so remote participants hear the effect without it passing through NS/AGC.
 *
 * On the P2P stack there is no JVM-side mic tap; the local monitor output is the
 * transmission path when "Share System Audio" is enabled (fork ADM loopback).
 *
 * Users can also upload their own sounds (WAV/AIFF/AU; hard limit of
 * [MAX_CUSTOM_SECONDS]); files are decoded + resampled to 48 kHz mono and
 * persisted under ~/.orbit/sounds so they survive restarts.
 */
object SoundboardMixer {
    const val SAMPLE_RATE = 48000
    const val MAX_CUSTOM_SECONDS = 10f

    private val busLock = ReentrantLock()
    private val pendingMix = ArrayDeque<Float>()
    private val busBoundSamples = SAMPLE_RATE * 3

    @Volatile
    private var effectVolume = 1.0f

    @Volatile
    private var monitorLine: javax.sound.sampled.SourceDataLine? = null

    private val customDir: File by lazy {
        util.OrbitPaths.soundsDir
    }
    private val customSamples = LinkedHashMap<String, FloatArray>()
    private val _customSounds = MutableStateFlow<List<SoundboardCustomSound>>(emptyList())
    val customSounds: StateFlow<List<SoundboardCustomSound>> = _customSounds.asStateFlow()

    init {
        loadPersistedCustomSounds()
    }

    fun setVolume(volume: Float) {
        effectVolume = volume.coerceIn(0f, 2f)
    }

    fun play(effectId: String) {
        val samples = render(effectId) ?: return
        val volume = effectVolume
        playLocally(samples)
        busLock.withLock {
            for (sample in samples) pendingMix.addLast(sample * volume)
            while (pendingMix.size > busBoundSamples) pendingMix.removeFirst()
        }
    }

    fun stopAll() {
        busLock.withLock { pendingMix.clear() }
    }

    /** Drains queued effect samples for one capture frame; null when nothing is playing. */
    fun drainMix(maxSamples: Int): FloatArray? {
        busLock.withLock {
            if (pendingMix.isEmpty()) return null
            val count = minOf(maxSamples, pendingMix.size)
            val out = FloatArray(count)
            for (i in 0 until count) out[i] = pendingMix.removeFirst()
            return out
        }
    }

    /**
     * Decodes an uploaded audio file into a custom effect. Returns null on success
     * or a user-presentable error message. Call from a background thread.
     */
    fun addCustomSound(displayName: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return "That file looks empty."
        val id = sanitizeCustomId(displayName)
        if (id.isBlank()) return "Please pick a name using letters or numbers."

        val samples = try {
            decodeToMono48k(bytes)
        } catch (error: Throwable) {
            return "Couldn't decode that file — WAV / AIFF / AU supported."
        }
        if (samples == null) return "Couldn't decode that file — WAV / AIFF / AU supported."
        if (samples.isEmpty()) return "That file contains no audio."
        if (samples.size > (MAX_CUSTOM_SECONDS * SAMPLE_RATE).toInt()) {
            return "Too long — keep it under ${MAX_CUSTOM_SECONDS.toInt()} seconds."
        }

        synchronized(customSamples) { customSamples[id] = samples }
        try {
            File(customDir, "$id.wav").writeBytes(bytes)
        } catch (error: Throwable) {
            println("WARN [SoundboardMixer] persisting custom sound '$id' failed: ${error.message}")
        }
        refreshCustomSoundList()
        return null
    }

    fun removeCustomSound(id: String) {
        synchronized(customSamples) { customSamples.remove(id) }
        File(customDir, "$id.wav").delete()
        refreshCustomSoundList()
    }

    private fun render(effectId: String): FloatArray? {
        synchronized(customSamples) { customSamples[effectId]?.let { return it } }
        return SoundEffects.render(effectId)
    }

    private fun sanitizeCustomId(name: String): String =
        name.substringBeforeLast('.').lowercase().map { char ->
            if (char.isLetterOrDigit()) char else '_'
        }.joinToString("").trim('_').take(48)

    private fun refreshCustomSoundList() {
        val names = synchronized(customSamples) { customSamples.keys.toList() }
        _customSounds.value = names.map { id ->
            SoundboardCustomSound(
                id = id,
                displayName = id.split('_').joinToString(" ") { part ->
                    part.replaceFirstChar { it.uppercase() }
                }
            )
        }
    }

    private fun loadPersistedCustomSounds() {
        try {
            customDir.listFiles { file -> file.extension.lowercase() in setOf("wav", "aiff", "au", "aif") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    runCatching { decodeToMono48k(file.readBytes()) }
                        .getOrNull()
                        ?.takeIf { it.isNotEmpty() && it.size <= (MAX_CUSTOM_SECONDS * SAMPLE_RATE).toInt() }
                        ?.let { samples -> synchronized(customSamples) { customSamples[file.nameWithoutExtension.lowercase()] = samples } }
                        ?: println("WARN [SoundboardMixer] skipping unloadable/over-long custom sound ${file.name}")
                }
        } catch (error: Throwable) {
            println("WARN [SoundboardMixer] scanning custom sounds failed: ${error.message}")
        }
        refreshCustomSoundList()
    }

    /** Decodes any javax.sound.sampled-supported container to 48 kHz mono float PCM. */
    private fun decodeToMono48k(bytes: ByteArray): FloatArray? {
        val sourceStream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        sourceStream.use { source ->
            val sourceFormat = source.format
            // Ask JavaSound for signed little-endian PCM at the source rate/channels first;
            // it handles container + encoding conversion, we do mono downmix + resample ourselves.
            val pcmFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.sampleRate,
                16,
                sourceFormat.channels,
                sourceFormat.channels * 2,
                sourceFormat.sampleRate,
                false
            )
            val pcm = AudioSystem.getAudioInputStream(pcmFormat, source)
            val raw = pcm.readBytes()
            val channels = pcmFormat.channels
            val sampleRate = pcmFormat.sampleRate.toInt().coerceAtLeast(1)

            val mono = FloatArray(raw.size / (2 * channels))
            for (i in mono.indices) {
                var sum = 0f
                for (ch in 0 until channels) {
                    val offset = (i * channels + ch) * 2
                    sum += ((raw[offset].toInt() and 0xFF) or (raw[offset + 1].toInt() shl 8)).toShort().toInt()
                }
                mono[i] = sum / channels / 32768f
            }

            if (sampleRate == SAMPLE_RATE) return normalize(mono)

            // Nearest-neighbor resample — fine for short one-shot effects.
            val targetLength = (mono.size.toLong() * SAMPLE_RATE / sampleRate).toInt()
            val resampled = FloatArray(targetLength)
            for (i in resampled.indices) {
                resampled[i] = mono[(i.toLong() * sampleRate / SAMPLE_RATE).toInt().coerceAtMost(mono.size - 1)]
            }
            return normalize(resampled)
        }
    }

    /** Peaks at ~0.9 so uploads neither clip nor come out whisper-quiet. */
    private fun normalize(samples: FloatArray): FloatArray {
        var peak = 0f
        for (sample in samples) peak = maxOf(peak, kotlin.math.abs(sample))
        if (peak <= 0.0001f || peak <= 0.9f) return samples
        val gain = 0.9f / peak
        for (i in samples.indices) samples[i] *= gain
        return samples
    }

    // A blocking SourceDataLine.write of the whole effect froze the caller — and
    // the caller is the Compose/EDT thread, so "applause" (~1.6 s) or "drumroll"
    // (~1.2 s) froze the window for over a second. Playback runs on this
    // single-thread executor instead.
    private val monitorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "orbit-soundboard-monitor").apply { isDaemon = true }
    }

    private fun playLocally(samples: FloatArray) {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            val value = (clamped * 32767f).toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        runCatching {
            monitorExecutor.execute { writeMonitorChunked(bytes) }
        }.onFailure { error ->
            println("WARN [SoundboardMixer] local monitor playback failed: ${error.message}")
        }
    }

    private fun writeMonitorChunked(bytes: ByteArray) {
        try {
            val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
            val line = monitorLine?.takeIf { it.isOpen } ?: AudioSystem.getSourceDataLine(format).also {
                it.open(format, SAMPLE_RATE * 2 / 5)
                it.start()
                monitorLine = it
            }
            synchronized(line) {
                var offset = 0
                while (offset < bytes.size) {
                    val chunk = minOf(line.bufferSize, bytes.size - offset)
                    line.write(bytes, offset, chunk)
                    offset += chunk
                }
            }
        } catch (error: Throwable) {
            println("WARN [SoundboardMixer] local monitor playback failed: ${error.message}")
        }
    }
}

/** Procedural synthesis of the built-in soundboard effects (no bundled assets). */
private object SoundEffects {
    private const val SR = SoundboardMixer.SAMPLE_RATE

    fun render(effectId: String): FloatArray? = when (effectId.lowercase()) {
        "applause" -> applause()
        "laugh" -> laugh()
        "airhorn" -> airhorn()
        "drumroll" -> drumroll()
        "party" -> sweep(300f, 1200f, 0.8f)
        "wow" -> wow()
        "nice" -> chime(listOf(660f, 880f))
        "ding" -> ding()
        "bruh" -> bruh()
        "music" -> chime(listOf(523.25f, 659.25f, 783.99f, 1046.5f))
        "meow" -> meow()
        "quack" -> quack()
        "join" -> chime(listOf(523.25f, 783.99f))
        "leave" -> chime(listOf(783.99f, 523.25f))
        "deafen" -> sweep(600f, 200f, 0.16f)
        "undeafen" -> sweep(200f, 600f, 0.16f)
        else -> null
    }

    private fun duration(seconds: Float): Int = (seconds * SR).toInt()

    private fun applause(): FloatArray {
        val random = Random(7)
        val out = FloatArray(duration(1.6f))
        for (i in out.indices) {
            // Dense noise bursts whose density decays towards the tail.
            val progress = i.toFloat() / out.size
            val density = if (progress < 0.15f) progress / 0.15f else 1f - (progress - 0.15f) * 0.55f
            val burst = if (random.nextFloat() < density * 0.35f) random.nextFloat() * 2f - 1f else 0f
            out[i] = burst * 0.45f * density
        }
        return out
    }

    private fun laugh(): FloatArray {
        val syllables = listOf(230f, 205f, 185f, 165f)
        val perSyllable = duration(0.22f)
        val out = FloatArray(perSyllable * syllables.size)
        for ((index, baseFreq) in syllables.withIndex()) {
            val offset = index * perSyllable
            for (i in 0 until perSyllable) {
                val t = i.toFloat() / SR
                val envelope = sin(PI * i / perSyllable).toFloat().coerceAtLeast(0f)
                val vibrato = 1f + 0.06f * sin(2.0 * PI * 30.0 * t).toFloat()
                out[offset + i] =
                    0.4f * envelope * sin(2.0 * PI * baseFreq * vibrato * t).toFloat()
            }
        }
        return out
    }

    private fun airhorn(): FloatArray {
        val out = FloatArray(duration(1.0f))
        for (i in out.indices) {
            val t = i.toFloat() / SR
            val attack = (i / (0.02f * SR)).coerceAtMost(1f)
            val release = if (i > out.size - 0.08f * SR) {
                ((out.size - i) / (0.08f * SR)).coerceAtMost(1f)
            } else 1f
            val saw = 2f * ((t * 311f) % 1f) - 1f
            val fifth = 2f * ((t * 415f) % 1f) - 1f
            out[i] = 0.32f * attack * release * (saw + fifth)
        }
        return out
    }

    private fun drumroll(): FloatArray {
        val random = Random(11)
        val tickEvery = (0.03f * SR).toInt()
        val out = FloatArray(duration(1.2f))
        for (start in out.indices step tickEvery) {
            val tickLength = minOf((0.05f * SR).toInt(), out.size - start)
            for (i in 0 until tickLength) {
                val decay = 1f - i.toFloat() / tickLength
                out[start + i] += 0.6f * decay * (random.nextFloat() * 2f - 1f)
            }
        }
        return out
    }

    private fun sweep(fromHz: Float, toHz: Float, seconds: Float): FloatArray {
        val out = FloatArray(duration(seconds))
        var phase = 0f
        for (i in out.indices) {
            val progress = i.toFloat() / out.size
            val freq = fromHz + (toHz - fromHz) * progress
            phase += 2f * PI.toFloat() * freq / SR
            val envelope = sin(PI * progress).toFloat()
            out[i] = 0.4f * envelope * sin(phase)
        }
        return out
    }

    private fun wow(): FloatArray {
        val out = FloatArray(duration(0.8f))
        for (i in out.indices) {
            val t = i.toFloat() / SR
            val progress = i.toFloat() / out.size
            val vibrato = 1f + 0.08f * sin(2.0 * PI * 8.0 * t).toFloat()
            val freq = 420f * vibrato * (1f - 0.25f * progress)
            val envelope = sin(PI * progress).toFloat()
            out[i] = 0.4f * envelope * sin(2f * PI.toFloat() * freq * t)
        }
        return out
    }

    private fun chime(frequencies: List<Float>): FloatArray {
        val perTone = duration(0.18f)
        val out = FloatArray(perTone * frequencies.size)
        frequencies.forEachIndexed { index, freq ->
            val offset = index * perTone
            for (i in 0 until perTone) {
                val t = i.toFloat() / SR
                val decay = kotlin.math.exp(-6f * i / perTone)
                out[offset + i] = 0.42f * decay * sin(2f * PI.toFloat() * freq * t)
            }
        }
        return out
    }

    private fun ding(): FloatArray {
        val out = FloatArray(duration(0.9f))
        for (i in out.indices) {
            val t = i.toFloat() / SR
            val decay = kotlin.math.exp(-5f * t)
            val fundamental = sin(2f * PI.toFloat() * 1180f * t)
            val harmonic = 0.4f * sin(2f * PI.toFloat() * 1770f * t)
            out[i] = 0.45f * decay * (fundamental + harmonic)
        }
        return out
    }

    private fun bruh(): FloatArray {
        val out = FloatArray(duration(0.35f))
        var previous = 0f
        for (i in out.indices) {
            val t = i.toFloat() / SR
            val progress = i.toFloat() / out.size
            val square = if (sin(2f * PI.toFloat() * 115f * (1f - 0.2f * progress) * t) >= 0f) 1f else -1f
            previous = previous + 0.35f * (square - previous) // cheap low-pass to soften edges
            out[i] = 0.5f * (1f - progress) * previous
        }
        return out
    }

    private fun meow(): FloatArray {
        val out = FloatArray(duration(0.6f))
        var phase = 0f
        for (i in out.indices) {
            val progress = i.toFloat() / out.size
            val shape = if (progress < 0.4f) 500f + 1000f * (progress / 0.4f) else 900f - 1100f * ((progress - 0.4f) / 0.6f)
            phase += 2f * PI.toFloat() * shape / SR
            val envelope = sin(PI * progress).toFloat()
            out[i] = 0.38f * envelope * sin(phase)
        }
        return out
    }

    private fun quack(): FloatArray {
        val out = FloatArray(duration(0.28f))
        for (i in out.indices) {
            val t = i.toFloat() / SR
            val progress = i.toFloat() / out.size
            val buzz = if (sin(2f * PI.toFloat() * 190f * t) >= 0f) 1f else -1f
            val am = 0.5f + 0.5f * sin(2f * PI.toFloat() * 55f * t)
            out[i] = 0.45f * (1f - progress) * buzz * am
        }
        return out
    }
}
