package livekit

enum class NativeMediaState {
    Idle,
    DiscoveringMatrixRtc,
    PublishingMembership,
    FetchingToken,
    ConnectingLiveKit,
    LiveKitConnected,
    PublishingAudio,
    AudioPublished,
    PublishingVideo,
    VideoPublished,
    MediaReady,
    TokenRefreshScheduled,
    RefreshingToken,
    Reconnecting,
    Disconnecting,
    Disconnected,
    Failed
}

enum class CameraPublishState {
    NotRequested,
    Probing,
    Publishing,
    Published,
    Unavailable,
    PermissionDenied,
    DeviceBusy,
    DriverFailure,
    FormatRejected,
    Failed
}

enum class JoinVideoPolicy {
    AudioOnly,
    VideoOptional,
    VideoRequired
}

data class NativeMediaReadiness(
    val sessionId: Long,
    val epoch: Long,
    val state: NativeMediaState,
    val connected: Boolean,
    val audioPublished: Boolean,
    val videoPublished: Boolean,
    val acceptingAudioFrames: Boolean,
    val acceptingVideoFrames: Boolean,
    val cameraRequested: Boolean,
    val cameraRequired: Boolean,
    val cameraState: CameraPublishState,
    val joinedAsAudioOnlyFallback: Boolean,
    val tokenRefreshScheduled: Boolean,
    val lastError: NativeError?
)
