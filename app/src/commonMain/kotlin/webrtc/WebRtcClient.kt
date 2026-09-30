package webrtc

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import repository.TurnServer

data class IceCandidateModel(val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)
data class SessionDescriptionModel(val type: String, val sdp: String)

interface WebRtcClient {
    val onIceCandidate: SharedFlow<IceCandidateModel>
    val onRemoteDescription: SharedFlow<SessionDescriptionModel>
    val isScreenSharing: StateFlow<Boolean>
    val isRemoteScreenSharing: StateFlow<Boolean>
    val onStreamAdded: SharedFlow<Boolean>
    var isGroupCall: Boolean

    fun initialize(iceServers: List<TurnServer> = emptyList())
    fun startLocalMedia(isVideoEnabled: Boolean)
    fun stopLocalMedia()
    fun startScreenShare(data: Any? = null) // data for Android MediaProjection intent
    fun stopScreenShare()
    fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit)
    fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit)
    fun setRemoteDescription(sessionDescription: SessionDescriptionModel, onSuccess: () -> Unit = {}, onError: (String) -> Unit = {})
    fun addIceCandidate(candidate: IceCandidateModel)
    fun endCall()
    
    fun getLocalVideoRenderer(): Any?
    fun getRemoteVideoRenderer(): Any?
    fun getRemoteScreenRenderer(): Any?
    
    fun toggleMute(isMuted: Boolean)
    fun toggleVideo(isVideoEnabled: Boolean)
    fun switchCamera()
    fun setStreamAudioEnabled(enabled: Boolean)
    fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean)
    fun setOrbitZeroEnabled(enabled: Boolean)
    fun setAttenuationStrength(strength: Float)
    var isVirtualBackgroundEnabled: Boolean
    fun setRemoteVolume(volume: Float)

    /** Deafen hook: silence remote playback while mic keeps publishing. */
    fun setPlaybackMuted(muted: Boolean) {}
    
    fun setAudioInputDevice(deviceName: String)
    fun setAudioOutputDevice(deviceName: String)
    fun setVideoCaptureDevice(deviceName: String)
    fun setMirrorVideo(enabled: Boolean)
    
    fun getDesktopSources(): List<DesktopSourceModel>

    /**
     * Build the screen-share factory and enumerate desktop sources ahead of time.
     *
     * Both are one-time costs that otherwise land on the click that starts a
     * share: a `PeerConnectionFactory` initialises the whole WebRTC native stack,
     * and the enumeration walks every monitor and window through COM. Measured
     * together on a machine running a 10 ms-deadline audio mixer, they cost a
     * clear ~1 second of audio dropout at the start of every share.
     *
     * Calling this when the call connects moves that cost into the join, which
     * already takes seconds and has spare CPU.
     *
     * Must stay a pure optimisation: implementations are expected to do the work
     * lazily anyway if this is never called, and to swallow any failure. Nothing
     * here may become a prerequisite for sharing.
     */
    fun prewarmScreenShare() {}
    
    fun getAudioInputDevices(): List<String>
    fun getAudioOutputDevices(): List<String>
    fun getVideoCaptureDevices(): List<String>

    /** Last device chosen via setAudioInputDevice, or null if never set. */
    fun getSelectedAudioInputDevice(): String? = null
    /** Last device chosen via setAudioOutputDevice, or null if never set. */
    fun getSelectedAudioOutputDevice(): String? = null
}

data class DesktopSourceModel(val id: String, val name: String, val isWindow: Boolean)

expect fun createWebRtcClient(): WebRtcClient
