package ui.model

/**
 * Represents a channel within an Orbit server.
 */
data class OrbitChannel(
    val id: String,
    val name: String,
    val type: ChannelType = ChannelType.TEXT,
    val categoryId: String? = null,
    val topic: String = "",
    val avatarUrl: String? = null,
    val activeCallUrl: String? = null,
    val matrixRoomId: String = id
)

enum class ChannelType {
    TEXT,
    VOICE
}
