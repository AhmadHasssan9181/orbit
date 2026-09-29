package ui.model

import androidx.compose.runtime.Immutable

/**
 * Represents a user in the Orbit application.
 */
// All fields are vals of stable types (String, Int, UserStatus), so composables
// taking a member list can skip instead of recomposing on every frame update.
@Immutable
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
