package webrtc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    var activeRoomId: String? = null
        private set
    var callId: String? = null
        private set
        
    var isMinimized = MutableStateFlow(false)
    var isIncomingCall = MutableStateFlow(false)
        private set
    val isVideoCall = MutableStateFlow(false)
    val isLocalVideoEnabled = MutableStateFlow(false)

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    // Callbacks to send Matrix events
    var refreshIceServers: (suspend () -> List<TurnServer>)? = null
    var sendInvite: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendAnswer: (suspend (roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendCandidate: (suspend (roomId: String, callId: String, candidate: IceCandidateModel) -> Unit)? = null
    var sendHangup: (suspend (roomId: String, callId: String) -> Unit)? = null

    private val pendingLocalCandidates = mutableListOf<IceCandidateModel>()
    private var candidatesAllowedForCallId: String? = null

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
    }

    fun startCall(roomId: String, isVideo: Boolean) {
        val newCallId = "call_${System.currentTimeMillis()}"
        activeRoomId = roomId
        callId = newCallId
        isIncomingCall.value = false
        isVideoCall.value = isVideo
        isLocalVideoEnabled.value = isVideo
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
            // This is a re-invite (e.g. upgrading to video or starting screen share)
            println("DEBUG: [WebRTC] Handling re-invite for $incomingCallId")
            scope.launch {
                webRtcClient.setRemoteDescription(SessionDescriptionModel("offer", sdp))
                webRtcClient.createAnswer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            sendAnswer?.invoke(roomId, incomingCallId, sessionDescription.sdp)
                        }
                    },
                    onError = {
                        println("ERROR: [WebRTC] Failed to create answer for re-invite: $it")
                    }
                )
            }
            return
        }
        
        if (_callState.value != CallState.IDLE) return // Already in a different call
        
        this.activeRoomId = roomId
        this.callId = incomingCallId
        isIncomingCall.value = true
        isVideoCall.value = sdpHasVideo(sdp)
        isLocalVideoEnabled.value = false
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
        _callState.value = CallState.RINGING

        scope.launch {
            loadIceServers()
            if (callId != incomingCallId) return@launch

            webRtcClient.initialize(iceServers)
            webRtcClient.setRemoteDescription(SessionDescriptionModel("offer", sdp))
        }
    }

    fun answerCall(isVideo: Boolean, sdp: String? = null) {
        val currentRoom = activeRoomId ?: return
        val currentCall = callId ?: return
        isVideoCall.value = isVideo
        isLocalVideoEnabled.value = isVideo
        _callState.value = CallState.CONNECTING

        scope.launch {
            loadIceServers()
            if (callId != currentCall) return@launch

            if (sdp != null) {
                webRtcClient.initialize(iceServers)
                webRtcClient.setRemoteDescription(SessionDescriptionModel("offer", sdp))
            }

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
                onError = {
                    endCall()
                }
            )
        }
    }

    fun answerCallInvite(roomId: String, incomingCallId: String, sdp: String, isVideo: Boolean = sdpHasVideo(sdp)) {
        if (_callState.value != CallState.IDLE && callId != incomingCallId) return

        activeRoomId = roomId
        callId = incomingCallId
        isIncomingCall.value = true
        answerCall(isVideo = isVideo, sdp = sdp)
    }

    fun handleCallAnswer(sdp: String) {
        webRtcClient.setRemoteDescription(SessionDescriptionModel("answer", sdp))
        _callState.value = CallState.CONNECTED
    }

    fun handleIceCandidate(candidate: IceCandidateModel) {
        webRtcClient.addIceCandidate(candidate)
    }

    fun endCall(notifyRemote: Boolean = true) {
        val currentRoom = activeRoomId
        val currentCall = callId

        _callState.value = CallState.ENDED
        webRtcClient.stopLocalMedia()
        webRtcClient.endCall()

        if (notifyRemote && currentRoom != null && currentCall != null) {
            scope.launch {
                sendHangup?.invoke(currentRoom, currentCall)
            }
        }

        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isIncomingCall.value = false
        _isScreenSharing.value = false
        isVideoCall.value = false
        isLocalVideoEnabled.value = false
        candidatesAllowedForCallId = null
        pendingLocalCandidates.clear()
    }

    fun toggleMute(isMuted: Boolean) {
        webRtcClient.toggleMute(isMuted)
    }

    fun toggleVideo(isVideoEnabled: Boolean) {
        val wasVideo = isVideoCall.value
        isVideoCall.value = isVideoCall.value || isVideoEnabled
        isLocalVideoEnabled.value = isVideoEnabled
        
        if (isVideoEnabled && !wasVideo && _callState.value == CallState.CONNECTED) {
            // Transition from voice to video requires re-negotiation
            scope.launch {
                webRtcClient.startLocalMedia(true)
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendInvite?.invoke(room, cId, sessionDescription.sdp)
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

    fun startScreenShare(data: Any? = null) {
        webRtcClient.startScreenShare(data)
        // Screen sharing often changes resolution/streams, trigger re-negotiation
        if (_callState.value == CallState.CONNECTED) {
            scope.launch {
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendInvite?.invoke(room, cId, sessionDescription.sdp)
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
        webRtcClient.stopScreenShare()
        // Trigger re-negotiation to go back to camera
        if (_callState.value == CallState.CONNECTED) {
            scope.launch {
                webRtcClient.createOffer(
                    onSuccess = { sessionDescription ->
                        scope.launch {
                            val room = activeRoomId ?: return@launch
                            val cId = callId ?: return@launch
                            sendInvite?.invoke(room, cId, sessionDescription.sdp)
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
}
