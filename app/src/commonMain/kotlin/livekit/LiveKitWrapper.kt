package livekit

expect suspend fun connectToLiveKit(url: String, token: String, iceServers: List<repository.TurnServer> = emptyList())

expect suspend fun connectAndPublishToLiveKit(
    authResponse: LiveKitAuthResponse,
    isVideo: Boolean,
    iceServers: List<repository.TurnServer> = emptyList(),
    refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)? = null
): NativeResult<NativeMediaReadiness>

expect suspend fun ensureLiveKitMediaReady(): NativeResult<NativeMediaReadiness>

expect fun disconnectFromLiveKit(userInitiated: Boolean)
