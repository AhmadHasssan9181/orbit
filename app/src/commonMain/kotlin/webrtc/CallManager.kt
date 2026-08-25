package webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.staticCompositionLocalOf
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

    // Group-call participant roster from MatrixRTC membership (covers voice-only members
    // that produce no video track). Wired via groupMembersProvider in WebRtcEffects.
    var groupMembersProvider: ((String) -> kotlinx.coroutines.flow.Flow<List<ui.model.OrbitUser>>)? = null
    private val _groupCallMembers = MutableStateFlow<List<ui.model.OrbitUser>>(emptyList())
    val groupCallMembers: StateFlow<List<ui.model.OrbitUser>> = _groupCallMembers

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

    // LiveKit identity of the current group-call join; reused for membership
    // re-affirmation when the camera is toggled mid-call.
    private var groupCallLivekitIdentity: String? = null

    private val pendingLocalCandidates = mutableListOf<IceCandidateModel>()
    // Remote ICE candidates can arrive before our setRemoteDescription completes
    // (peerConnection is null then and would silently drop them) — buffer until it's set.
    private val pendingRemoteCandidates = mutableListOf<IceCandidateModel>()
    private var remoteDescriptionSet = false
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
                    pendingLocalCandidates.add(candidate)
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

        isJoiningGroupCall = true
        activeRoomId = roomId
        this.callId = callId
        isIncomingCall.value = false
        isVideoCall.value = isVideo
        isGroupCall.value = true
        webRtcClient.isGroupCall = true
        isLocalVideoEnabled.value = isVideo
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
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
        activeRoomId = null
        callId = null
        isGroupCall.value = false
        webRtcClient.isGroupCall = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
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
            loadIceServers()
            var effectiveIceServers = iceServers
            if (effectiveIceServers.isEmpty()) {
                println("DEBUG [CallManager] No Matrix ICE servers found, injecting fallback STUN")
                effectiveIceServers = listOf(repository.TurnServer(listOf("stun:stun.l.google.com:19302"), null, null))
            }

            println("DEBUG [CallManager] Fetching LiveKit token for url: $livekitUrl")
            val authResponse = withTimeoutOrNull(TOKEN_FETCH_TIMEOUT_MS) { livekitTokenFetcher() }
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
            val membershipVisible = onUpdateGroupCallMembershipCapabilities
                ?.invoke(roomId, callId, true, false, authResponse.identity) ?: true
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
            val roomEncrypted = try {
                roomIsEncryptedChecker?.invoke(roomId) ?: false
            } catch (e: Exception) {
                println("WARN [CallManager] room encryption probe failed: ${e.message}")
                false
            }
            livekit.prepareNativeE2EE(roomEncrypted)
            println(
                "DEBUG [CallManager] room encrypted=$roomEncrypted → native E2EE media " +
                    if (roomEncrypted) "armed" else "disabled"
            )

            var readiness: livekit.NativeResult<livekit.NativeMediaReadiness>? = null
            var connectOk = false
            for (attempt in 1..GroupCallConnectionTracker.MAX_CONNECT_ATTEMPTS) {
                if (attempt > 1) {
                    groupConnectionTracker.noteRetryStarted()
                    println("WARN [CallManager] Retrying native LiveKit connect (attempt $attempt)")
                    livekit.disconnectFromLiveKit(userInitiated = false)
                    delay(RETRY_BACKOFF_MS)
                }
                readiness = connectOnceWithProgress(isVideo, effectiveIceServers, livekitTokenFetcher, authResponse)
                connectOk = readiness.ok && readiness.value != null &&
                    readiness.value.state == livekit.NativeMediaState.MediaReady &&
                    readiness.value.audioPublished &&
                    readiness.value.acceptingAudioFrames
                if (connectOk || groupConnectionTracker.connection.value.stage == GroupCallStage.Failed) break
            }
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
        activeRoomId = roomId
        callId = newCallId
        isIncomingCall.value = false
        isVideoCall.value = isVideo
        isGroupCall.value = false
        isLocalVideoEnabled.value = isVideo
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
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
        isLocalVideoEnabled.value = false
        isLocalAudioEnabled.value = true
        nativeMediaReadiness.value = null
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
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

        activeRoomId = roomId
        callId = incomingCallId
        isIncomingCall.value = true
        isGroupCall.value = false
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
        if (!remoteDescriptionSet) {
            pendingRemoteCandidates.add(candidate)
            println("DEBUG: [WebRTC] Queued remote ICE candidate until remote description is set")
        } else {
            webRtcClient.addIceCandidate(candidate)
        }
    }

    private fun flushRemoteCandidates() {
        remoteDescriptionSet = true
        val queued = pendingRemoteCandidates.toList()
        pendingRemoteCandidates.clear()
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
            if (notifyRemote && currentRoom != null && currentCall != null) {
                val room = currentRoom
                val call = currentCall
                scope.launch {
                    val leave = launch { onLeaveGroupCall?.invoke(room, call) }
                    withTimeoutOrNull(MEMBERSHIP_LEAVE_TIMEOUT_MS) { leave.join() }
                    livekit.disconnectFromLiveKit(userInitiated = true)
                    webRtcClient.endCall()
                }
            } else {
                livekit.disconnectFromLiveKit(userInitiated = true)
                webRtcClient.endCall()
            }
        } else {
            webRtcClient.stopLocalMedia()
            webRtcClient.endCall()
            if (notifyRemote && currentRoom != null && currentCall != null) {
                scope.launch { sendHangup?.invoke(currentRoom, currentCall) }
            }
        }

        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isIncomingCall.value = false
        isGroupCall.value = false
        if (isDeafened.value) {
            webRtcClient.setPlaybackMuted(false)
            livekit.setLiveKitPlaybackMuted(false)
        }
        isDeafened.value = false
        _participantGains.value.forEach { (identity, _) ->
            runCatching { livekit.setLiveKitParticipantGain(identity, 1f) }
        }
        _participantGains.value = emptyMap()
        isMinimized.value = false
        _isScreenSharing.value = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        isLocalAudioEnabled.value = true
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
        pendingInviteSdp = null
        hasRemoteStream.value = false
        nativeMediaReadiness.value = null
        groupCallLivekitIdentity = null
    }

    fun toggleMute(isMuted: Boolean) {
        isLocalAudioEnabled.value = !isMuted
        webRtcClient.toggleMute(isMuted)
    }

    fun toggleVideo(isVideoEnabled: Boolean) {
        val wasVideo = isVideoCall.value
        isVideoCall.value = isVideoCall.value || isVideoEnabled

        if (isGroupCall.value && _callState.value == CallState.CONNECTED) {
            // Group calls have no P2P sender; publish/unpublish through the native
            // LiveKit session instead (joins are audio-only, camera is lazy).
            scope.launch {
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
        val queuedCandidates = pendingLocalCandidates.toList()
        pendingLocalCandidates.clear()

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


