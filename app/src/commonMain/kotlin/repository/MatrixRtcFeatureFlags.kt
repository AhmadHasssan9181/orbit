package repository

expect object MatrixRtcFeatureFlags {
    val publishLegacyMember: Boolean
    val publishMemberMode: String?
    val mirrorVisibleElementShape: Boolean
}
