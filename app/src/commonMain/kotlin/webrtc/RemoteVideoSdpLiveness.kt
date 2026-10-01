package webrtc

/**
 * Whether a remote video track we are rendering still exists in the peer's latest
 * SDP.
 *
 * ## Why this exists
 *
 * Orbit cleared a finished peer screen share by listening for the track to *end*:
 *
 *     track.addTrackEndedListener { clearRemoteScreenShare(track) }
 *
 * That listener works when the peer stops by tearing the track down. Element does
 * not do that. Element stops sharing by renegotiating -- the video m-line is
 * withdrawn or re-pointed and the media simply stops arriving. The local
 * MediaStreamTrack object is never ended, so the listener never fires and the tile
 * stays for the rest of the call holding the spotlight on a frozen final frame.
 *
 * Run 58 is the proof in one line: `remote_screen` remained the featured node from
 * the moment it attached until `endCall`, and the only
 * `remote screen share ended, clearing tile` in the whole log came from hangup.
 *
 * ## Why the SDP is the right signal
 *
 * Renegotiation is the event Element actually sends when sharing stops. The SDP is
 * the authoritative statement of which tracks exist right now, so the question
 * "is this track still real?" has exactly one honest answer: read the SDP.
 *
 * Watched for track *end* as well as renegotiation, because both are real and
 * neither covers the other.
 */
internal object RemoteVideoSdpLiveness {

    /**
     * Every video track id the peer's SDP currently declares.
     *
     * Scoped to video media sections on purpose. An `a=msid` line also appears in
     * audio sections, and a track id is only unique within its own kind, so an
     * unscoped scan could match a video track id against an audio one.
     */
    fun videoTrackIdsIn(sdp: String): Set<String> {
        if (sdp.isBlank()) return emptySet()
        val ids = LinkedHashSet<String>()
        var inVideoSection = false
        sdp.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("m=") -> inVideoSection = line.startsWith("m=video")
                inVideoSection && line.startsWith("a=msid:") -> {
                    // `a=msid:<stream> <track>` -- the track id is the second token.
                    // A session-level `a=msid:<stream>` has no track and is skipped.
                    val parts = line.removePrefix("a=msid:").trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[1].isNotBlank()) ids.add(parts[1])
                }
            }
        }
        return ids
    }

    /**
     * Whether a track we are still rendering is still declared by [sdp].
     *
     * Returns true -- keep the tile -- in every case where the SDP cannot answer
     * the question:
     *
     *  - [trackId] is null or blank, so there is nothing to look for
     *  - the SDP is blank or declares no video at all, so it carries no opinion
     *  - [sdpHasVideoSection] is false, meaning the dump was not parsed as we expect
     *
     * A liveness check that fails closed destroys a working video tile over an SDP
     * it did not understand. Failing open leaves a stale tile for the length of one
     * renegotiation, which is the smaller failure and the one that used to happen
     * on every renegotiation at all.
     */
    fun survives(sdp: String, trackId: String?, sdpHasVideoSection: Boolean): Boolean {
        if (trackId.isNullOrBlank()) return true
        if (sdp.isBlank()) return true
        if (!sdpHasVideoSection) return true
        val declared = videoTrackIdsIn(sdp)
        if (declared.isEmpty()) return true
        return trackId in declared
    }

    /** Whether [sdp] contains at least one `m=video` section. */
    fun hasVideoSection(sdp: String): Boolean =
        sdp.lineSequence().any { it.trim().startsWith("m=video") }
}
