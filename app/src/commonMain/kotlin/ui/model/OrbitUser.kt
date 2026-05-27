package ui.model

/**
 * Represents a user in the Orbit application.
 */
data class OrbitUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarColor: Int, // Material color Int for rendering
    val status: UserStatus = UserStatus.ONLINE,
    val isBot: Boolean = false,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null
)

enum class UserStatus {
    ONLINE,
    IDLE,
    DO_NOT_DISTURB,
    OFFLINE
}
