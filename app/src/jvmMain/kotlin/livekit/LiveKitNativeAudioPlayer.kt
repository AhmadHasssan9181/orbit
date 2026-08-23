package livekit

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import kotlinx.coroutines.*

object LiveKitNativeAudioPlayer : NativeMixedAudioSink {
    private const val UNDERRUN_WINDOW_NS = 250_000_000L

    private val lock = Any()
    private var line: SourceDataLine? = null
    private var currentSampleRate = 0
    private var currentChannels = 0
    @Volatile private var expectedSessionId = 0L
    @Volatile private var expectedEpoch = 0L
    @Volatile private var bridge: OrbitLiveKitNativeBridge? = null
    @Volatile private var errorSink: ((NativeError) -> Unit)? = null
    @Volatile private var playbackErrorReported = false
    @Volatile private var outputMuted = false
    @Volatile private var outputDeviceName = ""
    @Volatile private var outputVolume = 1f

    // Deafen support: keep draining the queue but stop writing to the speaker line,
    // so mic capture/publish stays up and no backlog builds up.
    fun setOutputMuted(muted: Boolean) {
        if (outputMuted == muted) return
        outputMuted = muted
        println("DEBUG [LiveKitNativeAudioPlayer] playback output muted=$muted")
    }

    // Voice & Video settings: substring match against JavaSound mixer names.
    // Applies when the next playback line is opened (next call or device change).
    fun setOutputDeviceName(name: String) {
        val trimmed = name.trim()
        val next = if (trimmed.isEmpty() || trimmed.equals("Default", ignoreCase = true)) "" else trimmed
        if (outputDeviceName == next) return
        outputDeviceName = next
        closeLine()
        println("DEBUG [LiveKitNativeAudioPlayer] output device override=\"$next\"")
    }

