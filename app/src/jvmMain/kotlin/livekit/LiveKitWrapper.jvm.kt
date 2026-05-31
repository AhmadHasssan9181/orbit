package livekit

actual suspend fun connectToLiveKit(url: String, token: String, iceServers: List<repository.TurnServer>) {
    if (LiveKitFeatureFlags.cppMedia) {
        if (LiveKitNativeAudioSession.connect(url, token)) {
            return
        }
        if (!LiveKitFeatureFlags.ffiFallback) {
            throw IllegalStateException(
                "Native C++ LiveKit media connect failed and raw FFI fallback is disabled"
            )
        }
        println("DEBUG [LiveKitWrapper] Native C++ media connect failed; raw FFI fallback is explicitly enabled")
    }

    if (!LiveKitFeatureFlags.ffiFallback) {
        throw IllegalStateException("Raw FFI LiveKit media owner is disabled for MatrixRTC group calls")
    }

    LiveKitClient.connect(url, token, iceServers)
}

actual suspend fun connectAndPublishToLiveKit(
    authResponse: LiveKitAuthResponse,
    isVideo: Boolean,
    iceServers: List<repository.TurnServer>,
    refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)?
): NativeResult<NativeMediaReadiness> {
    if (!LiveKitFeatureFlags.cppMedia) {
        return NativeResult.failure(
            NativeError(
                code = NativeErrorCode.CONNECT_FAILED,
                message = "C++ LiveKit media is disabled",
                operation = "connectAndPublishToLiveKit",
                recoverable = false
            )
        )
    }

    return LiveKitNativeMediaSession.connectAndPublish(
        authResponse = authResponse,
        videoPolicy = if (isVideo && LiveKitFeatureFlags.videoRequired) {
            JoinVideoPolicy.VideoRequired
        } else if (isVideo) {
            JoinVideoPolicy.VideoOptional
        } else {
            JoinVideoPolicy.AudioOnly
        },
        refreshTokenFetcher = refreshTokenFetcher
    )
}

actual suspend fun ensureLiveKitMediaReady(): NativeResult<NativeMediaReadiness> =
    LiveKitNativeMediaSession.ensureMediaReady()

actual fun disconnectFromLiveKit(userInitiated: Boolean) {
    LiveKitNativeMediaSession.disconnect(userInitiated = userInitiated)
}
