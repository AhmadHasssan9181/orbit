package livekit

import dev.onvoid.webrtc.media.audio.AudioProcessing
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig
import java.util.concurrent.atomic.AtomicLong

/**
 * Echo canceller for the screen-share system-audio stream.
 *
 * On Windows builds that cannot exclude our own process from loopback capture
 * (pre-24H2), the shared audio contains Orbit's own mixed playout, which the
 * far end re-transmits as an endless echo.
 *
 * Default mode ("subtract") exploits the fact that a digital loopback tap is a
 * pure delay, not room acoustics: we know exactly which samples Orbit renders
 * (fed via [processRender] right before the player line write), so we
 * cross-correlate the rendered reference against each captured frame, lock the
 * delay, and sample-exact subtract the scaled reference. No non-linear
 * suppressor touches the signal, so shared music stays clean; when our playout
 * is absent the correlation collapses and frames ship untouched.
 *
 * Legacy mode ("apm", orbit.shareAec.mode=apm) keeps the old AEC3 instance —
 * kept only for comparison, known to leave residual echo and distort music.
 */
object ShareAudioAec {
    private const val SAMPLE_RATE = 48_000
    private const val CHANNELS = 2
    private const val FRAME_SAMPLES = SAMPLE_RATE / 100 // 480 per channel, 10 ms
    private const val FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2

    enum class Mode { SUBTRACT, APM, OFF }

    private val mode: Mode by lazy {
        when (System.getProperty("orbit.shareAec.mode")?.trim()?.lowercase()) {
            "apm" -> Mode.APM
            "off" -> Mode.OFF
            else -> Mode.SUBTRACT
        }
    }

    // ------------------------------------------------------------------
    // Shared entry points (API frozen: called from C++ upcall / player loop)
    // ------------------------------------------------------------------

    @JvmStatic
    fun onLoopbackFrame(bytes: ByteArray) {
        if (bytes.size != FRAME_BYTES) return
        when (mode) {
            Mode.SUBTRACT -> Subtractor.onCapture(bytes)
            Mode.APM -> LegacyApm.onCapture(bytes)
            Mode.OFF -> Subtractor.shipRaw(bytes)
        }
    }

    /** Feeds actual speaker output as far-end reference (player loop thread). */
    fun processRender(pcm: ByteArray, sampleRate: Int, channels: Int) {
        when (mode) {
            Mode.SUBTRACT -> Subtractor.onRender(pcm, sampleRate, channels)
            Mode.APM -> LegacyApm.onRender(pcm, sampleRate, channels)
            Mode.OFF -> Unit
        }
    }

    fun reset() {
        Subtractor.reset()
        LegacyApm.reset()
    }

    // ==================================================================
    // Deterministic delay-subtract canceller
    // ==================================================================

    private object Subtractor {
        private const val MIN_DELAY_FRAMES = 1_600      // ~33 ms floor
        private const val MAX_DELAY_FRAMES = 72_000     // 1.5 s ceiling
        private const val RING_FRAMES = MAX_DELAY_FRAMES + 8_192
        private const val COARSE_STEP = 16              // ~0.33 ms grid
        private const val LOCK_CORR = 0.50
        private const val KEEP_CORR = 0.35
        private const val MISS_UNLOCK_FRAMES = 30       // 300 ms of bad fit
        private const val REFINE_EVERY_FRAMES = 200L
        private const val DIAG_EVERY_FRAMES = 125L
        // The coarse sweep used to run 4x/second over up to 4500 candidate delays;
        // it now correlates against a local ring copy, so a slower rate costs
        // almost nothing and leaves the playback thread more room.
        private const val FULL_SWEEP_EVERY_MS = 1_000L
        private const val MIN_RENDER_RMS = 60.0

        private val lock = Any()
        private val ring = ShortArray(RING_FRAMES * CHANNELS) // interleaved 48k stereo
        private var totalRenderedFrames = 0L

