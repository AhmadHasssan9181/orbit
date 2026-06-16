package repository

actual object MatrixRtcFeatureFlags {
    actual val publishLegacyMember: Boolean
        get() = false

    actual val publishMemberMode: String?
        get() = null

    actual val mirrorVisibleElementShape: Boolean
        get() = true
}
