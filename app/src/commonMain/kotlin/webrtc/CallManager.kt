package webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.staticCompositionLocalOf
import repository.CredentialManager
import repository.TurnServer

val LocalCallManager = staticCompositionLocalOf<CallManager?> { null }

enum class CallState {
    IDLE, RINGING, CONNECTING, CONNECTED, ENDED
}

class CallManager(
    val webRtcClient: WebRtcClient,
    private val scope: CoroutineScope
) {
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState

    var iceServers: List<TurnServer> = emptyList()

    val activeRoomIdFlow = MutableStateFlow<String?>(null)
    var activeRoomId: String?
        get() = activeRoomIdFlow.value
        private set(value) {
            activeRoomIdFlow.value = value
        }
        
    var callId: String? = null
        private set
        
    var isMinimized = MutableStateFlow(false)
    var isIncomingCall = MutableStateFlow(false)
        private set
    val isVideoCall = MutableStateFlow(false)
    val isGroupCall = MutableStateFlow(false)
    val isLocalVideoEnabled = MutableStateFlow(false)
    val isLocalAudioEnabled = MutableStateFlow(true)
    val hasRemoteStream = MutableStateFlow(false)
    val nativeMediaReadiness = MutableStateFlow<livekit.NativeMediaReadiness?>(null)

    // E1: explicit group-call join ladder surfaced to the UI
    private val groupConnectionTracker = GroupCallConnectionTracker(nowMs = System::currentTimeMillis)
    val groupConnection: StateFlow<GroupCallConnection> get() = groupConnectionTracker.connection
    private var groupWatchdogJob: Job? = null

    // Bumped whenever a new call begins (1:1 or group). endCall() captures the
    // value and its deferred teardown re-checks it, so a hangup followed by a
    // quick new call cannot have the old teardown close the new call's media.
    private val callGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private fun beginCall() {
        callGeneration.incrementAndGet()
    }

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing
    val isRemoteScreenSharing: StateFlow<Boolean> = webRtcClient.isRemoteScreenSharing

    // Deafen: mutes remote playback output, mic keeps publishing. Group mutes the
    // mixed native player; P2P silences the remote AudioTrack.
    val isDeafened = MutableStateFlow(false)
    fun toggleDeafen() {
        val newValue = !isDeafened.value
        isDeafened.value = newValue
        if (isGroupCall.value) {
            livekit.setLiveKitPlaybackMuted(newValue)
        } else {
            webRtcClient.setPlaybackMuted(newValue)
        }
    }

    // Per-participant volume (group calls): LiveKit identity -> gain, 0.0 =
    // individually muted, 1.0 = unity. Independent of global deafen; backed by a
    // per-queue gain inside the native mixer.
    private val _participantGains = MutableStateFlow<Map<String, Float>>(emptyMap())
    val participantGains: StateFlow<Map<String, Float>> = _participantGains

    fun participantVolume(identity: String): Float = _participantGains.value[identity] ?: 1f

    fun setParticipantVolume(identity: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        _participantGains.value = _participantGains.value + (identity to clamped)
        livekit.setLiveKitParticipantGain(identity, clamped)
    }

    /** Returns the new muted state after toggling this participant locally. */
    fun toggleParticipantMute(identity: String): Boolean {
        val nowMuted = participantVolume(identity) > 0f
        setParticipantVolume(identity, if (nowMuted) 0f else 1f)
        return nowMuted
    }

    // Remote participants currently speaking (LiveKit identities), metered by
    // the native mixer — drives the speaking glow on call tiles.
    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers

    // LiveKit identities of remote participants whose mic is muted — drives
    // the mic-off badges on call tiles and voice-channel member rows.
    private val _remoteMicMutedIds = MutableStateFlow<Set<String>>(emptySet())
    val remoteMicMutedIds: StateFlow<Set<String>> = _remoteMicMutedIds

    // Latest WebRTC-stats sample per remote identity (jitter/RTT/loss) —
    // drives the connection-quality bars on call tiles.
    private val _remoteConnectionQuality =
        MutableStateFlow<Map<String, livekit.ConnectionQualitySample>>(emptyMap())
    val remoteConnectionQuality: StateFlow<Map<String, livekit.ConnectionQualitySample>> =
        _remoteConnectionQuality

    // True for a short window after a key-exchange self-nudge (membership
    // leave+rejoin that repairs missing peer keys) so the call UI can show a
    // calm "Repairing audio link…" status instead of alarming reconnect copy.
    private val _selfNudgeRecovery = MutableStateFlow(false)
    val selfNudgeRecovery: StateFlow<Boolean> = _selfNudgeRecovery

    init {
        scope.launch {
            livekit.liveKitActiveSpeakers().collect { _activeSpeakers.value = it }
        }
        scope.launch {
            livekit.liveKitRemoteAudioMuted().collect { _remoteMicMutedIds.value = it }
        }
        scope.launch {
            livekit.liveKitConnectionQuality().collect { _remoteConnectionQuality.value = it }
        }
        scope.launch {
            while (true) {
                val stamp = livekit.CallMediaKeyExchangeManager.lastSelfNudgeAtMs()
                val recent = stamp != 0L &&
                    System.currentTimeMillis() - stamp < livekit.NUDGE_RECOVERY_VISIBLE_MS
                if (_selfNudgeRecovery.value != recent) _selfNudgeRecovery.value = recent
                delay(1_000)
            }
        }
    }

    // Group-call participant roster from MatrixRTC membership (covers voice-only members
    // that produce no video track). Wired via groupMembersProvider in WebRtcEffects.
    // Raw membership record: may list people who left hours ago. Use
    // [displayCallMembers] for anything the user sees.
    var groupMembersProvider: ((String) -> kotlinx.coroutines.flow.Flow<List<ui.model.OrbitUser>>)? = null
    private val _groupCallMembers = MutableStateFlow<List<ui.model.OrbitUser>>(emptyList())
    val groupCallMembers: StateFlow<List<ui.model.OrbitUser>> = _groupCallMembers

    // The roster a human should see: [groupCallMembers] intersected with the
    // LiveKit participants actually in the room, and emptied when no call is
    // live. Populated by the reconciliation collector in `init`.
    private val _displayCallMembers = MutableStateFlow<List<ui.model.OrbitUser>>(emptyList())
    val displayCallMembers: StateFlow<List<ui.model.OrbitUser>> = _displayCallMembers

    // ── Identity → profile resolution (call tiles) ────────────────────────────
    //
    // A call tile is keyed by a LiveKit PARTICIPANT IDENTITY (`@user:host:DEV`),
    // not by an OrbitUser, so the tile cannot get a display name or an avatar
    // on its own. The Matrix membership roster is the usual lookup, but it is a
    // membership RECORD: a peer who joined from a client we never saw publish a
    // member event is simply absent, and the roster's own fallback entry carries
    // `avatarUrl = null`. Either way the tile fell back to a coloured circle with
    // one letter in it.
    //
    // So resolve the identity to a Matrix userId (CallMediaKeysCore already
    // defines the inverse of `participantIdentity`) and ask the repository for
    // the profile, which applies MatrixContext.convertMxcUrl and therefore hands
    // Coil a real http(s) URL. Without that conversion Coil is handed an
    // `mxc://` string and fails silently -- which is also consistent with the
    // letter-only tiles.
    //
    // Wired from WebRtcEffects, like groupMembersProvider.
    var groupProfileResolver: ((suspend (String) -> ui.model.OrbitUser?)?) = null
    private val _participantProfiles = MutableStateFlow<Map<String, ui.model.OrbitUser>>(emptyMap())
    val participantProfiles: StateFlow<Map<String, ui.model.OrbitUser>> = _participantProfiles

    /** Cached profile for a LiveKit identity, or null while it is unresolved. */
    fun profileForIdentity(identity: String?): ui.model.OrbitUser? {
        if (identity.isNullOrBlank()) return null
        _participantProfiles.value[identity]?.let { return it }
        // Same user on two devices: the avatar is per-USER, so accept a profile
        // fetched for any of this user's identities rather than re-fetching.
        val userId = livekit.CallRosterReconciliation.userIdOf(identity) ?: return null
        return _participantProfiles.value.entries
            .firstOrNull { (id, _) -> livekit.CallRosterReconciliation.userIdOf(id) == userId }
            ?.value
    }

    /**
     * Kicks off a profile fetch for [identities] that are not cached yet. Safe
     * to call on every recomposition: in-flight and already-resolved identities
     * are skipped, so a per-frame call site cannot spam /profile.
     */
    fun requestProfilesFor(identities: Collection<String>) {
        val resolver = groupProfileResolver ?: return
        val missing = identities
            .filter { it.isNotBlank() }
            .filter { profileForIdentity(it) == null }
            .distinct()
        if (missing.isEmpty()) return
        scope.launch {
            for (identity in missing) {
                val userId = livekit.CallRosterReconciliation.userIdOf(identity) ?: continue
                val user = runCatching { resolver(userId) }.getOrNull() ?: continue
                // A null avatarUrl is a legitimate answer ("this user set no
                // picture"), so the user IS cached — only a failed fetch skips.
                _participantProfiles.update { it + (identity to user) }
            }
        }
    }

    val duckingStrength = MutableStateFlow(0.8f)
    val noiseSuppression = MutableStateFlow(true)
    val echoCancellation = MutableStateFlow(true)
    val autoGainControl = MutableStateFlow(true)
    val isVirtualBackgroundEnabled = MutableStateFlow(false)
    var audioBitrateKbps: Int = 64

    // Callbacks to send Matrix events
    var refreshIceServers: (suspend () -> List<TurnServer>)? = null
    var sendInvite: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendAnswer: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendNegotiate: (suspend (roomId: String, callId: String, type: String, sdp: String) -> Unit)? = null
    var sendCandidate: (suspend (roomId: String, callId: String, candidate: IceCandidateModel) -> Unit)? = null
    var sendHangup: (suspend (roomId: String, callId: String) -> Unit)? = null
    var onLeaveGroupCall: (suspend (roomId: String, callId: String) -> Unit)? = null
    var onUpdateGroupCallMembershipCapabilities: (suspend (roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String?) -> Boolean)? = null

    // matrixRTC E2EE media keys (encrypted-room calls): wired from WebRtcEffects.
    var roomIsEncryptedChecker: (suspend (roomId: String) -> Boolean)? = null
    var mediaKeysStart: ((roomId: String, livekitIdentity: String) -> Unit)? = null
    var mediaKeysStop: (() -> Unit)? = null

    // Stops the per-room call-roster pollers (participant list + per-device key
    // roster). They poll /rooms/{id}/state every 3s, so anything that ends or
    // aborts a call must stop them; leaveGroupCall alone does not cover the
    // hangup-without-notify or failed-join paths.
    var onGroupCallObserversStop: ((roomId: String) -> Unit)? = null

    // LiveKit identity of the current group-call join; reused for membership
    // re-affirmation when the camera is toggled mid-call.
    private var groupCallLivekitIdentity: String? = null

    // Whether the room timeline is currently pushed aside to give the live call
    // the main column.
    //
    // Reading the room you are calling FROM is a real need, and the way it was
    // being satisfied was wrong twice. The first version made the drawer's
    // `Voice` row a toggle, so pressing it while already in the call hung up --
    // a run log shows `endCall ... <- ChannelDrawer.kt:250 <- ChannelList.kt:527`,
    // i.e. pressing "show me the call" disconnected you from it. The second
    // problem was that reading the room was impossible at all while the call
    // occupied the main column.
    //
    // So the call and the room are independent views of one live session, and
    // this flag is which one the main column is showing. It is reset to `false`
    // (call showing) by [joinGroupCall] and [endCall], so it can never be left
    // stranded across calls -- a flag that survives a hangup is how you end up
    // joining a call and seeing no call.
    private val _callSurfaceCollapsed = MutableStateFlow(false)
    val callSurfaceCollapsed: StateFlow<Boolean> = _callSurfaceCollapsed

    /** True to show the room timeline full-screen while a call stays live. */
    fun collapseCallSurface() {
        _callSurfaceCollapsed.value = true
    }

    /** True to give the main column back to the live call. */
    fun expandCallSurface() {
        _callSurfaceCollapsed.value = false
    }

    // Raise hand: Matrix userIds whose hand is currently up in the active call.
    // Fed from io.orbit.raise_hand room events; cleared when the call ends.
    private val _raisedHands = MutableStateFlow<Set<String>>(emptySet())
    val raisedHands: StateFlow<Set<String>> = _raisedHands
    val isMyHandRaised = MutableStateFlow(false)
    var sendRaiseHand: (suspend (roomId: String, callId: String, raised: Boolean) -> Unit)? = null

    fun setHandRaised(senderId: String, raised: Boolean) {
        if (senderId.isBlank()) return
        _raisedHands.value = if (raised) _raisedHands.value + senderId else _raisedHands.value - senderId
    }

    fun toggleRaiseHand() {
        val room = activeRoomId ?: return
        val myId = CredentialManager.userId ?: return
        val next = !isMyHandRaised.value
        isMyHandRaised.value = next
        setHandRaised(myId, next)
        val currentCall = callId
        scope.launch {
            sendRaiseHand?.invoke(room, currentCall ?: "", next)
        }
    }

    /**
     * ICE candidates that arrived before the descriptions they belong to.
     *
     * These MUST be guarded. Writers come from two different threads:
     * `handleIceCandidate` is driven by the `LaunchedEffect` collector in
     * `WebRtcEffects`, so it lands on the Compose/EDT dispatcher, while
     * `flushRemoteCandidates` is invoked from `setRemoteDescription`'s
     * `onSuccess` observer, which webrtc-java calls on its own SIGNALING thread.
     * `endCall`/`startCall` clear from the EDT, and `endCall` is also reachable
     * from a WebRTC `onError` observer on a non-EDT thread.
     *
     * A bare `ArrayList` under those conditions can lose entries, hand back a
     * truncated `toList()`, or -- with a concurrent structural modification --
     * spin forever inside `get()` on Java 8+ cyclic-bin chains. `remoteDescriptionSet`
     * was a plain `Boolean` with no happens-before edge between the two threads.
     * The observable symptom is a call that does not connect on the first
     * attempt, which is indistinguishable from a bad network.
     *
     * One lock for the flag and both lists, so the "set the flag AND drain the
     * list" step is atomic -- otherwise a candidate arriving between the two
     * would be stranded in a buffer nobody reads again.
     */
    private val candidateLock = Any()
    private val pendingLocalCandidates = mutableListOf<IceCandidateModel>()
    // Remote ICE candidates can arrive before our setRemoteDescription completes
    // (peerConnection is null then and would silently drop them) — buffer until it's set.
    private val pendingRemoteCandidates = mutableListOf<IceCandidateModel>()
    private var remoteDescriptionSet = false

    /**
     * Empties both candidate buffers and re-arms buffering.
     *
     * Every call-start and every hangup has to do exactly this, and there were six
     * copies of the three-line sequence doing it unguarded -- so one was going to
     * be missed eventually, and a missed reset means the next call starts with
     * the previous call's candidates already flushed as "remote description set".
     */
    private fun resetCandidateBuffers() {
        synchronized(candidateLock) {
            pendingLocalCandidates.clear()
            pendingRemoteCandidates.clear()
            remoteDescriptionSet = false
        }
    }

    /**
     * Takes the buffered LOCAL candidates, leaving the buffer empty.
     *
     * Drains under the lock and copies out, so the caller can hand the list to
     * `addIceCandidate` without holding the lock across a WebRTC call.
     */
    private fun drainLocalCandidates(): List<IceCandidateModel> = synchronized(candidateLock) {
        pendingLocalCandidates.toList().also { pendingLocalCandidates.clear() }
    }
    private var candidatesAllowedForCallId: String? = null
    private var pendingInviteSdp: String? = null  // Stores the offer SDP until the user answers
    private var ringTimeoutJob: Job? = null
    companion object {
        private const val DEFAULT_RING_TIMEOUT_MS = 60_000L // js-sdk CALL_TIMEOUT_MS
        private const val TOKEN_FETCH_TIMEOUT_MS = 10_000L
        private const val RETRY_BACKOFF_MS = 1_000L
        private const val MEMBERSHIP_LEAVE_TIMEOUT_MS = 1_500L
    }

    init {
        scope.launch {
            webRtcClient.onIceCandidate.collect { candidate ->
                val room = activeRoomId ?: return@collect
                val cId = callId ?: return@collect

                if (candidatesAllowedForCallId == cId) {
                    sendCandidate?.invoke(room, cId, candidate)
                } else {
                    synchronized(candidateLock) { pendingLocalCandidates.add(candidate) }
                    println("DEBUG: [WebRTC] Queued local ICE candidate until signaling is ready")
                }
            }
        }
        scope.launch {
            webRtcClient.isScreenSharing.collect { sharing ->
                _isScreenSharing.value = sharing
            }
        }
        scope.launch {
            webRtcClient.onStreamAdded.collect { added ->
                hasRemoteStream.value = added
            }
        }
        var membersJob: Job? = null
        // WHO IS IN THE CALL, for DISPLAY.
        //
        // `provider` is the raw matrixRTC MEMBERSHIP roster (m.call.member room
        // state), which is a record with a multi-hour expiry window, not a
        // presence signal: it keeps listing everybody who ever joined the room,
        // which is why the call surface and the drawer rendered 4-8 people for a
        // call with 2. While we are actually connected the LiveKit room is the
        // authoritative view, and the E2EE media-key exchange has been
        // reconciling against it all along (CallMediaKeyExchange.nudgeMissingPeers).
        // The SAME intersection is applied here — to the participant roster only.
        //
        // `_groupCallMembers` stays the raw Matrix roster on purpose: the join
        // watchdog's "did we expect remote media" heuristic keys off its size,
        // and the tile-name/avatar lookups want every profile the room knows,
        // not just the connected ones. Reconciliation is a DISPLAY concern.
        scope.launch {
            kotlinx.coroutines.flow.combine(
                _groupCallMembers,
                livekit.LiveKitRemoteVideoFrameStore.remoteParticipants,
                _callState
            ) { matrixRoster, liveKitIds, state ->
                // CONNECTED, not merely "not idle".
                //
                // During CONNECTING/RINGING the LiveKit participant set is empty,
                // and `visibleUserIds` treats an empty LiveKit view as "we have no
                // better information" and returns the Matrix roster VERBATIM. That
                // is the correct choice for a guard against a native-wiring
                // regression, and it is exactly wrong for a call that has not
                // finished joining: the roster it falls back to is a 12-hour-expiry
                // membership list, so a two-person call announced six participants
                // from the moment you pressed Join until the moment you connected.
                //
                // So the two cases are separated here rather than inside
                // `visibleUserIds`, which stays a pure function of its inputs:
                // only reconcile once CONNECTED, and report nobody until then.
                if (state != CallState.CONNECTED) {
                    emptyList()
                } else {
                    val visible = livekit.CallRosterReconciliation.visibleUserIds(
                        matrixUserIds = matrixRoster.map { it.id },
                        livekitIdentities = liveKitIds,
                        ownUserId = CredentialManager.userId
                    )
                    if (visible.size == matrixRoster.size) {
                        matrixRoster
                    } else {
                        matrixRoster.filter { visible.contains(it.id) }
                    }
                }
            }.collect { reconciled -> _displayCallMembers.value = reconciled }
        }
        scope.launch {
            activeRoomIdFlow.collect { roomId ->
                membersJob?.cancel()
                membersJob = null
                val provider = groupMembersProvider
                if (roomId == null || provider == null) {
                    _groupCallMembers.value = emptyList()
                } else {
                    membersJob = scope.launch {
                        provider(roomId).collect { members -> _groupCallMembers.value = members }
                    }
                }
            }
        }
        // E1 RENDERING evidence: first remote track/frame arriving during a join
        scope.launch {
            kotlinx.coroutines.flow.combine(
                isGroupCall,
                livekit.LiveKitRemoteVideoFrameStore.activeTracks
            ) { group, tracks -> group to tracks.isNotEmpty() }
                .collect { (group, hasTracks) ->
                    if (group && hasTracks) {
                        groupConnectionTracker.onFirstRemoteFrame()
                    }
                }
        }
    }

    private var isJoiningGroupCall = false

    fun joinGroupCall(
        roomId: String, 
        callId: String, 
        isVideo: Boolean, 
        livekitUrl: String? = null, 
        livekitTokenFetcher: (suspend () -> livekit.LiveKitAuthResponse)? = null
    ) {
        if (callState.value != CallState.IDLE && this.callId == callId) {
            println("DEBUG [CallManager] Already in call $callId, ensuring native media session is ready")
            ensureNativeMediaSessionStarted(roomId, callId, isVideo, livekitUrl, livekitTokenFetcher)
            return
        }
        if (isJoiningGroupCall) {
            println("DEBUG [CallManager] Join already in progress for $callId, ignoring redundant request")
            return
        }
        if (callState.value != CallState.IDLE) {
            // A DIFFERENT call is already live. The old code only guarded "same
            // callId" and "a join is in flight", so a group join from a chat
            // banner fell straight through and took over every field: the P2P
            // PeerConnection was never closed, the DM peer kept receiving our
            // audio, no m.call.invite was ever sent for the old call, and that
            // room kept showing us as in a call. Two media sessions, one live
            // mic. Both join buttons stay enabled while CONNECTED, so this was
            // one click away.
            println(
                "WARN [CallManager] group join for $callId supersedes live call " +
                    "${this.callId} in room $activeRoomId; ending it first"
            )
            endCall(notifyRemote = true)
        }

        isJoiningGroupCall = true
        // A new call always shows the call. Reset here rather than trusting the
        // previous call's state, so joining after a peeked call does not land
        // you in a room with no call surface and no way to know why.
        _callSurfaceCollapsed.value = false
        activeRoomId = roomId
        this.callId = callId
        isIncomingCall.value = false
        isVideoCall.value = isVideo
        isGroupCall.value = true
        webRtcClient.isGroupCall = true
        beginCall()
        isLocalVideoEnabled.value = isVideo
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        resetCandidateBuffers()
        _callState.value = CallState.CONNECTING
        groupConnectionTracker.begin()

        scope.launch {
            try {
                if (livekitUrl != null && livekitTokenFetcher != null) {
                    startGroupWatchdog(isVideo)
                    val mediaReady = connectNativeGroupMedia(roomId, callId, isVideo, livekitUrl, livekitTokenFetcher)
                    if (mediaReady) {
                        _callState.value = CallState.CONNECTED
                        println("DEBUG [CallManager] LiveKit call state set to CONNECTED after MediaReady")
                    } else {
                        failGroupJoin(groupConnectionTracker.connection.value.message ?: "Group call join failed")
                    }
                    return@launch
                }

                val error = livekit.NativeError(
                    code = livekit.NativeErrorCode.CONNECT_FAILED,
                    message = "MatrixRTC group call requires LiveKit URL and token fetcher",
                    operation = "joinGroupCall",
                    recoverable = false
                )
                println("ERROR [CallManager] ${error.code} ${error.operation}: ${error.message}")
                failGroupJoin(error.message)
            } finally {
                isJoiningGroupCall = false
            }
        }
    }

    private fun startGroupWatchdog(videoRequested: Boolean) {
        groupWatchdogJob?.cancel()
        groupWatchdogJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(500)
                val decision = groupConnectionTracker.evaluateDeadlines(
                    now = System.currentTimeMillis(),
                    videoRequested = videoRequested,
                    remoteMediaExpected = _groupCallMembers.value.size > 1
                )
                if (decision == TrackerDecision.GiveUp) {
                    val stage = groupConnectionTracker.connection.value.stage
                    println("ERROR [CallManager] Group-call join stalled at $stage, giving up visibly")
                    // Force-unblock any in-flight native wait through the normal teardown path.
                    livekit.disconnectFromLiveKit(userInitiated = false)
                    groupConnectionTracker.markFailed(
                        "Stalled at ${stage.name.lowercase().replaceFirstChar { it.uppercase() }}"
                    )
                    failGroupJoin(groupConnectionTracker.connection.value.message)
                    break
                }
                // H1: once in the call, watch for the native media chain dying
                // (reconnect budget exhausted or unrecoverable failure) so the user
                // returns to the lobby instead of sitting in a zombie call.
                val stage = groupConnectionTracker.connection.value.stage
                if (stage == GroupCallStage.InCall || stage == GroupCallStage.Reconnecting) {
                    val readiness = livekit.liveKitCurrentReadiness()
                    if (readiness != null) {
                        if (readiness.state == livekit.NativeMediaState.Failed) {
                            println("ERROR [CallManager] Group-call media failed mid-call, returning to lobby")
                            // Full teardown (not failGroupJoin) so the MSC3401 leave is
                            // published — otherwise peers keep counting us as a member.
                            scope.launch { endCall() }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun failGroupJoin(message: String? = null) {
        if (message != null) {
            groupConnectionTracker.markFailed(message)
        }
        groupWatchdogJob?.cancel()
        groupWatchdogJob = null
        livekit.setLiveKitReconnectedListener(null)
        _callState.value = CallState.IDLE

        // The room and call id are captured BEFORE they are nulled, because
        // teardown needs both.
        val failedRoom = activeRoomId
        val failedCallId = callId
        failedRoom?.let { room -> onGroupCallObserversStop?.invoke(room) }
        activeRoomId = null
        callId = null
        isGroupCall.value = false
        webRtcClient.isGroupCall = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        resetCandidateBuffers()

        // This path used to stop here. The ordinary failure mode is "SFU
        // connected, audio never reached MediaReady", and after the retry budget
        // runs out the user was dropped back to the lobby while the native
        // engine stayed connected and publishing, the media-key exchange stayed
        // running and distributing olm keys, and our m.call.member event stayed
        // in room state until TTL, so peers kept counting us as a participant.
        // The watchdog and mid-call paths already routed through endCall() for
        // exactly this reason.
        if (failedRoom != null && failedCallId != null) {
            scope.launch {
                runCatching { onLeaveGroupCall?.invoke(failedRoom, failedCallId) }
                    .onFailure { println("WARN [CallManager] membership leave after failed join failed: ${it.message}") }
            }
        }
        teardownNativeMedia()
    }

    /**
     * Rebuild the SFU session because a remote screen share never attached.
     *
     * Delegates to the media session's existing token-refresh reconnect, so the
     * Matrix membership and the call itself are untouched -- only the LiveKit
     * session is rebuilt. Deliberately not silent: the UI offers it, and the UI
     * also fires it automatically after five seconds, because a share nobody can
     * see is a dead call rather than a waiting one.
     */
    fun reconnectForStuckShare(reason: String) {
        if (_callState.value != CallState.CONNECTED) return
        scope.launch {
            livekit.reconnectForStuckShare(reason)
        }
    }

    private fun ensureNativeMediaSessionStarted(
        roomId: String,
        callId: String,
        isVideo: Boolean,
        livekitUrl: String?,
        livekitTokenFetcher: (suspend () -> livekit.LiveKitAuthResponse)?
    ) {
        if (isJoiningGroupCall) return
        scope.launch {
            val ready = livekit.ensureLiveKitMediaReady()
            if (ready.ok) {
                if (_callState.value != CallState.CONNECTED) {
                    _callState.value = CallState.CONNECTED
                }
                return@launch
            }

            if (livekitUrl == null || livekitTokenFetcher == null) {
                val error = livekit.NativeError(
                    code = livekit.NativeErrorCode.CONNECT_FAILED,
                    message = "MatrixRTC group call requires LiveKit URL and token fetcher",
                    operation = "ensureNativeMediaSessionStarted",
                    recoverable = false
                )
                println("ERROR [CallManager] ${error.code} ${error.operation}: ${error.message}")
                failGroupJoin()
                return@launch
            }

            isJoiningGroupCall = true
            try {
                groupConnectionTracker.begin()
                startGroupWatchdog(isVideo)
                if (connectNativeGroupMedia(roomId, callId, isVideo, livekitUrl, livekitTokenFetcher)) {
                    _callState.value = CallState.CONNECTED
                }
            } finally {
                isJoiningGroupCall = false
            }
        }
    }

    private suspend fun connectNativeGroupMedia(
        roomId: String,
        callId: String,
        isVideo: Boolean,
        livekitUrl: String,
        livekitTokenFetcher: suspend () -> livekit.LiveKitAuthResponse
    ): Boolean {
        return try {
            // Phase timer. Joining a MatrixRTC call is four network round trips
            // (ICE discovery, OpenID, SFU JWT, membership publish) plus the native
            // connect, and "joining takes a long time" is not a diagnosis. Each
            // step is stamped so the slow one is a number rather than a guess.
            // One line at the end; see JOIN-TIMING below.
            val t0 = System.nanoTime()
            fun phase() = (System.nanoTime() - t0) / 1_000_000L
            var tIce = 0L
            var tToken = 0L
            var tPublish = 0L
            var tProbe = 0L
            var tNative = 0L

            loadIceServers()
            tIce = phase()
            var effectiveIceServers = iceServers
            if (effectiveIceServers.isEmpty()) {
                println("DEBUG [CallManager] No Matrix ICE servers found, injecting fallback STUN")
                effectiveIceServers = listOf(repository.TurnServer(listOf("stun:stun.l.google.com:19302"), null, null))
            }

            println("DEBUG [CallManager] Fetching LiveKit token for url: $livekitUrl")
            val tokenStart = System.nanoTime()
            val authResponse = withTimeoutOrNull(TOKEN_FETCH_TIMEOUT_MS) { livekitTokenFetcher() }
            tToken = (System.nanoTime() - tokenStart) / 1_000_000L
            if (authResponse == null) {
                println("ERROR [CallManager] LiveKit token fetch timed out after ${TOKEN_FETCH_TIMEOUT_MS}ms")
                groupConnectionTracker.markFailed("Timed out fetching LiveKit token")
                return false
            }
            println("DEBUG [CallManager] Token and SFU URL fetched. Publishing MatrixRTC membership before native media...")
            // The token fetch (OpenID + SFU JWT round trips) can be slow; stamp
            // the Joining deadline so it only measures the membership publish.
            groupConnectionTracker.noteJoiningProgress()
            groupCallLivekitIdentity = authResponse.identity
            val publishStart = System.nanoTime()
            val membershipVisible = onUpdateGroupCallMembershipCapabilities
                ?.invoke(roomId, callId, true, false, authResponse.identity) ?: true
            tPublish = (System.nanoTime() - publishStart) / 1_000_000L
            groupConnectionTracker.onMembershipPublished(membershipVisible)
            if (!membershipVisible) {
                println("ERROR [CallManager] MatrixRTC membership was not visible after publish; aborting join")
                return false
            }
            if (groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) {
                // The watchdog gave up while the token/membership hops were in
                // flight — do not start a native session nobody is waiting for.
                println("WARN [CallManager] Join aborted: watchdog gave up during pre-join steps")
                return false
            }
            // H1: after a mid-call reconnect the m.call.member TTL hasn't moved —
            // re-affirm membership (fresh expires) and restore the InCall UI stage.
            val reconnectRoom = roomId
            val reconnectCall = callId
            val reconnectIdentity = authResponse.identity
            livekit.setLiveKitReconnectedListener {
                println("DEBUG [CallManager] LiveKit reconnected mid-call — restoring stage + re-affirming membership")
                groupConnectionTracker.onReconnectComplete()
                scope.launch {
                    runCatching {
                        onUpdateGroupCallMembershipCapabilities?.invoke(
                            reconnectRoom,
                            reconnectCall,
                            true,
                            isLocalVideoEnabled.value,
                            reconnectIdentity
                        )
                    }.onFailure { println("WARN [CallManager] membership re-affirm after reconnect failed: ${it.message}") }
                }
            }
            println("DEBUG [CallManager] MatrixRTC membership visible. Connecting native media at ${authResponse.serverUrl}...")

            // Encrypted rooms need per-participant media keys (matrixRTC olm
            // exchange) and the native frame cryptors armed BEFORE connect.
            val probeStart = System.nanoTime()
            val roomEncrypted = try {
                roomIsEncryptedChecker?.invoke(roomId) ?: false
            } catch (e: Exception) {
                println("WARN [CallManager] room encryption probe failed: ${e.message}")
                false
            }
            tProbe = (System.nanoTime() - probeStart) / 1_000_000L
            livekit.prepareNativeE2EE(roomEncrypted)
            println(
                "DEBUG [CallManager] room encrypted=$roomEncrypted → native E2EE media " +
                    if (roomEncrypted) "armed" else "disabled"
            )

            var readiness: livekit.NativeResult<livekit.NativeMediaReadiness>? = null
            var connectOk = false
            val nativeStart = System.nanoTime()
            var attempts = 0
            for (attempt in 1..GroupCallConnectionTracker.MAX_CONNECT_ATTEMPTS) {
                if (attempt > 1) {
                    groupConnectionTracker.noteRetryStarted()
                    println("WARN [CallManager] Retrying native LiveKit connect (attempt $attempt)")
                    livekit.disconnectFromLiveKit(userInitiated = false)
                    delay(RETRY_BACKOFF_MS)
                }
                attempts = attempt
                readiness = connectOnceWithProgress(isVideo, effectiveIceServers, livekitTokenFetcher, authResponse)
                connectOk = readiness.ok && readiness.value != null &&
                    readiness.value.state == livekit.NativeMediaState.MediaReady &&
                    readiness.value.audioPublished &&
                    readiness.value.acceptingAudioFrames
                if (connectOk || groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) break
            }
            tNative = (System.nanoTime() - nativeStart) / 1_000_000L
            println(
                "JOIN-TIMING ice=${tIce}ms token=${tToken}ms publish=${tPublish}ms " +
                    "encProbe=${tProbe}ms native=${tNative}ms (attempts=$attempts) total=${phase()}ms"
            )
            if (groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) {
                // Watchdog gave up mid-join (even if the session technically
                // connected afterwards): tear down instead of leaving a zombie
                // that keeps publishing after the user already saw the failure.
                println(
                    "WARN [CallManager] Join aborted: call already failed " +
                        "(stage=${groupConnectionTracker.connection.value.failedStage}); tearing down native session"
                )
                livekit.disconnectFromLiveKit(userInitiated = false)
                return false
            }
            finalizeGroupJoin(roomId, callId, isVideo, authResponse, readiness, roomEncrypted)
        } catch (e: Exception) {
            nativeMediaReadiness.value = null
            println("DEBUG [CallManager] Failed to connect native LiveKit media: ${e.message}")
            e.printStackTrace()
            groupConnectionTracker.markFailed(e.message ?: "Native media error")
            false
        }
    }

    private suspend fun connectOnceWithProgress(
        isVideo: Boolean,
        effectiveIceServers: List<repository.TurnServer>,
        livekitTokenFetcher: suspend () -> livekit.LiveKitAuthResponse,
        authResponse: livekit.LiveKitAuthResponse
    ): livekit.NativeResult<livekit.NativeMediaReadiness> {
        val pollJob = scope.launch {
            while (isActive) {
                delay(300)
                groupConnectionTracker.onNativeReadiness(livekit.liveKitCurrentReadiness())
            }
        }
        try {
            return livekit.connectAndPublishToLiveKit(
                authResponse = authResponse,
                isVideo = isVideo,
                iceServers = effectiveIceServers,
                refreshTokenFetcher = livekitTokenFetcher,
                audioBitrateKbps = audioBitrateKbps
            )
        } finally {
            pollJob.cancel()
        }
    }

    private suspend fun finalizeGroupJoin(
        roomId: String,
        callId: String,
        isVideo: Boolean,
        authResponse: livekit.LiveKitAuthResponse,
        readiness: livekit.NativeResult<livekit.NativeMediaReadiness>?,
        roomEncrypted: Boolean = false
    ): Boolean {
        if (readiness == null || !readiness.ok || readiness.value == null) {
            val message = readiness?.error?.message ?: "LiveKit connect did not complete"
            println("DEBUG [CallManager] Native media failed: ${readiness?.error?.code} $message")
            groupConnectionTracker.finalizeJoin(null, isVideo)
            return false
        }
        val value = readiness.value
        // A watchdog give-up may have already failed the call while this join coroutine
        // was suspended; don't publish stale readiness over a terminal tracker state.
        if (groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) {
            println("DEBUG [CallManager] Skipping finalizeGroupJoin readiness write: call already failed")
            groupConnectionTracker.finalizeJoin(null, isVideo)
            return false
        }
        nativeMediaReadiness.value = value
        val stable = groupConnectionTracker.finalizeJoin(value, isVideo)
        if (!stable) {
            println("DEBUG [CallManager] Native media not ready after join: $value")
            return false
        }
        if (roomEncrypted) {
            // Start the matrixRTC olm media-key exchange once media is live; the
            // LiveKit identity must match the JWT sub the SFU issued us.
            val ownIdentity = authResponse.matrixRtcMemberId
                ?: authResponse.identity
                ?: groupCallLivekitIdentity
                ?: ""
            try {
                mediaKeysStart?.invoke(roomId, ownIdentity)
            } catch (e: Exception) {
                println("WARN [CallManager] media key exchange start failed: ${e.message}")
            }
        }
        isLocalVideoEnabled.value = value.videoPublished
        isVideoCall.value = isVideoCall.value || value.videoPublished
        val membershipMode = livekit.LiveKitFeatureFlags.videoMembershipMode
        val membershipModeAllowsVideo = membershipMode == "intent-video" ||
            membershipMode == "capabilities-video" ||
            membershipMode == "element-reference"
        val updateVideoMembership = value.videoPublished &&
            livekit.LiveKitFeatureFlags.videoUpdateMembershipAfterPublish &&
            membershipModeAllowsVideo
        if (updateVideoMembership) {
            println("DEBUG [CallManager] publishing MatrixRTC membership after video publish audio=true video=true mode=$membershipMode")
            onUpdateGroupCallMembershipCapabilities?.invoke(roomId, callId, true, true, authResponse.identity)
        } else {
            println(
                "DEBUG [CallManager] keeping MatrixRTC membership audio-only after MediaReady " +
                    "videoPublished=${value.videoPublished} updateMembershipAfterPublish=${livekit.LiveKitFeatureFlags.videoUpdateMembershipAfterPublish} membershipMode=$membershipMode"
            )
        }
        return true
    }

    fun startCall(roomId: String, isVideo: Boolean) {
        val newCallId = "call_${System.currentTimeMillis()}"
        beginCall()
        activeRoomId = roomId
        callId = newCallId
        isIncomingCall.value = false
        isVideoCall.value = isVideo
        isGroupCall.value = false
        // Must mirror the flag above: the WebRtcClient is session-scoped, so a
        // leftover `true` from a previous group call would make
        // startLocalMedia() below attach no P2P audio/video track.
        webRtcClient.isGroupCall = false
        isLocalVideoEnabled.value = isVideo
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        resetCandidateBuffers()
        _callState.value = CallState.CONNECTING

        scope.launch {
            loadIceServers()
            if (callId != newCallId) return@launch

            webRtcClient.initialize(iceServers)
            webRtcClient.startLocalMedia(isVideo)

            webRtcClient.createOffer(
                onSuccess = { sessionDescription ->
                    scope.launch {
                        sendInvite?.invoke(roomId, newCallId, sessionDescription.sdp)
                        allowLocalCandidatesAndFlush(roomId, newCallId)
                        if (callId == newCallId) {
                            _callState.value = CallState.RINGING // Waiting for answer
                        }
                    }
                },
                onError = {
                    endCall()
                }
            )
        }
    }

    fun handleCallInvite(roomId: String, incomingCallId: String, sdp: String, lifetimeMs: Long? = null) {
        if (_callState.value != CallState.IDLE && callId == incomingCallId) {
            println("DEBUG: [WebRTC] Ignoring duplicate invite for active call $incomingCallId")
            return
        }

        if (_callState.value != CallState.IDLE) {
            println("DEBUG: [WebRTC] Ignoring invite for $incomingCallId because already in call $callId")
            return // Already in a different call
        }
        
        this.activeRoomId = roomId
        this.callId = incomingCallId
        isIncomingCall.value = true
        isVideoCall.value = sdpHasVideo(sdp)
        isGroupCall.value = false
        // The client instance outlives the call; a stale `true` makes
        // startLocalMedia() take the LiveKit branch and produce no P2P track.
        webRtcClient.isGroupCall = false
        beginCall()
        isLocalVideoEnabled.value = false
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        resetCandidateBuffers()
        pendingInviteSdp = sdp  // Store SDP; actual init happens when user answers
        _callState.value = CallState.RINGING
        println("DEBUG: [CallMgr] Incoming invite from $roomId callId=$incomingCallId, isVideo=${sdpHasVideo(sdp)}")

        // Expire the invite like js-sdk does: stop ringing after its advertised lifetime
        // (the caller's own CALL_TIMEOUT_MS timer hangs up remotely; here we just reset).
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            val waitMs = lifetimeMs ?: DEFAULT_RING_TIMEOUT_MS
            delay(waitMs)
            if (_callState.value == CallState.RINGING && callId == incomingCallId) {
                println("DEBUG: [CallMgr] Incoming call $incomingCallId expired after ${waitMs}ms (missed)")
                isIncomingCall.value = false
                activeRoomId = null
                callId = null
                pendingInviteSdp = null
                _callState.value = CallState.IDLE
            }
        }

        // Warm up ICE servers in background so they're ready when user answers
        scope.launch {
            loadIceServers()
        }
    }

    fun answerCall(isVideo: Boolean, sdp: String? = null) {
        val currentRoom = activeRoomId ?: return
        val currentCall = callId ?: return
        // Use explicitly passed SDP or fall back to the stored pending invite SDP
        val offerSdp = sdp ?: pendingInviteSdp
        
        isVideoCall.value = isVideo
        isGroupCall.value = false
        webRtcClient.isGroupCall = false
        isLocalVideoEnabled.value = isVideo
        isLocalAudioEnabled.value = true
        _callState.value = CallState.CONNECTING
        pendingInviteSdp = null
        println("DEBUG: [CallMgr] answerCall: callId=$currentCall isVideo=$isVideo hasOfferSdp=${offerSdp != null}")

        scope.launch {
            loadIceServers()
            if (callId != currentCall) return@launch

            // Always initialize and set remote description here to avoid race with handleCallInvite's coroutine
            if (offerSdp != null) {
                webRtcClient.initialize(iceServers)
                webRtcClient.setRemoteDescription(
                    SessionDescriptionModel("offer", offerSdp),
                    onSuccess = {
                        flushRemoteCandidates()
                        println("DEBUG: [CallMgr] Remote description set, creating answer...")
                        webRtcClient.startLocalMedia(isVideo)
                        webRtcClient.createAnswer(
                            onSuccess = { sessionDescription ->
                                scope.launch {
                                    sendAnswer?.invoke(currentRoom, currentCall, sessionDescription.sdp)
                                    allowLocalCandidatesAndFlush(currentRoom, currentCall)
                                    if (callId == currentCall) {
                                        _callState.value = CallState.CONNECTED
                                    }
                                }
                            },
                            onError = { err ->
                                println("ERROR: [WebRTC] createAnswer failed: $err")
                                endCall()
                            }
                        )
                    },
                    onError = { err ->
                        println("ERROR: [WebRTC] setRemoteDescription failed: $err")
                        endCall()
                    }
                )
            } else {
                println("ERROR: [CallMgr] answerCall called with no offer SDP! createAnswer will fail.")
                webRtcClient.startLocalMedia(isVideo)
                webRtcClient.createAnswer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            sendAnswer?.invoke(currentRoom, currentCall, sessionDescription.sdp)
                            allowLocalCandidatesAndFlush(currentRoom, currentCall)
                            if (callId == currentCall) {
                                _callState.value = CallState.CONNECTED
                            }
                        }
                    },
                    onError = { err ->
                        println("ERROR: [WebRTC] createAnswer failed: $err")
                        endCall()
                    }
                )
            }
        }
    }

    fun answerCallInvite(roomId: String, incomingCallId: String, sdp: String, isVideo: Boolean = sdpHasVideo(sdp)) {
        if (_callState.value != CallState.IDLE && callId != incomingCallId) return

        beginCall()
        activeRoomId = roomId
        callId = incomingCallId
        isIncomingCall.value = true
        isGroupCall.value = false
        webRtcClient.isGroupCall = false
        answerCall(isVideo = isVideo, sdp = sdp)
    }

    fun handleCallAnswer(sdp: String) {
        println("DEBUG: [CallMgr] Received answer, setting remote description and going CONNECTED")
        webRtcClient.setRemoteDescription(
            SessionDescriptionModel("answer", sdp),
            onSuccess = {
                flushRemoteCandidates()
                _callState.value = CallState.CONNECTED
            }
        )
    }

    fun handleCallNegotiate(roomId: String, incomingCallId: String, type: String, sdp: String) {
        println("DEBUG: [CallMgr] Received negotiate $type for callId=$incomingCallId")
        if (callId != incomingCallId || _callState.value == CallState.IDLE) return

        when (type) {
            "offer" -> {
                if (sdpHasVideo(sdp)) {
                    isVideoCall.value = true
                }
                // Call setRemoteDescription and createAnswer back-to-back so they
                // queue in order on the native WebRTC signaling thread.
                // Do NOT use callback chaining with scope.launch — that dispatches
                // createAnswer onto a different thread and introduces a timing gap
                // where the signaling state can change (causing INVALID_STATE).
                webRtcClient.setRemoteDescription(
                    SessionDescriptionModel("offer", sdp),
                    onSuccess = {
                        flushRemoteCandidates()
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to set remote description for negotiate: $it")
                    }
                )
                webRtcClient.createAnswer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            sendNegotiate?.invoke(roomId, incomingCallId, "answer", sessionDescription.sdp)
                        }
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to answer negotiate offer: $it")
                    }
                )
            }
            "answer" -> {
                webRtcClient.setRemoteDescription(
                    SessionDescriptionModel("answer", sdp),
                    onSuccess = {
                        flushRemoteCandidates()
                        _callState.value = CallState.CONNECTED
                    }
                )
            }
        }
    }

    fun handleIceCandidate(candidate: IceCandidateModel) {
        // Hand off inside the lock rather than returning a "should I buffer?"
        // decision and doing the add outside it -- otherwise a candidate could
        // be queued and then drained into a peerConnection that a concurrent
        // endCall had already torn down.
        val queued = synchronized(candidateLock) {
            if (!remoteDescriptionSet) {
                pendingRemoteCandidates.add(candidate)
                true
            } else {
                false
            }
        }
        if (queued) {
            println("DEBUG: [WebRTC] Queued remote ICE candidate until remote description is set")
        } else {
            webRtcClient.addIceCandidate(candidate)
        }
    }

    private fun flushRemoteCandidates() {
        val queued = synchronized(candidateLock) {
            remoteDescriptionSet = true
            // toList() under the lock, so the copy cannot observe a concurrent
            // structural modification of the backing array.
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        if (queued.isNotEmpty()) {
            println("DEBUG: [WebRTC] Flushing ${queued.size} queued remote ICE candidates")
            queued.forEach { webRtcClient.addIceCandidate(it) }
        }
    }

    fun setDuckingStrength(strength: Float) {
        duckingStrength.value = strength
        webRtcClient.setAttenuationStrength(strength)
    }

    fun setAudioOptions(ns: Boolean, ec: Boolean, agc: Boolean) {
        noiseSuppression.value = ns
        echoCancellation.value = ec
        autoGainControl.value = agc
        webRtcClient.setAudioOptions(ns, ec, agc)
        // Group path: same toggles drive the native APM chain (live via applyConfig)
        livekit.setLiveKitAudioProcessingOptions(ns, ec, agc)
    }

    fun toggleVirtualBackground() {
        isVirtualBackgroundEnabled.value = !isVirtualBackgroundEnabled.value
        webRtcClient.isVirtualBackgroundEnabled = isVirtualBackgroundEnabled.value
        // Group path picks the flag up at next camera open (same restart semantics
        // as the P2P engine — applied on video restart, not mid-stream).
        livekit.setLiveKitVirtualBackground(isVirtualBackgroundEnabled.value)
        // Only the P2P engine needs an explicit camera bounce to pick the flag up;
        // in group calls the LiveKit publisher handles it and bouncing here would
        // tear down the shared camera track for everyone.
        val wasVideoEnabled = isLocalVideoEnabled.value
        if (wasVideoEnabled && !isGroupCall.value) {
            webRtcClient.toggleVideo(false)
            webRtcClient.toggleVideo(true)
        }
    }

    fun endCall(notifyRemote: Boolean = true) {
        val currentRoom = activeRoomId
        val currentCall = callId
        val wasGroupCall = isGroupCall.value
        // Clear the view flag with the call, so the next one starts on the call
        // surface rather than inheriting a "show me the room instead" left over
        // from the previous session.
        _callSurfaceCollapsed.value = false
        // Snapshot the generation so the deferred group teardown below can tell
        // "still tearing down call N" from "the user already started call N+1".
        val teardownGeneration = callGeneration.get()

        // Random-close attribution: endCall has many invocation sites (buttons,
        // watchdog, hangup events) and none logged who called, which left a
        // reported "call randomly closes" undiagnosable from logs alone.
        val callerChain = Throwable().stackTrace
            .drop(1)
            .take(5)
            .joinToString(" <- ") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
        println(
            "INFO [CallManager] endCall invoked room=$currentRoom callId=$currentCall " +
                "group=$wasGroupCall notifyRemote=$notifyRemote by $callerChain"
        )

        ringTimeoutJob?.cancel()
        groupWatchdogJob?.cancel()
        groupWatchdogJob = null
        groupConnectionTracker.reset()
        _callState.value = CallState.ENDED
        livekit.setLiveKitReconnectedListener(null)
        if (wasGroupCall) {
            // Stop the E2EE media-key exchange before tearing media down.
            try {
                mediaKeysStop?.invoke()
            } catch (e: Exception) {
                println("WARN [CallManager] media key exchange stop failed: ${e.message}")
            }
            // H2 teardown order: unpublish media (peers stop receiving us
            // immediately) → membership leave (bounded wait) → close engine →
            // release devices/peer connection.
            webRtcClient.stopLocalMedia()
            currentRoom?.let { room -> onGroupCallObserversStop?.invoke(room) }
            if (notifyRemote && currentRoom != null && currentCall != null) {
                val room = currentRoom
                val call = currentCall
                scope.launch {
                    // Bound BOTH the wait and the work.
                    //
                    // `withTimeoutOrNull { leave.join() }` bounded only the WAIT: it
                    // did not cancel the child, so a hung Matrix PUT left `leave`
                    // running as a child of this coroutine. Since the coroutine
                    // never completed, the job and its HTTP connection stayed alive
                    // until process exit -- one leaked request per hangup where the
                    // homeserver accepted the connection and went quiet.
                    //
                    // `leave.cancelAndJoin()` inside the timeout is the pair that
                    // actually ends it. If the PUT is genuinely wedged rather than
                    // slow, the timeout returns and the last line logs it, so the
                    // leak is bounded and visible instead of silent.
                    val leave = launch { onLeaveGroupCall?.invoke(room, call) }
                    val settled = withTimeoutOrNull(MEMBERSHIP_LEAVE_TIMEOUT_MS) {
                        leave.join()
                        true
                    } ?: false
                    if (!settled) {
                        leave.cancelAndJoin()
                        println(
                            "WARN [CallManager] membership leave for $room did not settle within " +
                                "${MEMBERSHIP_LEAVE_TIMEOUT_MS}ms; cancelled"
                        )
                    }
                    // The membership-leave hop can burn the full 1.5s budget. If a
                    // newer call started meanwhile it already built its own
                    // PeerConnection/session, and closing here would tear THAT
                    // down and null it out from under the new call.
                    if (callGeneration.get() != teardownGeneration) {
                        println(
                            "WARN [CallManager] endCall teardown superseded by a newer call " +
                                "(generation $teardownGeneration -> ${callGeneration.get()}); skipping peer teardown"
                        )
                        return@launch
                    }
                    teardownNativeMedia()
                }
            } else {
                teardownNativeMedia()
            }
        } else {
            webRtcClient.stopLocalMedia()
            scope.launch { webRtcClient.endCall() }
            if (notifyRemote && currentRoom != null && currentCall != null) {
                scope.launch { sendHangup?.invoke(currentRoom, currentCall) }
            }
        }

        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isIncomingCall.value = false
        isGroupCall.value = false
        // The WebRtcClient is session-scoped, so its copy of the flag has to be
        // cleared here too; otherwise every later 1:1 call silently runs with
        // no local media.
        webRtcClient.isGroupCall = false
        if (isDeafened.value) {
            webRtcClient.setPlaybackMuted(false)
            livekit.setLiveKitPlaybackMuted(false)
        }
        isDeafened.value = false
        _participantGains.value.forEach { (identity, _) ->
            runCatching { livekit.setLiveKitParticipantGain(identity, 1f) }
        }
        _participantGains.value = emptyMap()
        _activeSpeakers.value = emptySet()
        // Per-call identity→profile cache. Identities are per-session, so
        // keeping them would hand the NEXT call's tiles a stranger's avatar
        // whenever the new peer reused a device id.
        _participantProfiles.value = emptyMap()
        _selfNudgeRecovery.value = false
        _raisedHands.value = emptySet()
        isMyHandRaised.value = false
        pttPreHoldAudioEnabled = null
        isMinimized.value = false
        _isScreenSharing.value = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        isLocalAudioEnabled.value = true
        candidatesAllowedForCallId = null
        resetCandidateBuffers()
        pendingInviteSdp = null
        hasRemoteStream.value = false
        nativeMediaReadiness.value = null
        groupCallLivekitIdentity = null
    }

    /**
     * Tears down the native media session off the caller's thread.
     *
     * `endCall` runs on `rememberCoroutineScope()`, which is the Compose/EDT
     * dispatcher, and both of these block: disconnectFromLiveKit drives the
     * native teardown (player job join, device line close, factory dispose --
     * six-plus JNI calls, the last tearing down WebRTC worker threads), and
     * webRtcClient.endCall() does the same for the P2P engine. Leaving that
     * synchronous meant every hangup and every failed join froze the window,
     * on the same thread the H12 "Joining… hangs" and H13 zombie-JVM write-ups
     * both care about.
     *
     * The `Dispatchers.IO` is load-bearing and was previously missing. `launch`
     * with no dispatcher INHERITS its context, and this `scope` is
     * `rememberCoroutineScope()` -- whose dispatcher on Compose Desktop is the
     * AWT event thread. So the comment above described the opposite of what the
     * code did. Worse, the teardown holds `g_sessionMutex` while it joins the
     * mixer thread, which is itself contending for that same mutex ~200x/sec
     * (see orbit_livekit_audio.cpp:1231), so the freeze could last as long as
     * the hold rather than being momentary.
     *
     * `scope` is still the right parent: the work is cancelled with the
     * composition, which is what we want. Only the thread is wrong.
     */
    private fun teardownNativeMedia() {
        scope.launch(Dispatchers.IO) {
            runCatching { livekit.disconnectFromLiveKit(userInitiated = true) }
                .onFailure { println("WARN [CallManager] native disconnect during teardown failed: ${it.message}") }
            runCatching { webRtcClient.endCall() }
                .onFailure { println("WARN [CallManager] P2P endCall during teardown failed: ${it.message}") }
        }
    }

    /**
     * Releases the native media stack for good: the process-global LiveKit
     * session object as well as the P2P engine.
     *
     * Called when this manager is unmounted (logout, or the account changing).
     * `endCall()` alone was not enough -- it tears down the current call, but
     * the underlying native resources were only reclaimed by `endCall`, which
     * nothing invoked on the logout path, so the OS recording indicator stayed
     * on and audio kept reaching the peer.
     */
    fun release() {
        if (released) return
        released = true
        scope.launch {
            runCatching { livekit.releaseNativeLiveKitSession() }
                .onFailure { println("WARN [CallManager] native session release failed: ${it.message}") }
            runCatching { webRtcClient.endCall() }
                .onFailure { println("WARN [CallManager] P2P endCall during release failed: ${it.message}") }
        }
    }

    private var released = false

    fun toggleMute(isMuted: Boolean) {
        isLocalAudioEnabled.value = !isMuted
        webRtcClient.toggleMute(isMuted)
    }

    // Push-to-talk (Voice & Video settings): while enabled and in a call, the
    // Ctrl+Shift+Space combo momentarily opens the mic and restores the prior
    // mute state on release. Repeats while the key auto-repeats are ignored.
    var pushToTalkMode: Boolean = false
    private var pttPreHoldAudioEnabled: Boolean? = null

    fun setPushToTalkActive(active: Boolean) {
        if (!pushToTalkMode || _callState.value == CallState.IDLE) return
        if (active) {
            if (pttPreHoldAudioEnabled != null) return
            pttPreHoldAudioEnabled = isLocalAudioEnabled.value
            if (!isLocalAudioEnabled.value) toggleMute(true)
        } else {
            val restore = pttPreHoldAudioEnabled ?: return
            pttPreHoldAudioEnabled = null
            if (!restore) toggleMute(false)
        }
    }

    fun toggleVideo(isVideoEnabled: Boolean) {
        val wasVideo = isVideoCall.value
        isVideoCall.value = isVideoCall.value || isVideoEnabled

        if (isGroupCall.value && _callState.value == CallState.CONNECTED) {
            // Group calls have no P2P sender; publish/unpublish through the native
            // LiveKit session instead (joins are audio-only, camera is lazy).
            //
            // Dispatchers.IO: enableLiveKitCamera() enumerates capture devices,
            // builds a PeerConnectionFactory and starts the source, and this
            // scope is the Compose/EDT dispatcher. That is several hundred ms of
            // UI freeze on every camera toggle.
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                if (isVideoEnabled) {
                    val result = livekit.enableLiveKitCamera()
                    if (result.ok) {
                        isLocalVideoEnabled.value = true
                        val room = activeRoomId ?: return@launch
                        val cId = callId ?: return@launch
                        val membershipMode = livekit.LiveKitFeatureFlags.videoMembershipMode
                        val membershipModeAllowsVideo = membershipMode == "intent-video" ||
                            membershipMode == "capabilities-video" ||
                            membershipMode == "element-reference"
                        if (livekit.LiveKitFeatureFlags.videoUpdateMembershipAfterPublish && membershipModeAllowsVideo) {
                            runCatching {
                                onUpdateGroupCallMembershipCapabilities?.invoke(
                                    room,
                                    cId,
                                    true,
                                    true,
                                    groupCallLivekitIdentity
                                )
                            }.onFailure { println("WARN [CallManager] membership video re-affirm failed: ${it.message}") }
                        }
                    } else {
                        println("ERROR [CallManager] group camera enable failed: ${result.error?.code} ${result.error?.message}")
                        isLocalVideoEnabled.value = false
                    }
                } else {
                    livekit.disableLiveKitCamera()
                    isLocalVideoEnabled.value = false
                }
            }
            return
        }

        isLocalVideoEnabled.value = isVideoEnabled

        if (!isGroupCall.value && isVideoEnabled && !wasVideo && _callState.value == CallState.CONNECTED) {
            // Transition from voice to video requires re-negotiation ONLY for P2P
            scope.launch {
                webRtcClient.startLocalMedia(true)
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendRenegotiationOffer(room, cId, sessionDescription.sdp)
                        }
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to create offer for video upgrade: $it")
                    }
                )
            }
        } else {
            webRtcClient.toggleVideo(isVideoEnabled)
        }
    }

    fun switchCamera() {
        webRtcClient.switchCamera()
    }

    fun setAudioInputDevice(deviceName: String) {
        webRtcClient.setAudioInputDevice(deviceName)
        livekit.setLiveKitInputDevice(deviceName)
    }

    fun setAudioOutputDevice(deviceName: String) {
        webRtcClient.setAudioOutputDevice(deviceName)
        livekit.setLiveKitOutputDevice(deviceName)
    }

    fun setVideoCaptureDevice(deviceName: String) {
        webRtcClient.setVideoCaptureDevice(deviceName)
    }

    // Voice & Video settings: mic gain / playback volume for the native group path
    // (1.0 = 100%). P2P levels stay managed by the WebRTC engine.
    fun setMicVolume(volume: Float) {
        livekit.setLiveKitInputVolume(volume)
    }

    fun setPlaybackVolume(volume: Float) {
        livekit.setLiveKitOutputVolume(volume)
    }

    // Soundboard: synthesized effects mixed into outgoing mic frames (group path);
    // on P2P they play locally and transmit only while system-audio share is on.
    fun playSoundEffect(effectId: String) {
        livekit.playSoundEffect(effectId)
    }

    fun stopSoundEffects() {
        livekit.stopSoundEffects()
    }

    fun setSoundEffectVolume(volume: Float) {
        livekit.setSoundEffectVolume(volume)
    }

    fun setMirrorVideo(enabled: Boolean) {
        webRtcClient.setMirrorVideo(enabled)
        livekit.LiveKitLocalVideoFrameStore.setMirrorPreview(enabled)
    }

    suspend fun getDesktopSources(): List<DesktopSourceModel> = withContext(Dispatchers.Default) {
        webRtcClient.getDesktopSources()
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        webRtcClient.setStreamAudioEnabled(enabled)
    }

    fun startScreenShare(data: Any? = null) {
        println("DEBUG [CallManager] startScreenShare requested data=$data group=${isGroupCall.value} state=${_callState.value}")
        scope.launch(Dispatchers.Default) {
            webRtcClient.startScreenShare(data)
            // Screen sharing often changes resolution/streams, trigger re-negotiation (P2P only)
            if (!isGroupCall.value && _callState.value == CallState.CONNECTED) {
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendRenegotiationOffer(room, cId, sessionDescription.sdp)
                        }
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to create offer for screen share: $it")
                    }
                )
            }
        }
    }

    fun stopScreenShare() {
        scope.launch(Dispatchers.Default) {
            webRtcClient.stopScreenShare()
            // Trigger re-negotiation to go back to camera (P2P only)
            if (!isGroupCall.value && _callState.value == CallState.CONNECTED) {
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendRenegotiationOffer(room, cId, sessionDescription.sdp)
                        }
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to create offer after stopping screen share: $it")
                    }
                )
            }
        }
    }

    private fun sdpHasVideo(sdp: String): Boolean {
        return sdp.split('\n').any { line ->
            line.startsWith("m=video") && !line.contains(" 0 ")
        }
    }

    private suspend fun loadIceServers() {
        val fetch = refreshIceServers ?: run {
            println("DEBUG: [WebRTC] ICE config has ${iceServers.size} server entries")
            return
        }

        runCatching { fetch() }
            .onSuccess { servers ->
                iceServers = servers
                println("DEBUG: [WebRTC] ICE config has ${servers.size} server entries")
            }
            .onFailure { error ->
                println("DEBUG: [WebRTC] Failed to refresh ICE servers: ${error.message}")
            }
    }

    private suspend fun allowLocalCandidatesAndFlush(roomId: String, cId: String) {
        candidatesAllowedForCallId = cId
        val queuedCandidates = drainLocalCandidates()

        if (queuedCandidates.isNotEmpty()) {
            println("DEBUG: [WebRTC] Flushing ${queuedCandidates.size} queued local ICE candidates")
        }

        queuedCandidates.forEach { candidate ->
            sendCandidate?.invoke(roomId, cId, candidate)
        }
    }

    private suspend fun sendRenegotiationOffer(roomId: String, cId: String, sdp: String) {
        val negotiate = sendNegotiate
        if (negotiate != null) {
            negotiate(roomId, cId, "offer", sdp)
        } else {
            sendInvite?.invoke(roomId, cId, sdp)
        }
    }
}


