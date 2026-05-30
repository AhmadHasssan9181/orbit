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

    private var rootEglBase: EglBase? = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var cameraVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var localStream: MediaStream? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var isVideoCaptureRunning = false
    
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var videoSender: RtpSender? = null
    override var isVirtualBackgroundEnabled: Boolean = false

    private var localRenderer: SurfaceViewRenderer? = SurfaceViewRenderer(context).apply {
        init(rootEglBase?.eglBaseContext, null)
        setMirror(true)
    }
    private var remoteRenderer: SurfaceViewRenderer? = SurfaceViewRenderer(context).apply {
        init(rootEglBase?.eglBaseContext, null)
    }

    override fun initialize(iceServers: List<TurnServer>) {
        if (peerConnection != null) return

        if (peerConnectionFactory == null) {
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
        }

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
                stream.audioTracks.firstOrNull()?.let { track ->
                    track.setEnabled(true)
                    remoteAudioTrack = track
                }
                if (stream.videoTracks.isNotEmpty()) {
                    remoteRenderer?.let { stream.videoTracks[0].addSink(it) }
                }
            }
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                when (track) {
                    is AudioTrack -> {
                        track.setEnabled(true)
                        remoteAudioTrack = track
                        _onStreamAdded.tryEmit(true)
                    }
                    is VideoTrack -> {
                        _onStreamAdded.tryEmit(true)
                        remoteRenderer?.let { track.addSink(it) }
                    }
                }
            }
        })
    }

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        val factory = peerConnectionFactory ?: return
        
        if (localStream == null) {
            localStream = factory.createLocalMediaStream("ARDMS")
        }
        
        // Audio
        if (localAudioTrack == null) {
            val audioSource = factory.createAudioSource(MediaConstraints())
            localAudioTrack = factory.createAudioTrack("ARDMSA0", audioSource)
            localAudioTrack?.setEnabled(true)
            localStream?.addTrack(localAudioTrack)
            peerConnection?.addTrack(localAudioTrack, listOf("ARDMS"))
        }

        // Video
        if (isVideoEnabled && cameraVideoTrack == null) {
            val videoSource = factory.createVideoSource(false)
            localVideoSource = videoSource
            val capturer = createVideoCapturer()
            videoCapturer = capturer as? CameraVideoCapturer
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase?.eglBaseContext)
            capturer?.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            capturer?.startCapture(1280, 720, 30)
            isVideoCaptureRunning = capturer != null

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
        cameraVideoTrack?.dispose()
        cameraVideoTrack = null
        localVideoTrack?.dispose()
        localVideoSource?.dispose()
        localAudioTrack?.dispose()
        localVideoTrack = null
        localVideoSource = null
        localAudioTrack = null
        localStream = null
    }

    override fun startScreenShare(data: Any?) {
        val mediaProjectionIntent = data as? Intent ?: return
        val factory = peerConnectionFactory ?: return
        
        // Stop camera if running
        stopVideoCapture()
        
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
        if (cameraVideoTrack != null) {
            try {
                videoCapturer?.startCapture(1280, 720, 30)
                isVideoCaptureRunning = true
            } catch (e: Exception) {}
            
            if (videoSender != null) {
                videoSender?.setTrack(cameraVideoTrack, true)
                localVideoTrack = cameraVideoTrack
            }
        }
        
        _isScreenSharing.tryEmit(false)
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        if (localVideoTrack == null && isVideoEnabled) {
            startLocalMedia(true)
            return
        }
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

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val type = SessionDescription.Type.fromCanonicalForm(sessionDescription.type)
        val sdp = SessionDescription(type, sessionDescription.sdp)
        val observer = object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { onSuccess() }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { onError(p0 ?: "Unknown error") }
        }
        if (peerConnection != null) {
            peerConnection?.setRemoteDescription(observer, sdp)
        } else {
            onError("PeerConnection is null")
        }
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        if (candidate.sdp.isBlank()) return
        val rtcCandidate = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        peerConnection?.addIceCandidate(rtcCandidate)
    }

    override fun endCall() {
        stopLocalMedia()
        peerConnection?.close()
        peerConnection = null
        remoteAudioTrack = null
        // Do NOT dispose peerConnectionFactory or rootEglBase here, as it destroys the WebRTC context
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer

    override fun setStreamAudioEnabled(enabled: Boolean) {
        // No-op for Android placeholder
    }
    
    override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        // No-op for Android placeholder
    }

    override fun getAudioInputDevices(): List<String> = listOf("Default Microphone")
    override fun getAudioOutputDevices(): List<String> = listOf("Default Speaker")
    override fun getVideoCaptureDevices(): List<String> = listOf("Front Camera", "Back Camera")

    override fun setAttenuationStrength(strength: Float) {
        // TODO: Implement audio ducking
    }

    override fun setRemoteVolume(volume: Float) {
        // TODO: Implement
    }

    override fun setAudioInputDevice(deviceName: String) {}
    override fun setAudioOutputDevice(deviceName: String) {}
    override fun setVideoCaptureDevice(deviceName: String) {}
    override fun getDesktopSources(): List<DesktopSourceModel> = emptyList()
}



actual fun createWebRtcClient(): WebRtcClient = AndroidWebRtcClient(util.appContext)

