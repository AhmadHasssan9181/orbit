package webrtc

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import dev.onvoid.webrtc.*
import repository.TurnServer

class JvmWebRtcClient : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription
    
    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var localRenderer: Any? = null
    private var remoteRenderer: Any? = null

    override fun initialize(iceServers: List<TurnServer>) {
        if (factory != null) return
        
        factory = PeerConnectionFactory()

        val rtcIceServers = iceServers.map { server ->
            val iceServer = RTCConfiguration().iceServers
            RTCIceServer().apply {
                urls = server.uris
                username = server.username
                password = server.password
            }
        }

        val rtcConfig = RTCConfiguration().apply {
            this.iceServers = if (rtcIceServers.isNotEmpty()) rtcIceServers else listOf(RTCIceServer().apply { urls = listOf("stun:stun.l.google.com:19302") })
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) {
                _onIceCandidate.tryEmit(IceCandidateModel(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }
            override fun onAddStream(stream: MediaStream) {
                _onStreamAdded.tryEmit(true)
            }
            override fun onSignalingChange(state: SignalingState) {}
            override fun onIceConnectionChange(state: IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dataChannel: RTCDataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {}
        })
    }

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        // Implementation for JVM video capture depends on platform specific APIs or libraries like OpenCV/VLCJ
        // dev.onvoid.webrtc has some support but it's complex for a simple stub.
    }

    override fun stopLocalMedia() {
    }

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        peerConnection?.createOffer(CreateSessionDescriptionObserver { sdp, error ->
            if (error != null) onError(error)
            else if (sdp != null) {
                peerConnection?.setLocalDescription(SetSessionDescriptionObserver { err ->
                    if (err != null) onError(err)
                    else onSuccess(SessionDescriptionModel(sdp.type.toString().lowercase(), sdp.sdp))
                }, sdp)
            }
        }, RTCOfferOptions())
    }

    override fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        peerConnection?.createAnswer(CreateSessionDescriptionObserver { sdp, error ->
            if (error != null) onError(error)
            else if (sdp != null) {
                peerConnection?.setLocalDescription(SetSessionDescriptionObserver { err ->
                    if (err != null) onError(err)
                    else onSuccess(SessionDescriptionModel(sdp.type.toString().lowercase(), sdp.sdp))
                }, sdp)
            }
        }, RTCAnswerOptions())
    }

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel) {
        val type = RTCSdpType.valueOf(sessionDescription.type.uppercase())
        val sdp = RTCSessionDescription(type, sessionDescription.sdp)
        peerConnection?.setRemoteDescription(SetSessionDescriptionObserver { }, sdp)
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        val rtcCandidate = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        peerConnection?.addIceCandidate(rtcCandidate)
    }

    override fun endCall() {
        peerConnection?.close()
        peerConnection = null
        factory?.dispose()
        factory = null
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
