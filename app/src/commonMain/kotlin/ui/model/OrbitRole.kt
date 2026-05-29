package ui.model

import kotlinx.serialization.Serializable

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Serializable
enum class OrbitPermissionCategory(val label: String) {
    GENERAL("General"),
    CHANNELS("Channels"),
    MESSAGES("Messages"),
    MODERATION("Moderation"),
    VOICE("Voice"),
    EVENTS("Events")
}

@Serializable
enum class PermissionGrant(val label: String, val isAllowed: Boolean) {
    YES("Yes", true),
    NO("No", false),
    LIMITED("Limited", true),
    SENIOR_ONLY("Senior only", true),
    OPTIONAL("Optional", true);

    fun next(): PermissionGrant = when (this) {
        YES -> LIMITED
        LIMITED -> OPTIONAL
        OPTIONAL -> SENIOR_ONLY
        SENIOR_ONLY -> NO
        NO -> YES
    }
}

@Serializable
enum class OrbitPermission(
    val label: String,
    val category: OrbitPermissionCategory,
    val description: String
) {
    ADMINISTRATOR(
        "Administrator",
        OrbitPermissionCategory.GENERAL,
        "Grants every permission and should only belong to the most trusted owners."
    ),
    VIEW_SERVER(
        "View Server",
        OrbitPermissionCategory.GENERAL,
        "Allows the member to see the server in their Orbit sidebar."
    ),
    MANAGE_SERVER(
        "Manage Server",
        OrbitPermissionCategory.GENERAL,
        "Allows editing server identity, overview settings, and core metadata."
    ),
    MANAGE_ROLES(
        "Manage Roles",
        OrbitPermissionCategory.GENERAL,
        "Allows creating, editing, and assigning roles below the member's top role."
    ),
    MANAGE_CHANNELS(
        "Manage Channels",
        OrbitPermissionCategory.CHANNELS,
        "Allows creating, editing, reordering, and removing rooms."
    ),
    VIEW_AUDIT_LOG(
        "View Audit Log",
        OrbitPermissionCategory.GENERAL,
        "Allows reading moderation and server-management history."
    ),
    VIEW_CHANNEL(
        "View Channel",
        OrbitPermissionCategory.CHANNELS,
        "Allows entering visible text and voice rooms."
    ),
    READ_MESSAGE_HISTORY(
        "Read Message History",
        OrbitPermissionCategory.MESSAGES,
        "Allows reading messages sent before the member opened a room."
    ),
    SEND_MESSAGES(
        "Send Messages",
        OrbitPermissionCategory.MESSAGES,
        "Allows sending text messages in rooms."
    ),
    ATTACH_FILES(
        "Attach Files",
        OrbitPermissionCategory.MESSAGES,
        "Allows uploading images, audio, video, and documents."
    ),
    SEND_GIFS(
        "Send GIFs",
        OrbitPermissionCategory.MESSAGES,
        "Allows sending GIF content."
    ),
    ADD_REACTIONS(
        "Add Reactions",
        OrbitPermissionCategory.MESSAGES,
        "Allows reacting to messages."
    ),
    MENTION_EVERYONE(
        "Mention Everyone",
        OrbitPermissionCategory.MESSAGES,
        "Allows notifying everyone-style audience groups."
    ),
    MANAGE_MESSAGES(
        "Manage Messages",
        OrbitPermissionCategory.MODERATION,
        "Allows deleting, cleaning up, or otherwise moderating messages."
    ),
    PIN_MESSAGES(
        "Pin Messages",
        OrbitPermissionCategory.MODERATION,
        "Allows pinning important messages in a room."
    ),
    TIMEOUT_MEMBERS(
        "Timeout Members",
        OrbitPermissionCategory.MODERATION,
        "Allows temporarily restricting disruptive members."
    ),
    KICK_MEMBERS(
        "Kick Members",
        OrbitPermissionCategory.MODERATION,
        "Allows removing members from the server."
    ),
    BAN_MEMBERS(
        "Ban Members",
        OrbitPermissionCategory.MODERATION,
        "Allows permanently blocking members from rejoining."
    ),
    CONNECT_VOICE(
        "Connect Voice",
        OrbitPermissionCategory.VOICE,
        "Allows joining voice rooms."
    ),
    SPEAK(
        "Speak",
        OrbitPermissionCategory.VOICE,
        "Allows transmitting microphone audio in voice rooms."
    ),
    MUTE_MEMBERS(
        "Mute Members",
        OrbitPermissionCategory.VOICE,
        "Allows server-side muting members in voice rooms."
    ),
    MOVE_MEMBERS(
        "Move Members",
        OrbitPermissionCategory.VOICE,
        "Allows moving members between voice rooms."
    ),
    CREATE_EVENTS(
        "Create Events",
        OrbitPermissionCategory.EVENTS,
        "Allows scheduling server events."
    ),
    MANAGE_EVENTS(
        "Manage Events",
        OrbitPermissionCategory.EVENTS,
        "Allows editing and removing server events."
    ),
    MANAGE_WEBHOOKS(
        "Manage Webhooks",
        OrbitPermissionCategory.GENERAL,
        "Allows creating integrations and webhook entry points."
    )
}

