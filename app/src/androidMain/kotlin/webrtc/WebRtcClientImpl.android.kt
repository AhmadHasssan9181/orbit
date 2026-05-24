package webrtc

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.webrtc.*
import repository.TurnServer

class AndroidWebRtcClient(private val context: Context) : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription
    
    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private var rootEglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localStream: MediaStream? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isVideoCaptureRunning = false

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    override fun initialize(iceServers: List<TurnServer>) {
        if (peerConnectionFactory != null) return

        rootEglBase = EglBase.create()
        
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val factoryOptions = PeerConnectionFactory.Options()
        val videoEncoderFactory = DefaultVideoEncoderFactory(rootEglBase?.eglBaseContext, true, true)
        val videoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase?.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .setVideoEncoderFactory(videoEncoderFactory)
            .setVideoDecoderFactory(videoDecoderFactory)
            .createPeerConnectionFactory()

        val rtcIceServers = iceServers.map { server ->
            PeerConnection.IceServer.builder(server.uris)
                .setUsername(server.username)
                .setPassword(server.password)
                .createIceServer()
        }.toMutableList()

        if (rtcIceServers.isEmpty()) {
            rtcIceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                _onIceCandidate.tryEmit(IceCandidateModel(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream) {
                _onStreamAdded.tryEmit(true)
                if (stream.videoTracks.isNotEmpty()) {
                    stream.videoTracks[0].addSink(remoteRenderer)
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        })

        // Prepare renderers
        localRenderer = SurfaceViewRenderer(context).apply {
            init(rootEglBase?.eglBaseContext, null)
            setMirror(true)
        }
        remoteRenderer = SurfaceViewRenderer(context).apply {
            init(rootEglBase?.eglBaseContext, null)
        }
    }

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        val factory = peerConnectionFactory ?: return
        
        localStream = factory.createLocalMediaStream("ARDMS")
        
        // Audio
        val audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("ARDMSA0", audioSource)
        localStream?.addTrack(localAudioTrack)
        peerConnection?.addTrack(localAudioTrack, listOf("ARDMS"))

        // Video
        if (isVideoEnabled) {
            val videoSource = factory.createVideoSource(false)
            localVideoSource = videoSource
            val capturer = createVideoCapturer()
            videoCapturer = capturer as? CameraVideoCapturer
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
            capturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer?.startCapture(1280, 720, 30)
            isVideoCaptureRunning = capturer != null

            localVideoTrack = factory.createVideoTrack("ARDMSV0", videoSource)
            localVideoTrack?.addSink(localRenderer)
            localStream?.addTrack(localVideoTrack)
            peerConnection?.addTrack(localVideoTrack, listOf("ARDMS"))
        }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    private fun stopVideoCapture() {
        if (isVideoCaptureRunning) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {
                println("DEBUG: [WebRTC] stopCapture failed: ${e.message}")
            } finally {
                isVideoCaptureRunning = false
            }
        }
    }

    override fun stopLocalMedia() {
        stopVideoCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        localRenderer?.let { renderer -> localVideoTrack?.removeSink(renderer) }
        localVideoTrack?.dispose()
        localVideoSource?.dispose()
        localAudioTrack?.dispose()
        localVideoTrack = null
        localVideoSource = null
        localAudioTrack = null
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        localVideoTrack?.setEnabled(isVideoEnabled)
        if (!isVideoEnabled) {
            stopVideoCapture()
        } else {
            try {
                videoCapturer?.startCapture(1280, 720, 30)
                isVideoCaptureRunning = videoCapturer != null
            } catch (e: Exception) {
                println("DEBUG: [WebRTC] startCapture failed: ${e.message}")
            }
        }
    }

    override fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(this, sdp)
                onSuccess(SessionDescriptionModel(sdp.type.canonicalForm(), sdp.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { onError(p0 ?: "Unknown error") }
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Unknown error") }
        }, MediaConstraints())
    }

    override fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(this, sdp)
                onSuccess(SessionDescriptionModel(sdp.type.canonicalForm(), sdp.description))
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { onError(p0 ?: "Unknown error") }
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Unknown error") }
        }, MediaConstraints())
    }

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel) {
        val type = SessionDescription.Type.fromCanonicalForm(sessionDescription.type)
        val sdp = SessionDescription(type, sessionDescription.sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        val rtcCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        peerConnection?.addIceCandidate(rtcCandidate)
    }

    override fun endCall() {
        stopLocalMedia()
        peerConnection?.close()
        peerConnection = null
        // Do NOT dispose peerConnectionFactory or rootEglBase here, as it destroys the WebRTC context
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
}

actual fun createWebRtcClient(): WebRtcClient = AndroidWebRtcClient(util.appContext)
