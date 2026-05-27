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
import repository.TurnServer
import kotlin.math.abs

class JvmWebRtcClient : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription

    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private val _isScreenSharing = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    override val isScreenSharing: SharedFlow<Boolean> = _isScreenSharing

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: OrbitAudioDeviceModule? = null
    private var peerConnection: RTCPeerConnection? = null

    private var localAudioSource: dev.onvoid.webrtc.media.audio.AudioTrackSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RTCRtpSender? = null
    
    private var cameraVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var screenSource: VideoDesktopSource? = null

    private var localRenderer: DesktopVideoRenderer? = DesktopVideoRenderer(mirror = true)
    private var remoteRenderer: DesktopVideoRenderer? = DesktopVideoRenderer()

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (peerConnection != null) return

            if (factory == null) {
                factory = createFactory()
            }

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

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return

            if (localAudioTrack == null) {
                val audioSource = f.createAudioSource(AudioOptions())
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
        val pConn = peerConnection ?: return
        val f = factory ?: return

        if (localVideoSource == null || cameraVideoTrack == null) {
            val devices = MediaDevices.getVideoCaptureDevices()
            if (devices.isEmpty()) return

            val device = devices.first()
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
                
                // Inject our custom video processor
                val modelPath = extractModelToTempFile("selfie_segmentation.onnx")
                if (modelPath != null) {
                    println("DEBUG: [WebRTC] Injecting video processor with model: $modelPath")
                    OrbitVideoEngine.injectVideoProcessor(videoSource, modelPath)
                }
            }.onFailure {
                runCatching { videoSource.dispose() }
                return
            }

            val videoTrack = f.createVideoTrack("videoTrack", videoSource)
            localVideoSource = videoSource
            cameraVideoTrack = videoTrack
            localVideoTrack = videoTrack
            (localRenderer as? VideoTrackSink)?.let { sink ->
                videoTrack.addSink(sink)
            }
            localVideoSender = pConn.addTrack(videoTrack, listOf("mediaStream"))
        }

        localVideoTrack?.setEnabled(true)
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
            val audioModule = OrbitAudioDeviceModule()
            
            // JNI Injection hack removed because cross-ABI (vtable) mismatch causes 
            // EXCEPTION_ACCESS_VIOLATION during PeerConnectionFactory native init.
            
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

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        when (track) {
            is AudioTrack -> attachRemoteAudioTrack(track)
            is VideoTrack -> {
                println("DEBUG: [WebRTC] Attaching VideoTrack sink! track ID: ${track.getId()}")
                _onStreamAdded.tryEmit(true)
                (remoteRenderer as? VideoTrackSink)?.let { sink ->
                    track.addSink(sink)
                    println("DEBUG: [WebRTC] Sink successfully added to VideoTrack.")
                }
                remoteVideoTrack = track
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
        localVideoTrack?.setEnabled(false)

        if (isVideoCaptureRunning) {
            runCatching { localVideoSource?.stop() }
            isVideoCaptureRunning = false
        }

        if (disposeTrack) {
            localVideoSender?.let { sender ->
                runCatching { peerConnection?.removeTrack(sender) }
            }
            localVideoSender = null
            (localRenderer as? VideoTrackSink)?.let { sink ->
                runCatching { localVideoTrack?.removeSink(sink) }
            }
            runCatching { cameraVideoTrack?.dispose() }
            cameraVideoTrack = null
            localVideoTrack = null
            runCatching { localVideoSource?.dispose() }
            localVideoSource = null
        }
    }

    override fun stopLocalMedia() {
        stopVideoCapture(disposeTrack = true)

        localAudioSender?.let { sender ->
            runCatching { peerConnection?.removeTrack(sender) }
        }
        localAudioSender = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        localAudioSource = null
    }

    override fun startScreenShare(data: Any?) {
        val includeAudio = data as? Boolean ?: false
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return

            // Stop camera if running
            stopVideoCapture(disposeTrack = false)
            
            val screenCapturer = ScreenCapturer()
            val sources = screenCapturer.desktopSources
            if (sources.isNotEmpty()) {
                screenSource = VideoDesktopSource()
                screenSource?.setSourceId(sources[0].id, false)
                screenSource?.start()
                
                val screenTrack = f.createVideoTrack("screenTrack", screenSource)
                screenTrack.setEnabled(true)
                
                if (localVideoSender != null) {
                    localVideoSender?.replaceTrack(screenTrack)
                } else {
                    localVideoSender = pConn.addTrack(screenTrack, listOf("mediaStream"))
                }
                
                localVideoTrack = screenTrack
                _isScreenSharing.tryEmit(true)

                  if (includeAudio) {
                      runCatching { audioDeviceModule?.setSystemAudioShareEnabled(true) }
                  }
            }
            screenCapturer.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun stopScreenShare() {
        try {
            screenSource?.stop()
            screenSource?.dispose()
            screenSource = null
            
            // Resume camera if it was enabled
            if (cameraVideoTrack != null) {
                startVideoCapture()
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
            }
            
            // Stop sharing system audio
            runCatching { audioDeviceModule?.setSystemAudioShareEnabled(false) }
            
            _isScreenSharing.tryEmit(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        if (localVideoTrack == null && isVideoEnabled) {
            startLocalMedia(true)
            return
        }
        if (isVideoEnabled) {
            startVideoCapture()
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

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel) {
        try {
            val type = RTCSdpType.valueOf(sessionDescription.type.uppercase())
            val sdp = RTCSessionDescription(type, sessionDescription.sdp)
            peerConnection?.setRemoteDescription(sdp, object : SetSessionDescriptionObserver {
                override fun onSuccess() {}
                override fun onFailure(error: String) {}
            })
        } catch (e: Exception) {
            e.printStackTrace()
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
            peerConnection?.close()
            peerConnection = null
            screenSource?.stop()
            screenSource = null
            localVideoTrack = null
            localAudioTrack = null
            remoteVideoTrack = null
            remoteAudioTrack = null
            localVideoSender = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer

    override fun setStreamAudioEnabled(enabled: Boolean) {
        audioDeviceModule?.setSystemAudioShareEnabled(enabled)
        recreateLocalAudioTrack(enabled)
    }
    
    override fun setOrbitZeroEnabled(enabled: Boolean) {
        audioDeviceModule?.setOrbitZeroEnabled(enabled)
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
            
            // Clean up old track
            runCatching { localAudioTrack?.dispose() }
            
            localAudioSource = newAudioSource
            localAudioTrack = newAudioTrack
            
            println("DEBUG: [WebRTC] AudioOptions updated. NS=${!enabled}, AGC=${!enabled}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    override fun getAudioInputDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getAudioCaptureDevices().map { it.name }
        }.getOrDefault(listOf("Default"))
    }

    override fun getAudioOutputDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getAudioRenderDevices().map { it.name }
        }.getOrDefault(listOf("Default"))
    }

    override fun getVideoCaptureDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices().map { it.name }
        }.getOrDefault(listOf("Default"))
    }
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
