package livekit

expect object LiveKitFeatureFlags {
    val cppMedia: Boolean
    val ffiFallback: Boolean
    val ffiAudio: Boolean
    val ffiVideo: Boolean
    val debugFrames: Boolean
    val videoRequired: Boolean
    val videoPublish: Boolean
    val videoUpdateMembershipAfterPublish: Boolean
    val videoMembershipMode: String
    val dumpTonePublisherEnv: Boolean
}
