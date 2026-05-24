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
<<<<<<< HEAD
=======
import dev.onvoid.webrtc.RTCRtpTransceiver
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
<<<<<<< HEAD
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
=======
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
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
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RTCRtpSender? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var localVideoTrack: dev.onvoid.webrtc.media.video.VideoTrack? = null
    private var localAudioTrack: dev.onvoid.webrtc.media.audio.AudioTrack? = null
    private var remoteVideoTrack: dev.onvoid.webrtc.media.video.VideoTrack? = null
    
    private var videoSource: dev.onvoid.webrtc.media.video.VideoDeviceSource? = null
    private var screenSource: dev.onvoid.webrtc.media.video.VideoDesktopSource? = null
    
    private var videoSender: RTCRtpSender? = null

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (peerConnection != null) return

            if (factory == null) {
                factory = PeerConnectionFactory()
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
            val configuredIceUrls = rtcConfig.iceServers.flatMap { it.urls }
            println("DEBUG: [WebRTC] ICE servers configured: ${configuredIceUrls.size} urls: ${configuredIceUrls.joinToString { it.redactedIceUrl() }}")

            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    println("DEBUG: [WebRTC] Local ICE candidate: ${candidate.sdp.iceCandidateType()} mid=${candidate.sdpMid}")
                    _onIceCandidate.tryEmit(
                        IceCandidateModel(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    )
                }

                override fun onAddStream(stream: MediaStream) {
                    _onStreamAdded.tryEmit(true)
<<<<<<< HEAD
                    val tracks = stream.videoTracks
                    if (tracks.isNotEmpty()) {
                        remoteVideoTrack = tracks[0]
=======
                    val sink = remoteRenderer as? VideoTrackSink
                    if (sink != null) {
                        stream.videoTracks.firstOrNull()?.addSink(sink)
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
                    }
                }

                override fun onSignalingChange(state: RTCSignalingState) {}
                override fun onConnectionChange(state: RTCPeerConnectionState) {
                    println("DEBUG: [WebRTC] Peer connection state: $state")
                }
                override fun onIceConnectionChange(state: RTCIceConnectionState) {
                    println("DEBUG: [WebRTC] ICE connection state: $state")
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: RTCIceGatheringState) {
                    println("DEBUG: [WebRTC] ICE gathering state: $state")
                }
                override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: RTCDataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {
<<<<<<< HEAD
                    _onStreamAdded.tryEmit(true)
                    val track = receiver.track
                    if (track is dev.onvoid.webrtc.media.video.VideoTrack) {
                        remoteVideoTrack = track
                    }
=======
                    attachRemoteVideoTrack(receiver.track)
                }
                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    attachRemoteVideoTrack(transceiver.receiver.track)
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
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

<<<<<<< HEAD
            val audioSource = f.createAudioSource(dev.onvoid.webrtc.media.audio.AudioOptions())
            localAudioTrack = f.createAudioTrack("audioTrack", audioSource)
            pConn.addTrack(localAudioTrack, listOf("mediaStream"))

            if (isVideoEnabled) {
                val devices = dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()
                if (devices.isNotEmpty()) {
                    videoSource = dev.onvoid.webrtc.media.video.VideoDeviceSource()
                    videoSource?.setVideoCaptureDevice(devices[0])
                    videoSource?.start()
                    localVideoTrack = f.createVideoTrack("videoTrack", videoSource)
                    videoSender = pConn.addTrack(localVideoTrack, listOf("mediaStream"))
                }
=======
            if (localAudioTrack == null) {
                val audioSource = f.createAudioSource(dev.onvoid.webrtc.media.audio.AudioOptions())
                val audioTrack = f.createAudioTrack("audioTrack", audioSource)
                localAudioTrack = audioTrack
                localAudioSender = pConn.addTrack(audioTrack, listOf("mediaStream"))
            }

            if (isVideoEnabled) {
                startVideoCapture()
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

<<<<<<< HEAD
    override fun stopLocalMedia() {
        videoSource?.stop()
        videoSource = null
        localVideoTrack = null
        localAudioTrack = null
    }

    override fun startScreenShare(data: Any?) {
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return

            // Stop camera if running
            videoSource?.stop()
            
            val screenCapturer = ScreenCapturer()
            val sources = screenCapturer.desktopSources
            if (sources.isNotEmpty()) {
                screenSource = dev.onvoid.webrtc.media.video.VideoDesktopSource()
                // Assuming sources[0] is the primary screen. Use id and isWindow=false.
                screenSource?.setSourceId(sources[0].id, false)
                screenSource?.start()
                
                val screenTrack = f.createVideoTrack("screenTrack", screenSource)
                
                if (videoSender != null) {
                    videoSender?.replaceTrack(screenTrack)
                } else {
                    videoSender = pConn.addTrack(screenTrack, listOf("mediaStream"))
                }
                
                localVideoTrack = screenTrack
                _isScreenSharing.tryEmit(true)
            }
            screenCapturer.dispose()
        } catch (e: Exception) {
            e.printStackTrace()
=======
    private fun startVideoCapture() {
        val pConn = peerConnection ?: return
        val f = factory ?: return

        if (localVideoSource == null || localVideoTrack == null) {
            val devices = MediaDevices.getVideoCaptureDevices()
            if (devices.isEmpty()) {
                println("DEBUG: [WebRTC] No desktop video capture devices found")
                return
            }

            val device = devices.first()
            val capabilities = MediaDevices.getVideoCaptureCapabilities(device)
            val capability = chooseVideoCapability(capabilities)
            println("DEBUG: [WebRTC] Using desktop camera: ${device.name}")
            println("DEBUG: [WebRTC] Using desktop camera capability: ${capability ?: "default"}")

            val videoSource = VideoDeviceSource().apply {
                setVideoCaptureDevice(device)
                if (capability != null) {
                    setVideoCaptureCapability(capability)
                }
            }

            runCatching {
                videoSource.start()
                isVideoCaptureRunning = true
                println("DEBUG: [WebRTC] Desktop video capture started")
            }.onFailure { error ->
                println("DEBUG: [WebRTC] Desktop video capture failed: ${error.message}")
                runCatching { videoSource.dispose() }
                return
            }

            val videoTrack = f.createVideoTrack("videoTrack", videoSource)
            localVideoSource = videoSource
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
                println("DEBUG: [WebRTC] Desktop video capture started")
            }.onFailure { error ->
                println("DEBUG: [WebRTC] Desktop video capture failed: ${error.message}")
            }
        }
    }

    private fun attachRemoteVideoTrack(track: dev.onvoid.webrtc.media.MediaStreamTrack?) {
        if (track is VideoTrack) {
            println("DEBUG: [WebRTC] Remote video track attached: ${track.id}")
            _onStreamAdded.tryEmit(true)
            (remoteRenderer as? VideoTrackSink)?.let { sink ->
                track.addSink(sink)
            }
        }
    }

    private fun chooseVideoCapability(capabilities: List<VideoCaptureCapability>): VideoCaptureCapability? {
        if (capabilities.isEmpty()) return null

        return capabilities.minByOrNull { capability ->
            val widthScore = abs(capability.width - 640)
            val heightScore = abs(capability.height - 480)
            val fpsScore = abs(capability.frameRate - 30) * 20
            widthScore + heightScore + fpsScore
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
            runCatching { localVideoTrack?.dispose() }
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
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.setEnabled(!isMuted)
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        if (isVideoEnabled) {
            startVideoCapture()
        } else {
            stopVideoCapture(disposeTrack = false)
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
        }
    }

    override fun stopScreenShare() {
        try {
            screenSource?.stop()
            screenSource?.dispose()
            screenSource = null
            
            // Resume camera if it was enabled
            val f = factory ?: return
            val devices = dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()
            if (devices.isNotEmpty()) {
                videoSource = dev.onvoid.webrtc.media.video.VideoDeviceSource()
                videoSource?.setVideoCaptureDevice(devices[0])
                videoSource?.start()
                localVideoTrack = f.createVideoTrack("videoTrack", videoSource)
                videoSender?.replaceTrack(localVideoTrack)
            }
            
            _isScreenSharing.tryEmit(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun toggleMute(isMuted: Boolean) {
        localAudioTrack?.isEnabled = !isMuted
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        localVideoTrack?.isEnabled = isVideoEnabled
        if (isVideoEnabled) {
            videoSource?.start()
        } else {
            videoSource?.stop()
        }
    }

    override fun switchCamera() {
        // Implementation for switching camera
    }

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            println("DEBUG: [WebRTC] Offer SDP has video: ${description.sdp.hasVideoMLine()}")
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) {
                            onError(error)
                        }
                    })
                }
                override fun onFailure(error: String) {
                    onError(error)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            onError(e.message ?: "Unknown error")
        }
    }

    override fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createAnswer(RTCAnswerOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            println("DEBUG: [WebRTC] Answer SDP has video: ${description.sdp.hasVideoMLine()}")
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) {
                            onError(error)
                        }
                    })
                }
                override fun onFailure(error: String) {
                    onError(error)
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            onError(e.message ?: "Unknown error")
        }
    }

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel) {
        try {
            val type = RTCSdpType.valueOf(sessionDescription.type.uppercase())
            val sdp = RTCSessionDescription(type, sessionDescription.sdp)
            peerConnection?.setRemoteDescription(sdp, object : SetSessionDescriptionObserver {
                override fun onSuccess() {
                    println("DEBUG: [WebRTC] Remote ${sessionDescription.type} SDP has video: ${sessionDescription.sdp.hasVideoMLine()}")
                }
                override fun onFailure(error: String) {
                    println("DEBUG: [WebRTC] Failed to set remote ${sessionDescription.type}: $error")
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        if (candidate.sdp.isBlank()) return
        try {
            println("DEBUG: [WebRTC] Adding remote ICE candidate: ${candidate.sdp.iceCandidateType()} mid=${candidate.sdpMid}")
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
            videoSource?.stop()
            videoSource = null
            screenSource?.stop()
            screenSource = null
            localVideoTrack = null
            localAudioTrack = null
            remoteVideoTrack = null
            videoSender = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

<<<<<<< HEAD
    override fun getLocalVideoRenderer(): Any? = localVideoTrack
    override fun getRemoteVideoRenderer(): Any? = remoteVideoTrack
=======
    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer

    private fun String.hasVideoMLine(): Boolean {
        return lineSequence().any { line -> line.startsWith("m=video") && !line.contains(" 0 ") }
    }

    private fun String.iceCandidateType(): String {
        return substringAfter(" typ ", "unknown").substringBefore(' ')
    }

    private fun String.redactedIceUrl(): String {
        return substringBefore('?')
    }
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
