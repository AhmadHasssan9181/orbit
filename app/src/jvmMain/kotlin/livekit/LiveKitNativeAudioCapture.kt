package livekit

import util.OrbitLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LiveKitNativeAudioCapture {
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val FRAME_MS = 10
    const val SAMPLES_PER_CHANNEL = 480
    const val FRAME_BYTES = 960
    private const val BYTES_PER_SAMPLE = 2
    private const val CAPTURE_BUFFER_FRAMES = 25
    private const val CAPTURE_BUFFER_BYTES = FRAME_BYTES * CAPTURE_BUFFER_FRAMES
    private const val TEST_TONE_HZ = 1000.0
    private const val TEST_TONE_PEAK = 12_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var captureJob: Job? = null

    /**
     * Monotonic token identifying the CURRENT capture session.
     *
     * The capture loop used to test a shared `@Volatile started` flag. That flag
     * belongs to whoever set it last, not to the loop that is running, so:
     *
     *  - Hang up, then immediately rejoin. The old loop is parked in
     *    `line.read()` (which blocks ~10ms) and only re-checks the flag on the
     *    next iteration. `start()` has already flipped it back to true by then, so
     *    the old loop now sees `read <= 0` from ITS closed line, takes the
     *    undelayed `continue`, hot-spins, and after 100 empty reads reports
     *    `AUDIO_DEVICE_UNAVAILABLE` -- against the NEW call. That is the
     *    "join right after hanging up fails" symptom.
     *  - `activeLine` had no owner either, so a second `requestStop()` closed the
     *    NEW loop's line.
     *
     * A per-session token fixes both: a stale loop's generation no longer matches,
     * so it exits at the top of its own `while` instead of competing with the new
     * session, and `requestStop` only closes the line it actually owns.
     */
    private val captureGeneration = java.util.concurrent.atomic.AtomicLong(0)

    @Volatile private var activeLine: TargetDataLine? = null

    /** Which generation [activeLine] belongs to, so stop cannot close someone else's. */
    @Volatile private var activeLineGeneration: Long = -1L

    @Volatile private var started = false
    @Volatile private var muted = false
    @Volatile private var sessionId = 0L
    @Volatile private var epoch = 0L
    @Volatile private var bridge: OrbitLiveKitNativeBridge? = null
    @Volatile private var failureSink: ((NativeError) -> Unit)? = null
    @Volatile private var diagnosticFramesLogged = 0
    @Volatile private var diagnosticReadsLogged = 0
    @Volatile private var lowInputFramesObserved = 0
    @Volatile private var lowInputFramesAtOrBelowThreshold = 0
    @Volatile private var lowInputMaxPeak = 0
    @Volatile private var lowInputWarningLogged = false
    @Volatile private var gainLogged = false
    @Volatile private var apmLogged = false
    @Volatile private var preparedMixerKey: MixerKey? = null
    @Volatile private var preparedProbeResult: MicProbeResult? = null
    @Volatile private var preparedOverrideName: String = ""
    // What the probe was run against, so a second join can tell "same inputs,
    // same override" from "the hardware changed" without re-probing to find out.
    @Volatile private var preparedCandidatesSignature: String = ""
    @Volatile private var preparedSelfTestResult: MicCaptureSelfTestResult? = null
    @Volatile private var inputDeviceOverride = ""
    @Volatile private var userVolumeFactor = 1f

    /**
     * Voice & Video settings: runtime input-device selection. Substring-matched
     * against JavaSound mixer names when the capture line opens.
     *
     * Takes effect IMMEDIATELY, including mid-call.
     *
     * This used to be documented as "applies on next capture start" and behaved
     * exactly that way: it stored the name, logged it, and did nothing else. The
     * capture line is opened once when a call starts and `start()` returns early
     * while `started` is true, so a live call never re-read the override at all.
     * Picking a different microphone during a call therefore produced a settings
     * screen that looked like it had changed something and did not -- reported
     * as "changing input device doesn't really change it".
     *
     * The restart goes through the normal stop/start path rather than swapping
     * the line in place, because the generation token is what retires the old
     * loop safely: a capture loop that had its line closed underneath it would
     * read a closed `TargetDataLine` and report a device failure against the
     * call that just asked for the new device. Done on [scope], never on the
     * caller's thread, because the caller is the Compose/EDT path.
     */
    fun setInputDeviceOverride(name: String) {
        val trimmed = name.trim()
        val next = if (trimmed.isEmpty() || trimmed.equals("Default", ignoreCase = true)) "" else trimmed
        if (inputDeviceOverride == next) return
        inputDeviceOverride = next

        // Snapshot what a restart needs. Read under the same lock `requestStop`
        // uses, because that clears them.
        val restartSessionId: Long
        val restartEpoch: Long
        val restartBridge: OrbitLiveKitNativeBridge?
        val restartFailure: ((NativeError) -> Unit)?
        synchronized(captureGeneration) {
            if (!started || captureJob == null) {
                println(
                    "DEBUG [LiveKitNativeAudioCapture] input device override=\"$next\" " +
                        "(no capture running; will be used when one starts)"
                )
                return
            }
            restartSessionId = sessionId
            restartEpoch = epoch
            restartBridge = bridge
            restartFailure = failureSink
        }
        println(
            "DEBUG [LiveKitNativeAudioCapture] input device override=\"$next\" " +
                "(restarting capture now — the old line is still open on the previous device)"
        )
        scope.launch {
            val previous = requestStop()
            previous?.cancelAndJoin()
            if (restartBridge != null) {
                val ok = start(restartSessionId, restartEpoch, restartBridge, restartFailure ?: {})
                println(
                    "DEBUG [LiveKitNativeAudioCapture] capture restarted on " +
                        "override=\"$next\" ok=$ok"
                )
            }
        }
    }

    // Multiplies the fixed capture gain / post-APM signal; 1.0 leaves levels untouched.
    fun setUserInputVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        if (userVolumeFactor == clamped) return
        userVolumeFactor = clamped
        println("DEBUG [LiveKitNativeAudioCapture] user input volume=$clamped")
    }

    private const val LOW_INPUT_MESSAGE =
        "Captured microphone PCM is near silence. Check Windows mic level or select another input device."

    // LiveKitNativeAudioBridge.acceptingFrames is a JNI call that takes the global
    // native session mutex, which the EDT also holds across thread joins � polling
    // it every 10 ms stalled mic capture. The native push already re-checks
    // hasReadyMicLocked() per frame and silently drops, so a coarser poll costs at
    // most a handful of dropped frames.
    private const val ACCEPTING_FRAMES_POLL_FRAMES = 50

    data class MicProbeResult(
        val mixerName: String,
        val mixerDescription: String,
        val mixerVendor: String,
        val mixerVersion: String,
        val desiredFormatSupported: Boolean,
        val overrideMatch: Boolean,
        val score: Int,
        val min: Int,
        val max: Int,
        val peak: Int,
        val rms: Double,
        val firstSamples: List<Int>,
        val nonZeroSamples: Long,
        val totalSamples: Long,
        val error: String? = null
    )

    data class MicCaptureSelfTestResult(
        val selectedMixerName: String?,
        val selectedMixerDescription: String?,
        val selectedPeak: Int,
        val selectedRms: Double,
        val overrideName: String,
        val allInputsTooLow: Boolean,
        val results: List<MicProbeResult>
    )

    fun start(
        sessionId: Long,
        epoch: Long,
        nativeBridge: OrbitLiveKitNativeBridge,
        onFailure: (NativeError) -> Unit
    ): Boolean {
        // Synchronised so two concurrent starts cannot both pass the guard and
        // launch two capture loops onto the same device. The old `if (started)`
        // check-then-set had a window between the read and the write.
        val myGeneration: Long
        synchronized(captureGeneration) {
            if (started) return true
            myGeneration = captureGeneration.incrementAndGet()
            started = true
        }

        LiveKitNativeAudioBridge.resetFrameDropLog()
        if (!LiveKitNativeAudioBridge.acceptingFrames) {
            println("ERROR [LiveKitNativeAudioCapture] Native C++ audio is not accepting frames")
            started = false
            return false
        }

        this.sessionId = sessionId
        this.epoch = epoch
        bridge = nativeBridge
        failureSink = onFailure
        CallPathStats.ensureReporter()
        diagnosticFramesLogged = 0
        diagnosticReadsLogged = 0
        lowInputFramesObserved = 0
        lowInputFramesAtOrBelowThreshold = 0
        lowInputMaxPeak = 0
        lowInputWarningLogged = false
        gainLogged = false
        apmLogged = false
        captureJob = scope.launch {
            runCaptureLoop(myGeneration)
        }
        return true
    }

    /**
     * Stops capture and returns the job that was running, so a caller that is
     * NOT on the UI thread can actually join it.
     *
     * Two things this fixes:
     *
     *  - `activeLine` is only closed if it belongs to the CURRENT generation. It
     *    was a bare field, so a second `requestStop()` after a rejoin closed the
     *    NEW loop's line.
     *  - The generation is bumped, which is what actually retires a stale loop.
     *    Setting `started = false` alone did not, because the stale loop reads
     *    that flag only between 10ms reads and by then a rejoin had set it true
     *    again.
     */
    /**
     * Is [generation] still the live capture session?
     *
     * This is what a capture loop asks instead of reading `started`. The flag
     * says "someone is capturing"; this says "*I* am the one capturing". The
     * difference is the whole bug: a stale loop and a fresh one both saw
     * `started == true`, so the stale one kept reading its closed line and
     * eventually reported a device failure against the new call.
     */
    private fun isCurrentGeneration(generation: Long): Boolean =
        started && captureGeneration.get() == generation

    private fun requestStop(): Job? {
        val job = captureJob
        synchronized(captureGeneration) {
            started = false
            captureGeneration.incrementAndGet()
            // Only close the line this manager currently owns. The generation is
            // bumped FIRST so the loop sees itself retired before its line is
            // closed underneath it.
            activeLine?.let { line ->
                runCatching { line.stop() }
                runCatching { line.close() }
            }
            activeLine = null
            activeLineGeneration = -1L
        }
        job?.cancel()
        captureJob = null
        sessionId = 0L
        epoch = 0L
        bridge = null
        failureSink = null
        return job
    }

    fun stop() {
        requestStop()
    }

    fun setMuted(isMuted: Boolean) {
        muted = isMuted
    }

    /**
     * Stops capture and returns the capture job, or null if nothing was running.
     *
     * Prefer this over [stop] wherever the caller is not the UI thread: [stop]
     * returns immediately, so "the old loop has definitely finished" is never
     * actually established and a caller that reuses resources immediately after
     * it is racing the loop it just cancelled.
     */
    fun stopAndJoin(): Job? = requestStop()

    /**
     * Fire-and-forget stop for callers on the UI thread.
     *
     * The join deliberately happens on the IO scope rather than blocking: this
     * is reached from the Compose/EDT thread via
     * `LiveKitNativeMediaSession.disconnect()`, and the capture loop only notices
     * cancellation between 10ms reads, so a blocking join froze the window.
     *
     * Prefer [stopAndJoin] wherever the caller is NOT the UI thread — this
     * returns before the loop has actually stopped, so a caller that immediately
     * reuses the device is racing the loop it just cancelled.
     */
    fun stopAndJoinAsync() {
        val job = requestStop() ?: return
        scope.launch { job.cancelAndJoin() }
    }

    fun runMicCaptureSelfTest(durationMs: Long = 3000): MicCaptureSelfTestResult {
        if (configuredTestTone()) {
            println("DEBUG [LiveKitNativeAudioCapture] microphone self-test skipped because test tone is enabled")
            return MicCaptureSelfTestResult(
                selectedMixerName = "<test-tone>",
                selectedMixerDescription = "Generated 1 kHz sine wave",
                selectedPeak = TEST_TONE_PEAK,
                selectedRms = TEST_TONE_PEAK / sqrt(2.0),
                overrideName = configuredInputDeviceName(),
                allInputsTooLow = false,
                results = emptyList()
            )
        }

        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val overrideName = configuredInputDeviceName()
        val candidates = enumerateInputMixers(format, overrideName)
        logInputMixerInventory(candidates, overrideName)

        val candidatesToProbe = if (overrideName.isNotBlank()) {
            val matches = candidates.filter { it.overrideMatch }
            if (matches.isEmpty()) {
                // FALL BACK, do not hard-fail. A configured device that Java
                // cannot see is routine, not fatal: WASAPI-only virtual inputs
                // (NVIDIA Broadcast, virtual audio cables, some Bluetooth
                // headsets) never appear in AudioSystem's mixer list, because
                // that list is DirectAudio. Probing nothing here produced
                // "mixers=0", MIC_INPUT_TOO_LOW, and AUDIO_DEVICE_UNAVAILABLE --
                // i.e. the app refused to call at all while four working inputs
                // sat there unused. The override is a preference; the call is
                // more important than the preference.
                println(
                    "WARN [LiveKitNativeAudioCapture] configured input '$overrideName' is not " +
                        "visible to Java's audio mixer list (common with WASAPI-only virtual " +
                        "devices). Falling back to the best available input; clear " +
                        "orbit.audio.inputDeviceName to use the system default."
                )
                candidates
            } else {
                matches
            }
        } else {
            candidates
        }

        val signature = candidatesSignature(candidatesToProbe, overrideName)
        val cached = preparedSelfTestResult
        if (cached != null && preparedMixerKey != null &&
            preparedCandidatesSignature == signature && preparedOverrideName == overrideName
        ) {
            println(
                "DEBUG [LiveKitNativeAudioCapture] microphone self-test reused cached probe " +
                    "mixer=${cached.selectedMixerName} peak=${cached.selectedPeak} " +
                    "rms=${cached.selectedRms.formatRms()} mixers=${candidatesToProbe.size} " +
                    "(inputs unchanged since the last probe; skipped ${candidatesToProbe.size * durationMs}ms of re-probing)"
            )
            return cached
        }

        println("DEBUG [LiveKitNativeAudioCapture] microphone self-test started durationMs=$durationMs mixers=${candidatesToProbe.size}")
        val results = probeInputMixers(format, candidatesToProbe, durationMs)
        val selected = selectBestProbeResult(results)
        val allInputsTooLow = results.isEmpty() || results.all { it.peak <= 3 }
        if (allInputsTooLow) {
            println("WARN [LiveKitNativeAudioCapture] MIC_INPUT_TOO_LOW operation=JVM_MIC_CAPTURE message=\"$LOW_INPUT_MESSAGE\"")
        }
        if (selected != null) {
            preparedMixerKey = selected.toMixerKey()
            preparedProbeResult = selected
            preparedOverrideName = overrideName
        } else {
            preparedMixerKey = null
            preparedProbeResult = null
            preparedOverrideName = overrideName
        }
        if (selected != null) {
            println(
                "DEBUG [LiveKitNativeAudioCapture] microphone self-test selected mixer=${selected.mixerName} " +
                    "peak=${selected.peak} rms=${selected.rms.formatRms()} " +
                    "first10Samples=${selected.firstSamples.joinToString(prefix = "[", postfix = "]")} " +
                    "nonZeroSamples=${selected.nonZeroSamples}/${selected.totalSamples}"
            )
        }
        val result = MicCaptureSelfTestResult(
            selectedMixerName = selected?.mixerName,
            selectedMixerDescription = selected?.mixerDescription,
            selectedPeak = selected?.peak ?: 0,
            selectedRms = selected?.rms ?: 0.0,
            overrideName = overrideName,
            allInputsTooLow = allInputsTooLow,
            results = results
        )
        preparedCandidatesSignature = signature
        preparedSelfTestResult = result
        return result
    }

    /**
     * The probe is `durationMs` of blocking audio per mixer, run SEQUENTIALLY,
     * and it sat on the join path. On the machine this was measured on that is
     * four inputs at 3 s each — 12 s of the 21 s it took to join a call, spent
     * listening to microphones before the SFU connection was even allowed to
     * start.
     *
     * But the answer it produces does not change between two calls in the same
     * session: which mic is plugged in, and which one the user picked, are both
     * the same. The chosen mixer was already being cached in [preparedMixerKey]
     * and reused when the capture line opened — only the *probing* was repeated,
     * which is the expensive part and produced a result that was then thrown
     * away in favour of the cached one.
     *
     * So: remember which inputs were probed, and skip the probe when they are
     * unchanged. Any difference — a device plugged in or unplugged, the
     * override edited, the per-candidate format support changing — produces a
     * different signature and forces a real probe again.
     */
    private fun candidatesSignature(candidates: List<InputMixerCandidate>, overrideName: String): String =
        buildString {
            append(overrideName)
            for (candidate in candidates) {
                append('\u0000')
                append(candidate.mixerInfo.name)
                append('|')
                append(candidate.mixerInfo.description)
                append('|')
                append(candidate.desiredFormatSupported)
                append('|')
                append(candidate.overrideMatch)
                append('|')
                append(candidate.score)
            }
        }

    suspend fun prepareMicrophoneForCall(durationMs: Long = 3000): NativeResult<MicCaptureSelfTestResult> =
        // 3 s PER MIXER of blocking TargetDataLine.read() probing. The connect path
        // is driven from the Compose scope, so the whole probe is offloaded here
        // rather than relying on the caller to pick the right dispatcher.
        withContext(Dispatchers.IO) { prepareMicrophoneForCallOnIo(durationMs) }

    private fun prepareMicrophoneForCallOnIo(durationMs: Long): NativeResult<MicCaptureSelfTestResult> {
        if (configuredTestTone()) {
            println("DEBUG [LiveKitNativeAudioCapture] microphone self-test skipped because test tone is enabled")
            return NativeResult.success(
                MicCaptureSelfTestResult(
                    selectedMixerName = "<test-tone>",
                    selectedMixerDescription = "Generated 1 kHz sine wave",
                    selectedPeak = TEST_TONE_PEAK,
                    selectedRms = TEST_TONE_PEAK / sqrt(2.0),
                    overrideName = configuredInputDeviceName(),
                    allInputsTooLow = false,
                    results = emptyList()
                )
            )
        }

        val result = runMicCaptureSelfTest(durationMs)
        if (result.overrideName.isNotBlank() && result.results.isEmpty()) {
            return NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                    message = "No microphone input matched orbit.audio.inputDeviceName=${result.overrideName}",
                    operation = "JVM_MIC_CAPTURE",
                    recoverable = true
                )
            )
        }
        if (result.selectedMixerName == null) {
            return NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                    message = "No supported microphone input device is available",
                    operation = "JVM_MIC_CAPTURE",
                    recoverable = true
                )
            )
        }
        return NativeResult.success(result)
    }

    fun configuredSelfTestDurationMs(): Long =
        orbitAudioConfig("orbit.audio.selfTestDurationMs", "ORBIT_AUDIO_SELF_TEST_DURATION_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(250L)
            ?: 3_000L

    fun configuredTestTone(): Boolean =
        orbitAudioConfig("orbit.audio.testTone", "ORBIT_AUDIO_TEST_TONE")
            ?.equals("true", ignoreCase = true)
            ?: false

    fun configuredTestToneStereo(): Boolean =
        orbitAudioConfig("orbit.audio.testToneStereo", "ORBIT_AUDIO_TEST_TONE_STEREO")
            ?.equals("true", ignoreCase = true)
            ?: false

    fun configuredPublishChannels(): Int =
        if (configuredTestTone() && configuredTestToneStereo()) 2 else CHANNELS

    /**
     * @param myGeneration the token [start] minted for THIS loop. The loop tests
     *   its own token rather than the shared `started` flag, so a loop that has
     *   been superseded stops itself instead of racing the session that replaced
     *   it. See [captureGeneration] for the failure this prevents.
     */
    private suspend fun runCaptureLoop(myGeneration: Long) {
        if (!isCurrentGeneration(myGeneration)) {
            println("DEBUG [LiveKitNativeAudioCapture] capture loop for generation $myGeneration exited before opening a device (superseded)")
            return
        }
        val testTone = configuredTestTone()
        val testToneStereo = configuredTestToneStereo()
        val overrideName = configuredInputDeviceName()
        val explicitCaptureGain = orbitAudioConfig("orbit.audio.captureGain", "ORBIT_AUDIO_CAPTURE_GAIN")
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 100.0)
        // With the APM active, AGC2 owns loudness; the old �8 default boost is
        // only a fallback for the unprocessed path.
        val captureGain = explicitCaptureGain ?: if (OrbitApm.enabled) 1.0 else 8.0
        println("DEBUG [LiveKitNativeAudioCapture] audio flags: testTone=$testTone testToneStereo=$testToneStereo captureGain=$captureGain apm=${OrbitApm.enabled} inputDeviceName=${overrideName.ifBlank { "<none>" }} publishChannels=${configuredPublishChannels()}")
        if (testTone) {
            runTestToneCaptureLoop(myGeneration)
            return
        }

        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val autoProbeDurationMs = orbitAudioConfig("orbit.audio.autoProbeDurationMs", "ORBIT_AUDIO_AUTO_PROBE_DURATION_MS")
            ?.toLongOrNull()
            ?.coerceAtLeast(250L)
            ?: 1500L
        val inputMixers = enumerateInputMixers(format, overrideName)
        logInputMixerInventory(inputMixers, overrideName)
        val selectedLine = openPreferredInputLine(format, inputMixers, overrideName, autoProbeDurationMs)
        if (selectedLine == null) {
            println("ERROR [LiveKitNativeAudioCapture] Audio line not supported")
            failureSink?.invoke(
                NativeError(
                    code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                    message = if (overrideName.isNotBlank()) {
                        "No microphone input matched orbit.audio.inputDeviceName=$overrideName"
                    } else {
                        "No supported microphone input device is available"
                    },
                    operation = "JVM_MIC_CAPTURE",
                    recoverable = true
                )
            )
            started = false
            return
        }

        val heapBuffer = ByteArray(FRAME_BYTES)
        val directBuffer = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var consecutivePushFailures = 0
        var frameOffset = 0
        var acceptingPollCounter = 0

        val line = selectedLine.line
        // Record which session owns this line, so `requestStop` can close it only
        // for the session that opened it.
        synchronized(captureGeneration) {
            if (!isCurrentGeneration(myGeneration)) {
                runCatching { line.stop() }
                runCatching { line.close() }
                return
            }
            activeLine = line
            activeLineGeneration = myGeneration
        }
        // C1: when the device refused the pipeline format, the line opens at its
        // native format and every chunk converts through the resampler.
        val openFormat = selectedLine.openFormat ?: format
        val resampler = if (
            PcmResampler.needsConversion(
                openFormat.sampleRate.toInt(),
                openFormat.channels,
                openFormat.isBigEndian,
                SAMPLE_RATE,
                CHANNELS
            )
        ) {
            println("DEBUG [LiveKitNativeAudioCapture] SRC active ${openFormat.toDebugString()} -> ${SAMPLE_RATE} Hz/${CHANNELS} ch")
            PcmResampler(openFormat.sampleRate.toInt(), openFormat.channels, openFormat.isBigEndian, SAMPLE_RATE)
        } else {
            null
        }
        // ~One output frame of source per read keeps pushes evenly paced; a large
        // burst read would trip the single-slot native backpressure and drop frames.
        val srcFrameBytes = maxOf(1, openFormat.frameSize)
        val srcChunkBytes = if (resampler == null) {
            0
        } else {
            maxOf(
                srcFrameBytes,
                kotlin.math.ceil(SAMPLES_PER_CHANNEL * (openFormat.sampleRate.toFloat() / SAMPLE_RATE)).toInt() * srcFrameBytes
            )
        }
        val srcChunk = ByteArray(srcChunkBytes)
        try {
            line.open(openFormat, CAPTURE_BUFFER_BYTES)
            line.start()
            runCatching { line.flush() }
            logSelectedInputLine(selectedLine, line)
            println("DEBUG [LiveKitNativeAudioCapture] capture line flushed after start available=${line.available()} bufferSize=${line.bufferSize}")
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop started")

            // A device that vanishes mid-call makes line.read return 0 immediately �
            // an unbounded `continue` here would hot-spin at 100% CPU with a "live"
            // call and nothing captured. Bail out via failureSink after a burst of
            // consecutive empty reads.
            var consecutiveEmptyReads = 0
            while (isCurrentGeneration(myGeneration) && kotlinx.coroutines.currentCoroutineContext().isActive) {
                if (resampler == null) {
                    val requested = FRAME_BYTES - frameOffset
                    val read = line.read(heapBuffer, frameOffset, requested)
                    logReadDiagnostics(line, FRAME_BYTES, requested, read)
                    if (read <= 0) {
                        CallPathStats.onMicReadStall()
                        consecutiveEmptyReads += 1
                        if (consecutiveEmptyReads == 25) {
                            println("WARN [LiveKitNativeAudioCapture] mic read repeatedly returned $read (${consecutiveEmptyReads}x) � device may be gone")
                        }
                        if (consecutiveEmptyReads >= 100) {
                            failureSink?.invoke(
                                NativeError(
                                    code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                                    message = "Microphone stopped delivering samples ($consecutiveEmptyReads empty reads)",
                                    operation = "JVM_MIC_CAPTURE",
                                    recoverable = true
                                )
                            )
                            break
                        }
                        continue
                    }
                    consecutiveEmptyReads = 0
                    frameOffset += read
                    if (frameOffset < FRAME_BYTES) continue
                    frameOffset = 0
                } else {
                    val read = line.read(srcChunk, 0, srcChunk.size)
                    logReadDiagnostics(line, srcChunk.size, srcChunk.size, read)
                    if (read <= 0) {
                        CallPathStats.onMicReadStall()
                        consecutiveEmptyReads += 1
                        if (consecutiveEmptyReads == 25) {
                            println("WARN [LiveKitNativeAudioCapture] resampled mic read repeatedly returned $read (${consecutiveEmptyReads}x) � device may be gone")
                        }
                        if (consecutiveEmptyReads >= 100) {
                            failureSink?.invoke(
                                NativeError(
                                    code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                                    message = "Microphone stopped delivering samples ($consecutiveEmptyReads empty reads)",
                                    operation = "JVM_MIC_CAPTURE",
                                    recoverable = true
                                )
                            )
                            break
                        }
                        continue
                    }
                    consecutiveEmptyReads = 0
                    resampler.push(srcChunk, read)
                    if (!resampler.pullFrame(heapBuffer, 0, SAMPLES_PER_CHANNEL)) continue
                }

                val rawStats = pcmStats(heapBuffer, FRAME_BYTES)
                observeInputLevel(rawStats)
                if (!muted && OrbitApm.enabled && OrbitApm.ensureStarted()) {
                    OrbitApm.processCapture(heapBuffer, FRAME_BYTES, SAMPLE_RATE, CHANNELS)
                    if (!apmLogged) {
                        println("DEBUG [LiveKitNativeAudioCapture] APM capture chain active (aec3+ns+agc2+hpf)")
                        apmLogged = true
                    }
                } else if (!muted && captureGain != 1.0 && rawStats.peak > 0) {
                    applyCaptureGain(heapBuffer, FRAME_BYTES, captureGain)
                    if (!gainLogged) {
                        val gainedStats = pcmStats(heapBuffer, FRAME_BYTES)
                        println("DEBUG [LiveKitNativeAudioCapture] capture gain applied gain=$captureGain rawPeak=${rawStats.peak} rawRms=${rawStats.rms} gainedPeak=${gainedStats.peak} gainedRms=${gainedStats.rms}")
                        gainLogged = true
                    }
                }
                if (!muted && userVolumeFactor != 1f) {
                    applyCaptureGain(heapBuffer, FRAME_BYTES, userVolumeFactor.toDouble())
                }
                if (muted) {
                    // C2: transmission gating is owned by the native track mute
                    // (nativeSetAudioMuted + C++ worker skip) � no JVM-side zero-fill,
                    // so a stuck JVM boolean can no longer cause permanent silence.
                    CallPathStats.onMicFrameMuted()
                }
                // Soundboard effects mix into the frame; whether anything transmits
                // is decided by the native mute flag alone.
                SoundboardMixer.drainMix(SAMPLES_PER_CHANNEL)?.let { effect ->
                    mixEffectIntoFrame(heapBuffer, FRAME_BYTES, effect)
                }
                logCapturedFrame(heapBuffer, FRAME_BYTES, muted, rawStats, captureGain)
                CallPathStats.onMicFrameRead()

                acceptingPollCounter += 1
                if (acceptingPollCounter >= ACCEPTING_FRAMES_POLL_FRAMES) {
                    acceptingPollCounter = 0
                    if (!LiveKitNativeAudioBridge.acceptingFrames) {
                        continue
                    }
                }

                directBuffer.clear()
                directBuffer.put(heapBuffer, 0, FRAME_BYTES)
                directBuffer.flip()

                val result = bridge?.pushAudioFrame(
                    sessionId,
                    epoch,
                    directBuffer,
                    SAMPLE_RATE,
                    CHANNELS,
                    SAMPLES_PER_CHANNEL,
                    System.nanoTime()
                )
                if (result == null || result.ok) {
                    consecutivePushFailures = 0
                    CallPathStats.onPushOk()
                    continue
                }

                consecutivePushFailures += 1
                CallPathStats.onPushFail()
                if (consecutivePushFailures >= 3) {
                    val error = result.error ?: NativeError(
                        code = NativeErrorCode.AUDIO_CAPTURE_FAILED,
                        message = "Native audio frame push failed",
                        operation = "nativePushAudioFrame",
                        recoverable = true
                    )
                    failureSink?.invoke(error)
                    break
                }
            }
        } catch (error: CancellationException) {
            // Rethrown, not reported.
            //
            // `kotlinx.coroutines.CancellationException` is a `RuntimeException` on
            // the JVM, so a `catch (Throwable)` — or even a `catch (Exception)` —
            // catches it. Swallowing it here meant a loop that was deliberately
            // cancelled ran its failure path, `cancelAndJoin` observed a
            // "completed" job that had actually reported a device failure, and
            // the failure was attributed to whichever call happened to be running.
            //
            // This was the only place in the codebase that did this. The
            // repository layer rethrows it in 16 places; `NativeAwait` even calls
            // `coroutineContext.ensureActive()` before converting a result.
            throw error
        } catch (error: Throwable) {
            if (isCurrentGeneration(myGeneration)) {
                println("ERROR [LiveKitNativeAudioCapture] Capture failed: ${error.message}")
                // C1: a failed open/start used to be swallowed here — the call looked
                // live while nothing was ever captured. Surface it loudly instead.
                failureSink?.invoke(
                    NativeError(
                        code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                        message = error.message ?: error::class.simpleName.orEmpty(),
                        operation = "JVM_MIC_CAPTURE",
                        recoverable = true
                    )
                )
            } else {
                println("DEBUG [LiveKitNativeAudioCapture] capture generation $myGeneration failed after being superseded; not reporting")
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            synchronized(captureGeneration) {
                // Only tear down state if THIS loop still owns it. The old code
                // cleared `started` unconditionally, so a superseded loop's
                // `finally` retired the session that had replaced it — which then
                // saw itself as not-current and exited, taking the new call's mic
                // down with it. One stale loop could therefore kill two calls.
                if (activeLineGeneration == myGeneration) {
                    activeLine = null
                    activeLineGeneration = -1L
                    started = false
                }
            }
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop stopped (generation=$myGeneration)")
        }
    }

    private suspend fun runTestToneCaptureLoop(myGeneration: Long) {
        val channels = configuredPublishChannels()
        val frameBytes = SAMPLES_PER_CHANNEL * channels * BYTES_PER_SAMPLE
        println("DEBUG [LiveKitNativeAudioCapture] test tone enabled channels=$channels bytesPerFrame=$frameBytes")
        val heapBuffer = ByteArray(frameBytes)
        val directBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        val phaseStep = 2.0 * Math.PI * TEST_TONE_HZ / SAMPLE_RATE.toDouble()
        var diagnosticsLogged = false
        var consecutivePushFailures = 0
        var acceptingPollCounter = 0

        try {
            while (isCurrentGeneration(myGeneration) && kotlinx.coroutines.currentCoroutineContext().isActive) {
                phase = writeTestToneFrame(heapBuffer, phase, phaseStep, channels)
                if (!diagnosticsLogged) {
                    val stats = pcmStats(heapBuffer, frameBytes)
                    println("DEBUG [LiveKitNativeAudioCapture] tone peak=${stats.peak} tone RMS=${stats.rms} channels=$channels sampleRate=$SAMPLE_RATE samplesPerChannel=$SAMPLES_PER_CHANNEL bytes=$frameBytes durationMs=$FRAME_MS format=int16 signed little-endian first10Samples=${stats.firstSamples.joinToString(prefix = "[", postfix = "]")}")
                    diagnosticsLogged = true
                }

                acceptingPollCounter += 1
                if (acceptingPollCounter >= ACCEPTING_FRAMES_POLL_FRAMES) {
                    acceptingPollCounter = 0
                    if (!LiveKitNativeAudioBridge.acceptingFrames) {
                        delay(FRAME_MS.toLong())
                        continue
                    }
                }

                directBuffer.clear()
                directBuffer.put(heapBuffer, 0, frameBytes)
                directBuffer.flip()

                val result = bridge?.pushAudioFrame(
                    sessionId,
                    epoch,
                    directBuffer,
                    SAMPLE_RATE,
                    channels,
                    SAMPLES_PER_CHANNEL,
                    System.nanoTime()
                )
                if (result == null || result.ok) {
                    consecutivePushFailures = 0
                } else {
                    consecutivePushFailures += 1
                    if (consecutivePushFailures >= 3) {
                        failureSink?.invoke(
                            result.error ?: NativeError(
                                code = NativeErrorCode.AUDIO_CAPTURE_FAILED,
                                message = "Native test tone frame push failed",
                                operation = "nativePushAudioFrame",
                                recoverable = true
                            )
                        )
                        break
                    }
                }

                delay(FRAME_MS.toLong())
            }
        } finally {
            started = false
            println("DEBUG [LiveKitNativeAudioCapture] Test tone loop stopped")
        }
    }

    private fun enumerateInputMixers(format: AudioFormat, overrideName: String): List<InputMixerCandidate> {
        return AudioSystem.getMixerInfo().mapNotNull { mixerInfo ->
            val mixer = runCatching { AudioSystem.getMixer(mixerInfo) }.getOrNull() ?: return@mapNotNull null
            val targetInfos = mixer.targetLineInfo
                .filter { TargetDataLine::class.java.isAssignableFrom(it.lineClass) }
            if (targetInfos.isEmpty()) return@mapNotNull null

            val desiredInfo = DataLine.Info(TargetDataLine::class.java, format)
            val supportedFormats = targetInfos
                .filterIsInstance<DataLine.Info>()
                .flatMap { it.formats.toList() }
            val desiredFormatSupported = runCatching { mixer.isLineSupported(desiredInfo) }.getOrDefault(false) ||
                targetInfos.filterIsInstance<DataLine.Info>().any { it.isFormatSupported(format) }
            val searchable = "${mixerInfo.name} ${mixerInfo.description} ${mixerInfo.vendor} ${mixerInfo.version}"
            val overrideSearchable = "${mixerInfo.name} ${mixerInfo.description}"
            InputMixerCandidate(
                mixerInfo = mixerInfo,
                targetLineClasses = targetInfos.map { it.lineClass.name },
                supportedFormats = supportedFormats,
                desiredFormatSupported = desiredFormatSupported,
                overrideMatch = overrideName.isNotBlank() && overrideSearchable.contains(overrideName, ignoreCase = true),
                score = scoreInputMixer(searchable)
            )
        }
    }

    private fun logInputMixerInventory(candidates: List<InputMixerCandidate>, overrideName: String) {
        println("DEBUG [LiveKitNativeAudioCapture] audio input override orbit.audio.inputDeviceName=${overrideName.ifBlank { "<none>" }}")
        println("DEBUG [LiveKitNativeAudioCapture] TargetDataLine-capable input mixers=${candidates.size}")
        candidates.forEachIndexed { index, candidate ->
            val info = candidate.mixerInfo
            val formats = candidate.supportedFormats
                .take(8)
                .joinToString(" | ") { it.toDebugString() }
                .ifBlank { "<unspecified>" }
            val extra = if (candidate.supportedFormats.size > 8) " (+${candidate.supportedFormats.size - 8} more)" else ""
            // Behind the debug flag. This dumps every supported format of every
            // mixer, for every mic open, on every connect attempt -- hundreds of
            // lines that buried the one line that actually mattered
            // (AUDIO_DEVICE_UNAVAILABLE) in a wall of noise.
            if (OrbitLog.debugEnabled) {
                println(
                    "DEBUG [LiveKitNativeAudioCapture] input mixer #${index + 1}: name=${info.name} description=${info.description} vendor=${info.vendor} version=${info.version} targetLineClasses=${candidate.targetLineClasses.joinToString()} desiredFormatSupported=${candidate.desiredFormatSupported} overrideMatch=${candidate.overrideMatch} score=${candidate.score} supportedFormats=$formats$extra"
                )
            }
        }
        // Always say what was chosen, in one line, regardless of debug level.
        if (candidates.isNotEmpty()) {
            println(
                "DEBUG [LiveKitNativeAudioCapture] ${candidates.size} input mixer(s) available; " +
                    "override=${overrideName.ifBlank { "<none>" }}; " +
                    "names=${candidates.joinToString(", ") { it.mixerInfo.name }} " +
                    "(set -Dorbit.debug=true for the full inventory)"
            )
        }
    }

    private fun probeInputMixers(
        format: AudioFormat,
        candidates: List<InputMixerCandidate>,
        durationMs: Long
    ): List<MicProbeResult> {
        return candidates.map { candidate ->
            probeInputMixer(format, candidate, durationMs).also { result ->
                val status = result.error?.let { "error=$it" } ?: "ok"
                println(
                    "DEBUG [LiveKitNativeAudioCapture] mic self-test mixer=${result.mixerName} description=${result.mixerDescription} overrideMatch=${result.overrideMatch} desiredFormatSupported=${result.desiredFormatSupported} score=${result.score} min=${result.min} max=${result.max} peak=${result.peak} rms=${result.rms.formatRms()} first10Samples=${result.firstSamples.joinToString(prefix = "[", postfix = "]")} nonZeroSamples=${result.nonZeroSamples}/${result.totalSamples} $status"
                )
            }
        }
    }

    private fun probeInputMixer(
        format: AudioFormat,
        candidate: InputMixerCandidate,
        durationMs: Long
    ): MicProbeResult {
        val accumulator = PcmStatsAccumulator()
        var line: TargetDataLine? = null
        return try {
            line = openLineForCandidate(format, candidate) ?: return candidate.toProbeResult(
                stats = accumulator.toPcmStats(),
                totalSamples = 0,
                nonZeroSamples = 0,
                error = "TargetDataLine could not be opened"
            )
            line.open(format, CAPTURE_BUFFER_BYTES)
            line.start()
            runCatching { line.flush() }

            val buffer = ByteArray(FRAME_BYTES)
            val deadlineNs = System.nanoTime() + durationMs.coerceAtLeast(1L) * 1_000_000L
            while (System.nanoTime() < deadlineNs) {
                var offset = 0
                while (offset < FRAME_BYTES) {
                    val read = line.read(buffer, offset, FRAME_BYTES - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == FRAME_BYTES) {
                    accumulator.add(buffer, FRAME_BYTES)
                }
            }

            candidate.toProbeResult(
                stats = accumulator.toPcmStats(),
                totalSamples = accumulator.totalSamples,
                nonZeroSamples = accumulator.nonZeroSamples,
                error = null
            )
        } catch (error: Throwable) {
            candidate.toProbeResult(
                stats = accumulator.toPcmStats(),
                totalSamples = accumulator.totalSamples,
                nonZeroSamples = accumulator.nonZeroSamples,
                error = error.message ?: error::class.simpleName.orEmpty()
            )
        } finally {
            runCatching { line?.stop() }
            runCatching { line?.close() }
        }
    }

    private fun openLineForCandidate(format: AudioFormat, candidate: InputMixerCandidate): TargetDataLine? {
        val desiredInfo = DataLine.Info(TargetDataLine::class.java, format)
        val mixer = runCatching { AudioSystem.getMixer(candidate.mixerInfo) }.getOrNull() ?: return null
        return runCatching { mixer.getLine(desiredInfo) as? TargetDataLine }
            .onFailure { error ->
                println("WARN [LiveKitNativeAudioCapture] Could not open candidate input mixer ${candidate.mixerInfo.name}: ${error.message}")
            }
            .getOrNull()
    }

    private fun selectBestProbeResult(results: List<MicProbeResult>): MicProbeResult? {
        val usable = results.filter { it.error == null && it.desiredFormatSupported }
        val namedSpeechInputs = usable.filter { it.peak > 3 && !it.isPrimaryOrGenericInput() && it.score > -100 }
        val pool = namedSpeechInputs.ifEmpty { usable }
        return pool.maxWithOrNull(
            compareBy<MicProbeResult> { it.peak }
                .thenBy { it.rms }
                .thenBy { it.nonZeroSamples }
                .thenBy { it.score }
        )
    }

    private fun MicProbeResult.isPrimaryOrGenericInput(): Boolean {
        val text = "$mixerName $mixerDescription".lowercase()
        return "primary sound capture" in text || "java sound audio engine" in text || "default" in text
    }

    private fun openPreferredInputLine(
        format: AudioFormat,
        candidates: List<InputMixerCandidate>,
        overrideName: String,
        probeDurationMs: Long
    ): SelectedInputLine? {
        val usableCandidates = candidates.filter { it.desiredFormatSupported }
        val overrideMatches = if (overrideName.isNotBlank()) {
            usableCandidates.filter { it.overrideMatch }
        } else {
            emptyList()
        }
        // Same fallback as the self-test, and for the same reason: a configured
        // device that Java's DirectAudio mixer list cannot see must not stop
        // the call. Without this the self-test could pass on a fallback device
        // and then the real open would still refuse, failing the join.
        val candidatesToProbe = when {
            overrideName.isBlank() -> usableCandidates
            overrideMatches.isNotEmpty() -> overrideMatches
            else -> {
                println(
                    "WARN [LiveKitNativeAudioCapture] configured input '$overrideName' is not " +
                        "visible to Java's audio mixer list; falling back to the best available input."
                )
                usableCandidates
            }
        }

        if (candidatesToProbe.isEmpty()) {
            // C1: no device advertises the 48 kHz pipeline format � open one at its
            // native format and resample, instead of failing the call.
            openResampledFallbackLine(candidates, format)?.let { return it }
            println("ERROR [LiveKitNativeAudioCapture] no input mixer supports a convertible PCM format")
            return null
        }

        val preparedKey = preparedMixerKey
        val preparedResult = preparedProbeResult
        if (preparedKey != null && preparedOverrideName == overrideName) {
            val preparedCandidate = candidatesToProbe.firstOrNull { it.toMixerKey() == preparedKey }
            if (preparedCandidate != null) {
                println("DEBUG [LiveKitNativeAudioCapture] using pre-call microphone self-test mixer=${preparedCandidate.mixerInfo.name} probePeak=${preparedResult?.peak ?: 0} probeRms=${preparedResult?.rms?.formatRms() ?: "0.00"}")
                val line = openLineForCandidate(format, preparedCandidate)
                if (line != null) {
                    return SelectedInputLine(
                        line = line,
                        mixerInfo = preparedCandidate.mixerInfo,
                        defaultMixer = false,
                        overrideMatch = preparedCandidate.overrideMatch,
                        probeResult = preparedResult
                    )
                }
            } else {
                println("WARN [LiveKitNativeAudioCapture] pre-call microphone self-test mixer is no longer available")
            }
        }

        val results = probeInputMixers(format, candidatesToProbe, probeDurationMs)
        val selectedProbe = selectBestProbeResult(results)
        val selectedCandidate = selectedProbe?.let { result ->
            candidatesToProbe.firstOrNull { it.mixerInfo.name == result.mixerName && it.mixerInfo.description == result.mixerDescription }
        } ?: candidatesToProbe
            .sortedWith(
                compareByDescending<InputMixerCandidate> { it.overrideMatch }
                    .thenByDescending { it.score }
                    .thenBy { it.mixerInfo.name }
            )
            .firstOrNull()

        if (results.isEmpty() || results.all { it.peak <= 3 }) {
            println("WARN [LiveKitNativeAudioCapture] MIC_INPUT_TOO_LOW operation=JVM_MIC_CAPTURE message=\"$LOW_INPUT_MESSAGE\"")
        }

        if (selectedCandidate != null) {
            println("DEBUG [LiveKitNativeAudioCapture] auto-selected microphone mixer=${selectedCandidate.mixerInfo.name} probePeak=${selectedProbe?.peak ?: 0} probeRms=${selectedProbe?.rms?.formatRms() ?: "0.00"} overrideMatch=${selectedCandidate.overrideMatch} score=${selectedCandidate.score}")
            val line = openLineForCandidate(format, selectedCandidate)
            if (line != null) {
                return SelectedInputLine(
                    line = line,
                    mixerInfo = selectedCandidate.mixerInfo,
                    defaultMixer = false,
                    overrideMatch = selectedCandidate.overrideMatch,
                    probeResult = selectedProbe
                )
            }
        }

        if (overrideName.isNotBlank()) return null

        // C1: probes found no usable 48 kHz line � try a native-format + resample
        // open before degrading to the system default.
        openResampledFallbackLine(candidates, format)?.let { return it }

        val desiredInfo = DataLine.Info(TargetDataLine::class.java, format)
        return if (AudioSystem.isLineSupported(desiredInfo)) {
            println("WARN [LiveKitNativeAudioCapture] MIC_FALLBACK_DEFAULT operation=JVM_MIC_CAPTURE message=\"Falling back to system default TargetDataLine after mixer probe failed; if input stays silent, select another device in Settings > Voice & Video\"")
            SelectedInputLine(
                line = AudioSystem.getLine(desiredInfo) as TargetDataLine,
                mixerInfo = null,
                defaultMixer = true,
                overrideMatch = false,
                probeResult = selectedProbe
            )
        } else null
    }

    /**
     * C1 SRC fallback: open a mixer at its best natively advertised PCM_SIGNED 16-bit
     * format (rate nearest the pipeline rate, mono preferred). The capture loop detects
     * the format mismatch and converts through [PcmResampler].
     */
    private fun openResampledFallbackLine(
        candidates: List<InputMixerCandidate>,
        targetFormat: AudioFormat
    ): SelectedInputLine? {
        val targetRate = targetFormat.sampleRate.toInt()
        val ordered = candidates
            .sortedWith(
                compareByDescending<InputMixerCandidate> { it.score }
                    .thenBy { it.mixerInfo.name }
            )
        for (candidate in ordered) {
            val advertised = candidate.supportedFormats.mapNotNull { f ->
                if (f.encoding != AudioFormat.Encoding.PCM_SIGNED || f.sampleSizeInBits != 16) {
                    return@mapNotNull null
                }
                Pair(f.sampleRate.toInt(), f.channels)
            }.distinct()
            val picked = PcmResampler.pickNativeFormat(advertised, targetRate) ?: continue
            val nativeFormat = AudioFormat(
                picked.first.toFloat(),
                16,
                picked.second,
                true,
                false
            )
            val desiredInfo = DataLine.Info(TargetDataLine::class.java, nativeFormat)
            val mixer = runCatching { AudioSystem.getMixer(candidate.mixerInfo) }.getOrNull() ?: continue
            if (!runCatching { mixer.isLineSupported(desiredInfo) }.getOrDefault(false)) continue
            val line = openLineForCandidate(nativeFormat, candidate) ?: continue
            println(
                "WARN [LiveKitNativeAudioCapture] RESAMPLED_INPUT operation=JVM_MIC_CAPTURE message=\"No device supports ${targetFormat.toDebugString()}; opened ${candidate.mixerInfo.name} at ${nativeFormat.toDebugString()} and converting to ${targetRate} Hz mono\""
            )
            return SelectedInputLine(
                line = line,
                mixerInfo = candidate.mixerInfo,
                defaultMixer = false,
                overrideMatch = candidate.overrideMatch,
                probeResult = null,
                openFormat = nativeFormat
            )
        }
        return null
    }

    private fun scoreInputMixer(searchableText: String): Int {
        val text = searchableText.lowercase()
        var score = 0
        if ("microphone" in text || Regex("""\bmic\b""").containsMatchIn(text)) score += 100
        if ("headset" in text) score += 150
        if ("usb" in text) score += 150
        if ("array" in text) score += 50
        if ("webcam" in text || "camera" in text) score += 30
        if ("input" in text || "capture" in text || "communications" in text) score += 40
        if ("realtek" in text || "intel" in text) score += 20
        
        if ("primary sound capture" in text || "default" in text || "java sound" in text) score -= 60
        if ("virtual" in text || "cable" in text || "voicemeeter" in text || "droidcam" in text || "camo" in text) score -= 200
        if ("stereo mix" in text || "what u hear" in text || "wave out" in text || "loopback" in text || "monitor" in text) score -= 160
        if ("speaker" in text || "headphones" in text || "output" in text || "playback" in text || "renderer" in text || "hdmi" in text || "display audio" in text) score -= 220
        return score
    }

    private fun logSelectedInputLine(selected: SelectedInputLine, line: TargetDataLine) {
        val info = selected.mixerInfo
        println("DEBUG [LiveKitNativeAudioCapture] selected mixer name=${info?.name ?: "<system default>"}")
        println("DEBUG [LiveKitNativeAudioCapture] selected mixer description=${info?.description ?: "<system default>"}")
        println("DEBUG [LiveKitNativeAudioCapture] selected mixer vendor=${info?.vendor ?: "<system default>"}")
        println("DEBUG [LiveKitNativeAudioCapture] selected mixer version=${info?.version ?: "<system default>"}")
        println("DEBUG [LiveKitNativeAudioCapture] selected mixer default=${selected.defaultMixer} overrideMatch=${selected.overrideMatch}")
        println("DEBUG [LiveKitNativeAudioCapture] target line class=${line.javaClass.name}")
        println("DEBUG [LiveKitNativeAudioCapture] selected microphone/device: ${line.lineInfo}")
        println("DEBUG [LiveKitNativeAudioCapture] selected AudioFormat: ${line.format.toDebugString()}")
        selected.probeResult?.let { result ->
            println("DEBUG [LiveKitNativeAudioCapture] selected mixer probe min=${result.min} max=${result.max} peak=${result.peak} rms=${result.rms.formatRms()} first10Samples=${result.firstSamples.joinToString(prefix = "[", postfix = "]")} nonZeroSamples=${result.nonZeroSamples}/${result.totalSamples}")
        }
    }

    private fun logReadDiagnostics(
        line: TargetDataLine,
        frameBytes: Int,
        requestedBytes: Int,
        actualBytesRead: Int
    ) {
        if (diagnosticReadsLogged >= 10) return
        diagnosticReadsLogged += 1
        println(
            "DEBUG [LiveKitNativeAudioCapture] TargetDataLine read #$diagnosticReadsLogged frameBytes=$frameBytes requestedBytes=$requestedBytes actualBytesRead=$actualBytesRead line.isOpen=${line.isOpen} line.isActive=${line.isActive} line.isRunning=${line.isRunning} bufferSize=${line.bufferSize} available=${line.available()}"
        )
    }

    private fun observeInputLevel(rawStats: PcmStats) {
        if (lowInputWarningLogged || lowInputFramesObserved >= 100) return

        lowInputFramesObserved += 1
        if (rawStats.peak <= 3) {
            lowInputFramesAtOrBelowThreshold += 1
        }
        if (rawStats.peak > lowInputMaxPeak) {
            lowInputMaxPeak = rawStats.peak
        }

        if (lowInputFramesObserved == 100 && lowInputFramesAtOrBelowThreshold == 100) {
            val warning = NativeError(
                code = NativeErrorCode.MIC_INPUT_TOO_LOW,
                message = LOW_INPUT_MESSAGE,
                operation = "JVM_MIC_CAPTURE",
                recoverable = true
            )
            println("WARN [LiveKitNativeAudioCapture] ${warning.code} operation=${warning.operation} message=\"${warning.message}\" first100PeakMax=$lowInputMaxPeak")
            failureSink?.invoke(warning)
            lowInputWarningLogged = true
        }
    }


    private fun mixEffectIntoFrame(buffer: ByteArray, byteLength: Int, effect: FloatArray) {
        // Additive mono mix into every channel; effect samples are already volume-scaled.
        var index = 0
        var sampleIndex = 0
        while (index + 1 < byteLength) {
            val add = if (sampleIndex < effect.size) effect[sampleIndex] else break
            val current = readInt16LittleEndian(buffer, index)
            val mixed = (current + add * 32767f).toInt().coerceIn(-32768, 32767)
            buffer[index] = (mixed and 0xff).toByte()
            buffer[index + 1] = ((mixed shr 8) and 0xff).toByte()
            index += BYTES_PER_SAMPLE
            sampleIndex += 1
        }
    }

    private fun applyCaptureGain(buffer: ByteArray, byteLength: Int, gain: Double) {
        // Float-domain gain followed by a soft-knee limiter. The old int16 multiply + coerce
        // hard-clipped any voice that peaked above ~1/gain, which is exactly the "muffled/distorted"
        // symptom; with the knee, loud speech saturates smoothly and quiet speech is untouched.
        var index = 0
        var limitedSamples = 0
        while (index + 1 < byteLength) {
            val value = readInt16LittleEndian(buffer, index)
            val amplified = value * gain
            val outShort = softLimit(amplified)
            if (abs(amplified) > SOFT_LIMIT_KNEE) {
                limitedSamples += 1
            }
            buffer[index] = (outShort.toInt() and 0xff).toByte()
            buffer[index + 1] = ((outShort.toInt() shr 8) and 0xff).toByte()
            index += 2
        }
        CallPathStats.onMicClippedSamples(limitedSamples)
    }

    private fun softLimit(value: Double): Short {
        if (abs(value) <= SOFT_LIMIT_KNEE) {
            return value.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        val sign = if (value < 0.0) -1.0 else 1.0
        val over = abs(value) - SOFT_LIMIT_KNEE
        val limited = SOFT_LIMIT_KNEE +
            (SOFT_LIMIT_CEILING - SOFT_LIMIT_KNEE) * tanh(over / (SOFT_LIMIT_CEILING - SOFT_LIMIT_KNEE))
        return (sign * limited).roundToInt().toShort()
    }

    private const val SOFT_LIMIT_KNEE = 24_568.0 // ~-2.5 dBFS
    private const val SOFT_LIMIT_CEILING = 32_767.0

    private fun writeTestToneFrame(buffer: ByteArray, startingPhase: Double, phaseStep: Double, channels: Int): Double {
        var phase = startingPhase
        var index = 0
        repeat(SAMPLES_PER_CHANNEL) {
            val value = (kotlin.math.sin(phase) * TEST_TONE_PEAK.toDouble()).roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            repeat(channels) {
                buffer[index] = (value and 0xff).toByte()
                buffer[index + 1] = ((value shr 8) and 0xff).toByte()
                index += BYTES_PER_SAMPLE
            }
            phase += phaseStep
            if (phase >= 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI
            }
        }
        return phase
    }

    private fun logCapturedFrame(
        buffer: ByteArray,
        byteLength: Int,
        isMuted: Boolean,
        rawStats: PcmStats,
        captureGain: Double
    ) {
        if (diagnosticFramesLogged >= 10) return

        val stats = pcmStats(buffer, byteLength)
        diagnosticFramesLogged += 1
        println(
            "DEBUG [LiveKitNativeAudioCapture] captured mic frame #$diagnosticFramesLogged bytes=$byteLength min=${stats.min} max=${stats.max} rms=${stats.rms} peak=${stats.peak} allZero=${stats.allZero} muted=$isMuted gain=$captureGain rawMin=${rawStats.min} rawMax=${rawStats.max} rawRms=${rawStats.rms} rawPeak=${rawStats.peak} first10Samples=${stats.firstSamples.joinToString(prefix = "[", postfix = "]")}"
        )
        if (stats.allZero) {
            println("DEBUG [LiveKitNativeAudioCapture] captured mic frame is silence")
        } else {
            println("DEBUG [LiveKitNativeAudioCapture] captured mic frame RMS > 0")
        }
    }

    private fun pcmStats(buffer: ByteArray, byteLength: Int): PcmStats {
        var sumSquares = 0.0
        var peak = 0
        var min = 0
        var max = 0
        var allZero = true
        var samples = 0
        val firstSamples = mutableListOf<Int>()
        var index = 0
        while (index + 1 < byteLength) {
            val value = readInt16LittleEndian(buffer, index)
            val abs = kotlin.math.abs(value)
            if (value != 0) allZero = false
            if (abs > peak) peak = abs
            if (samples == 0 || value < min) min = value
            if (samples == 0 || value > max) max = value
            if (firstSamples.size < 10) firstSamples += value
            sumSquares += value.toDouble() * value.toDouble()
            samples += 1
            index += 2
        }
        val rms = if (samples == 0) 0.0 else sqrt(sumSquares / samples)
        return PcmStats(
            rms = "%.2f".format(rms),
            rmsValue = rms,
            peak = peak,
            min = min,
            max = max,
            allZero = allZero,
            firstSamples = firstSamples
        )
    }

    private fun readInt16LittleEndian(buffer: ByteArray, index: Int): Int {
        val low = buffer[index].toInt() and 0xff
        val high = buffer[index + 1].toInt()
        val sample = (high shl 8) or low
        return sample.toShort().toInt()
    }

    private fun configuredInputDeviceName(): String =
        inputDeviceOverride.ifBlank {
            orbitAudioConfig("orbit.audio.inputDeviceName", "ORBIT_AUDIO_INPUT_DEVICE_NAME").orEmpty()
        }

    private fun orbitAudioConfig(propertyName: String, environmentName: String): String? =
        System.getProperty(propertyName)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: System.getenv(environmentName)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

    private fun AudioFormat.toDebugString(): String =
        "sampleRate=${sampleRate.toInt()} channels=$channels sampleSizeBits=$sampleSizeInBits signed=${encoding == AudioFormat.Encoding.PCM_SIGNED} bigEndian=$isBigEndian frameSize=$frameSize frameRate=${frameRate.toInt()}"

    private fun Double.formatRms(): String = "%.2f".format(this)

    private fun InputMixerCandidate.toProbeResult(
        stats: PcmStats,
        totalSamples: Long,
        nonZeroSamples: Long,
        error: String?
    ): MicProbeResult =
        MicProbeResult(
            mixerName = mixerInfo.name,
            mixerDescription = mixerInfo.description,
            mixerVendor = mixerInfo.vendor,
            mixerVersion = mixerInfo.version,
            desiredFormatSupported = desiredFormatSupported,
            overrideMatch = overrideMatch,
            score = score,
            min = stats.min,
            max = stats.max,
            peak = stats.peak,
            rms = stats.rmsValue,
            firstSamples = stats.firstSamples,
            nonZeroSamples = nonZeroSamples,
            totalSamples = totalSamples,
            error = error
        )

    private class PcmStatsAccumulator {
        var totalSamples: Long = 0
            private set
        var nonZeroSamples: Long = 0
            private set
        private var sumSquares = 0.0
        private var peak = 0
        private var min = 0
        private var max = 0
        private val firstSamples = mutableListOf<Int>()

        fun add(buffer: ByteArray, byteLength: Int) {
            var index = 0
            while (index + 1 < byteLength) {
                val low = buffer[index].toInt() and 0xff
                val high = buffer[index + 1].toInt()
                val value = ((high shl 8) or low).toShort().toInt()
                val abs = kotlin.math.abs(value)
                if (value != 0) nonZeroSamples += 1
                if (abs > peak) peak = abs
                if (totalSamples == 0L || value < min) min = value
                if (totalSamples == 0L || value > max) max = value
                if (firstSamples.size < 10) firstSamples += value
                sumSquares += value.toDouble() * value.toDouble()
                totalSamples += 1
                index += 2
            }
        }

        fun toPcmStats(): PcmStats {
            val rms = if (totalSamples == 0L) 0.0 else sqrt(sumSquares / totalSamples.toDouble())
            return PcmStats(
                rms = rms.formatRms(),
                rmsValue = rms,
                peak = peak,
                min = min,
                max = max,
                allZero = nonZeroSamples == 0L,
                firstSamples = firstSamples.toList()
            )
        }
    }

    private data class MixerKey(
        val name: String,
        val description: String,
        val vendor: String,
        val version: String
    )

    private data class InputMixerCandidate(
        val mixerInfo: Mixer.Info,
        val targetLineClasses: List<String>,
        val supportedFormats: List<AudioFormat>,
        val desiredFormatSupported: Boolean,
        val overrideMatch: Boolean,
        val score: Int
    )

    private fun InputMixerCandidate.toMixerKey(): MixerKey =
        MixerKey(
            name = mixerInfo.name,
            description = mixerInfo.description,
            vendor = mixerInfo.vendor,
            version = mixerInfo.version
        )

    private fun MicProbeResult.toMixerKey(): MixerKey =
        MixerKey(
            name = mixerName,
            description = mixerDescription,
            vendor = mixerVendor,
            version = mixerVersion
        )

    private data class SelectedInputLine(
        val line: TargetDataLine,
        val mixerInfo: Mixer.Info?,
        val defaultMixer: Boolean,
        val overrideMatch: Boolean,
        val probeResult: MicProbeResult?,
        // Null = open at the 48 kHz pipeline format; non-null = device-native format
        // the capture loop converts to the pipeline rate via PcmResampler (C1).
        val openFormat: AudioFormat? = null
    )

    @Suppress("unused")
    private fun Line.Info.lineClassName(): String = lineClass.name

    private data class PcmStats(
        val rms: String,
        val rmsValue: Double,
        val peak: Int,
        val min: Int,
        val max: Int,
        val allZero: Boolean,
        val firstSamples: List<Int>
    )
}
