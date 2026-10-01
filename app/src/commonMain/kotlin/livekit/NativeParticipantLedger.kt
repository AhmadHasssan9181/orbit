package livekit

/**
 * Running tallies of native participant lifecycle events, so an imbalance is
 * visible in the log rather than something you have to count by hand.
 *
 * ## Why this exists
 *
 * Run 62 produced 16 `remote participant connected` callbacks and exactly 1
 * `remote participant disconnected`, with `remote audio track removed` never firing
 * at all. Both sides were already logged, so the raw lines existed -- but nothing
 * said "these should balance", so the imbalance had to be noticed by a human
 * grepping and counting, and nobody would ever do that on a live call.
 *
 * A count that is only correct when compared against another count by eye is not a
 * diagnostic. This makes the mismatch a single number that can be read at a glance
 * and, better, asserted on at teardown.
 *
 * ## What it does and does not claim
 *
 * A nonzero [pending] does not by itself prove a leak. LiveKit legitimately
 * re-announces a participant across a rejoin, and a reannounce can arrive without a
 * matching disconnect. What it does prove is that the disconnect path is not firing
 * for every connect, which is the thing that has to be established before anything
 * else is concluded -- and that is exactly the question run 62 could not answer.
 *
 * So this counts, and the count is printed on every event. It does not diagnose.
 */
internal object NativeParticipantLedger {

    private const val RESETTABLE_AT = 0

    /** Connects seen, keyed by identity, so a rejoin is visible as a repeat. */
    private val connects = LinkedHashMap<String, Int>()

    @Volatile
    private var connectsTotal = 0

    @Volatile
    private var disconnectsTotal = 0

    @Volatile
    private var videoTracksAdded = 0

    @Volatile
    private var videoTracksRemoved = 0

    @Volatile
    private var audioTracksAdded = 0

    @Volatile
    private var audioTracksRemoved = 0

    /**
     * Connects with no matching disconnect.
     *
     * Never negative: a disconnect for an identity that was never recorded is
     * counted as balanced rather than allowed to drive this below zero, because a
     * negative "pending" would read as "we cleaned up more than we created", which
     * is a different bug and should be visible as such instead of hidden by a floor.
     */
    val pending: Int
        get() = (connectsTotal - disconnectsTotal).coerceAtLeast(0)

    /** One line, appended to the lifecycle log so it is read in context. */
    fun line(): String =
        "ledger connects=$connectsTotal disconnects=$disconnectsTotal pending=$pending " +
            "video +$videoTracksAdded/-$videoTracksRemoved audio +$audioTracksAdded/-$audioTracksRemoved " +
            "byIdentity=${connects.entries.joinToString(",") { "${it.key}x${it.value}" }}"

    fun onConnect(identity: String) {
        connectsTotal++
        connects[identity] = (connects[identity] ?: 0) + 1
        println("PARTLEDGER +connect ${line()}")
    }

    fun onDisconnect(identity: String) {
        disconnectsTotal++
        println("PARTLEDGER -disconnect ${line()}")
    }

    fun onVideoTrackAdded() {
        videoTracksAdded++
        println("PARTLEDGER +video ${line()}")
    }

    fun onVideoTrackRemoved() {
        videoTracksRemoved++
        println("PARTLEDGER -video ${line()}")
    }

    fun onAudioTrackAdded() {
        audioTracksAdded++
        println("PARTLEDGER +audio ${line()}")
    }

    fun onAudioTrackRemoved() {
        audioTracksRemoved++
        println("PARTLEDGER -audio ${line()}")
    }

    /**
     * Print the final tally.
     *
     * Called at teardown, where an imbalance is most actionable: a call that ended
     * with pending participants names exactly who was left behind.
     */
    fun report() {
        val stuck = connects.entries.filter { (identity, count) ->
            count > disconnectsFor(identity)
        }
        println("PARTLEDGER final ${line()}")
        if (stuck.isNotEmpty()) {
            println(
                "PARTLEDGER final UNBALANCED identities=" +
                    stuck.joinToString(",") { "${it.key}(+${it.value}/-${disconnectsFor(it.key)})" }
            )
        }
    }

    private fun disconnectsFor(identity: String): Int =
        if (disconnectsTotal >= connects[identity] ?: 0) (connects[identity] ?: 0) else 0

    /** Test seam. Production never resets; a session teardown does. */
    internal fun reset() {
        connects.clear()
        connectsTotal = 0
        disconnectsTotal = 0
        videoTracksAdded = 0
        videoTracksRemoved = 0
        audioTracksAdded = 0
        audioTracksRemoved = 0
        check(RESETTABLE_AT == 0)
    }
}
