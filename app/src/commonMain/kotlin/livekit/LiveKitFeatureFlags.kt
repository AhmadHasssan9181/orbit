package livekit

expect object LiveKitFeatureFlags {
    val cppMedia: Boolean
    val ffiFallback: Boolean
    val ffiAudio: Boolean
    val ffiVideo: Boolean
    val debugFrames: Boolean
    val videoRequired: Boolean
}