        // Capture-side canceller state. reset() runs on the EDT (via
        // unpublishScreenShareAudio) while onCapture() runs on the native WASAPI
        // loopback thread, so this state is NOT thread-confined and lives under
        // its own lock. Lock order is always captureLock -> lock.
        private val captureLock = Any()

        // Render-side activity for gating (written on player thread).
        private val renderFramesSeen = AtomicLong(0)
        @Volatile private var renderRmsAvg = 0.0

        @Volatile private var excludedByProcessLoopback = false
        private var exclusionChecked = false
        private var arrivalProbe = 0L
        private var locked = false
        private var delayFrames = 0
        private var gain = 1.0
        private var missRun = 0
        private var framesSinceRefine = 0L
        private var lastFullSweepMs = 0L
        private var diagCount = 0L
        private var lockLogged = false
        private var unlockLogged = true

        // Coarse-sweep scratch, preallocated. The old sweep allocated a fresh
        // ShortArray(960) + DoubleArray(240) per candidate delay (~4500 delays =
        // ~19 MB of garbage per sweep) AND took the ring lock for each one, which
        // is the same lock the Thread.MAX_PRIORITY playback thread needs once per
        // 10 ms in pushRendered().
        private val sweepRing = ShortArray(RING_FRAMES * CHANNELS)
        private val sweepWindow = ShortArray(FRAME_SAMPLES * CHANNELS)
        private val sweepCapDs = DoubleArray(FRAME_SAMPLES * CHANNELS / (COARSE_STEP / 4))
        private val sweepRefDs = DoubleArray(FRAME_SAMPLES * CHANNELS / (COARSE_STEP / 4))

        fun reset() {
            synchronized(captureLock) {
                synchronized(lock) {
                    totalRenderedFrames = 0
                    java.util.Arrays.fill(ring, 0.toShort())
                }
                renderFramesSeen.set(0)
                renderRmsAvg = 0.0
                excludedByProcessLoopback = false
                exclusionChecked = false
                arrivalProbe = 0
                locked = false
                delayFrames = 0
                gain = 1.0
                missRun = 0
                framesSinceRefine = 0
                lastFullSweepMs = 0
                diagCount = 0
                lockLogged = false
                unlockLogged = true
                println("AEC-PROBE [ShareAudioAec] canceller reset (arrivalProbe=$arrivalProbe)")
            }
        }

        /** Player thread: append what actually hits the speakers to the ring. */
        fun onRender(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (renderFramesSeen.get() == 0L) {
                println(
                    "AEC-PROBE [ShareAudioAec] first render reference frame sr=$sampleRate " +
                        "ch=$channels bytes=${pcm.size}"
                )
            }
            val frameSamples = sampleRate / 100
            if (frameSamples <= 0 || channels !in 1..2 || pcm.size < frameSamples * channels * 2) return
            val srcFrameBytes = frameSamples * channels * 2

            var offset = 0
            while (offset + srcFrameBytes <= pcm.size) {
                val chunk = convertTo48kStereo(pcm, offset, frameSamples, sampleRate, channels)
                pushRendered(chunk)
                renderFramesSeen.incrementAndGet()
                renderRmsAvg = 0.9 * renderRmsAvg + 0.1 * rms(chunk)
                offset += srcFrameBytes
            }
        }

