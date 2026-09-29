package livekit

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import kotlin.math.roundToInt
import kotlinx.coroutines.*

/**
 * Content ground-truth capture for the playback path, gated by
 * -Dorbit.audio.dumpPlayback=<base path>. Writes the raw arriving mix and the
 * final pre-write buffer as 16-bit WAV files so a garbled call can be analyzed
 * numerically (RMS/zero-crossing windows) instead of guessed at from level stats.
 * Capped at 90 s per stream per session; headers are finalized on close or JVM exit.
 */
private object PlaybackDump {
    private const val MAX_FRAMES = 9_000 // 90 s at 10 ms frames
    private val basePath = System.getProperty("orbit.audio.dumpPlayback")?.trim()?.takeUnless { it.isEmpty() }
    val enabled: Boolean get() = basePath != null

    private val lock = Any()
    private var inStream: java.io.DataOutputStream? = null
    private var outStream: java.io.DataOutputStream? = null
    private var inFile: java.io.File? = null
    private var outFile: java.io.File? = null
    private var sampleRate = 0
    private var channels = 0
    private var framesIn = 0L
    private var framesOut = 0L
    private var closedLogged = false

    fun noteFormat(sr: Int, ch: Int) {
        if (!enabled) return
        synchronized(lock) {
            if (sampleRate != 0) return
            sampleRate = sr
            channels = ch
            val base = basePath ?: return
            val parent = java.io.File(base).parentFile
            parent?.mkdirs()
            inFile = java.io.File("$base.in.wav")
            outFile = java.io.File("$base.out.wav")
            runCatching {
                inStream = openWav(inFile!!, sr, ch)
                outStream = openWav(outFile!!, sr, ch)
                println("DEBUG [PlaybackDump] capturing raw-in + final-out to $base.{in,out}.wav sr=$sr ch=$ch cap=${MAX_FRAMES} frames")
            }.onFailure { error ->
                println("WARN [PlaybackDump] open failed: ${error.message}")
                inStream = null
                outStream = null
            }
        }
    }

    fun dumpIn(pcm: ByteArray) = writeFrame(isInput = true, pcm)

    fun dumpOut(pcm: ByteArray) = writeFrame(isInput = false, pcm)

    private fun writeFrame(isInput: Boolean, pcm: ByteArray) {
        if (!enabled) return
        synchronized(lock) {
            val s = ((if (isInput) inStream else outStream)) ?: return
            var count = if (isInput) framesIn else framesOut
            if (count >= MAX_FRAMES.toLong()) return
            runCatching { s.write(pcm) }.onFailure { error ->
                println("WARN [PlaybackDump] write failed: ${error.message}")
                closeLocked()
                return
            }
            count += 1
            if (isInput) framesIn = count else framesOut = count
            if (count == MAX_FRAMES.toLong()) closeLocked()
        }
    }

    fun close() {
        if (!enabled) return
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        val i = framesIn
        val o = framesOut
        runCatching { inStream?.close() }
        runCatching { outStream?.close() }
        inStream = null
        outStream = null
        finalizeHeader(inFile)
        finalizeHeader(outFile)
        if (!closedLogged && (i > 0 || o > 0)) {
            closedLogged = true
            println("DEBUG [PlaybackDump] closed framesIn=$i framesOut=$o files=${inFile?.path} ${outFile?.path}")
        }
    }

    private fun finalizeHeader(file: java.io.File?) {
        file ?: return
        if (!file.exists()) return
        runCatching {
            java.io.RandomAccessFile(file, "rw").use { raf ->
                val dataSize = raf.length() - 44L
                writeLeInt(raf, 4, dataSize.toInt() + 36)
                writeLeInt(raf, 40, dataSize.toInt())
            }
        }
    }

