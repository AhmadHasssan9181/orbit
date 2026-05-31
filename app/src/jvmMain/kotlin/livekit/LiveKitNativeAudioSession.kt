package livekit

object LiveKitNativeAudioSession {
    @Volatile private var connected = false
    @Volatile private var micPublished = false
    @Volatile private var acceptingFrames = false

    val isConnected: Boolean
        get() = connected || LiveKitNativeMediaSession.isConnected

    val isMicPublished: Boolean
        get() = micPublished || LiveKitNativeMediaSession.isAudioPublished

    val isAcceptingFrames: Boolean
        get() = (acceptingFrames && LiveKitNativeAudioBridge.acceptingFrames) ||
            LiveKitNativeMediaSession.isAcceptingAudioFrames

    fun connect(url: String, token: String): Boolean {
        if (!LiveKitNativeAudioBridge.enabled) return false
        if (connected) return true

        return runCatching {
            micPublished = false
            acceptingFrames = false
            val ok = LiveKitNativeAudioBridge.nativeConnect(url, token)
            if (ok) {
                LiveKitNativeAudioPlayer.start()
                connected = true
                println("DEBUG [LiveKitNativeAudioSession] Connected native C++ audio session")
            }
            ok
        }.getOrElse { error ->
            println("ERROR [LiveKitNativeAudioSession] Connect failed: ${error.message}")
            disconnect()
            false
        }
    }

    fun startPublishing(): Boolean {
        if (!connected) return false
        if (micPublished && isAcceptingFrames) return true

        return runCatching {
            if (!LiveKitNativeAudioBridge.nativePublishAudio(
                    LiveKitNativeAudioCapture.SAMPLE_RATE,
                    LiveKitNativeAudioCapture.CHANNELS,
                    LiveKitNativeAudioCapture.FRAME_MS
                )
            ) {
                micPublished = false
                acceptingFrames = false
                return@runCatching false
            }

            micPublished = true
            acceptingFrames = LiveKitNativeAudioBridge.acceptingFrames
            if (!acceptingFrames) {
                stopPublishing()
                return@runCatching false
            }

            val captureStarted = LiveKitNativeAudioCapture.start()
            if (!captureStarted) {
                stopPublishing()
                return@runCatching false
            }

            true
        }.getOrElse { error ->
            micPublished = false
            acceptingFrames = false
            println("ERROR [LiveKitNativeAudioSession] Publish failed: ${error.message}")
            false
        }
    }

    fun stopPublishing() {
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        acceptingFrames = false
        LiveKitNativeAudioCapture.stopAndJoin()
        micPublished = false
    }

    fun setMuted(isMuted: Boolean) {
        LiveKitNativeAudioCapture.setMuted(isMuted)
    }

    fun disconnect() {
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        acceptingFrames = false
        runCatching { LiveKitNativeAudioCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioPlayer.stop() }
        runCatching { LiveKitNativeAudioBridge.nativeDisconnect() }
        micPublished = false
        connected = false
    }
}