        /** Loopback thread: cancel our playout from one raw 10 ms capture frame. */
        fun onCapture(bytes: ByteArray) = synchronized(captureLock) {
            arrivalProbe += 1
            if (arrivalProbe <= 5 || arrivalProbe % 500L == 0L) {
                println(
                    "AEC-PROBE [ShareAudioAec] capture frame #$arrivalProbe size=${bytes.size} " +
                        "renderFrames=$renderFramesSeen renderRms=${renderRmsAvg.toInt()} " +
                        "locked=$locked excluded=$excludedByProcessLoopback"
                )
            }
            if (!exclusionChecked || (!excludedByProcessLoopback && !locked)) {
                checkExclusionOnce()
            }
            if (excludedByProcessLoopback) {
                shipRaw(bytes)
                return@synchronized
            }
            // Nothing of ours is audible: nothing to cancel, never distort.
            if (renderRmsAvg < MIN_RENDER_RMS) {
                shipRaw(bytes)
                return@synchronized
            }

            val cap = ShortArray(FRAME_SAMPLES * CHANNELS)
            bytesToShorts(bytes, cap)

            if (!locked) {
                tryLock(cap)
                if (!locked) {
                    shipRaw(bytes)
                    reportDiag(cap, cap, 0.0, 0.0)
                    return@synchronized
                }
            }

            val rawCap = cap.copyOf()
            val ref = snapshotWindow(delayFrames)
            val stats = fitStats(cap, ref)

            if (stats.correlation < KEEP_CORR) {
                missRun += 1
                if (missRun >= MISS_UNLOCK_FRAMES) {
                    locked = false
                    if (!unlockLogged) {
                        unlockLogged = true
                        println("AEC-EVENT [ShareAudioAec] unlocked (reference no longer dominant in capture); shipping raw")
                    }
                }
                shipRaw(bytes)
                reportDiag(cap, cap, stats.gain, stats.correlation)
                return@synchronized
            }
            missRun = 0
            gain = stats.gain

            subtractScaled(cap, ref, gain)
            shortsToBytes(cap, bytes)

            framesSinceRefine += 1
            if (framesSinceRefine >= REFINE_EVERY_FRAMES) {
                framesSinceRefine = 0
                refine(rawCap)
            }
            if (!lockLogged) {
                lockLogged = true
                unlockLogged = false
                println(
                    "AEC-EVENT [ShareAudioAec] locked delay=${delayMs()}ms gain=" +
                        "%.2f".format(gain) + " corr=" + "%.2f".format(stats.correlation)
                )
            }
            reportDiag(cap, null, gain, stats.correlation)
        }

        fun shipRaw(bytes: ByteArray) {
            runCatching { OrbitLiveKitNativeRaw.nativeFeedProcessedShareAudioFrame(bytes) }
        }

        private fun checkExclusionOnce() {
            exclusionChecked = true
            val m = runCatching { OrbitLiveKitNativeRaw.nativeGetShareLoopbackMode() }.getOrDefault(0)
            when (m) {
                1 -> {
                    excludedByProcessLoopback = true
                    println("AEC-EVENT [ShareAudioAec] process-loopback EXCLUDE active; canceller bypassed")
                }
                2 -> Unit // full-device fallback: canceller needed
                else -> exclusionChecked = false // activation still resolving; re-check next frame
            }
        }

        private fun tryLock(cap: ShortArray) {
            val now = System.currentTimeMillis()
            if (now - lastFullSweepMs < FULL_SWEEP_EVERY_MS) return
            lastFullSweepMs = now

            val history = availableHistory()
            if (history < MIN_DELAY_FRAMES) return
            // One consistent copy of the render ring, taken once per sweep. Every
            // correlation below then runs against that local copy with the
            // playback lock RELEASED, so pushRendered() never waits on the sweep.
            val total = synchronized(lock) {
                System.arraycopy(ring, 0, sweepRing, 0, ring.size)
                totalRenderedFrames
            }

            downsampleInto(cap, COARSE_STEP / 4, sweepCapDs)
            var bestDelay = -1
            var bestCorr = 0.0
            var d = MIN_DELAY_FRAMES
            while (d <= history) {
                windowFromRing(total, d, sweepWindow)
                downsampleInto(sweepWindow, COARSE_STEP / 4, sweepRefDs)
                val c = normalizedCorrelation(sweepCapDs, sweepRefDs)
                if (c > bestCorr) {
                    bestCorr = c
                    bestDelay = d
                }
                d += COARSE_STEP
            }
            if (bestDelay < 0 || bestCorr < LOCK_CORR * 0.8) return

            // Refine on the full-rate grid around the coarse peak.
            var fineBest = bestDelay
            var fineCorr = 0.0
            val lo = (bestDelay - COARSE_STEP).coerceAtLeast(MIN_DELAY_FRAMES)
            val hi = (bestDelay + COARSE_STEP).coerceAtMost(MAX_DELAY_FRAMES)
            for (cand in lo..hi) {
                windowFromRing(total, cand, sweepWindow)
                val s = fitStats(cap, sweepWindow)
                if (s.correlation > fineCorr) {
                    fineCorr = s.correlation
                    fineBest = cand
                }
            }
            if (fineCorr < LOCK_CORR || fineBest < 0) return
            delayFrames = fineBest
            locked = true
            missRun = 0
        }

