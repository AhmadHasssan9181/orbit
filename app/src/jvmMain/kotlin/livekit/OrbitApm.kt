package livekit

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig

/**
 * WebRTC AudioProcessing module (AEC3 + NS + AGC2 + HPF) driven by the forked
 * webrtc-java natives, applied on the LiveKit group-call audio path.
 *
 * Capture frames run through processStream before being pushed to the native
 * bridge; the mixed playout frames are fed back through processReverseStream
 * so the echo canceller gets a real far-end reference.
 */
object OrbitApm {
    @Volatile private var apm: AudioProcessing? = null
    @Volatile private var initFailed = false

    // Voice & Video settings (session source of truth); consumed by buildConfig()
    // and re-applied live via applyConfig when they change mid-call.
    @Volatile private var nsEnabled = true
    @Volatile private var ecEnabled = true
    @Volatile private var agcEnabled = true

    fun setAudioProcessingOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        val changed = noiseSuppression != nsEnabled || echoCancellation != ecEnabled || autoGainControl != agcEnabled
        nsEnabled = noiseSuppression
        ecEnabled = echoCancellation
        agcEnabled = autoGainControl
        val processor = apm ?: return
        if (!changed) return
        // processLock FIRST, then stateLock — the same order stop() uses, so the
        // pair cannot invert. applyConfig re-initialises the AEC3/NS/AGC2 state
        // inside the native object, so it must not land in the middle of an
        // in-flight processStream; taking only stateLock did not exclude the
        // processing threads, which use a different lock.
        synchronized(processLock) {
            synchronized(stateLock) {
                if (apm == null) return
                runCatching {
                    processor.applyConfig(buildConfig())
                    println(
                        "DEBUG [OrbitApm] config updated ns=$noiseSuppression ec=$echoCancellation " +
                            "agc2=$autoGainControl hpf=true"
                    )
                }.onFailure { error ->
                    println("WARN [OrbitApm] applyConfig failed: ${error.message}")
                }
            }
        }
    }

    @Volatile private var captureConfig: AudioProcessingStreamConfig =
        AudioProcessingStreamConfig(LiveKitNativeAudioCapture.SAMPLE_RATE, LiveKitNativeAudioCapture.CHANNELS)

    // Only touched from the playback loop thread; grows to the largest render chunk seen.
    private var renderChunk: ByteArray = ByteArray(0)

    private val stateLock = Any()
    // processCapture runs on the IO capture coroutine while processRender runs on
    // the dedicated orbit-audio-playback thread; one AudioProcessing instance is
    // NOT reentrant, so concurrent calls corrupt AEC/AGC/NS internal state.
    // EVERY native call on `apm` takes this lock — processStream,
    // processReverseStream, applyConfig, setStreamDelayMs and dispose — because
    // the corruption is in the native object, not in one particular entry point.
    // Lock order is processLock -> stateLock, always.
    private val processLock = Any()
    private var firstCaptureLogged = false
    private var firstRenderLogged = false
    private var captureFailures = 0L
    private var renderFailures = 0L

    val enabled: Boolean
        get() = System.getProperty("orbit.apm.enabled", "true") != "false" && !initFailed

    fun ensureStarted(): Boolean {
        if (!enabled) return false
        apm?.let { return true }
        return synchronized(stateLock) {
            apm?.let { return true }
            try {
                val processor = AudioProcessing()
                processor.applyConfig(buildConfig())
                processor.setStreamDelayMs(configuredStreamDelayMs())
                apm = processor
                println(
                    "DEBUG [OrbitApm] started ec3=${ecEnabled} ns=$nsEnabled agc2=$agcEnabled hpf=true " +
                        "preGain=${configuredCapturePreGain()} delayMs=${configuredStreamDelayMs()}"
                )
                true
            } catch (error: Throwable) {
                initFailed = true
                println("ERROR [OrbitApm] initialization failed, group audio continues unprocessed: ${error.message}")
                false
            }
        }
    }

    fun stop() {
        // processLock is taken first so a dispose can never land in the middle of
        // an in-flight processStream/processReverseStream on the native object.
        synchronized(processLock) {
            synchronized(stateLock) {
                runCatching { apm?.dispose() }
                apm = null
                firstCaptureLogged = false
                firstRenderLogged = false
            }
        }
    }

    /**
     * Processes one 10 ms interleaved int16 capture frame in place.
     * Returns true when the frame was processed.
     */
    fun processCapture(buffer: ByteArray, byteLength: Int, sampleRate: Int, channels: Int): Boolean {
        val config = captureConfig.takeIf { it.sampleRate == sampleRate && it.channels == channels }
            ?: AudioProcessingStreamConfig(sampleRate, channels).also { captureConfig = it }
        val result = synchronized(processLock) {
            // Re-read the handle INSIDE the lock. Reading it outside meant a
            // stop() could dispose the native object in the gap and the wait
            // here would then call processStream on freed memory.
            val processor = apm ?: return false
            runCatching {
                processor.processStream(buffer, config, config, buffer)
            }.getOrElse { error ->
                logCaptureFailure("processCapture threw: ${error.message}")
                return false
            }
        }
        if (result != 0) {
            logCaptureFailure("processCapture returned $result")
            return false
        }
        if (!firstCaptureLogged) {
            firstCaptureLogged = true
            println(
                "DEBUG [OrbitApm] first processed capture frame " +
                    "peak=${int16Peak(buffer, byteLength)} rate=$sampleRate channels=$channels"
            )
        }
        return true
    }

    /**
     * Feeds mixed playout audio to the echo canceller as far-end reference.
     * Accepts arbitrary frame sizes; internally processes 10 ms chunks.
     */
    fun processRender(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (apm == null) return
        val frameSamples = sampleRate / 100
        val frameBytes = frameSamples * channels * 2
        if (frameBytes <= 0 || pcm.size < frameBytes) return

        if (renderChunk.size < frameBytes) {
            renderChunk = ByteArray(frameBytes)
        }
        val config = AudioProcessingStreamConfig(sampleRate, channels)
        var offset = 0
        while (offset + frameBytes <= pcm.size) {
            System.arraycopy(pcm, offset, renderChunk, 0, frameBytes)
            val result = synchronized(processLock) {
                // Re-read inside the lock, for the same dispose-race reason as
                // processCapture.
                val processor = apm ?: return
                runCatching {
                    processor.processReverseStream(renderChunk, config, config, renderChunk)
                }.getOrElse { error ->
                    logRenderFailure("processReverseStream threw: ${error.message}")
                    return
                }
            }
            if (result != 0) {
                logRenderFailure("processReverseStream returned $result")
                return
            }
            offset += frameBytes
        }
        if (!firstRenderLogged) {
            firstRenderLogged = true
            println("DEBUG [OrbitApm] far-end reference flowing rate=$sampleRate channels=$channels")
        }
    }

    private fun buildConfig(): AudioProcessingConfig {
        val config = AudioProcessingConfig()
        config.echoCanceller.enabled = ecEnabled
        config.echoCanceller.enforceHighPassFiltering = true
        // HPF stays on independently of NS/AGC (voice-path hygiene).
        config.highPassFilter.enabled = true
        config.noiseSuppression.enabled = nsEnabled
        config.noiseSuppression.level = AudioProcessingConfig.NoiseSuppression.Level.MODERATE

        // AGC1 analog control is meaningless on desktop WASAPI lines; AGC2
        // adaptive digital owns loudness management now.
        config.gainController.enabled = false
        config.gainController.mode = AudioProcessingConfig.GainController.Mode.AdaptiveDigital
        config.gainControllerDigital.enabled = agcEnabled
        config.gainControllerDigital.adaptiveDigital.enabled = agcEnabled
        config.gainControllerDigital.adaptiveDigital.headroomDb = 6.0f
        config.gainControllerDigital.adaptiveDigital.initialGainDb = 15.0f
        config.gainControllerDigital.adaptiveDigital.maxGainDb = 40.0f
        config.gainControllerDigital.adaptiveDigital.maxGainChangeDbPerSecond = 6.0f
        config.gainControllerDigital.adaptiveDigital.maxOutputNoiseLevelDbfs = -50.0f

        val preGain = configuredCapturePreGain()
        if (preGain != 1.0) {
            config.captureLevelAdjustment.enabled = true
            config.captureLevelAdjustment.preGainFactor = preGain.toFloat()
        }
        return config
    }

    private fun configuredCapturePreGain(): Double =
        System.getProperty("orbit.audio.captureGain")?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?: System.getenv("ORBIT_AUDIO_CAPTURE_GAIN")?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            ?: 1.0

    private fun configuredStreamDelayMs(): Int =
        System.getProperty("orbit.apm.delayMs")?.trim()?.toIntOrNull()?.coerceIn(0, 500) ?: 140

    private fun logCaptureFailure(message: String) {
        captureFailures += 1
        if (captureFailures <= 5) {
            println("WARN [OrbitApm] capture processing failure ($captureFailures): $message")
        }
    }

    private fun logRenderFailure(message: String) {
        renderFailures += 1
        if (renderFailures <= 5) {
            println("WARN [OrbitApm] render reference failure ($renderFailures): $message")
        }
    }

    private fun int16Peak(buffer: ByteArray, byteLength: Int): Int {
        var peak = 0
        var index = 0
        while (index + 1 < byteLength) {
            val low = buffer[index].toInt() and 0xff
            val high = buffer[index + 1].toInt()
            val value = (high shl 8) or low
            val magnitude = if (value < 0) -value else value
            if (magnitude > peak) peak = magnitude
            index += 2
        }
        return peak
    }
}
