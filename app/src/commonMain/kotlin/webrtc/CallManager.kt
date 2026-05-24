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

    // Callbacks to send Matrix events
    var sendInvite: ((roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendAnswer: ((roomId: String, callId: String, sdp: String) -> Unit)? = null
    var sendCandidate: ((roomId: String, callId: String, candidate: IceCandidateModel) -> Unit)? = null
    var sendHangup: ((roomId: String, callId: String) -> Unit)? = null

    init {
        scope.launch {
            webRtcClient.onIceCandidate.collect { candidate ->
                activeRoomId?.let { room ->
                    callId?.let { cId ->
                        sendCandidate?.invoke(room, cId, candidate)
                    }
                }
            }
        }
    }

    fun startCall(roomId: String, isVideo: Boolean) {
        activeRoomId = roomId
        callId = "call_${System.currentTimeMillis()}"
        isIncomingCall.value = false
        _callState.value = CallState.CONNECTING
        
        webRtcClient.initialize(iceServers)
        webRtcClient.startLocalMedia(isVideo)
        
        webRtcClient.createOffer(
            onSuccess = { sessionDescription ->
                sendInvite?.invoke(roomId, callId!!, sessionDescription.sdp)
                _callState.value = CallState.RINGING // Waiting for answer
            },
            onError = {
                endCall()
            }
        )
    }

    fun handleCallInvite(roomId: String, incomingCallId: String, sdp: String) {
        if (_callState.value != CallState.IDLE) return // Already in a call
        
        this.activeRoomId = roomId
        this.callId = incomingCallId
        isIncomingCall.value = true
        _callState.value = CallState.RINGING
        
        webRtcClient.initialize(iceServers)
        webRtcClient.setRemoteDescription(SessionDescriptionModel("offer", sdp))
    }

    fun answerCall(isVideo: Boolean, sdp: String? = null) {
        _callState.value = CallState.CONNECTING
        
        if (sdp != null) {
            webRtcClient.initialize(iceServers)
            webRtcClient.setRemoteDescription(SessionDescriptionModel("offer", sdp))
        }
        
        webRtcClient.startLocalMedia(isVideo)
        webRtcClient.createAnswer(
            onSuccess = { sessionDescription ->
                activeRoomId?.let { room ->
                    callId?.let { cId ->
                        sendAnswer?.invoke(room, cId, sessionDescription.sdp)
                    }
                }
                _callState.value = CallState.CONNECTED
            },
            onError = {
                endCall()
            }
        )
    }

    fun handleCallAnswer(sdp: String) {
        webRtcClient.setRemoteDescription(SessionDescriptionModel("answer", sdp))
        _callState.value = CallState.CONNECTED
    }

    fun handleIceCandidate(candidate: IceCandidateModel) {
        webRtcClient.addIceCandidate(candidate)
    }

    fun endCall() {
        val currentRoom = activeRoomId
        val currentCall = callId

        _callState.value = CallState.ENDED
        webRtcClient.stopLocalMedia()
        webRtcClient.endCall()
        
        if (currentRoom != null && currentCall != null) {
            sendHangup?.invoke(currentRoom, currentCall)
        }
        
        _callState.value = CallState.IDLE
        activeRoomId = null
        callId = null
        isIncomingCall.value = false
    }

    fun toggleMute(isMuted: Boolean) {
        webRtcClient.toggleMute(isMuted)
    }

    fun toggleVideo(isVideoEnabled: Boolean) {
        webRtcClient.toggleVideo(isVideoEnabled)
    }

    fun switchCamera() {
        webRtcClient.switchCamera()
    }
}