        // Called with the already-subtracted capture; correlation peak location
        // is still valid for tracking small drift, magnitude is not reused.
        private fun refine(subtractedCap: ShortArray) {
            var fineBest = delayFrames
            var fineCorr = -1.0
            val lo = (delayFrames - 16).coerceAtLeast(MIN_DELAY_FRAMES)
            val hi = (delayFrames + 16).coerceAtMost(MAX_DELAY_FRAMES)
            for (cand in lo..hi) {
                val ref = snapshotWindow(cand)
                val s = fitStats(subtractedCap, ref)
                if (s.correlation > fineCorr) {
                    fineCorr = s.correlation
                    fineBest = cand
                }
            }
            if (fineBest != delayFrames) {
                delayFrames = fineBest
            }
        }

        private fun availableHistory(): Int = synchronized(lock) {
            ((totalRenderedFrames.coerceAtMost(RING_FRAMES.toLong())) - FRAME_SAMPLES).toInt()
                .coerceAtMost(MAX_DELAY_FRAMES)
        }

        /** Lock-free window read from the sweep's local ring copy. */
        private fun windowFromRing(totalFrames: Long, delay: Int, out: ShortArray) {
            val endAbs = totalFrames - delay          // exclusive, in frames
            val startAbs = endAbs - FRAME_SAMPLES
            if (startAbs < 0) {
                java.util.Arrays.fill(out, 0.toShort())
                return
            }
            var abs = startAbs
            var i = 0
            while (abs < endAbs) {
                val ringIdx = ((abs % RING_FRAMES).toInt()) * CHANNELS
                out[i++] = sweepRing[ringIdx]
                out[i++] = sweepRing[ringIdx + 1]
                abs++
            }
        }

        private fun downsampleInto(data: ShortArray, factor: Int, out: DoubleArray) {
            if (factor <= 1) {
                for (i in out.indices) out[i] = data[i].toDouble()
                return
            }
            for (i in out.indices) out[i] = data[i * factor].toDouble()
        }

        private fun pushRendered(chunk: ShortArray) {
            synchronized(lock) {
                val base = (totalRenderedFrames % RING_FRAMES).toInt()
                var idx = base * CHANNELS
                var i = 0
                val end = chunk.size
                while (idx < ring.size && i < end) {
                    ring[idx++] = chunk[i++]
                }
                var idx2 = 0
                while (i < end) {
                    ring[idx2++] = chunk[i++]
                }
                totalRenderedFrames += FRAME_SAMPLES
            }
        }

        /** Latest `FRAME_SAMPLES` frames ending `delayFrames` ago. */
        private fun snapshotWindow(delay: Int): ShortArray {
            val out = ShortArray(FRAME_SAMPLES * CHANNELS)
            synchronized(lock) {
                val endAbs = totalRenderedFrames - delay          // exclusive, in frames
                val startAbs = endAbs - FRAME_SAMPLES
                if (startAbs < 0) return out
                var abs = startAbs
                var i = 0
                while (abs < endAbs) {
                    val ringIdx = ((abs % RING_FRAMES).toInt()) * CHANNELS
                    out[i++] = ring[ringIdx]
                    out[i++] = ring[ringIdx + 1]
                    abs++
                }
            }
            return out
        }

