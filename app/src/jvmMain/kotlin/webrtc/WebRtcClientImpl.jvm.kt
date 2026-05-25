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
import dev.onvoid.webrtc.media.MediaDevices
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
    private var peerConnection: RTCPeerConnection? = null
    private var audioDeviceModule: dev.onvoid.webrtc.media.audio.AudioDeviceModule? = null

    private var localAudioSource: dev.onvoid.webrtc.media.audio.AudioTrackSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RTCRtpSender? = null
    
    private var cameraVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var remoteVideoTrack: VideoTrack? = null
    private var screenSource: VideoDesktopSource? = null

    private var localRenderer: DesktopVideoRenderer? = null
    private var remoteRenderer: DesktopVideoRenderer? = null

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (peerConnection != null) return

            if (factory == null) {
                audioDeviceModule = dev.onvoid.webrtc.media.audio.AudioDeviceModule(dev.onvoid.webrtc.media.audio.AudioLayer.kWindowsCoreAudio)
                factory = PeerConnectionFactory(audioDeviceModule)
            }
            if (localRenderer == null) {
                localRenderer = DesktopVideoRenderer(mirror = true)
            }
            if (remoteRenderer == null) {
                remoteRenderer = DesktopVideoRenderer()
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
                    attachRemoteVideoTrack(receiver.track)
                }
                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    attachRemoteVideoTrack(transceiver.receiver.track)
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

    private fun attachRemoteVideoTrack(track: dev.onvoid.webrtc.media.MediaStreamTrack?) {
        if (track is VideoTrack) {
            _onStreamAdded.tryEmit(true)
            (remoteRenderer as? VideoTrackSink)?.let { sink ->
                track.addSink(sink)
            }
            remoteVideoTrack = track
        }
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
                
                if (localVideoSender != null) {
                    localVideoSender?.replaceTrack(screenTrack)
                } else {
                    localVideoSender = pConn.addTrack(screenTrack, listOf("mediaStream"))
                }
                
                localVideoTrack = screenTrack
                _isScreenSharing.tryEmit(true)

                if (includeAudio) {
                    val loopbackDevice = audioDeviceModule?.recordingDevices?.firstOrNull { 
                        it.name.contains("Stereo Mix", true) || 
                        it.name.contains("Wave Out", true) || 
                        it.name.contains("What U Hear", true) 
                    }
                    if (loopbackDevice != null) {
                        runCatching { audioDeviceModule?.setRecordingDevice(loopbackDevice) }
                    }
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
            
            // Restore default microphone
            runCatching { 
                val defaultMic = audioDeviceModule?.recordingDevices?.firstOrNull()
                if (defaultMic != null) {
                    audioDeviceModule?.setRecordingDevice(defaultMic)
                }
            }
            
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
            localVideoSender = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
