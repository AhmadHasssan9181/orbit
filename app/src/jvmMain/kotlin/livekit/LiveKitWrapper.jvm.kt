package livekit

actual suspend fun connectToLiveKit(url: String, token: String, iceServers: List<repository.TurnServer>) {
    LiveKitClient.connect(url, token, iceServers)
}