        private class FitStats(val gain: Double, val correlation: Double)

        /** Least-squares scale + normalized correlation between capture and reference. */
        private fun fitStats(cap: ShortArray, ref: ShortArray): FitStats {
            var capE = 0.0
            var refE = 0.0
            var dot = 0.0
            var i = 0
            while (i < cap.size) {
                val c = cap[i].toDouble()
                val r = ref[i].toDouble()
                capE += c * c
                refE += r * r
                dot += c * r
                i++
            }
            if (refE < 1.0e6 || capE < 1.0e6) return FitStats(0.0, 0.0)
            val corr = dot / kotlin.math.sqrt(capE * refE)
            val g = (dot / refE).coerceIn(0.3, 1.8)
            return FitStats(g, corr)
        }

        private fun subtractScaled(cap: ShortArray, ref: ShortArray, g: Double) {
            for (i in cap.indices) {
                val v = cap[i] - (g * ref[i]).toInt()
                cap[i] = v.coerceIn(-32768, 32767).toShort()
            }
        }

        private fun reportDiag(cap: ShortArray, pre: ShortArray?, g: Double, corr: Double) {
            diagCount += 1
            if (diagCount % DIAG_EVERY_FRAMES != 0L || diagCount > 3200) return
            val rmsIn = if (pre != null) rms(pre) else -1.0
            println(
                "AEC-DIAG [ShareAudioAec] frame=$diagCount locked=$locked " +
                    "delay=${delayMs()}ms gain=%.2f".format(g) +
                    " corr=%.2f".format(corr) +
                    " rmsIn=${rmsIn.toInt()} rmsOut=${rms(cap).toInt()}" +
                    " renderRms=${renderRmsAvg.toInt()}"
            )
        }

        private fun delayMs(): Int = delayFrames / (SAMPLE_RATE / 1000)

        private fun normalizedCorrelation(a: DoubleArray, b: DoubleArray): Double {
            var ae = 0.0
            var be = 0.0
            var dot = 0.0
            for (i in a.indices) {
                ae += a[i] * a[i]
                be += b[i] * b[i]
                dot += a[i] * b[i]
            }
            if (ae < 1.0e4 || be < 1.0e4) return 0.0
            return dot / kotlin.math.sqrt(ae * be)
        }

        private fun convertTo48kStereo(
            pcm: ByteArray,
            offset: Int,
            frameSamples: Int,
            sampleRate: Int,
            channels: Int
        ): ShortArray {
            val out = ShortArray(FRAME_SAMPLES * CHANNELS)
            if (sampleRateMatches(frameSamples)) {
                if (channels == CHANNELS) {
                    bytesToShorts(pcm, offset, out)
                    return out
                }
                for (i in 0 until FRAME_SAMPLES) {
                    val lo = pcm[offset + i * 2].toInt()
                    val hi = pcm[offset + i * 2 + 1].toInt()
                    val v = ((hi shl 8) or (lo and 0xff)).toShort()
                    out[i * 2] = v
                    out[i * 2 + 1] = v
                }
                return out
            }
            // Nearest-neighbor resample from arbitrary rate.
            for (i in 0 until FRAME_SAMPLES) {
                val src = (i.toDouble() * sampleRate / SAMPLE_RATE).toInt()
                    .coerceAtMost(frameSamples - 1)
                val b = offset + src * channels * 2
                val lo = pcm[b].toInt()
                val hi = pcm[b + 1].toInt()
                val v = ((hi shl 8) or (lo and 0xff)).toShort()
                out[i * 2] = v
                out[i * 2 + 1] = v
            }
            return out
        }

        private fun sampleRateMatches(frameSamples: Int): Boolean = frameSamples == FRAME_SAMPLES

