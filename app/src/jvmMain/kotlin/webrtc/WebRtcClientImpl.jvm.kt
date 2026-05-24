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
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import repository.TurnServer

class JvmWebRtcClient : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription


    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: RTCPeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSender: RTCRtpSender? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var localRenderer: Any? = null
    private var remoteRenderer: Any? = null

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (factory != null) return

            factory = PeerConnectionFactory()

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
                override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {}
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
                val audioSource = f.createAudioSource(dev.onvoid.webrtc.media.audio.AudioOptions())
                val audioTrack = f.createAudioTrack("audioTrack", audioSource)
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

        if (localVideoSource == null || localVideoTrack == null) {
            val devices = dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices()
            if (devices.isEmpty()) return

            val videoSource = VideoDeviceSource().apply {
                setVideoCaptureDevice(devices[0])
            }
            val videoTrack = f.createVideoTrack("videoTrack", videoSource)
            localVideoSource = videoSource
            localVideoTrack = videoTrack
            localVideoSender = pConn.addTrack(videoTrack, listOf("mediaStream"))
        }

        localVideoTrack?.setEnabled(true)
        if (!isVideoCaptureRunning) {
            localVideoSource?.start()
            isVideoCaptureRunning = true
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
        }
    }

    override fun switchCamera() {}

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createOffer(RTCOfferOptions(), object : CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
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
                override fun onSuccess() {}
                override fun onFailure(error: String) {}
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
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
            // Do NOT dispose factory here, as it permanently destroys the JVM WebRTC context
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
