package ui.model

/**
 * Represents a Discord-like server (space) in Orbit.
 */
data class OrbitServer(
    val id: String,
    val name: String,
    val iconColor: Int, // Material color Int for rendering
    val description: String = "",
    val isDM: Boolean = false
)

/**
 * Represents a category within an Orbit server.
 */
data class ChannelCategory(
    val id: String,
    val name: String,
    val channelId: String // references first channel in this category
)
