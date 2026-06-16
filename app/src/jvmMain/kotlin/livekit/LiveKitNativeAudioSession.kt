package livekit

object LiveKitNativeAudioSession {
    val isConnected: Boolean
        get() = LiveKitNativeMediaSession.isConnected

    val isMicPublished: Boolean
        get() = LiveKitNativeMediaSession.isAudioPublished

    val isAcceptingFrames: Boolean
        get() = LiveKitNativeMediaSession.isAcceptingAudioFrames

    fun connect(url: String, token: String): Boolean {
        println("DEBUG [LiveKitNativeAudioSession] Legacy direct connect disabled; use LiveKitNativeMediaSession")
        return LiveKitNativeMediaSession.isConnected
    }

    fun startPublishing(): Boolean {
        println("DEBUG [LiveKitNativeAudioSession] Legacy direct publish disabled; media readiness is owned by LiveKitNativeMediaSession")
        return LiveKitNativeMediaSession.isAcceptingAudioFrames
    }

    fun stopPublishing() {
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        LiveKitNativeAudioCapture.stopAndJoin()
    }

    fun setMuted(isMuted: Boolean) {
        val result = LiveKitNativeMediaSession.setAudioMuted(isMuted)
        if (!result.ok) {
            println("ERROR [LiveKitNativeAudioSession] Failed to set native audio mute: ${result.error?.message}")
        }
    }

    fun disconnect() {
        LiveKitNativeMediaSession.disconnect(userInitiated = true)
    }
}