@Serializable
data class OrbitRolePermission(
    val permission: OrbitPermission,
    val grant: PermissionGrant
)

@Serializable
data class OrbitRole(
    val id: String,
    val name: String,
    val description: String,
    val color: Int,
    val rank: Int,
    val permissions: List<OrbitRolePermission>
) {
    fun directGrantFor(permission: OrbitPermission): PermissionGrant {
        return permissions.firstOrNull { it.permission == permission }?.grant ?: PermissionGrant.NO
    }

    fun grantFor(permission: OrbitPermission): PermissionGrant {
        val direct = directGrantFor(permission)
        val hasAdministrator = permissions.any {
            it.permission == OrbitPermission.ADMINISTRATOR && it.grant == PermissionGrant.YES
        }
        return if (permission != OrbitPermission.ADMINISTRATOR && hasAdministrator) PermissionGrant.YES else direct
    }

    fun withGrant(permission: OrbitPermission, grant: PermissionGrant): OrbitRole {
        val updated = permissions
            .filterNot { it.permission == permission }
            .plus(OrbitRolePermission(permission, grant))
            .sortedBy { OrbitPermission.entries.indexOf(it.permission) }
        return copy(permissions = updated)
    }

    val allowedCount: Int
        get() = OrbitPermission.entries.count { grantFor(it).isAllowed }
}

@Serializable
data class OrbitMemberRoleAssignment(
    val userId: String,
    val roleIds: List<String>
)

@Serializable
data class OrbitServerRolesState(
    val serverId: String,
    val roles: List<OrbitRole>,
    val memberAssignments: List<OrbitMemberRoleAssignment> = emptyList()
)

data class OrbitRoleSuggestion(
    val title: String,
    val detail: String
)

