package repository

import kotlinx.coroutines.flow.Flow
import ui.model.*

/**
 * Repository interface for all data operations in Orbit.
 *
 * The UI layer calls these methods; implementations translate to/from
 * whatever backend (Matrix, custom API, local DB) is configured.
 */
interface OrbitRepository {

    // ─── Authentication ──────────────────────────────────────
    suspend fun login(
        identifier: String,       // username / email / mxid
        password: String,
        deviceName: String = "Orbit"
    ): AuthResult

    suspend fun register(
        username: String,
        password: String,
    ): RegisterResult

    suspend fun getCurrentUser(): OrbitUser?

    // ─── Servers / Spaces ────────────────────────────────────
    suspend fun getSpaces(): List<OrbitServer>

    suspend fun createSpace(name: String): Result<String>

    suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer>

    // ─── Channels / Rooms ────────────────────────────────────
    suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel>

    suspend fun createChannel(
        spaceId: String,
        name: String,
        type: ChannelType,
        topic: String = ""
    ): Result<String> // returns channelId (room_id)

    suspend fun getChannelById(channelId: String): OrbitChannel?

    // ─── Messages ────────────────────────────────────────────
    suspend fun getMessagesForChannel(
        channelId: String,
        limit: Int = 50,
        fromToken: String? = null
    ): List<OrbitMessage>

    suspend fun sendMessage(
        channelId: String,
        body: String
    ): Result<String> // returns eventId

    suspend fun editMessage(
        eventId: String,
        newBody: String
    ): Result<Unit>

    suspend fun deleteMessage(
        eventId: String
    ): Result<Unit>

    // ─── User Profile ────────────────────────────────────────
    suspend fun updateDisplayName(name: String): Result<Unit>
    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> // mxc:// URI
    suspend fun getUserProfile(userId: String): OrbitUser?

    // ─── Presence / Status ───────────────────────────────────
    suspend fun setPresence(status: UserStatus)
    suspend fun observePresence(userId: String): Flow<UserStatus>

    // ─── Real-time Events ────────────────────────────────────
    fun observeMessages(channelId: String): Flow<List<OrbitMessage>>
    fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>>
}

/**
 * Result of a successful login.
 */
data class AuthResult(
    val accessToken: String,
    val deviceId: String,
    val homeserverUrl: String,
    val userId: String
)

/**
 * Result of a registration attempt.
 */
data class RegisterResult(
    val authFlow: AuthFlow,
    val nextEndpoint: String
)

sealed class AuthFlow {
    data class Password(val user: String, val password: String) : AuthFlow()
}