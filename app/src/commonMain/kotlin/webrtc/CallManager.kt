package webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing
    val isRemoteScreenSharing: StateFlow<Boolean> = webRtcClient.isRemoteScreenSharing

    val duckingStrength = MutableStateFlow(0.8f)
    val noiseSuppression = MutableStateFlow(true)
    val echoCancellation = MutableStateFlow(true)
    val autoGainControl = MutableStateFlow(true)
    val isVirtualBackgroundEnabled = MutableStateFlow(false)

    // Callbacks to send Matrix events
    var refreshIceServers: (suspend () -> List<TurnServer>)? = null
    var sendInvite: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendAnswer: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendNegotiate: (suspend (roomId: String, callId: String, type: String, sdp: String) -> Unit)? = null
    var sendCandidate: (suspend (roomId: String, callId: String, candidate: IceCandidateModel) -> Unit)? = null
    var sendHangup: (suspend (roomId: String, callId: String) -> Unit)? = null
    var onLeaveGroupCall: (suspend (roomId: String, callId: String) -> Unit)? = null
    var onUpdateGroupCallMembershipCapabilities: (suspend (roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String?) -> Unit)? = null

    private val pendingLocalCandidates = mutableListOf<IceCandidateModel>()
    private var candidatesAllowedForCallId: String? = null
    private var pendingInviteSdp: String? = null  // Stores the offer SDP until the user answers

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
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        _callState.value = CallState.CONNECTING

        scope.launch {
            try {
                if (livekitUrl != null && livekitTokenFetcher != null) {
                    val mediaReady = connectNativeGroupMedia(roomId, callId, isVideo, livekitUrl, livekitTokenFetcher)
                    if (mediaReady) {
                        _callState.value = CallState.CONNECTED
                        println("DEBUG [CallManager] LiveKit call state set to CONNECTED after MediaReady")
                    } else {
                        failGroupJoin()
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
                failGroupJoin()
            } finally {
                isJoiningGroupCall = false
            }
        }
    }

    private fun failGroupJoin() {
        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isGroupCall.value = false
        webRtcClient.isGroupCall = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
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
            val authResponse = livekitTokenFetcher()
            println("DEBUG [CallManager] Token and SFU URL fetched. Publishing MatrixRTC membership before native media...")
            onUpdateGroupCallMembershipCapabilities?.invoke(roomId, callId, true, false, authResponse.identity)
            println("DEBUG [CallManager] MatrixRTC membership publish requested before native media. Connecting native media at ${authResponse.serverUrl}...")
            val readiness = livekit.connectAndPublishToLiveKit(
                authResponse = authResponse,
                isVideo = isVideo,
                iceServers = effectiveIceServers,
                refreshTokenFetcher = livekitTokenFetcher
            )

            if (!readiness.ok || readiness.value == null) {
                println("DEBUG [CallManager] Native media failed: ${readiness.error?.code} ${readiness.error?.message}")
                false
            } else {
                val value = readiness.value
                val ready = value.state == livekit.NativeMediaState.MediaReady &&
                    value.audioPublished &&
                    value.acceptingAudioFrames
                if (!ready) {
                    println("DEBUG [CallManager] Native media not ready after join: $value")
                } else {
                    if (!value.videoPublished) {
                        isLocalVideoEnabled.value = false
                    }
                    println("DEBUG [CallManager] publishing MatrixRTC membership after MediaReady audio=true video=${value.videoPublished}")
                    onUpdateGroupCallMembershipCapabilities?.invoke(roomId, callId, true, value.videoPublished, authResponse.identity)
                }
                ready
            }
        } catch (e: Exception) {
            println("DEBUG [CallManager] Failed to connect native LiveKit media: ${e.message}")
            e.printStackTrace()
            false
        }
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
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
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

    fun handleCallInvite(roomId: String, incomingCallId: String, sdp: String) {
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
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingInviteSdp = sdp  // Store SDP; actual init happens when user answers
        _callState.value = CallState.RINGING
        println("DEBUG: [CallMgr] Incoming invite from $roomId callId=$incomingCallId, isVideo=${sdpHasVideo(sdp)}")

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
                        _callState.value = CallState.CONNECTED
                    }
                )
            }
        }
    }

    fun handleIceCandidate(candidate: IceCandidateModel) {
        webRtcClient.addIceCandidate(candidate)
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
    }

    fun toggleVirtualBackground() {
        isVirtualBackgroundEnabled.value = !isVirtualBackgroundEnabled.value
        webRtcClient.isVirtualBackgroundEnabled = isVirtualBackgroundEnabled.value
        // WebRTC client will read this when video is re-enabled, or we can toggle video.
        val wasVideoEnabled = isLocalVideoEnabled.value
        if (wasVideoEnabled) {
            webRtcClient.toggleVideo(false)
            webRtcClient.toggleVideo(true)
        }
    }

    fun endCall(notifyRemote: Boolean = true) {
        val currentRoom = activeRoomId
        val currentCall = callId
        val wasGroupCall = isGroupCall.value

        _callState.value = CallState.ENDED
        if (wasGroupCall) {
            livekit.disconnectFromLiveKit(userInitiated = true)
        }
        webRtcClient.stopLocalMedia()
        webRtcClient.endCall()

        if (notifyRemote && currentRoom != null && currentCall != null) {
            scope.launch {
                if (wasGroupCall) {
                    onLeaveGroupCall?.invoke(currentRoom, currentCall)
                } else {
                    sendHangup?.invoke(currentRoom, currentCall)
                }
            }
        }

        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isIncomingCall.value = false
        isGroupCall.value = false
        _isScreenSharing.value = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        isLocalAudioEnabled.value = true
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        pendingInviteSdp = null
        hasRemoteStream.value = false
    }

    fun toggleMute(isMuted: Boolean) {
        isLocalAudioEnabled.value = !isMuted
        webRtcClient.toggleMute(isMuted)
    }

    fun toggleVideo(isVideoEnabled: Boolean) {
        val wasVideo = isVideoCall.value
        isVideoCall.value = isVideoCall.value || isVideoEnabled
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
    }

    fun setAudioOutputDevice(deviceName: String) {
        webRtcClient.setAudioOutputDevice(deviceName)
    }

    fun setVideoCaptureDevice(deviceName: String) {
        webRtcClient.setVideoCaptureDevice(deviceName)
    }

    suspend fun getDesktopSources(): List<DesktopSourceModel> = withContext(Dispatchers.Default) {
        webRtcClient.getDesktopSources()
    }

    fun setStreamAudioEnabled(enabled: Boolean) {
        webRtcClient.setStreamAudioEnabled(enabled)
    }

    fun startScreenShare(data: Any? = null) {
        scope.launch(Dispatchers.Default) {
            webRtcClient.startScreenShare(data)
            // Screen sharing often changes resolution/streams, trigger re-negotiation
            if (_callState.value == CallState.CONNECTED) {
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
            // Trigger re-negotiation to go back to camera
            if (_callState.value == CallState.CONNECTED) {
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


