package livekit

actual suspend fun connectToLiveKit(url: String, token: String, iceServers: List<repository.TurnServer>) {
    if (LiveKitNativeAudioBridge.enabled) {
        if (LiveKitNativeAudioSession.connect(url, token)) {
            return
        }
        println("DEBUG [LiveKitWrapper] Native C++ audio connect failed; falling back to raw FFI")
    }

    LiveKitClient.connect(url, token, iceServers)
}