    fun setOutputVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        if (outputVolume == clamped) return
        outputVolume = clamped
        println("DEBUG [LiveKitNativeAudioPlayer] output volume=$clamped")
    }

    // Full-device share-audio loopback (Windows without process-loopback exclusion)
    // re-captures our own playback and feeds it back forever. Ducking keeps loop
    // gain below unity so repetitions decay instead of building up.
    @Volatile private var loopbackDuckFactor = 1f

    fun setLoopbackDuckEnabled(enabled: Boolean) {
        val next = if (enabled) 0.3f else 1f
        if (loopbackDuckFactor == next) return
        loopbackDuckFactor = next
        println("DEBUG [LiveKitNativeAudioPlayer] share-loopback playback duck=$next")
    }

    private val audioQueue = LinkedBlockingQueue<ByteArray>(100) // Buffer up to ~1 sec without blocking C++
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playerJob: Job? = null
    private val lastPcmAtNs = AtomicLong(0L)

    fun start(
        sessionId: Long,
        epoch: Long,
        nativeBridge: OrbitLiveKitNativeBridge,
        onError: (NativeError) -> Unit
    ) {
        expectedSessionId = sessionId
        expectedEpoch = epoch
        bridge = nativeBridge
        errorSink = onError
        playbackErrorReported = false
        // Preserve outputMuted across restarts (e.g. reconnect): un-deafening is
        // owned by CallManager, which explicitly clears it on call end.
        audioQueue.clear()
        CallPathStats.ensureReporter()
        startPlayerLoop()
        nativeBridge.setMixedAudioSink(this)
    }

    private fun startPlayerLoop() {
        playerJob?.cancel()
        playerJob = scope.launch {
            println("DEBUG [LiveKitNativeAudioPlayer] Async playback loop started")
            while (isActive) {
                val pcm = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    CallPathStats.onPlayerIdle()
                    continue
                }
                if (outputMuted) continue // Deafened: discard frame, keep queue draining
                lastPcmAtNs.set(System.nanoTime())
                val (sr, ch) = synchronized(lock) { currentSampleRate to currentChannels }
                if (sr <= 0 || ch <= 0) continue
                val out = synchronized(lock) { ensureLine(sr, ch) } ?: continue
                runCatching {
                    // True underrun = output line fully drained while remote frames are still flowing
                    val available = runCatching { out.available() }.getOrDefault(-1)
                    if (available >= out.bufferSize &&
                        System.nanoTime() - lastPcmAtNs.get() < UNDERRUN_WINDOW_NS
                    ) {
                        CallPathStats.onPlayerUnderrun()
                    }
                    if (OrbitApm.enabled) {
                        OrbitApm.processRender(pcm, sr, ch)
                    }
                    val vol = outputVolume * loopbackDuckFactor
                    val outPcm = if (vol != 1f) applyOutputVolume(pcm, vol) else pcm
                    // Reference must match what actually hits the speakers so the
                    // share-path AEC subtracts exactly Orbit's audible contribution.
                    ShareAudioAec.processRender(outPcm, sr, ch)
                    out.write(outPcm, 0, outPcm.size)
                    CallPathStats.onPlayerFramePlayed()
                }.onFailure { error ->
                    CallPathStats.onPlayerWriteFailure()
                    synchronized(lock) {
                        reportPlaybackError(
                            code = NativeErrorCode.REMOTE_AUDIO_FAILED,
                            message = error.message ?: "JVM audio playback write failed"
                        )
                        closeLine()
                    }
                }
            }
        }
    }

    fun stop() {
        bridge?.setMixedAudioSink(null)
        bridge = null
        errorSink = null
        expectedSessionId = 0L
        expectedEpoch = 0L
        playerJob?.cancel()
        playerJob = null
        audioQueue.clear()
        closeLine()
    }

    override fun onMixedAudioFrame(
        sessionId: Long,
        epoch: Long,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int,
        timestampNs: Long
    ) {
        if (sessionId != expectedSessionId || epoch != expectedEpoch) return
        if (pcm.isEmpty() || sampleRate <= 0 || channels <= 0 || samplesPerChannel <= 0) return

        synchronized(lock) {
            if (currentSampleRate != sampleRate || currentChannels != channels) {
                currentSampleRate = sampleRate
                currentChannels = channels
                ensureLine(sampleRate, channels)
            }
        }

        if (!audioQueue.offer(pcm.copyOf())) {
            // Queue full: drop oldest frame to maintain low latency without blocking native C++
            audioQueue.poll()
            audioQueue.offer(pcm.copyOf())
            CallPathStats.onPlayerDroppedOldest()
        }
    }

    private fun ensureLine(sampleRate: Int, channels: Int): SourceDataLine? {
        val existing = line
        if (existing != null && currentSampleRate == sampleRate && currentChannels == channels) {
            return existing
        }

        closeLine()

        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (!AudioSystem.isLineSupported(info)) {
            reportPlaybackError(
                code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                message = "Audio output line not supported for ${sampleRate}Hz/${channels}ch"
            )
            return null
        }

        return runCatching {
            val mixer = resolveOutputMixer(info)
            (if (mixer != null) {
                println("DEBUG [LiveKitNativeAudioPlayer] playback line on mixer=${mixer.name}")
                AudioSystem.getSourceDataLine(format, mixer)
            } else {
                AudioSystem.getLine(info) as SourceDataLine
            }).also { newLine ->
                val bufferSize = sampleRate * channels * 2 / 12 // ~83 ms buffer (was 250 ms — added latency)
                newLine.open(format, bufferSize)
                newLine.start()
                line = newLine
                currentSampleRate = sampleRate
                currentChannels = channels
                println("DEBUG [LiveKitNativeAudioPlayer] Playback line started")
            }
        }.getOrElse { error ->
            reportPlaybackError(
                code = NativeErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                message = error.message ?: "Failed to start JVM audio playback"
            )
            null
        }
    }

    private fun resolveOutputMixer(info: DataLine.Info): Mixer.Info? {
        if (outputDeviceName.isBlank()) return null
        return AudioSystem.getMixerInfo().firstOrNull { candidate ->
            ("${candidate.name} ${candidate.description}").contains(outputDeviceName, ignoreCase = true) &&
                runCatching { AudioSystem.getMixer(candidate).isLineSupported(info) }.getOrDefault(false)
        } ?: run {
            println("WARN [LiveKitNativeAudioPlayer] output device \"$outputDeviceName\" matched no mixer, using default")
            null
        }
    }

    // Scales int16 little-endian PCM in place. Queue frames are private per loop
    // iteration (enqueued as copies), so mutation is safe.
    private fun applyOutputVolume(pcm: ByteArray, factor: Float): ByteArray {
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (((pcm[i + 1].toInt()) shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val scaled = (sample * factor).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[i] = (scaled and 0xFF).toByte()
            pcm[i + 1] = ((scaled shr 8) and 0xFF).toByte()
            i += 2
        }
        return pcm
    }

    private fun reportPlaybackError(code: NativeErrorCode, message: String) {
        if (playbackErrorReported) return
        playbackErrorReported = true
        val error = NativeError(
            code = code,
            message = message,
            operation = "JVM_AUDIO_PLAYBACK",
            recoverable = true
        )
        println("ERROR [LiveKitNativeAudioPlayer] ${error.code} ${error.operation}: ${error.message}")
        errorSink?.invoke(error)
    }

    private fun closeLine() {
        synchronized(lock) {
            runCatching { line?.drain() }
            runCatching { line?.stop() }
            runCatching { line?.close() }
            line = null
            currentSampleRate = 0
            currentChannels = 0
        }
    }
}
