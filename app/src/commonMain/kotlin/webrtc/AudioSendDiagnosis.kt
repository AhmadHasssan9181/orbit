package webrtc

/**
 * What a 1:1 audio stats sample can and cannot tell us.
 *
 * ## Why this exists
 *
 * Reported as: "the Element user can't hear me", with Orbit's own UI showing a live,
 * unmuted microphone.
 *
 * The log line that made this look fine was:
 *
 *     Desktop local audio track enabled=true id=audioTrack
 *
 * That says the track object exists and is not muted. It says nothing about whether
 * the microphone produced a sample, whether the samples reached the encoder, or
 * whether a single byte left the machine. All three failure modes produce that exact
 * same line. There was no 1:1 audio instrumentation at all: no mic level, no sender
 * byte count, and Orbit never logged its own SDP.
 *
 * So the honest position is that "can't hear me" had at least four candidate causes
 * and nothing in the build could tell them apart:
 *
 *   1. the mic is muted (orbit knows -- `enabled` covers this)
 *   2. the mic is open but capturing silence (device / exclusive-mode problem)
 *   3. audio is encoded but not negotiated, so nothing is sent
 *   4. audio is sent but the peer rejects or fails to decode it
 *
 * Only `bytesSent` separates them. Rising bytes with a live track rules out 1 and 3.
 * Flat zero with an open track points at 2 or 4, and those are then separable by the
 * codec stats.
 *
 * Kept as a pure function so the reading is pinned by a test rather than trusted.
 */
internal object AudioSendDiagnosis {

    /** How often the 1:1 send sampler reads the counters. */
    const val AUDIO_SEND_DIAGNOSTIC_INTERVAL_MS = 5_000L

    /** One stats sample, reduced to the few numbers that decide anything. */
    internal data class Sample(
        val trackEnabled: Boolean,
        val bytesSent: Long,
        val packetsSent: Long,
        /** Non-null when the report carried an outbound-rtp entry for this sender. */
        val hasOutboundEntry: Boolean,
        val active: Boolean?,
    ) {
        /**
         * The one-word verdict, chosen so it cannot be misread in a log.
         *
         * `no-outbound-entry` is deliberately distinct from `not-sending`: the first
         * means the sender was never negotiated (audio track added after the offer,
         * or the m-line came back recvonly), the second means it was negotiated and
         * is producing nothing. They have different fixes and the earlier version of
         * this file could not tell them apart.
         */
        val verdict: String
            get() = when {
                !trackEnabled -> "muted"
                !hasOutboundEntry -> "no-outbound-entry"
                active == false -> "sender-inactive"
                bytesSent <= 0L -> "not-sending"
                else -> "sending"
            }

        /** True only when audio is genuinely on the wire. */
        val isTransmitting: Boolean get() = verdict == "sending"
    }

    /**
     * Pull the outbound-rtp numbers out of a raw stats report.
     *
     * Returns null when the report carries no outbound-rtp entry at all, which is a
     * real and distinct answer rather than a zero: libwebrtc omits the entry entirely
     * for a sender that was never negotiated, so a default of zero would report
     * "not-sending" for a cause that is actually "never-negotiated".
     *
     * Attribute reads are defensive because the report is a native-constructed map
     * whose keys vary by WebRTC version; a missing key must not throw inside a stats
     * callback, where the exception would be swallowed and the diagnostic lost.
     */
    fun readOutbound(
        attributes: Map<*, *>,
        trackEnabled: Boolean,
    ): Sample? {
        if (attributes.isEmpty()) return null
        val kind = attributes["kind"]?.toString()
        // Only outbound-rtp carries send counters. Inbound has bytesReceived, which
        // would otherwise let a receive-only stream read as "sending".
        if (kind != null && kind != "outbound-rtp") return null

        val bytesSent = (attributes["bytesSent"] as? Number)?.toLong() ?: 0L
        val packetsSent = (attributes["packetsSent"] as? Number)?.toLong() ?: 0L
        // `active` was added in later WebRTC builds; absent means unknown, not false.
        val active = when (val raw = attributes["active"]) {
            is Boolean -> raw
            is String -> raw.toBooleanStrictOrNull()
            else -> null
        }
        return Sample(
            trackEnabled = trackEnabled,
            bytesSent = bytesSent,
            packetsSent = packetsSent,
            hasOutboundEntry = true,
            active = active,
        )
    }

    /** The sample for a sender WebRTC has not published any outbound-rtp entry for. */
    fun noOutboundEntry(trackEnabled: Boolean): Sample = Sample(
        trackEnabled = trackEnabled,
        bytesSent = 0L,
        packetsSent = 0L,
        hasOutboundEntry = false,
        active = null,
    )
}
