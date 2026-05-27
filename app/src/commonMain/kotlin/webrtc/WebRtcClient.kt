package webrtc

import kotlinx.coroutines.flow.SharedFlow
import repository.TurnServer

data class IceCandidateModel(val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)
data class SessionDescriptionModel(val type: String, val sdp: String)

interface WebRtcClient {
    val onIceCandidate: SharedFlow<IceCandidateModel>
    val onRemoteDescription: SharedFlow<SessionDescriptionModel>
    val onStreamAdded: SharedFlow<Boolean>
    val isScreenSharing: SharedFlow<Boolean>
    
    fun initialize(iceServers: List<TurnServer> = emptyList())
    fun startLocalMedia(isVideoEnabled: Boolean)
    fun stopLocalMedia()
    fun startScreenShare(data: Any? = null) // data for Android MediaProjection intent
    fun stopScreenShare()
    fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit)
    fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit)
    fun setRemoteDescription(sessionDescription: SessionDescriptionModel)
    fun addIceCandidate(candidate: IceCandidateModel)
    fun endCall()
    
    fun getLocalVideoRenderer(): Any?
    fun getRemoteVideoRenderer(): Any?
    
    fun toggleMute(isMuted: Boolean)
    fun toggleVideo(isVideoEnabled: Boolean)
    fun switchCamera()
    fun setStreamAudioEnabled(enabled: Boolean)
    fun setOrbitZeroEnabled(enabled: Boolean)
}

expect fun createWebRtcClient(): WebRtcClient
