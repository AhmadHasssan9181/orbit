package livekit

/**
 * DISPLAY-side reconciliation between the MatrixRTC membership roster and the
 * LiveKit room.
 *
 * `m.call.member` is a MEMBERSHIP RECORD, not a presence signal. The deployed
 * Element shape carries a 12 h expiry (`MatrixRtcMembershipContent.LEGACY_EXPIRES_MS`),
 * a room accumulates one record per DEVICE per CALL and nothing removes a record
 * when its owner actually leaves, so a room that has hosted a few calls still
 * lists everybody who ever joined. That is where the ghost rosters came from.
 *
 * While a call is CONNECTED the authoritative answer is the LiveKit room
 * itself, and the key exchange already proved that: `nudgeMissingPeers`
 * (`CallMediaKeyExchange`) intersects the very same roster with
 * `LiveKitRemoteVideoFrameStore.remoteParticipants` before it spends any work.
 * This object is that same intersection, lifted out so the DISPLAY path can use
 * it too — without touching the device roster the E2EE exchange consumes.
 *
 * Pure string logic (no trixnity, no JNI, no Compose) so it stays unit-testable
 * in isolation, exactly like [CallMediaKeys].
 */
object CallRosterReconciliation {

    /**
     * The Matrix user id inside a LiveKit participant identity.
     *
     * The canonical form is `${userId}:${deviceId}` (see
     * [CallMediaKeys.participantIdentity]), which [callDeviceFromParticipantIdentity]
     * parses strictly. This wrapper is the forgiving version for DISPLAY lookups:
     * a peer that joined with a bare `${userId}:${host}` (or an opaque
     * service-generated identity) must still resolve to something we can ask the
     * homeserver about, otherwise its tile degrades to a letter.
     *
     * Returns null when the string is not a Matrix id at all — an opaque
     * service-generated identity, or a track/session id. Guessing a user id from
     * one of those would quietly blank a roster, so it is refused instead.
     */
    fun userIdOf(identity: String?): String? {
        if (identity.isNullOrBlank() || !identity.startsWith("@")) return null
        // A Matrix user id ALWAYS contains a colon (`@localpart:server`), so two
        // or more colons means the last one separates the device id.
        val colons = identity.count { it == ':' }
        if (colons >= 2) return identity.substringBeforeLast(':')
        // Exactly one colon: a bare but complete mxid (`@alice:example.org`) with
        // no device segment. callDeviceFromParticipantIdentity rejects that shape
        // on purpose (it must not be mistaken for a device), but for a display
        // lookup the whole string IS the user id.
        if (colons == 1) return identity
        return null
    }

    /**
     * Matrix user ids of the LiveKit participants currently in the room.
     *
     * Identities are `${userId}:${deviceId}` (see
     * [CallMediaKeys.participantIdentity] and [callDeviceFromParticipantIdentity]),
     * so the user id is everything before the LAST colon. An identity that does
     * not parse is ignored rather than treated as a user id: feeding a
     * track/session id into a user-id comparison would silently blank the roster.
     */
    fun userIdsInRoom(livekitIdentities: Collection<String>): Set<String> {
        if (livekitIdentities.isEmpty()) return emptySet()
        return livekitIdentities
            .mapNotNullTo(LinkedHashSet()) { userIdOf(it) }
    }

    /**
     * The roster a human should see.
     *
     * - LiveKit view EMPTY -> the Matrix roster verbatim. Mirrors the
     *   `livekitIds.isEmpty() ||` guard in `nudgeMissingPeers`: a native-wiring
     *   regression must never blank the list.
     * - LiveKit view populated -> INTERSECTION with the Matrix roster, so a
     *   departed member can never be shown again while their membership record
     *   is still unexpired.
     *
     * ## "Empty LiveKit view" is ambiguous, and the caller resolves it
     *
     * The Matrix fallback cannot tell a native-wiring regression from a call that
     * simply has not finished joining, because in both cases LiveKit has told us
     * nothing. For the E2EE key exchange the Matrix fallback is right either
     * way: over-including a peer costs a wasted key send, under-including one
     * costs a silent participant, and the exchange has its own convergence sweep
     * to recover.
     *
     * For DISPLAY it is not the same trade, and this is why the fallback is a
     * parameter rather than the default. A caller that is still CONNECTING gets
     * a 12-hour-expiry membership list rendered as the live participant count,
     * which is how a two-person call announced six people from the moment Join
     * was pressed; `CallManager` therefore gates on `CallState.CONNECTED` and
     * reports an empty roster before that.
     *
     * Gating on CONNECTED is not on its own sufficient, because a call you are
     * alone in produces exactly the same empty LiveKit set as one you have not
     * finished joining. Falling back there rendered the whole stale membership
     * record — the "8 in call" next to a stage reading "No one else is here".
     * So once the call is CONNECTED the caller passes
     * `livekitIsAuthoritative = true` and an empty LiveKit view is read as the
     * thing it now unambiguously means: nobody else is here.
     *
     * The information needed to disambiguate — the call state — is not an input
     * here, which is why the caller owns the decision. There is exactly one
     * production caller and it passes `true`, so the default stays `false` and
     * a future key-exchange caller keeps the forgiving fallback it needs.
     *
     * The local user is always kept while the LiveKit view is populated: they
     * are in the room by definition (we are connected to it), but
     * `remoteParticipants` only ever holds REMOTE identities, so a plain
     * intersection would drop "You" from your own roster.
     *
     * Order and duplicates of the Matrix roster are preserved — it is the
     * display ordering, and the two lists are both small.
     */
    fun visibleUserIds(
        matrixUserIds: List<String>,
        livekitIdentities: Set<String>,
        ownUserId: String? = null,
        livekitIsAuthoritative: Boolean = false
    ): List<String> {
        val roster = matrixUserIds.filter { it.isNotBlank() }.distinct()
        val live = userIdsInRoom(livekitIdentities)
        // Empty LiveKit view, and we have been told to believe it. Note this also
        // covers "populated, but no identity parsed" — an opaque service identity
        // tells us nothing about who is in the room either, and guessing from the
        // stale record would be worse than admitting the room looks empty.
        if (live.isEmpty() && !livekitIsAuthoritative) return roster
        val out = LinkedHashSet<String>()
        for (userId in roster) {
            if (userId == ownUserId || userId in live) out += userId
        }
        // Only reachable when the Matrix roster has not caught up with our own
        // membership record yet; keeping self visible beats a roster that omits
        // the person reading it.
        if (ownUserId != null && ownUserId.isNotBlank() && ownUserId !in out) out += ownUserId
        return out.toList()
    }
}