        private fun bytesToShorts(bytes: ByteArray, dest: ShortArray) =
            bytesToShorts(bytes, 0, dest)

        private fun bytesToShorts(bytes: ByteArray, offset: Int, dest: ShortArray) {
            var j = offset
            for (i in dest.indices) {
                val lo = bytes[j++].toInt()
                val hi = bytes[j++].toInt()
                dest[i] = ((hi shl 8) or (lo and 0xff)).toShort()
            }
        }

        private fun shortsToBytes(src: ShortArray, dest: ByteArray) {
            var j = 0
            for (i in src.indices) {
                val v = src[i].toInt()
                dest[j++] = (v and 0xff).toByte()
                dest[j++] = ((v shr 8) and 0xff).toByte()
            }
        }

        private fun rms(data: ShortArray): Double {
            var sum = 0.0
            for (v in data) {
                val s = v.toDouble()
                sum += s * s
            }
            return if (data.isEmpty()) 0.0 else kotlin.math.sqrt(sum / data.size)
        }
    }

    // ==================================================================
    // Legacy AEC3 path (orbit.shareAec.mode=apm)
    // ==================================================================

    private object LegacyApm {
        private val DELAY_CANDIDATES_MS = intArrayOf(40, 90, 140, 190, 240)
        private const val CALIB_SETTLE_FRAMES = 75L
        private const val CALIB_WINDOW_FRAMES = 275L
        private const val MIN_NEAR_RMS = 80L
        private const val MIN_RENDER_RMS = 30.0

        @Volatile private var apm: AudioProcessing? = null
        @Volatile private var initFailed = false
        @Volatile private var currentDelayMs = 120

        private val stateLock = Any()
        // Serializes every native call on `apm`: processStream (loopback
        // thread), processReverseStream (playback thread) and dispose (EDT).
        // One AudioProcessing is not reentrant, so concurrent calls corrupt the
        // AEC3 internal state. Order is always processLock -> stateLock.
        private val processLock = Any()
        private val streamConfig = AudioProcessingStreamConfig(SAMPLE_RATE, CHANNELS)
        private var renderChunk: ByteArray = ByteArray(0)
        private var firstProcessedLogged = false
        private var diagFramesSeen = 0L
        private val renderFramesSeen = AtomicLong(0)
        @Volatile private var renderRmsAvg = 0.0
        @Volatile private var calibrating = false
        @Volatile private var calibDone = false
        private var calibCandidateIndex = 0
        private var calibFramesAtDelay = 0L
        private var calibNearRms = -1L
        private var calibRatioSum = 0.0
        private var calibRatioCount = 0
        private val calibResults = mutableListOf<Pair<Int, Double>>()

        fun reset() {
            // processLock first, then stateLock -- the same order onCapture /
            // onRender take, so the pair cannot invert. Disposing under only
            // stateLock let the loopback or playback thread call into a freed
            // native object.
            synchronized(processLock) {
            synchronized(stateLock) {
                runCatching { apm?.dispose() }
                apm = null
                initFailed = false
                firstProcessedLogged = false
                diagFramesSeen = 0
                currentDelayMs = 120
                calibrating = false
                calibDone = false
                calibCandidateIndex = 0
                calibFramesAtDelay = 0
                calibRatioSum = 0.0
                calibRatioCount = 0
                calibResults.clear()
            }
            }
            renderFramesSeen.set(0)
            renderRmsAvg = 0.0
        }

