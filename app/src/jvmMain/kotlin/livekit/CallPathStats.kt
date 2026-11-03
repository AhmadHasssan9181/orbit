package livekit

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Unconditional stream diagnostics. Unlike [util.OrbitLog.d], these lines are
 * always emitted and also go to the rolling orbit.log, so a share test matrix
 * can be read back without the Gradle console.
 */
object StreamDiag {
    fun log(message: String) {
        val line = "STREAMDIAG $message"
        util.OrbitLog.emit(line)
        util.StreamLogFile.write(line)
        println(line)
    }

    fun logNative(line: String) {
        util.StreamLogFile.write(line)
    }
}

/**
 * Per-stage counters along the group-call media path (Phase 0 "black box recorder").
 * A daemon thread prints one rate summary every 5 s so every symptom becomes measurable:
 * silence at a stage shows up as that stage's counter stalling while earlier ones advance.
 *
 * Disable with -Dorbit.debug.calls=false.
 */
object CallPathStats {
    val enabled: Boolean = System.getProperty("orbit.debug.calls", "true")?.equals("true", ignoreCase = true) ?: true

    private val micFramesRead = AtomicLong()
    private val micReadStalls = AtomicLong()
    private val micMutedFrames = AtomicLong()
    private val micClippedSamples = AtomicLong()

    private val pushOk = AtomicLong()
    private val pushFail = AtomicLong()

    private val playerFramesArrived = AtomicLong()
    private val playerFramesPlayed = AtomicLong()
    private val playerUnderruns = AtomicLong()
    private val playerIdlePolls = AtomicLong()
    private val playerDroppedOldest = AtomicLong()
    private val playerWriteFailures = AtomicLong()

    private val remoteVideoFrames = AtomicLong()
    private val remoteVideoBytes = AtomicLong()
    private val shareFramesPushed = AtomicLong()
    private val shareBytesPushed = AtomicLong()
    private val cameraPreviewPacks = AtomicLong()

    @Volatile private var reporterThread: Thread? = null

    fun onMicFrameRead() { if (enabled) micFramesRead.incrementAndGet() }
    fun onMicReadStall() { if (enabled) micReadStalls.incrementAndGet() }
    fun onMicFrameMuted() { if (enabled) micMutedFrames.incrementAndGet() }
    fun onMicClippedSamples(count: Int) { if (enabled && count > 0) micClippedSamples.addAndGet(count.toLong()) }

    fun onPushOk() { if (enabled) pushOk.incrementAndGet() }
    fun onPushFail() { if (enabled) pushFail.incrementAndGet() }

    fun onPlayerFrameArrived() { if (enabled) playerFramesArrived.incrementAndGet() }
    fun onPlayerFramePlayed() { if (enabled) playerFramesPlayed.incrementAndGet() }
    /**
     * The output line went longer than the underrun threshold without being fed.
     *
     * A writer-side stall, not a buffer measurement. See the note in
     * `printSummaryAndReset` -- the old version of this counter tested
     * `available() >= bufferSize`, which is the *healthy* state.
     */
    fun onPlayerUnderrun() { if (enabled) playerUnderruns.incrementAndGet() }

    /**
     * `poll` returned null: the mixer produced nothing for the poll timeout.
     *
     * Producer-side starvation, and the counter that actually moves when a call
     * is having trouble upstream.
     */
    fun onPlayerIdle() { if (enabled) playerIdlePolls.incrementAndGet() }
    fun onPlayerDroppedOldest() { if (enabled) playerDroppedOldest.incrementAndGet() }
    fun onPlayerWriteFailure() { if (enabled) playerWriteFailures.incrementAndGet() }

    fun onRemoteVideoFrame(bytes: Int) {
        if (!enabled) return
        remoteVideoFrames.incrementAndGet()
        remoteVideoBytes.addAndGet(bytes.toLong())
    }

    fun onShareFramePushed(bytes: Int) {
        if (!enabled) return
        shareFramesPushed.incrementAndGet()
        shareBytesPushed.addAndGet(bytes.toLong())
    }

    fun onCameraPreviewPack() { if (enabled) cameraPreviewPacks.incrementAndGet() }

    fun ensureReporter() {
        if (!enabled) return
        synchronized(this) {
            if (reporterThread?.isAlive == true) return
            reporterThread = thread(name = "call-path-stats", isDaemon = true) {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(5_000)
                    } catch (_: InterruptedException) {
                        return@thread
                    }
                    printSummaryAndReset()
                }
            }
        }
    }

    private fun printSummaryAndReset() {
        val read = micFramesRead.getAndSet(0)
        val stalls = micReadStalls.getAndSet(0)
        val muted = micMutedFrames.getAndSet(0)
        val clipped = micClippedSamples.getAndSet(0)
        val pOk = pushOk.getAndSet(0)
        val pFail = pushFail.getAndSet(0)
        val arrived = playerFramesArrived.getAndSet(0)
        val played = playerFramesPlayed.getAndSet(0)
        val underruns = playerUnderruns.getAndSet(0)
        val idlePolls = playerIdlePolls.getAndSet(0)
        val dropped = playerDroppedOldest.getAndSet(0)
        val writeFails = playerWriteFailures.getAndSet(0)
        val rvf = remoteVideoFrames.getAndSet(0)
        val rvb = remoteVideoBytes.getAndSet(0)
        val sf = shareFramesPushed.getAndSet(0)
        val sfb = shareBytesPushed.getAndSet(0)
        val camPrev = cameraPreviewPacks.getAndSet(0)

        // Heap + pending-finalization numbers make memory behavior measurable:
        // a heap pinned near max with a rising pendingFin count means bitmap
        // finalizers can't keep pace with frame churn.
        val memory = java.lang.management.ManagementFactory.getMemoryMXBean()
        val heapUsedMb = memory.heapMemoryUsage.used / (1024L * 1024L)
        val heapMaxMb = memory.heapMemoryUsage.max / (1024L * 1024L)
        val pendingFinalizations = memory.objectPendingFinalizationCount

        // 100 frames/s ≈ healthy per direction at 10 ms frames.
        //
        // `underruns` is a WRITER stall: the output line went longer than 60 ms
        // without being fed, so the line itself ran dry. It used to be reported
        // inverted — `SourceDataLine.available() >= bufferSize` means the buffer
        // is empty and writes are instant, i.e. we are AHEAD — which is why runs
        // showed `underruns=364` during calls that were completely clean.
        //
        // `starved` is the PRODUCER-side counterpart and is usually the one that
        // moves first: `poll` returned null, so the mixer delivered nothing for
        // 50 ms. `droppedOldest` is the consumer being too slow (queue overflow).
        // All three at zero is the healthy call.
        StreamDiag.log(
            "STATS [CallPathStats/5s] mic.read=$read stalls=$stalls muted=$muted clippedSamples=$clipped | " +
                "jni.push ok=$pOk fail=$pFail | play.arrived=$arrived play.played=$played " +
                "underruns=$underruns starved=$idlePolls droppedOldest=$dropped writeFails=$writeFails | " +
                "vid.remote n=$rvf mb=${rvb / (1024L * 1024L)} vid.share n=$sf mb=${sfb / (1024L * 1024L)} " +
                "camPrev=$camPrev | heap used=${heapUsedMb}MB max=${heapMaxMb}MB pendingFin=$pendingFinalizations"
        )
    }
}
