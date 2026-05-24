package webrtc

import android.content.Context
import android.content.Intent
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

    private val _isScreenSharing = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    override val isScreenSharing: SharedFlow<Boolean> = _isScreenSharing

    private var rootEglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var localStream: MediaStream? = null
    private var videoCapturer: CameraVideoCapturer? = null
    
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSender: RtpSender? = null

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
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                _onStreamAdded.tryEmit(true)
                val track = receiver?.track()
                if (track is VideoTrack) {
                    track.addSink(remoteRenderer)
                }
            }
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
            val capturer = createVideoCapturer()
            videoCapturer = capturer as? CameraVideoCapturer
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
            capturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer?.startCapture(1280, 720, 30)

            cameraVideoTrack = factory.createVideoTrack("ARDMSV0", videoSource)
            cameraVideoTrack?.addSink(localRenderer)
            localVideoTrack = cameraVideoTrack
            localStream?.addTrack(localVideoTrack)
            videoSender = peerConnection?.addTrack(localVideoTrack, listOf("ARDMS"))
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

    override fun stopLocalMedia() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        videoCapturer = null
        cameraVideoTrack?.dispose()
        cameraVideoTrack = null
        localVideoTrack = null
        localAudioTrack = null
    }

    override fun startScreenShare(data: Any?) {
        val mediaProjectionIntent = data as? Intent ?: return
        val factory = peerConnectionFactory ?: return
        
        // Stop camera if running
        videoCapturer?.stopCapture()
        
        val videoSource = factory.createVideoSource(true)
        screenCapturer = ScreenCapturerAndroid(mediaProjectionIntent, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() {
                stopScreenShare()
            }
        })
        
        val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", rootEglBase?.eglBaseContext)
        screenCapturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
        screenCapturer?.startCapture(1280, 720, 15)
        
        val screenTrack = factory.createVideoTrack("SCREEN_TRACK_ID", videoSource)
        screenTrack.addSink(localRenderer)
        
        if (videoSender != null) {
            videoSender?.setTrack(screenTrack, true)
        } else {
            videoSender = peerConnection?.addTrack(screenTrack, listOf("ARDMS"))
        }
        
        localVideoTrack = screenTrack
        _isScreenSharing.tryEmit(true)
    }

    override fun stopScreenShare() {
        screenCapturer?.stopCapture()
        screenCapturer?.dispose()
        screenCapturer = null
        
        // Stop the foreground service
        context.stopService(Intent(context, com.noobdev.orbit.ScreenShareService::class.java))
        
        // Resume camera
        videoCapturer?.startCapture(1280, 720, 30)
        if (cameraVideoTrack != null && videoSender != null) {
            videoSender?.setTrack(cameraVideoTrack, true)
            localVideoTrack = cameraVideoTrack
        }
        
        _isScreenSharing.tryEmit(false)
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        localVideoTrack?.setEnabled(isVideoEnabled)
        if (!isVideoEnabled) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: Exception) {}
        } else {
            try {
                videoCapturer?.startCapture(1280, 720, 30)
            } catch (e: Exception) {}
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
        if (candidate.sdp.isBlank()) return
        val rtcCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        peerConnection?.addIceCandidate(rtcCandidate)
    }

    override fun endCall() {
        peerConnection?.close()
        peerConnection = null
        // Do NOT dispose peerConnectionFactory or rootEglBase here, as it destroys the WebRTC context
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
}

actual fun createWebRtcClient(): WebRtcClient = AndroidWebRtcClient(util.appContext)