        fun onCapture(bytes: ByteArray) {
            if (bytes.size != SAMPLE_RATE / 100 * CHANNELS * 2) return
            diagFramesSeen += 1

            val processor = ensureProcessor()
            if (!calibrating && !calibDone) maybeStartCalibration(processor)

            val wantDiag = diagFramesSeen < 3200 && diagFramesSeen % 125 == 0L
            val rmsBefore = if (wantDiag || calibrating) int16Rms(bytes) else -1L

            if (processor != null) {
                // processLock, same as OrbitApm: onCapture is the WASAPI loopback
                // thread and onRender is the MAX_PRIORITY playback thread, and one
                // AudioProcessing is NOT reentrant. Without this, both run native
                // AEC3 on the same object at once, and reset() (from the EDT) can
                // dispose it under either. Only reachable with
                // -Dorbit.shareAec.mode=apm, but it is the same corruption the
                // OrbitApm fix exists to prevent.
                val result = synchronized(processLock) {
                    val p = apm ?: return@synchronized -1
                    runCatching {
                        p.processStream(bytes, streamConfig, streamConfig, bytes)
                    }.getOrDefault(-1)
                }
                if (result != 0 && diagFramesSeen <= 5) {
                    println("WARN [ShareAudioAec] legacy processStream returned $result")
                } else if (result == 0 && !firstProcessedLogged) {
                    firstProcessedLogged = true
                    println("DEBUG [ShareAudioAec] first loopback frame echo-cancelled (legacy)")
                }
                if (calibrating) scoreCalibrationFrame(rmsBefore, int16Rms(bytes))
            }
            if (rmsBefore >= 0) {
                val tag = if (calibrating) "CALIB" else "DIAG"
                println(
                    "$tag [ShareAudioAec] frame=$diagFramesSeen " +
                        "delayMs=$currentDelayMs rmsBefore=$rmsBefore " +
                        "rmsAfter=${int16Rms(bytes)} renderRms=${renderRmsAvg.toInt()}"
                )
            }
            runCatching { OrbitLiveKitNativeRaw.nativeFeedProcessedShareAudioFrame(bytes) }
        }

        fun onRender(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (apm == null) return
            val frameSamples = sampleRate / 100
            val srcFrameBytes = frameSamples * channels * 2
            if (srcFrameBytes <= 0 || pcm.size < srcFrameBytes) return

            val stereo = channels == CHANNELS
            val refConfig = AudioProcessingStreamConfig(sampleRate, CHANNELS)
            val refFrameBytes = frameSamples * CHANNELS * 2
            if (renderChunk.size < refFrameBytes) {
                renderChunk = ByteArray(refFrameBytes)
            }

            var offset = 0
            while (offset + srcFrameBytes <= pcm.size) {
                if (stereo) {
                    System.arraycopy(pcm, offset, renderChunk, 0, srcFrameBytes)
                } else {
                    for (i in 0 until frameSamples) {
                        val lo = pcm[offset + i * 2]
                        val hi = pcm[offset + i * 2 + 1]
                        val base = i * 4
                        renderChunk[base] = lo
                        renderChunk[base + 1] = hi
                        renderChunk[base + 2] = lo
                        renderChunk[base + 3] = hi
                    }
                }
                renderFramesSeen.incrementAndGet()
                renderRmsAvg = 0.9 * renderRmsAvg + 0.1 * int16Rms(renderChunk)

                val ok = synchronized(processLock) {
                    val p = apm ?: return
                    runCatching {
                        p.processReverseStream(renderChunk, refConfig, refConfig, renderChunk) == 0
                    }.getOrElse { error ->
                        println("WARN [ShareAudioAec] reverse-stream failure: ${error.message}")
                        return
                    }
                }
                if (!ok) return
                offset += srcFrameBytes
            }
        }

        private fun ensureProcessor(): AudioProcessing? {
            apm?.let { return it }
            if (initFailed) return null
            synchronized(stateLock) {
                apm?.let { return it }
                try {
                    val processor = AudioProcessing()
                    val config = AudioProcessingConfig()
                    config.echoCanceller.enabled = true
                    config.echoCanceller.enforceHighPassFiltering = true
                    config.highPassFilter.enabled = true
                    config.noiseSuppression.enabled = false
                    config.gainController.enabled = false
                    config.gainControllerDigital.enabled = false
                    processor.applyConfig(config)
                    processor.setStreamDelayMs(currentDelayMs)
                    apm = processor
                    println(
                        "DEBUG [ShareAudioAec] legacy started ec3=true hpf=true ns=false agc=false " +
                            "delayMs=$currentDelayMs"
                    )
                    return processor
                } catch (error: Throwable) {
                    initFailed = true
                    println("WARN [ShareAudioAec] legacy initialization failed; share audio ships unprocessed: ${error.message}")
                    return null
                }
            }
        }

