package repository

actual object MatrixRtcFeatureFlags {
    actual val publishLegacyMember: Boolean
        get() = boolProperty("orbit.matrixrtc.publishLegacyMember", false)

    actual val publishMemberMode: String?
        get() = System.getProperty("orbit.matrixrtc.publishMemberMode")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    actual val mirrorVisibleElementShape: Boolean
        get() = boolProperty("orbit.matrixrtc.mirrorVisibleElementShape", false)

    private fun boolProperty(key: String, defaultValue: Boolean): Boolean =
        when (System.getProperty(key)?.trim()?.lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            null, "" -> defaultValue
            else -> defaultValue
        }
}
