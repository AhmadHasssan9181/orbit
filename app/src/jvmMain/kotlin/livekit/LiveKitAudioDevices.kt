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
 * The names are probed against the formats Orbit actually uses — 48 kHz, 16-bit,
 * signed, little-endian, mono for capture and the render format for playback. A
 * mixer is listed only if it can open a line for the format we will ask it for,
 * so the list cannot contain a device that is guaranteed to fail on selection.
 *
 * `Default` is always offered and always listed first. It means "let the system
 * choose", and it is what both ends fall back to, so it has to be reachable.
 */
object LiveKitAudioDevices {

    /** What the capture loop opens. Mono, because the mixer downmixes. */
    private val CAPTURE_FORMAT = AudioFormat(48_000f, 16, 1, true, false)

    /** What the playback line opens. Mono out; the mixer is already downmixed. */
    private val PLAYBACK_FORMAT = AudioFormat(48_000f, 16, 1, true, false)

    private const val DEFAULT_LABEL = "Default"

    fun inputNames(): List<String> = listOf(DEFAULT_LABEL) + mixersFor(TargetDataLine::class.java, CAPTURE_FORMAT)

    fun outputNames(): List<String> = listOf(DEFAULT_LABEL) + mixersFor(SourceDataLine::class.java, PLAYBACK_FORMAT)

    /**
     * Every mixer that can open [lineClass] for [format], named the way the
     * matcher in `LiveKitNativeAudioCapture` / `LiveKitNativeAudioPlayer` will
     * see it.
     *
     * Sorted so the list is stable between runs: `AudioSystem.getMixerInfo()`
     * order is not guaranteed, and a picker whose options reshuffle on every
     * open is its own small bug.
     */
    private fun mixersFor(
        lineClass: Class<out DataLine>,
        format: AudioFormat
    ): List<String> {
        val info = DataLine.Info(lineClass, format)
        return runCatching {
            AudioSystem.getMixerInfo()
                .filter { candidate ->
                    runCatching {
                        AudioSystem.getMixer(candidate).isLineSupported(info)
                    }.getOrDefault(false)
                }
                .map { it.name }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }
}
