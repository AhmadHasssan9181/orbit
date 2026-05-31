package livekit

actual object LiveKitFeatureFlags {
    actual val cppMedia: Boolean
        get() = true

    actual val ffiFallback: Boolean
        get() = false

    actual val ffiAudio: Boolean
        get() = false

    actual val ffiVideo: Boolean
        get() = false

    actual val debugFrames: Boolean
        get() = false

    actual val videoRequired: Boolean
        get() = false
}
