package livekit

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The audio device names the settings pickers offer.
 *
 * ## Why this exists
 *
 * The pickers used to be fed by WebRTC's `MediaDevices.getAudioCaptureDevices()`
 * / `getAudioRenderDevices()`, while Orbit's group-call audio path opens a
 * JavaSound `TargetDataLine` (capture) and `SourceDataLine` (playback) directly.
 * The user therefore chose a name from one enumeration and the audio stack
 * substring-matched it against a different one.
 *
 * Those name spaces overlap but are not equal, and where they differ the choice
 * silently did nothing. A real run selected `Speakers (NVIDIA Broadcast)` and
 * produced
 *
 *     WARN output device "Speakers (NVIDIA Broadcast)" matched no mixer,
 *     using default
 *
 * — a line in a log, and no change to what anyone heard. Reported as "changing
 * output device doesn't really change it". Same story on the input side.
 *
 * Offering names from the layer that actually opens the line makes every name
 * resolve by construction, which is the only way a picker can be honest.
 *
 * ## Format
 *
 * Names are probed against what the two paths actually open, and the two
 * differ:
 *
 * - capture opens a `TargetDataLine` at 48 kHz / 16-bit / **mono** (the run log
 *   confirms: `selected AudioFormat: sampleRate=48000 channels=1`)
 * - playback opens a `SourceDataLine` at 48 kHz / 16-bit / **stereo**, because
 *   the native mixer builds `AudioFrame(..., 2, kSamplesPerChannel)`
 *
 * That difference was a real bug, and it was mine: this list filtered outputs on
 * MONO support while the player asked for stereo, so the picker offered devices
 * the player could not open and omitted devices it could. Input worked (its
 * filter matched) and output did not, which is exactly the reported symptom --
 * "input is getting mapped not the output".
 *
 * A mixer is listed if it can open the line at 48 kHz / 16-bit signed LE in
 * EITHER channel count, because that is what either path might ask for and
 * neither should be offered a device that will be rejected.
 *
 * `Default` is always offered and always listed first. It means "let the system
 * choose", and it is what both ends fall back to, so it has to be reachable.
 */
object LiveKitAudioDevices {

    private const val SAMPLE_RATE = 48_000f
    private const val BITS = 16
    private const val DEFAULT_LABEL = "Default"

    /** What the capture loop opens. */
    private val CAPTURE_FORMATS = listOf(AudioFormat(SAMPLE_RATE, BITS, 1, true, false))

    /**
     * What the playback line opens: stereo, matching the frames the native mixer
     * produces. Mono is accepted too, so a device that only does mono is still
     * offered rather than silently missing.
     */
    private val PLAYBACK_FORMATS = listOf(
        AudioFormat(SAMPLE_RATE, BITS, 2, true, false),
        AudioFormat(SAMPLE_RATE, BITS, 1, true, false),
    )

    fun inputNames(): List<String> = listOf(DEFAULT_LABEL) + mixersFor(TargetDataLine::class.java, CAPTURE_FORMATS)

    fun outputNames(): List<String> = listOf(DEFAULT_LABEL) + mixersFor(SourceDataLine::class.java, PLAYBACK_FORMATS)

    /**
     * Every mixer that can open [lineClass] for at least one of [formats], named
     * the way the matcher in `LiveKitNativeAudioCapture` / `LiveKitNativeAudioPlayer`
     * will see it.
     *
     * Sorted so the list is stable between runs: `AudioSystem.getMixerInfo()`
     * order is not guaranteed, and a picker whose options reshuffle on every
     * open is its own small bug.
     */
    private fun mixersFor(
        lineClass: Class<out DataLine>,
        formats: List<AudioFormat>
    ): List<String> {
        return runCatching {
            AudioSystem.getMixerInfo()
                .filter { candidate ->
                    runCatching {
                        val mixer = AudioSystem.getMixer(candidate)
                        formats.any { mixer.isLineSupported(DataLine.Info(lineClass, it)) }
                    }.getOrDefault(false)
                }
                .map { it.name }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }
}
