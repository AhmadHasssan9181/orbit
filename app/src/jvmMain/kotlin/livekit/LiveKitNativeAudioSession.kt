package livekit

object LiveKitNativeAudioSession {
    @Volatile private var connected = false

    val isConnected: Boolean
        get() = connected

    fun connect(url: String, token: String): Boolean {
        if (!LiveKitNativeAudioBridge.enabled) return false
        if (connected) return true

        return runCatching {
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
        return runCatching {
            LiveKitNativeAudioCapture.start()
        }.getOrElse { error ->
            println("ERROR [LiveKitNativeAudioSession] Publish failed: ${error.message}")
            false
        }
    }

    fun stopPublishing() {
        LiveKitNativeAudioCapture.stop()
    }

    fun setMuted(isMuted: Boolean) {
        LiveKitNativeAudioCapture.setMuted(isMuted)
    }

    fun disconnect() {
        runCatching { LiveKitNativeAudioCapture.stop() }
        runCatching { LiveKitNativeAudioPlayer.stop() }
        runCatching { LiveKitNativeAudioBridge.nativeDisconnect() }
        connected = false
    }
}
