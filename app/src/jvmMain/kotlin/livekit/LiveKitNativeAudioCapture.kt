package livekit

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    @Volatile private var activeLine: TargetDataLine? = null
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
    @Volatile private var preparedMixerKey: MixerKey? = null
    @Volatile private var preparedProbeResult: MicProbeResult? = null
    @Volatile private var preparedOverrideName: String = ""

    private const val LOW_INPUT_MESSAGE =
        "Captured microphone PCM is near silence. Check Windows mic level or select another input device."

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
        if (started) return true

        LiveKitNativeAudioBridge.resetFrameDropLog()
        if (!LiveKitNativeAudioBridge.acceptingFrames) {
            println("ERROR [LiveKitNativeAudioCapture] Native C++ audio is not accepting frames")
            return false
        }

        this.sessionId = sessionId
        this.epoch = epoch
        bridge = nativeBridge
        failureSink = onFailure
        diagnosticFramesLogged = 0
        diagnosticReadsLogged = 0
        lowInputFramesObserved = 0
        lowInputFramesAtOrBelowThreshold = 0
        lowInputMaxPeak = 0
        lowInputWarningLogged = false
        gainLogged = false
        started = true
        captureJob = scope.launch {
            runCaptureLoop()
        }
        return true
    }

    private fun requestStop(): Job? {
        val job = captureJob
        started = false
        activeLine?.let { line ->
            runCatching { line.stop() }
            runCatching { line.close() }
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

    fun stopAndJoin() {
        val job = requestStop()
        if (job != null) {
            runBlocking {
                job.cancelAndJoin()
            }
        }
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
                println("ERROR [LiveKitNativeAudioCapture] orbit.audio.inputDeviceName matched no TargetDataLine input device: $overrideName")
            }
            matches
        } else {
            candidates
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
            println("DEBUG [LiveKitNativeAudioCapture] microphone self-test selected mixer=${selected.mixerName} peak=${selected.peak} rms=${selected.rms.formatRms()} first10Samples=${selected.firstSamples.joinToString(prefix = "[", postfix = "]")} nonZeroSamples=${selected.nonZeroSamples}/${selected.totalSamples}")
        } else {
            preparedMixerKey = null
            preparedProbeResult = null
            preparedOverrideName = overrideName
        }
        return MicCaptureSelfTestResult(
            selectedMixerName = selected?.mixerName,
            selectedMixerDescription = selected?.mixerDescription,
            selectedPeak = selected?.peak ?: 0,
            selectedRms = selected?.rms ?: 0.0,
            overrideName = overrideName,
            allInputsTooLow = allInputsTooLow,
            results = results
        )
    }

    fun prepareMicrophoneForCall(durationMs: Long = 3000): NativeResult<MicCaptureSelfTestResult> {
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

    private suspend fun runCaptureLoop() {
        val testTone = configuredTestTone()
        val testToneStereo = configuredTestToneStereo()
        val overrideName = configuredInputDeviceName()
        val captureGain = orbitAudioConfig("orbit.audio.captureGain", "ORBIT_AUDIO_CAPTURE_GAIN")
            ?.toDoubleOrNull()
            ?.coerceIn(0.0, 100.0)
            ?: 8.0
        println("DEBUG [LiveKitNativeAudioCapture] audio flags: testTone=$testTone testToneStereo=$testToneStereo captureGain=$captureGain inputDeviceName=${overrideName.ifBlank { "<none>" }} publishChannels=${configuredPublishChannels()}")
        if (testTone) {
            runTestToneCaptureLoop()
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

        val line = selectedLine.line
        activeLine = line
        try {
            line.open(format, CAPTURE_BUFFER_BYTES)
            line.start()
            runCatching { line.flush() }
            logSelectedInputLine(selectedLine, line)
            println("DEBUG [LiveKitNativeAudioCapture] capture line flushed after start available=${line.available()} bufferSize=${line.bufferSize}")
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop started")

            while (started && kotlinx.coroutines.currentCoroutineContext().isActive) {
                val requested = FRAME_BYTES - frameOffset
                val read = line.read(heapBuffer, frameOffset, requested)
                logReadDiagnostics(line, FRAME_BYTES, requested, read)
                if (read <= 0) continue

                frameOffset += read
                if (frameOffset < FRAME_BYTES) continue
                frameOffset = 0

                val rawStats = pcmStats(heapBuffer, FRAME_BYTES)
                observeInputLevel(rawStats)
                if (!muted && captureGain != 1.0 && rawStats.peak > 0) {
                    applyCaptureGain(heapBuffer, FRAME_BYTES, captureGain)
                    if (!gainLogged) {
                        val gainedStats = pcmStats(heapBuffer, FRAME_BYTES)
                        println("DEBUG [LiveKitNativeAudioCapture] capture gain applied gain=$captureGain rawPeak=${rawStats.peak} rawRms=${rawStats.rms} gainedPeak=${gainedStats.peak} gainedRms=${gainedStats.rms}")
                        gainLogged = true
                    }
                }
                logCapturedFrame(heapBuffer, FRAME_BYTES, muted, rawStats, captureGain)
                if (muted) {
                    heapBuffer.fill(0, 0, FRAME_BYTES)
                }

                if (!LiveKitNativeAudioBridge.acceptingFrames) {
                    continue
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
                    continue
                }

                consecutivePushFailures += 1
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
        } catch (error: Throwable) {
            if (started) {
                println("ERROR [LiveKitNativeAudioCapture] Capture failed: ${error.message}")
            }
        } finally {
            runCatching { line.stop() }
            runCatching { line.close() }
            if (activeLine === line) {
                activeLine = null
            }
            started = false
            println("DEBUG [LiveKitNativeAudioCapture] Recording loop stopped")
        }
    }

    private suspend fun runTestToneCaptureLoop() {
        val channels = configuredPublishChannels()
        val frameBytes = SAMPLES_PER_CHANNEL * channels * BYTES_PER_SAMPLE
        println("DEBUG [LiveKitNativeAudioCapture] test tone enabled channels=$channels bytesPerFrame=$frameBytes")
        val heapBuffer = ByteArray(frameBytes)
        val directBuffer = ByteBuffer.allocateDirect(frameBytes).order(ByteOrder.LITTLE_ENDIAN)
        var phase = 0.0
        val phaseStep = 2.0 * Math.PI * TEST_TONE_HZ / SAMPLE_RATE.toDouble()
        var diagnosticsLogged = false
        var consecutivePushFailures = 0

        try {
            while (started && kotlinx.coroutines.currentCoroutineContext().isActive) {
                phase = writeTestToneFrame(heapBuffer, phase, phaseStep, channels)
                if (!diagnosticsLogged) {
                    val stats = pcmStats(heapBuffer, frameBytes)
                    println("DEBUG [LiveKitNativeAudioCapture] tone peak=${stats.peak} tone RMS=${stats.rms} channels=$channels sampleRate=$SAMPLE_RATE samplesPerChannel=$SAMPLES_PER_CHANNEL bytes=$frameBytes durationMs=$FRAME_MS format=int16 signed little-endian first10Samples=${stats.firstSamples.joinToString(prefix = "[", postfix = "]")}")
                    diagnosticsLogged = true
                }

                if (!LiveKitNativeAudioBridge.acceptingFrames) {
                    delay(FRAME_MS.toLong())
                    continue
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
            println(
                "DEBUG [LiveKitNativeAudioCapture] input mixer #${index + 1}: name=${info.name} description=${info.description} vendor=${info.vendor} version=${info.version} targetLineClasses=${candidate.targetLineClasses.joinToString()} desiredFormatSupported=${candidate.desiredFormatSupported} overrideMatch=${candidate.overrideMatch} score=${candidate.score} supportedFormats=$formats$extra"
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
        val candidatesToProbe = if (overrideName.isNotBlank()) {
            usableCandidates.filter { it.overrideMatch }
        } else {
            usableCandidates
        }

        if (overrideName.isNotBlank() && candidatesToProbe.isEmpty()) {
            println("ERROR [LiveKitNativeAudioCapture] orbit.audio.inputDeviceName did not match a usable input mixer: $overrideName")
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

        val desiredInfo = DataLine.Info(TargetDataLine::class.java, format)
        return if (AudioSystem.isLineSupported(desiredInfo)) {
            println("WARN [LiveKitNativeAudioCapture] Falling back to system default TargetDataLine after mixer probe")
            SelectedInputLine(
                line = AudioSystem.getLine(desiredInfo) as TargetDataLine,
                mixerInfo = null,
                defaultMixer = true,
                overrideMatch = false,
                probeResult = selectedProbe
            )
        } else null
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


    private fun applyCaptureGain(buffer: ByteArray, byteLength: Int, gain: Double) {
        var index = 0
        while (index + 1 < byteLength) {
            val value = readInt16LittleEndian(buffer, index)
            val amplified = (value * gain).roundToInt()
            val outShort = amplified.coerceIn(-32768, 32767)
            buffer[index] = (outShort and 0xff).toByte()
            buffer[index + 1] = ((outShort shr 8) and 0xff).toByte()
            index += 2
        }
    }

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
        orbitAudioConfig("orbit.audio.inputDeviceName", "ORBIT_AUDIO_INPUT_DEVICE_NAME").orEmpty()

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
        val probeResult: MicProbeResult?
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
