package ui.model

import kotlinx.serialization.Serializable

/**
 * Custom Matrix Account Data schemas used by Orbit to store 
 * user preferences that sync across devices.
 */

// Event Type: im.orbit.profile_extras
@Serializable
data class OrbitProfileExtras(
    val pronouns: String = "",
    val email: String = "",
    val aboutMe: String = "",
    val customStatus: String = "",
    val profileColor: String = "Purple",
    val avatarDecoration: String = "None",
    val profileEffect: String = "None",
    val bannerUrl: String? = null
)

// Event Type: im.orbit.privacy
@Serializable
data class OrbitPrivacySettings(
    val dmFilter: String = "My friends are nice",
    val allowDmsFromServerMembers: Boolean = true,
    val allowFriendRequestsFromEveryone: Boolean = true,
    val allowFriendRequestsFromServerMembers: Boolean = true,
    val messageRequestInbox: Boolean = true,
    val shareAnalytics: Boolean = false,
    val personalization: Boolean = false,
    val dataRequest: Boolean = false
)

// Event Type: im.orbit.family
@Serializable
data class OrbitFamilyCenterSettings(
    val restrictDms: Boolean = false,
    val restrictServerJoining: Boolean = false,
    val blockNsfw: Boolean = true,
    val restrictFriendRequests: Boolean = false,
    val enableScreenTimeLimits: Boolean = false,
    val dailyTimeLimit: String = "2 hours",
    val weeklyDigest: Boolean = true,
    val alertOnNewDm: Boolean = true
)

// Event Type: im.orbit.connections
@Serializable
data class OrbitConnectionSettings(
    val displayOnProfile: Boolean = true,
    val enableRichPresence: Boolean = true,
    val syncDisplayName: Boolean = false,
    val syncAvatar: Boolean = false,
    val linkedServices: Map<String, Boolean> = emptyMap() // Map of service name to enabled state
)
