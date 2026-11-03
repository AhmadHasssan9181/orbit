package ui.model

import androidx.compose.runtime.Immutable

/**
 * Represents a channel within an Orbit server.
 */
// All fields are vals of stable types (String, ChannelType, String?), so every
// composable taking a list of channels can skip rather than recompose per row.
@Immutable
data class OrbitChannel(
    val id: String,
    val name: String,
    val type: ChannelType = ChannelType.TEXT,
    val categoryId: String? = null,
    /**
     * Human name for [categoryId], resolved server-side.
     *
     * Carried on the channel rather than looked up by the drawer because the drawer
     * groups a flat `List<OrbitChannel>` and has no map to consult; keeping the
     * label beside the id is what lets a row render its own section header without
     * the list holding a second structure that can drift out of sync with this one.
     * Null when the server has no categories, in which case the drawer groups by
     * channel type instead.
     */
    val categoryName: String? = null,
    /**
     * Where the server said this room sits among its siblings.
     *
     * Carried through so the drawer can order category groups the way the person
     * who built the space ordered them. Sourced from the `position` field of
     * `/_matrix/client/v1/categories`; 0.0 when the server reported no position,
     * in which case the group falls back to alphabetical.
     */
    val categoryPosition: Double = 0.0,
    val topic: String = "",
    val avatarUrl: String? = null,
    val activeCallUrl: String? = null,
    val matrixRoomId: String = id,
    val isOrbitManaged: Boolean = false
)

enum class ChannelType {
    TEXT,
    VOICE
}