    private fun openWav(file: java.io.File, sr: Int, ch: Int): java.io.DataOutputStream {
        if (file.exists()) file.delete()
        val out = java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(file), 1 shl 16))
        val byteRate = sr * ch * 2
        out.writeBytes("RIFF")
        writeLeInt(out, 0) // patched on close
        out.writeBytes("WAVEfmt ")
        writeLeInt(out, 16)
        writeLeShort(out, 1) // PCM
        writeLeShort(out, ch.toShort())
        writeLeInt(out, sr)
        writeLeInt(out, byteRate)
        writeLeShort(out, (ch * 2).toShort())
        writeLeShort(out, 16)
        out.writeBytes("data")
        writeLeInt(out, 0) // patched on close
        return out
    }

    private fun writeLeInt(out: java.io.DataOutputStream, v: Int) {
        out.writeByte(v and 0xff)
        out.writeByte((v shr 8) and 0xff)
        out.writeByte((v shr 16) and 0xff)
        out.writeByte((v shr 24) and 0xff)
    }

    private fun writeLeShort(out: java.io.DataOutputStream, v: Short) {
        out.writeByte(v.toInt() and 0xff)
        out.writeByte((v.toInt() shr 8) and 0xff)
    }

    private fun writeLeInt(raf: java.io.RandomAccessFile, offset: Long, v: Int) {
        raf.seek(offset)
        raf.writeByte(v and 0xff)
        raf.writeByte((v shr 8) and 0xff)
        raf.writeByte((v shr 16) and 0xff)
        raf.writeByte((v shr 24) and 0xff)
    }

    init {
        if (enabled) {
            Runtime.getRuntime().addShutdownHook(Thread { close() })
        }
    }
}

object LiveKitNativeAudioPlayer : NativeMixedAudioSink {
    private const val UNDERRUN_WINDOW_NS = 250_000_000L

    // ReentrantLock, not `synchronized`: onMixedAudioFrame runs on the native
    // real-time render thread and must be able to tryLock() instead of blocking.
    private val lock = ReentrantLock()
    // All three are owned by `lock`. The player loop is the only writer; the
    // native render thread only publishes what format it wants via the
    // pending* fields below, because opening a SourceDataLine on a real-time
    // callback is an audible dropout.
    private var line: SourceDataLine? = null
    private var currentSampleRate = 0
    private var currentChannels = 0

    // Written by the native render thread (volatile, no lock taken), read by the
    // player loop. Kept separate from current* so the render thread can never
    // desynchronise the line cache by writing the field ensureLine uses as its
    // "the line is already open at this format" key.
    @Volatile private var pendingSampleRate = 0
    @Volatile private var pendingChannels = 0
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
    @Volatile private var playerJob: Job? = null
    private val lastPcmAtNs = AtomicLong(0L)

    // Playback is the most deadline-sensitive thread in the app; a dedicated
    // high-priority thread keeps writes steady when the rest of the process is
    // busy (e.g. decoding many remote video streams in a big room). It is created
    // on first use and shut down again in stop() — an executor that is never
    // closed keeps its thread (and stack) alive for the whole process.
    private val playerExecutorLock = Any()
    @Volatile private var playerDispatcherRef: ExecutorCoroutineDispatcher? = null

