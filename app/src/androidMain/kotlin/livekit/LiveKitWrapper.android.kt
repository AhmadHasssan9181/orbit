package livekit

actual suspend fun connectToLiveKit(url: String, token: String, iceServers: List<repository.TurnServer>) {
    throw UnsupportedOperationException("MatrixRTC LiveKit media is not implemented on Android yet")
}

actual suspend fun connectAndPublishToLiveKit(
    authResponse: LiveKitAuthResponse,
    isVideo: Boolean,
    iceServers: List<repository.TurnServer>,
    refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)?
): NativeResult<NativeMediaReadiness> =
    NativeResult.failure(
        NativeError(
            code = NativeErrorCode.CONNECT_FAILED,
            message = "MatrixRTC LiveKit media is not implemented on Android yet",
            operation = "connectAndPublishToLiveKit",
            recoverable = false
        )
    )

actual suspend fun ensureLiveKitMediaReady(): NativeResult<NativeMediaReadiness> =
    NativeResult.failure(
        NativeError(
            code = NativeErrorCode.CONNECT_FAILED,
            message = "MatrixRTC LiveKit media is not implemented on Android yet",
            operation = "ensureLiveKitMediaReady",
            recoverable = false
        )
    )

actual fun disconnectFromLiveKit(userInitiated: Boolean) = Unit
