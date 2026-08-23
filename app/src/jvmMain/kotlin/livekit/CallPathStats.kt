package livekit

import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

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

    @Volatile private var reporterThread: Thread? = null

    fun onMicFrameRead() { if (enabled) micFramesRead.incrementAndGet() }
    fun onMicReadStall() { if (enabled) micReadStalls.incrementAndGet() }
    fun onMicFrameMuted() { if (enabled) micMutedFrames.incrementAndGet() }
    fun onMicClippedSamples(count: Int) { if (enabled && count > 0) micClippedSamples.addAndGet(count.toLong()) }

    fun onPushOk() { if (enabled) pushOk.incrementAndGet() }
    fun onPushFail() { if (enabled) pushFail.incrementAndGet() }

    fun onPlayerFrameArrived() { if (enabled) playerFramesArrived.incrementAndGet() }
    fun onPlayerFramePlayed() { if (enabled) playerFramesPlayed.incrementAndGet() }
    fun onPlayerUnderrun() { if (enabled) playerUnderruns.incrementAndGet() }
    fun onPlayerIdle() { if (enabled) playerIdlePolls.incrementAndGet() }
    fun onPlayerDroppedOldest() { if (enabled) playerDroppedOldest.incrementAndGet() }
    fun onPlayerWriteFailure() { if (enabled) playerWriteFailures.incrementAndGet() }

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

        // 100 frames/s ≈ healthy per direction at 10 ms frames; played persistently
        // above arrived means the sink consumes faster than production paces.
        println(
            "STATS [CallPathStats/5s] mic.read=$read stalls=$stalls muted=$muted clippedSamples=$clipped | " +
                "jni.push ok=$pOk fail=$pFail | play.arrived=$arrived play.played=$played underruns=$underruns " +
                "idlePolls=$idlePolls droppedOldest=$dropped writeFails=$writeFails"
        )
    }
}
