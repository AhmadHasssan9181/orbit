package webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import repository.TurnServer
import kotlin.math.abs

class JvmWebRtcClient : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription

    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: OrbitAudioDeviceModule? = null
    private var peerConnection: RTCPeerConnection? = null

    private var localAudioSource: dev.onvoid.webrtc.media.audio.AudioTrackSource? = null
    private var localAudioTrack: AudioTrack? = null
    
    private var attenuationStrength = 0.0f
    private var localAudioSender: RTCRtpSender? = null
    
    private var cameraVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    override var isVirtualBackgroundEnabled: Boolean = false
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteScreenVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var screenSource: VideoDesktopSource? = null
    private var screenVideoTrack: VideoTrack? = null
    override var isGroupCall: Boolean = false

    private var selectedAudioInput: String = "Default"
    private val _isScreenSharing = MutableStateFlow(false)
    override val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    private val _isRemoteScreenSharing = MutableStateFlow(false)
    override val isRemoteScreenSharing: StateFlow<Boolean> = _isRemoteScreenSharing

    private var selectedAudioOutput: String = "Default"
    private var selectedCamera: String = "Default"

    private var localRenderer: DesktopVideoRenderer? = DesktopVideoRenderer(mirror = true)
    private var remoteRenderer: DesktopVideoRenderer? = DesktopVideoRenderer()
    private var remoteScreenRenderer: DesktopVideoRenderer? = DesktopVideoRenderer()

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (peerConnection != null) return

            if (factory == null) {
                factory = createFactory()
            }

            applyAudioDevices()

            val rtcIceServers = iceServers.map { server ->
                RTCIceServer().apply {
                    urls = server.uris
                    username = server.username
                    password = server.password
                }
            }

            val rtcConfig = RTCConfiguration()
            rtcConfig.iceServers.addAll(if (rtcIceServers.isNotEmpty()) {
                rtcIceServers
            } else {
                listOf(RTCIceServer().apply { urls = listOf("stun:stun.l.google.com:19302") })
            })

            WindowsAudioDucker.duckVolumes(attenuationStrength)
        
            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    _onIceCandidate.tryEmit(
                        IceCandidateModel(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    )
                }

                override fun onAddStream(stream: MediaStream) {
                    _onStreamAdded.tryEmit(true)
                    stream.audioTracks.firstOrNull()?.let { attachRemoteAudioTrack(it) }
                    val sink = remoteRenderer as? VideoTrackSink
                    if (sink != null) {
                        stream.videoTracks.firstOrNull()?.addSink(sink)
                    }
                }

                override fun onSignalingChange(state: RTCSignalingState) {}
                override fun onConnectionChange(state: RTCPeerConnectionState) {}
                override fun onIceConnectionChange(state: RTCIceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: RTCIceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: RTCDataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {
                    runCatching {
                        attachRemoteTrack(receiver.track)
                    }.onFailure { e ->
                        println("DEBUG: [WebRTC] Failed to attach remote track via onAddTrack: ${e.message}")
                    }
                }
                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    runCatching {
                        attachRemoteTrack(transceiver.receiver.track)
                    }.onFailure { e ->
                        println("DEBUG: [WebRTC] Failed to attach remote track via onTrack: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun applyAudioDevices() {
        val adm = audioDeviceModule ?: return
        
        if (selectedAudioInput != "Default") {
            val devices = MediaDevices.getAudioCaptureDevices()
            devices.find { it.getName() == selectedAudioInput }?.let {
                runCatching { adm.setRecordingDevice(it) }
            }
        }
        
        if (selectedAudioOutput != "Default") {
            val devices = MediaDevices.getAudioRenderDevices()
            devices.find { it.getName() == selectedAudioOutput }?.let {
                runCatching { adm.setPlayoutDevice(it) }
            }
        }
    }

    private var isLiveKitMediaStarted = false

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        try {
            if (isGroupCall) {
                if (isLiveKitMediaStarted) {
                    println("DEBUG: [WebRTC] LiveKit media already started, ignoring redundant startLocalMedia")
                    return
                }
                isLiveKitMediaStarted = true
                println("DEBUG: [WebRTC] Redirecting local media to LiveKit Data Plane")
                
                // 1. Start standard camera capture but don't add to peer connection
                startVideoCapture()
                
                // 2. Hook LiveKit sink into our camera track
                cameraVideoTrack?.addSink(livekit.LiveKitLocalTrack.sink)
                
                // 3. Start the LiveKit native source and publish
                livekit.LiveKitLocalTrack.startCapture(640, 480)
                livekit.LiveKitClient.publishVideoTrack(livekit.LiveKitLocalTrack.getSourceHandle())
                
                // 4. Start audio capture and publish
                livekit.LiveKitLocalAudioTrack.startCapture()
                livekit.LiveKitClient.publishAudioTrack(livekit.LiveKitLocalAudioTrack.getSourceHandle())
                
                println("DEBUG: [WebRTC] Group call local media started and published to LiveKit")
                return
            }
            
            val pConn = peerConnection ?: return
            val f = factory ?: return

            if (localAudioTrack == null) {
                val options = AudioOptions()
                options.noiseSuppression = currentNs
                options.echoCancellation = currentEc
                options.autoGainControl = currentAgc
                options.highpassFilter = currentNs
                val audioSource = f.createAudioSource(options)
                localAudioSource = audioSource
                val audioTrack = f.createAudioTrack("audioTrack", audioSource)
                audioTrack.setEnabled(true)
                localAudioTrack = audioTrack
                localAudioSender = pConn.addTrack(audioTrack, listOf("mediaStream"))
                println("DEBUG: [WebRTC] Desktop local audio track enabled=${audioTrack.isEnabled()} id=${audioTrack.getId()}")
            }

            if (isVideoEnabled) {
                startVideoCapture()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVideoCapture() {
        val f = factory ?: return
        // In group calls, peerConnection is null but we still need to capture video
        if (!isGroupCall && peerConnection == null) return

        if (localVideoSource == null || cameraVideoTrack == null) {
            val devices = MediaDevices.getVideoCaptureDevices()
            if (devices.isEmpty()) return

            val device = devices.find { it.name == selectedCamera } ?: devices.first()
            val capabilities = MediaDevices.getVideoCaptureCapabilities(device)
            val capability = chooseVideoCapability(capabilities)

            val videoSource = VideoDeviceSource().apply {
                setVideoCaptureDevice(device)
                if (capability != null) {
                    setVideoCaptureCapability(capability)
                }
            }

            runCatching {
                videoSource.start()
                isVideoCaptureRunning = true
                
                // Inject our custom video processor if enabled
                if (isVirtualBackgroundEnabled) {
                    val modelPath = extractModelToTempFile("selfie_segmentation.onnx")
                    if (modelPath != null) {
                        println("DEBUG: [WebRTC] Injecting video processor with model: $modelPath")
                        OrbitVideoEngine.injectVideoProcessor(videoSource, modelPath)
                    }
                }
            }.onFailure {
                runCatching { videoSource.dispose() }
                return
            }

            val videoTrack = f.createVideoTrack("videoTrack", videoSource)
            localVideoSource = videoSource
            cameraVideoTrack = videoTrack
            
            // If we are screen sharing, don't set this as localVideoTrack yet
            if (screenVideoTrack == null) {
                localVideoTrack = videoTrack
                localRenderer?.isScreenShare = false
                (localRenderer as? VideoTrackSink)?.let { sink ->
                    videoTrack.addSink(sink)
                }
                if (localVideoSender != null) {
                    localVideoSender?.replaceTrack(videoTrack)
                } else if (peerConnection != null) {
                    localVideoSender = peerConnection?.addTrack(videoTrack, listOf("mediaStream"))
                }
            }
        }

        cameraVideoTrack?.setEnabled(true)
        if (!isVideoCaptureRunning && localVideoSource != null) {
            runCatching {
                localVideoSource?.start()
                isVideoCaptureRunning = true
            }
        }
    }

    private fun createFactory(): PeerConnectionFactory {
        return runCatching {
            WebrtcNativeDepsLoader.load()
            WindowsAudioDucker.disableWindowsDucking()
            
            // JNI Injection hack removed because cross-ABI (vtable) mismatch causes 
            // EXCEPTION_ACCESS_VIOLATION during PeerConnectionFactory native init.
            
            val audioModule = OrbitAudioDeviceModule()
            audioDeviceModule = audioModule

            val captureDevice = runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()
            val renderDevice = runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()

            println(
                "DEBUG: [WebRTC] Desktop audio devices capture=${captureDevice?.getName() ?: "default"} " +
                    "render=${renderDevice?.getName() ?: "default"}"
            )
            PeerConnectionFactory(audioModule)
        }.getOrElse { error ->
            println("DEBUG: [WebRTC] Falling back to default desktop audio module: ${error.message}")
            runCatching { audioDeviceModule?.dispose() }
            audioDeviceModule = null
            PeerConnectionFactory()
        }
    }

    private val activeRemoteVideoTracks = mutableSetOf<String>()

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        when (track) {
            is AudioTrack -> attachRemoteAudioTrack(track)
            is VideoTrack -> {
                if (activeRemoteVideoTracks.contains(track.getId())) {
                    println("DEBUG: [WebRTC] Ignoring duplicate attachRemoteTrack for VideoTrack ID: ${track.getId()}")
                    return
                }
                activeRemoteVideoTracks.add(track.getId())

                println("DEBUG: [WebRTC] Attaching VideoTrack sink! track ID: ${track.getId()}")
                _onStreamAdded.tryEmit(true)
                
                // Element and other Matrix clients often don't label screen share track IDs with "screen".
                // If we already have a remote camera track, assume any subsequent video track is a screen share.
                val isScreen = track.getId().contains("screen", ignoreCase = true) || 
                               track.getId().contains("desktop", ignoreCase = true) ||
                               remoteVideoTrack != null
                
                if (isScreen) {
                    remoteScreenVideoTrack = track
                    _isRemoteScreenSharing.value = true
                    remoteScreenRenderer?.let {
                        it.isScreenShare = true
                        track.addSink(it)
                        println("DEBUG: [WebRTC] Sink successfully added to Remote ScreenShare VideoTrack. ID: ${track.getId()}")
                    }
                } else {
                    remoteVideoTrack = track
                    remoteRenderer?.let {
                        it.isScreenShare = false
                        track.addSink(it)
                        println("DEBUG: [WebRTC] Sink successfully added to Remote Camera VideoTrack. ID: ${track.getId()}")
                    }
                }
            }
        }
    }

    private fun attachRemoteAudioTrack(track: AudioTrack) {
        track.setEnabled(true)
        remoteAudioTrack = track
        _onStreamAdded.tryEmit(true)
    }

    private fun chooseVideoCapability(capabilities: List<VideoCaptureCapability>): VideoCaptureCapability? {
        if (capabilities.isEmpty()) return null
        return capabilities.minByOrNull { capability ->
            abs(capability.width - 640) + abs(capability.height - 480) + abs(capability.frameRate - 30) * 20
        }
    }

    private fun stopVideoCapture(disposeTrack: Boolean) {
        cameraVideoTrack?.setEnabled(false)

        if (isVideoCaptureRunning) {
            runCatching { localVideoSource?.stop() }
            isVideoCaptureRunning = false
        }

        if (disposeTrack) {
            // Only remove from sender if screen share is NOT active
            if (screenVideoTrack == null) {
                localVideoSender?.let { sender ->
                    runCatching { peerConnection?.removeTrack(sender) }
                }
                localVideoSender = null
            }
            
            (localRenderer as? VideoTrackSink)?.let { sink ->
                runCatching { cameraVideoTrack?.removeSink(sink) }
            }
            runCatching { cameraVideoTrack?.dispose() }
            cameraVideoTrack = null
            
            if (screenVideoTrack == null) {
                localVideoTrack = null
            }
            
            runCatching { localVideoSource?.dispose() }
            localVideoSource = null
        }
    }

    override fun stopLocalMedia() {
        stopVideoCapture(disposeTrack = true)
        stopScreenShare()

        localAudioSender?.let { sender ->
            runCatching { peerConnection?.removeTrack(sender) }
        }
        localAudioSender = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        localAudioSource = null
    }

    override fun startScreenShare(data: Any?) {
        val sourceId = data as? String
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return

            val screenCapturer = ScreenCapturer()
            val windowCapturer = dev.onvoid.webrtc.media.video.desktop.WindowCapturer()
            
            val screenSources = screenCapturer.desktopSources
            val windowSources = windowCapturer.desktopSources
            
            val sSource = screenSources.find { it.id.toString() == sourceId }
            val wSource = windowSources.find { it.id.toString() == sourceId }
            
            val isWindow = wSource != null
            val finalSourceId = wSource?.id ?: sSource?.id ?: screenSources.firstOrNull()?.id
            
            if (finalSourceId != null) {
                // If already screen sharing, stop old one
                if (screenVideoTrack != null) {
                    stopScreenShare()
                }

                screenSource = VideoDesktopSource()
                screenSource?.setSourceId(finalSourceId, isWindow)
                screenSource?.start()
                
                val screenTrack = f.createVideoTrack("screenTrack_${System.currentTimeMillis()}", screenSource)
                screenTrack.setEnabled(true)
                
                if (localVideoSender != null) {
                    localVideoSender?.replaceTrack(screenTrack)
                } else {
                    localVideoSender = pConn.addTrack(screenTrack, listOf("mediaStream"))
                }
                
                screenVideoTrack = screenTrack
                localVideoTrack = screenTrack
                localRenderer?.isScreenShare = true
                (localRenderer as? VideoTrackSink)?.let { sink ->
                    screenTrack.addSink(sink)
                }
                
                _isScreenSharing.tryEmit(true)
            }
            
            screenCapturer.dispose()
            windowCapturer.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stopScreenShare() {
        try {
            val pConn = peerConnection ?: return
            
            (localRenderer as? VideoTrackSink)?.let { sink ->
                runCatching { screenVideoTrack?.removeSink(sink) }
            }
            
            screenSource?.stop()
            screenSource?.dispose()
            screenSource = null
            
            runCatching { screenVideoTrack?.dispose() }
            screenVideoTrack = null
            
            // Resume camera if it was enabled
            if (cameraVideoTrack != null) {
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
                localRenderer?.isScreenShare = false
                (localRenderer as? VideoTrackSink)?.let { sink ->
                    cameraVideoTrack?.addSink(sink)
                }
            } else {
                localVideoSender?.let { sender ->
                    runCatching { pConn.removeTrack(sender) }
                }
                localVideoSender = null
                localVideoTrack = null
            }
            
            // Stop sharing system audio
            runCatching { audioDeviceModule?.setSystemAudioShareEnabled(false) }
        
            WindowsAudioDucker.unduckVolumes()
        
            _isScreenSharing.tryEmit(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        if (cameraVideoTrack == null && isVideoEnabled) {
            startVideoCapture()
            // If screen share is active, startVideoCapture won't swap it in. 
            // We should only swap if screen share is NOT active.
            if (screenVideoTrack == null && cameraVideoTrack != null) {
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
            }
            return
        }
        if (isVideoEnabled) {
            startVideoCapture()
            if (screenVideoTrack == null) {
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
            }
        } else {
            stopVideoCapture(disposeTrack = false)
        }
    }

    override fun switchCamera() {}

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createOffer(RTCOfferOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) { onError(error) }
                    })
                }
                override fun onFailure(error: String) { onError(error) }
            })
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createAnswer(RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) { onError(error) }
                    })
                }
                override fun onFailure(error: String) { onError(error) }
            })
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel, onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            val type = RTCSdpType.valueOf(sessionDescription.type.uppercase())
            val sdp = RTCSessionDescription(type, sessionDescription.sdp)
            val observer = object : SetSessionDescriptionObserver {
                override fun onSuccess() { onSuccess() }
                override fun onFailure(error: String) { onError(error) }
            }
            if (peerConnection != null) {
                peerConnection?.setRemoteDescription(sdp, observer)
            } else {
                onError("PeerConnection is null")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError(e.message ?: "Unknown error")
        }
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        if (candidate.sdp.isBlank()) return
        try {
            val rtcCandidate = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            peerConnection?.addIceCandidate(rtcCandidate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun endCall() {
        try {
            stopLocalMedia()
            isLiveKitMediaStarted = false // Reset LiveKit media guard
            
            // Explicitly remove sinks to prevent native threads from calling back during shutdown
            localVideoTrack?.removeSink(localRenderer)
            cameraVideoTrack?.removeSink(localRenderer)
            screenVideoTrack?.removeSink(localRenderer)
            remoteVideoTrack?.removeSink(remoteRenderer)
            remoteScreenVideoTrack?.removeSink(remoteScreenRenderer)

            peerConnection?.close()
            peerConnection = null
            screenSource?.stop()
            screenSource = null
            screenVideoTrack = null
            localVideoTrack = null
            cameraVideoTrack = null
            localAudioTrack = null
            remoteVideoTrack = null
            remoteScreenVideoTrack = null
            _isRemoteScreenSharing.value = false
            remoteAudioTrack = null
            localVideoSender = null
            
            WindowsAudioDucker.unduckVolumes()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setOrbitZeroEnabled(enabled: Boolean) {
        audioDeviceModule?.setOrbitZeroEnabled(enabled)
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
    override fun getRemoteScreenRenderer(): Any? = if (remoteScreenVideoTrack != null) remoteScreenRenderer else null

    override fun setStreamAudioEnabled(enabled: Boolean) {
        audioDeviceModule?.setSystemAudioShareEnabled(enabled)
        recreateLocalAudioTrack(enabled)
    }
    
    private var currentNs = true
    private var currentEc = true
    private var currentAgc = true

    override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        currentNs = noiseSuppression
        currentEc = echoCancellation
        currentAgc = autoGainControl
        
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return
            
            val options = dev.onvoid.webrtc.media.audio.AudioOptions()
            options.noiseSuppression = noiseSuppression
            options.echoCancellation = echoCancellation
            options.autoGainControl = autoGainControl
            options.highpassFilter = noiseSuppression
            
            val newAudioSource = f.createAudioSource(options)
            val newAudioTrack = f.createAudioTrack("audioTrack_", newAudioSource)
            newAudioTrack.setEnabled(true)
            
            if (localAudioSender != null) {
                localAudioSender?.replaceTrack(newAudioTrack)
            } else {
                localAudioSender = pConn.addTrack(newAudioTrack, listOf("mediaStream"))
            }
            
            // Clean up old track AND source
            kotlin.runCatching { localAudioTrack?.dispose() }
            
            localAudioSource = newAudioSource
            localAudioTrack = newAudioTrack
            
            println("DEBUG: [WebRTC] AudioOptions updated. NS=, EC=")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun setAttenuationStrength(strength: Float) {
        attenuationStrength = strength
        if (peerConnection != null) {
            WindowsAudioDucker.duckVolumes(strength)
        }
    }

    override fun setRemoteVolume(volume: Float) {
        remoteAudioTrack?.setVolume(volume.toDouble())
    }

    override fun setAudioInputDevice(deviceName: String) {
        selectedAudioInput = deviceName
        applyAudioDevices()
    }

    override fun setAudioOutputDevice(deviceName: String) {
        selectedAudioOutput = deviceName
        applyAudioDevices()
    }

    override fun setVideoCaptureDevice(deviceName: String) {
        selectedCamera = deviceName
        // If camera is already running, we need to restart it
        if (cameraVideoTrack != null) {
            stopVideoCapture(disposeTrack = true)
            startVideoCapture()
        }
    }

    override fun getDesktopSources(): List<DesktopSourceModel> {
        val screenCapturer = ScreenCapturer()
        val sources = screenCapturer.desktopSources.map { 
            val title = it.title ?: "Screen ${it.id}"
            DesktopSourceModel(it.id.toString(), title, title.contains("Window")) 
        }
        screenCapturer.dispose()
        
        val windowCapturer = dev.onvoid.webrtc.media.video.desktop.WindowCapturer()
        val windowSources = windowCapturer.desktopSources.map {
            val title = it.title ?: "Window ${it.id}"
            DesktopSourceModel(it.id.toString(), title, true)
        }
        windowCapturer.dispose()
        
        return sources + windowSources
    }

    private fun recreateLocalAudioTrack(enabled: Boolean) {
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return
            
            // To prevent muffled desktop audio, we disable NS, AGC, and Highpass filters
            // when streaming system audio. We re-enable them when stopping.
            val options = AudioOptions().apply {
                noiseSuppression = !enabled
                autoGainControl = !enabled
                highpassFilter = !enabled
                echoCancellation = true // Keep AEC to cancel acoustic echo
            }
            
            val newAudioSource = f.createAudioSource(options)
            val newAudioTrack = f.createAudioTrack("audioTrack_${System.currentTimeMillis()}", newAudioSource)
            newAudioTrack.setEnabled(true)
            
            if (localAudioSender != null) {
                localAudioSender?.replaceTrack(newAudioTrack)
            } else {
                localAudioSender = pConn.addTrack(newAudioTrack, listOf("mediaStream"))
            }
            
            // Clean up old track AND source
            kotlin.runCatching { localAudioTrack?.dispose() }
            
            localAudioSource = newAudioSource
            localAudioTrack = newAudioTrack
            
            println("DEBUG: [WebRTC] AudioOptions updated. NS=${!enabled}, AGC=${!enabled}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    override fun getAudioInputDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getAudioCaptureDevices().map { it.getName() }
        }.getOrDefault(listOf("Default"))
    }

    override fun getAudioOutputDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getAudioRenderDevices().map { it.getName() }
        }.getOrDefault(listOf("Default"))
    }

    override fun getVideoCaptureDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices().map { it.getName() }
        }.getOrDefault(listOf("Default"))
    }
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