    private fun playerDispatcher(): ExecutorCoroutineDispatcher = synchronized(playerExecutorLock) {
        val existing = playerDispatcherRef
        if (existing != null) {
            existing
        } else {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "orbit-audio-playback").apply {
                    priority = Thread.MAX_PRIORITY
                    isDaemon = true
                }
            }.asCoroutineDispatcher().also { playerDispatcherRef = it }
        }
    }

    private fun shutdownPlayerDispatcher() {
        val dispatcher = synchronized(playerExecutorLock) {
            val current = playerDispatcherRef
            playerDispatcherRef = null
            current
        }
        runCatching { dispatcher?.close() }
    }

    private val firstFrameParamsLogged = AtomicLong(0)
    private val sizeMismatches = AtomicLong(0)

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
        firstFrameParamsLogged.set(0)
        sizeMismatches.set(0)
        // Preserve outputMuted across restarts (e.g. reconnect): un-deafening is
        // owned by CallManager, which explicitly clears it on call end.
        audioQueue.clear()
        CallPathStats.ensureReporter()
        startPlayerLoop()
        nativeBridge.setMixedAudioSink(this)
    }

    private fun startPlayerLoop() {
        // cancelAndJoin, not cancel: a plain cancel left the previous loop alive
        // inside audioQueue.poll(50ms) or out.write(), so a reconnect overlapped
        // two loops on one dispatcher and the old loop's onFailure branch could
        // close the line the new one had just opened.
        val previous = playerJob
        playerJob = scope.launch(playerDispatcher()) {
            previous?.cancelAndJoin()
            println("DEBUG [LiveKitNativeAudioPlayer] Async playback loop started")
            while (isActive) {
                val pcm = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                if (pcm == null) {
                    CallPathStats.onPlayerIdle()
                    continue
                }
                if (outputMuted) continue // Deafened: discard frame, keep queue draining
                lastPcmAtNs.set(System.nanoTime())
                // Adopt whatever format the render thread last published, then
                // open/reopen the line under the lock if it changed. This is the
                // only place a SourceDataLine is ever opened.
                val sr = maxOf(currentSampleRate, pendingSampleRate)
                val ch = maxOf(currentChannels, pendingChannels)
                if (sr <= 0 || ch <= 0) continue
                val out = lock.withLock { ensureLine(sr, ch) } ?: continue
                runCatching {
                    // True underrun = output line fully drained while remote frames are still flowing
                    val available = runCatching { out.available() }.getOrDefault(-1)
                    if (available >= out.bufferSize &&
                        System.nanoTime() - lastPcmAtNs.get() < UNDERRUN_WINDOW_NS
                    ) {
                        CallPathStats.onPlayerUnderrun()
                    }
                    PlaybackDump.dumpIn(pcm)
                    if (OrbitApm.enabled) {
                        OrbitApm.processRender(pcm, sr, ch)
                    }
                    val vol = outputVolume * loopbackDuckFactor
                    val outPcm = if (vol != 1f) applyOutputVolume(pcm, vol) else pcm
                    // Reference must match what actually hits the speakers so the
                    // share-path AEC subtracts exactly Orbit's audible contribution.
                    ShareAudioAec.processRender(outPcm, sr, ch)
                    PlaybackDump.dumpOut(outPcm)
                    out.write(outPcm, 0, outPcm.size)
                    CallPathStats.onPlayerFramePlayed()
                }.onFailure { error ->
                    CallPathStats.onPlayerWriteFailure()
                    lock.withLock {
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
        val job = playerJob
        playerJob = null
        audioQueue.clear()
        PlaybackDump.close()
        if (job == null) {
            closeLine()
            shutdownPlayerDispatcher()
            return
        }
        // stop() is called from the EDT (LiveKitNativeMediaSession.disconnect()).
        // Join the playback coroutine on the IO scope so it cannot open a NEW
        // SourceDataLine after closeLine() already ran, and so the caller is not
        // blocked while it unwinds.
        scope.launch {
            job.cancelAndJoin()
            // A start() during the join means playback is live again: leave the
            // line alone.
            if (playerJob == null) {
                closeLine()
                shutdownPlayerDispatcher()
            }
        }
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

        if (firstFrameParamsLogged.compareAndSet(0, 1)) {
            println(
                "DEBUG [LiveKitNativeAudioPlayer] first mixed frame sr=$sampleRate ch=$channels " +
                    "spc=$samplesPerChannel bytes=${pcm.size} expected=${samplesPerChannel * channels * 2}"
            )
            PlaybackDump.noteFormat(sampleRate, channels)
        }
        if (pcm.size != samplesPerChannel * channels * 2) {
            val n = sizeMismatches.incrementAndGet()
            if (n <= 5L || n % 500L == 0L) {
                println(
                    "WARN [LiveKitNativeAudioPlayer] mixed frame size mismatch #$n bytes=${pcm.size} " +
                        "expected=${samplesPerChannel * channels * 2} sr=$sampleRate ch=$channels spc=$samplesPerChannel"
                )
            }
        }
        CallPathStats.onPlayerFrameArrived()

        // Record the format and hand off. Opening a SourceDataLine takes tens of
        // ms, and this runs on the native real-time render thread, so the line
        // must be opened by the player loop, never here. The previous code
        // only avoided BLOCKING on the lock and then opened the line anyway
        // inside the tryLock-success branch -- and since closeLine() zeroes
        // currentSampleRate/currentChannels, that branch is taken on the first
        // frame after every stop(), every output-device change and every call
        // start, so the render thread stalled for tens of ms each time.
        pendingSampleRate = sampleRate
        pendingChannels = channels

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
                val bufferSize = sampleRate * channels * 2 / 8 // ~125 ms; cushion absorbs scheduler jitter (83 ms starved under load)
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
        lock.withLock {
            // flush(), never drain(): drain() blocks until the speaker line has
            // played everything still buffered (hundreds of ms) and it is called
            // while holding the lock the render thread needs.
            runCatching { line?.flush() }
            runCatching { line?.stop() }
            runCatching { line?.close() }
            line = null
            currentSampleRate = 0
            currentChannels = 0
        }
    }
}
