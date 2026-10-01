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
 * ## Why it counts state, not calls
 *
 * The first version of this counted callbacks, and run 82 proved that cannot work:
 * one peer with three tracks produced EIGHT connects and zero disconnects, and the
 * ledger reported UNBALANCED for a room holding one person. A LiveKit delegate
 * announces a participant from five independent sites, so "eight" was eight
 * announcements, not eight participants -- and a count of calls cannot express that
 * difference.
 *
 * So [present] is the state, and [pending] is its size. Everything else here is
 * reporting.
 *
 * ## What it does and does not claim
 *
 * A nonzero [pending] at teardown means Orbit still believes someone is in the room.
 * It does not by itself prove a leak: LiveKit can drop a disconnect, and a peer can
 * be genuinely present when the call ends. What it does prove is that the departure
 * path did not fire for every arrival, which has to be established before anything
 * else can be concluded -- and that is exactly the question run 62 could not answer.
 *
 * So this counts, and the count is printed on every event. It does not diagnose.
 */
internal object NativeParticipantLedger {

    private const val RESETTABLE_AT = 0

    /**
     * Participants currently believed present, keyed by SID.
     *
     * This set -- not the connect count -- is the state.
     *
     * A LiveKit delegate announces a participant from onParticipantConnected,
     * onTrackPublished, onTrackSubscribed, the connect-time roster walk and the
     * periodic rescan: five independent sites for one person, so a peer with three
     * tracks is easily announced eight times. Native now collapses those before the
     * JNI upcall (see dispatchRemoteParticipantConnected), but a diagnostic that
     * counts CALLS rather than state cannot tell "announced eight times" apart from
     * "eight participants", which is the entire question this ledger exists to
     * answer.
     *
     * Keyed by sid, NOT by identity, and that distinction is load-bearing. A
     * genuine rejoin keeps the same identity and gets a fresh sid, so a sid key
     * counts it as the new arrival it is. An identity key would collapse every
     * rejoin after the first into a "duplicate" and report a healthy churning
     * call as balanced -- a diagnostic that lies in the safe direction, which is
     * worse than one that is merely wrong.
     */
    private val present = LinkedHashSet<String>()

    @Volatile
    private var duplicateConnects = 0

    @Volatile
    private var duplicateDisconnects = 0

    /**
     * Connects seen, keyed by identity, so a rejoin is visible as a repeat.
     *
     * A rejoin is a genuine arrival, so it increments. What does not increment is
     * a second arrival for someone already here -- that is a duplicate callback,
     * and the dedup in onConnect keeps it out of this map.
     */
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
        get() = present.size

    /** Test seam for the raw counters, which [pending] now derives from state. */
    internal val connectsTotalForTest: Int
        get() = connectsTotal

    internal val disconnectsTotalForTest: Int
        get() = disconnectsTotal

    /** One line, appended to the lifecycle log so it is read in context. */
    fun line(): String =
        "ledger connects=$connectsTotal disconnects=$disconnectsTotal pending=$pending " +
            "dupConnects=$duplicateConnects dupDisconnects=$duplicateDisconnects " +
            "present=${present.size} " +
            "video +$videoTracksAdded/-$videoTracksRemoved audio +$audioTracksAdded/-$audioTracksRemoved " +
            "byIdentity=${connects.entries.joinToString(",") { "${it.key}x${it.value}" }}"

    /**
     * Record an arrival.
     *
     * [participantSid] is what identifies the arrival. It is optional only so the
     * existing tests can call this without one; production always has it, and
     * without a sid two arrivals for the same person are indistinguishable, which
     * is the situation this was written to end.
     */
    fun onConnect(identity: String, participantSid: String = "") {
        if (identity.isBlank()) return
        val key = arrivalKey(identity, participantSid)
        val wasPresent = !present.add(key)
        if (wasPresent) {
            duplicateConnects++
            println(
                "PARTLEDGER +connect DUPLICATE (same arrival seen again, not counted) " +
                    "identity=$identity sid=$participantSid ${line()}"
            )
            return
        }
        connectsTotal++
        connects[identity] = (connects[identity] ?: 0) + 1
        println("PARTLEDGER +connect ${line()}")
    }

    fun onDisconnect(identity: String, participantSid: String = "") {
        if (identity.isBlank()) return
        val key = departureKey(identity, participantSid)
        if (!present.remove(key)) {
            // A departure for someone never announced as present. Counted in
            // neither total: a negative pending, or a disconnect cancelling a
            // real participant, would both mean something they do not mean.
            duplicateDisconnects++
            println(
                "PARTLEDGER -disconnect DUPLICATE (never announced, not counted) " +
                    "identity=$identity sid=$participantSid ${line()}"
            )
            return
        }
        disconnectsTotal++
        println("PARTLEDGER -disconnect ${line()}")
    }

    /**
     * Key for an arrival.
     *
     * With a sid, that is the sid. Without one it falls back to the identity --
     * worse, because a rejoin is then indistinguishable from a duplicate callback,
     * and gets reported as the latter. The fallback keeps the ledger usable when
     * the sid is genuinely unavailable rather than silently dropping the event.
     */
    private fun arrivalKey(identity: String, participantSid: String): String =
        if (participantSid.isBlank()) "identity:$identity" else "sid:$participantSid"

    /**
     * Key for a departure.
     *
     * Same derivation as [arrivalKey] on purpose: the two must agree or a
     * participant is announced under one key and removed under another, leaving a
     * permanent phantom in [present].
     */
    private fun departureKey(identity: String, participantSid: String): String =
        arrivalKey(identity, participantSid)

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
        println("PARTLEDGER final ${line()}")
        // The set, not a comparison of two counters.
        //
        // The old check derived "was this identity cleaned up" by comparing the
        // GLOBAL disconnect total against a PER-IDENTITY connect count, which is
        // not a comparison of like things: with one peer connected once and never
        // disconnected it printed +1/-0, and with two peers each connecting and
        // disconnecting once it happened to print nothing. It could only ever be
        // accidentally right, and it could not name who was left behind.
        //
        // `present` is the actual answer, because it is the state the callbacks
        // maintain: an identity in it at teardown is a participant Orbit still
        // believes is in the room.
        if (present.isNotEmpty()) {
            // Printed as identities, not keys. "sid:PA_xyz" is an implementation
            // detail of the dedup and is not what a reader needs to go and look
            // for in the room.
            println(
                "PARTLEDGER final UNBALANCED identities=" +
                    present.joinToString(",") { it.substringAfter(':', it) }
            )
        }
    }

    /** Test seam. Production never resets; a session teardown does. */
    internal fun reset() {
        present.clear()
        connects.clear()
        connectsTotal = 0
        disconnectsTotal = 0
        duplicateConnects = 0
        duplicateDisconnects = 0
        videoTracksAdded = 0
        videoTracksRemoved = 0
        audioTracksAdded = 0
        audioTracksRemoved = 0
        check(RESETTABLE_AT == 0)
    }
}