object OrbitRoleDefaults {
    fun accountDataEventType(serverId: String): String {
        val safeServerId = serverId.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_'
        }.joinToString("")
        return "com.orbit.server_roles.$safeServerId"
    }

    fun defaultState(serverId: String): OrbitServerRolesState {
        return OrbitServerRolesState(serverId = serverId, roles = defaultRoles())
    }

    fun defaultRoles(): List<OrbitRole> {
        return listOf(
            OrbitRole(
                id = "owner",
                name = "Owner",
                description = "Full server control, including administrator access.",
                color = 0xFFF2C94C.toInt(),
                rank = 100,
                permissions = grants(defaultGrant = PermissionGrant.YES)
            ),
            OrbitRole(
                id = "admin",
                name = "Admin",
                description = "Full trusted administrator access without ownership language.",
                color = 0xFFEB5757.toInt(),
                rank = 90,
                permissions = grants(defaultGrant = PermissionGrant.YES)
            ),
            OrbitRole(
                id = "server-manager",
                name = "Server Manager",
                description = "Runs server structure and integrations without member punishment power.",
                color = 0xFF2F80ED.toInt(),
                rank = 80,
                permissions = grants(
                    OrbitPermission.ADMINISTRATOR to PermissionGrant.NO,
                    OrbitPermission.MANAGE_ROLES to PermissionGrant.LIMITED,
                    OrbitPermission.MANAGE_MESSAGES to PermissionGrant.NO,
                    OrbitPermission.TIMEOUT_MEMBERS to PermissionGrant.NO,
                    OrbitPermission.KICK_MEMBERS to PermissionGrant.NO,
                    OrbitPermission.BAN_MEMBERS to PermissionGrant.NO,
                    OrbitPermission.MUTE_MEMBERS to PermissionGrant.NO,
                    OrbitPermission.MOVE_MEMBERS to PermissionGrant.NO,
                    defaultGrant = PermissionGrant.YES
                )
            ),
            OrbitRole(
                id = "moderator",
                name = "Moderator",
                description = "Keeps chats and voice clean with moderation tools.",
                color = 0xFF27AE60.toInt(),
                rank = 70,
                permissions = grants(
                    OrbitPermission.ADMINISTRATOR to PermissionGrant.NO,
                    OrbitPermission.MANAGE_SERVER to PermissionGrant.NO,
                    OrbitPermission.MANAGE_ROLES to PermissionGrant.NO,
                    OrbitPermission.MANAGE_CHANNELS to PermissionGrant.NO,
                    OrbitPermission.MENTION_EVERYONE to PermissionGrant.NO,
                    OrbitPermission.BAN_MEMBERS to PermissionGrant.SENIOR_ONLY,
                    OrbitPermission.CREATE_EVENTS to PermissionGrant.OPTIONAL,
                    OrbitPermission.MANAGE_EVENTS to PermissionGrant.NO,
                    OrbitPermission.MANAGE_WEBHOOKS to PermissionGrant.NO,
                    defaultGrant = PermissionGrant.YES
                )
            ),
            OrbitRole(
                id = "helper",
                name = "Helper",
                description = "Community support role with light moderation only.",
                color = 0xFF56CCF2.toInt(),
                rank = 60,
                permissions = grants(
                    OrbitPermission.VIEW_SERVER to PermissionGrant.YES,
                    OrbitPermission.VIEW_CHANNEL to PermissionGrant.YES,
                    OrbitPermission.READ_MESSAGE_HISTORY to PermissionGrant.YES,
                    OrbitPermission.SEND_MESSAGES to PermissionGrant.YES,
                    OrbitPermission.ATTACH_FILES to PermissionGrant.YES,
                    OrbitPermission.SEND_GIFS to PermissionGrant.YES,
                    OrbitPermission.ADD_REACTIONS to PermissionGrant.YES,
                    OrbitPermission.MANAGE_MESSAGES to PermissionGrant.LIMITED,
                    OrbitPermission.TIMEOUT_MEMBERS to PermissionGrant.LIMITED,
                    OrbitPermission.CONNECT_VOICE to PermissionGrant.YES,
                    OrbitPermission.SPEAK to PermissionGrant.YES
                )
            ),
            OrbitRole(
                id = "member",
                name = "Member",
                description = "Default trusted community role for normal chat and voice use.",
                color = 0xFF9B8CFF.toInt(),
                rank = 50,
                permissions = grants(
                    OrbitPermission.VIEW_SERVER to PermissionGrant.YES,
                    OrbitPermission.VIEW_CHANNEL to PermissionGrant.YES,
                    OrbitPermission.READ_MESSAGE_HISTORY to PermissionGrant.YES,
                    OrbitPermission.SEND_MESSAGES to PermissionGrant.YES,
                    OrbitPermission.ATTACH_FILES to PermissionGrant.YES,
                    OrbitPermission.SEND_GIFS to PermissionGrant.YES,
                    OrbitPermission.ADD_REACTIONS to PermissionGrant.YES,
                    OrbitPermission.CONNECT_VOICE to PermissionGrant.YES,
                    OrbitPermission.SPEAK to PermissionGrant.YES
                )
            ),
            OrbitRole(
                id = "guest",
                name = "Guest",
                description = "Limited preview access for new or temporary members.",
                color = 0xFFBDBDBD.toInt(),
                rank = 20,
                permissions = grants(
                    OrbitPermission.VIEW_SERVER to PermissionGrant.YES,
                    OrbitPermission.VIEW_CHANNEL to PermissionGrant.LIMITED,
                    OrbitPermission.READ_MESSAGE_HISTORY to PermissionGrant.LIMITED,
                    OrbitPermission.SEND_MESSAGES to PermissionGrant.LIMITED,
                    OrbitPermission.ADD_REACTIONS to PermissionGrant.LIMITED,
                    OrbitPermission.CONNECT_VOICE to PermissionGrant.LIMITED,
                    OrbitPermission.SPEAK to PermissionGrant.LIMITED
                )
            ),
            OrbitRole(
                id = "muted",
                name = "Muted",
                description = "Read-only role for members who should not send chat or voice.",
                color = 0xFF828282.toInt(),
                rank = 10,
                permissions = grants(
                    OrbitPermission.VIEW_SERVER to PermissionGrant.YES,
                    OrbitPermission.VIEW_CHANNEL to PermissionGrant.YES,
                    OrbitPermission.READ_MESSAGE_HISTORY to PermissionGrant.YES,
                    OrbitPermission.CONNECT_VOICE to PermissionGrant.YES
                )
            )
        )
    }

    val suggestions: List<OrbitRoleSuggestion> = listOf(
        OrbitRoleSuggestion(
            "Keep Administrator rare",
            "Owner and Admin should be tiny groups because Administrator bypasses normal channel restrictions."
        ),
        OrbitRoleSuggestion(
            "Use hierarchy for moderation",
            "Server Manager should manage structure, while Moderator handles messages, timeouts, voice mutes, and kicks."
        ),
        OrbitRoleSuggestion(
            "Make Guest and Muted explicit",
            "Guest is good for previews; Muted should keep read access but remove sending and speaking."
        ),
        OrbitRoleSuggestion(
            "Channel overrides come next",
            "A strong Discord-style system needs room-level overrides on top of these server defaults."
        )
    )

    private fun grants(
        vararg explicit: Pair<OrbitPermission, PermissionGrant>,
        defaultGrant: PermissionGrant = PermissionGrant.NO
    ): List<OrbitRolePermission> {
        val explicitMap = explicit.toMap()
        return OrbitPermission.entries.map { permission ->
            OrbitRolePermission(permission, explicitMap[permission] ?: defaultGrant)
        }
    }
}