        private fun maybeStartCalibration(processor: AudioProcessing?) {
            processor ?: return
            if (OrbitLiveKitNativeRaw.nativeGetShareLoopbackMode() != 2) {
                calibDone = true
                return
            }
            if (renderFramesSeen.get() < 100 || renderRmsAvg < MIN_RENDER_RMS) return
            calibrating = true
            calibCandidateIndex = 0
            calibFramesAtDelay = 0
            currentDelayMs = DELAY_CANDIDATES_MS[0]
            processor.setStreamDelayMs(currentDelayMs)
            println(
                "CALIB [ShareAudioAec] starting delay sweep over " +
                    "${DELAY_CANDIDATES_MS.joinToString("/")}ms (full-device loopback)"
            )
        }

        private fun scoreCalibrationFrame(nearRms: Long, afterRms: Long) {
            calibFramesAtDelay += 1
            if (calibFramesAtDelay <= CALIB_SETTLE_FRAMES) return
            if (calibFramesAtDelay >= CALIB_SETTLE_FRAMES + CALIB_WINDOW_FRAMES) {
                finishCandidate()
                advanceCalibration()
                return
            }
            if (nearRms < MIN_NEAR_RMS || renderRmsAvg < MIN_RENDER_RMS) return
            calibRatioSum += afterRms.toDouble() / nearRms.coerceAtLeast(1)
            calibRatioCount += 1
        }

        private fun finishCandidate() {
            val candidate = DELAY_CANDIDATES_MS[calibCandidateIndex]
            if (calibRatioCount > 0) {
                val mean = calibRatioSum / calibRatioCount
                calibResults.add(candidate to mean)
                println(
                    "CALIB [ShareAudioAec] delay=${candidate}ms meanResidualRatio=" +
                        "%.3f".format(mean) + " samples=$calibRatioCount"
                )
            } else {
                println(
                    "CALIB [ShareAudioAec] delay=${candidate}ms skipped (no active samples)"
                )
            }
            calibRatioSum = 0.0
            calibRatioCount = 0
            calibFramesAtDelay = 0
        }

        private fun advanceCalibration() {
            calibCandidateIndex += 1
            val processor = apm ?: run { calibrating = false; return }
            if (calibCandidateIndex < DELAY_CANDIDATES_MS.size) {
                val next = DELAY_CANDIDATES_MS[calibCandidateIndex]
                currentDelayMs = next
                processor.setStreamDelayMs(next)
                return
            }
            calibrating = false
            calibDone = true
            val best = calibResults.minByOrNull { it.second }
            if (best != null) {
                currentDelayMs = best.first
                processor.setStreamDelayMs(best.first)
                println(
                    "CALIB [ShareAudioAec] locked delayMs=${best.first} " +
                        "ratio=%.3f".format(best.second) + " | all=" +
                        calibResults.joinToString(" ") { "${it.first}:%.3f".format(it.second) }
                )
            } else {
                println(
                    "WARN [ShareAudioAec] calibration produced no usable samples " +
                        "(silent session?); keeping delayMs=$currentDelayMs"
                )
            }
        }

        private fun int16Rms(bytes: ByteArray): Long {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < bytes.size) {
                val value = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
                sum += (value * value).toDouble()
                count++
                i += 2
            }
            return if (count == 0) 0 else kotlin.math.sqrt(sum / count).toLong()
        }
    }
}
