package livekit

actual object LiveKitFeatureFlags {
    actual val cppMedia: Boolean
        get() = boolProperty(
            key = "orbit.livekit.cppMedia",
            defaultValue = boolProperty("orbit.livekit.cppAudio", true)
        )

    actual val ffiFallback: Boolean
        get() = boolProperty("orbit.livekit.ffiFallback", false)

    actual val ffiAudio: Boolean
        get() = boolProperty("orbit.livekit.ffiAudio", false)

    actual val ffiVideo: Boolean
        get() = boolProperty("orbit.livekit.ffiVideo", false)

    actual val debugFrames: Boolean
        get() = boolProperty("orbit.livekit.debugFrames", false)

    actual val videoRequired: Boolean
        get() = boolProperty("orbit.livekit.videoRequired", false)

    actual val dumpTonePublisherEnv: Boolean
        get() = boolProperty("orbit.livekit.dumpTonePublisherEnv", false)

    private fun boolProperty(key: String, defaultValue: Boolean): Boolean =
        when (System.getProperty(key)?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            null, "" -> defaultValue
            else -> defaultValue
        }
}
