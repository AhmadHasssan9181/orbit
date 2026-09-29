package ui.model

import androidx.compose.runtime.Immutable

/**
 * Represents a Discord-like server (space) in Orbit.
 */
// Stable: every field is a val of String/Int/Boolean.
@Immutable
data class OrbitServer(
    val id: String,
    val name: String,
    val iconColor: Int, // Material color Int for rendering
    val description: String = "",
    val isDM: Boolean = false,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null
)

/**
 * Represents a category within an Orbit server.
 */
data class ChannelCategory(
    val id: String,
    val name: String,
    val channelId: String // references first channel in this category
)

/** Human-friendly label for a raw category id ("dm" → "Direct Messages"). */
fun categoryDisplayName(categoryId: String): String = when (categoryId.lowercase()) {
    "dm", "dms" -> "Direct Messages"
    else -> categoryId.replaceFirstChar { it.uppercase() }
}
