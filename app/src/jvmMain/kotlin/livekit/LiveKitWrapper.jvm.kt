package livekit

actual suspend fun connectToLiveKit(url: String, token: String) {
    LiveKitClient.connect(url, token)
}
