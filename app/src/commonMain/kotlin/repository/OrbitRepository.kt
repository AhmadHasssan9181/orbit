package repository

import kotlinx.coroutines.flow.Flow
import ui.model.*

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

interface OrbitAuthRepository {
    val authStatus: Flow<AuthStatus>
    val activeDeviceVerification: Flow<Any?>? get() = null

    /**
     * Starts a SAS verification with another one of your own devices, the user
     * identified by [theirUserId].
     *
     * [activeDeviceVerification] flows with the result, and
     * [ui.screen.auth.VerificationOverlay] renders every state of it, including
     * `OwnRequest` -- the state you sit in while the other device decides. That
     * overlay was already complete; what was missing was the one call that starts
     * the thing, so Orbit could accept a verification but could never begin one. A
     * user who installs Orbit on a second machine had no way to verify it short of
     * the other client asking first.
     *
     * Fails with a message worth showing if [theirUserId] is not a well-formed
     * Matrix ID, so the caller can surface it rather than discovering an empty
     * state flow and no explanation.
     */
    suspend fun startDeviceVerification(theirUserId: String): Result<Unit> =
        Result.failure(IllegalStateException("Verification is not available"))
    suspend fun loginWithSso(): AuthResult
    suspend fun login(identifier: String, password: String, deviceName: String = "Orbit"): AuthResult
    /**
     * Register an account.
     *
     * [auth] and [session] carry a user-interactive-auth retry: pass the stage
     * answers plus the session the homeserver handed back. Most self-hosted
     * servers require at least one stage (typically `m.login.dummy`) before they
     * will issue credentials.
     */
    suspend fun register(
        username: String,
        password: String,
        auth: Map<String, Map<String, String>> = emptyMap(),
        session: String? = null
    ): RegisterResult

    /**
     * The `m.login.*` flow types this homeserver offers, e.g. `m.login.password`,
     * `m.login.sso`. Empty when discovery failed. Lets the UI hide an SSO button
     * the server does not support instead of offering one that always fails.
     */
    suspend fun getLoginFlows(): List<String>

    /**
     * Resolves a Matrix ID or domain to a homeserver base URL via
     * `/.well-known/matrix/client`, then validates it against
     * `/_matrix/client/versions`.
     *
     * The "continue in browser" flow hands back a URL rather than an MXID, so
     * the user often has a domain and not a base URL -- and a self-hosted server
     * is usually reached by a name that is not the same as its base URL.
     */
    suspend fun discoverHomeserver(serverNameOrId: String): ServerDiscoveryResult

    suspend fun getCurrentUser(): OrbitUser?
    suspend fun logout()
}

interface OrbitSpacesRepository {
    suspend fun getSpaces(): List<OrbitServer>
    suspend fun joinSpace(roomIdOrAlias: String): Result<String>
    suspend fun createSpace(name: String, isPublic: Boolean = false, category: String = "Community", avatarBytes: ByteArray? = null): Result<String>
    suspend fun leaveSpace(spaceId: String): Result<Unit>
    suspend fun deleteSpace(spaceId: String): Result<Unit>
    suspend fun isSpacePublic(spaceId: String): Boolean
    suspend fun makeSpacePublic(spaceId: String): Result<Unit>
    suspend fun inviteUserToSpace(spaceId: String, userId: String): Result<Unit>
    suspend fun getIncomingInvites(): List<ui.model.ServerInvite>
    suspend fun acceptInvite(spaceId: String): Result<Unit>
    suspend fun declineInvite(spaceId: String): Result<Unit>
    suspend fun getPublicServers(searchTerm: String = ""): List<ui.screen.server.DiscoverServer>
    suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer>
    fun observeSpaces(): Flow<List<OrbitServer>>
    suspend fun uploadSpaceBanner(spaceId: String, imageBytes: ByteArray): Result<String>
    suspend fun uploadSpaceAvatar(spaceId: String, imageBytes: ByteArray): Result<String>
    suspend fun updateSpaceName(spaceId: String, name: String): Result<Unit>
}

interface OrbitChannelsRepository {
    suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel>
    suspend fun createChannel(spaceId: String, name: String, type: ChannelType, topic: String = "", isPublic: Boolean = false): Result<String>
    suspend fun getChannelById(channelId: String): OrbitChannel?
    suspend fun getRoomMembers(roomId: String): List<OrbitUser>

    /**
     * Resolves the OTHER member of each given DM room, so the DM list can show
     * a real avatar and real presence instead of a seeded initial and a
     * hard-coded "online". Returns roomId -> OrbitUser; rooms whose other member
     * cannot be resolved are simply absent, and the caller falls back to the
     * channel's own display name.
     *
     * Group rooms (3+ joined members) resolve to nobody, so a group accidentally
     * passed in simply will not appear in the result.
     */
    suspend fun resolveDmContacts(roomIds: Collection<String>): Map<String, OrbitUser>
    fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>>
    suspend fun joinChannelIfNeeded(channelId: String): Result<String>
    suspend fun updateChannel(spaceId: String, channelId: String, name: String, type: ChannelType): Result<Unit>
    suspend fun deleteChannel(spaceId: String, channelId: String): Result<Unit>
}

interface OrbitMessagesRepository {
    suspend fun getMessagesForChannel(channelId: String, limit: Int = 50, fromToken: String? = null): List<OrbitMessage>
    suspend fun sendMessage(channelId: String, body: String): Result<String>
    suspend fun sendEmoteMessage(channelId: String, body: String): Result<String>
    suspend fun sendReplyMessage(roomId: String, replyToEventId: String, body: String): Result<String>
    suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String>
    suspend fun uploadMedia(bytes: ByteArray, mimeType: String, filename: String): Result<String>
    suspend fun sendImageMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, width: Int? = null, height: Int? = null, size: Long? = null): Result<String>
    suspend fun sendFileMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, size: Long? = null): Result<String>
    suspend fun sendAudioMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, durationMs: Int? = null, size: Long? = null): Result<String>
    suspend fun editMessage(roomId: String, eventId: String, newBody: String): Result<Unit>
    suspend fun deleteMessage(roomId: String, eventId: String): Result<Unit>
    suspend fun pinMessage(roomId: String, eventId: String, pinned: Boolean): Result<Unit>
    suspend fun getPinnedMessageIds(roomId: String): Result<List<String>>
    suspend fun sendReadReceipt(roomId: String, eventId: String)
    suspend fun registerPusher(pushKey: String, appId: String, url: String)
    fun observeMessages(channelId: String): Flow<List<OrbitMessage>>
    // Live arrivals for EVERY room as (matrixRoomId, message); drives unread counts in the shell.
    fun observeIncomingMessages(): Flow<Pair<String, OrbitMessage>>
    // Other users' public read receipts in a room: userId -> eventId of their latest read message.
    fun observeReadReceipts(roomId: String): Flow<Map<String, String>>
}

// Server-truth moderation facts for one member, snapshotted per member-list load.
data class MemberRoleInfo(
    val userId: String,
    val powerLevel: Long,
    val canKick: Boolean,
    val canBan: Boolean,
    val maxSettablePowerLevel: Long
)

interface OrbitUsersRepository {
    suspend fun updateDisplayName(name: String): Result<Unit>
    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String>

    /**
     * Uploads [imageBytes] and returns a URL that can be handed to an image loader
     * as-is -- http(s), never `mxc://`.
     *
     * That is not the same contract as [uploadAvatar], which returns the raw mxc URI
     * because the avatar is written to a profile field the server converts for
     * everyone else. The banner lives in this client's own account data, so nothing
     * downstream converts it and the caller would be left holding something only the
     * repository can read.
     *
     * Does not set any profile field. Callers store the returned URL themselves.
     */
    suspend fun uploadBanner(imageBytes: ByteArray): Result<String>
    suspend fun getUserProfile(userId: String): OrbitUser?
    suspend fun setPresence(status: UserStatus)
    suspend fun observePresence(userId: String): Flow<UserStatus>
    suspend fun setTyping(roomId: String, isTyping: Boolean)
    fun observeTyping(roomId: String): Flow<List<String>>
    suspend fun getMemberRoles(roomId: String, memberIds: List<String>): Map<String, MemberRoleInfo>
    suspend fun kickUser(roomId: String, userId: String, reason: String?): Result<Unit>
    suspend fun banUser(roomId: String, userId: String, reason: String?): Result<Unit>
    suspend fun setUserPowerLevel(roomId: String, userId: String, level: Long): Result<Unit>
    suspend fun searchUsers(query: String, limit: Int = 10): List<OrbitUser>
}

interface OrbitCallsRepository {
    suspend fun getTurnServers(): Result<List<TurnServer>>
    suspend fun configureCallEndpoints(sfuUrlOverride: String?)
    suspend fun sendCallEvent(channelId: String, eventType: String, content: kotlinx.serialization.json.JsonObject): Result<String>
    fun observeCallEvents(): Flow<CallEvent>
    fun observeActiveCall(channelId: String): Flow<CallEvent.Invite?>
    fun observeActiveGroupCall(channelId: String): Flow<String?>
    fun observeCallMembers(channelId: String): Flow<List<OrbitUser>>
    suspend fun resolveGroupCallJoinTarget(roomId: String): ResolvedGroupCall
    suspend fun startGroupCall(roomId: String, callId: String)
    suspend fun endGroupCall(roomId: String, callId: String)
    suspend fun joinGroupCall(roomId: String, callId: String)
    suspend fun updateGroupCallMembershipCapabilities(roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String? = null): Boolean
    suspend fun leaveGroupCall(roomId: String, callId: String)
    suspend fun fetchLiveKitToken(roomId: String, callId: String, livekitServiceUrl: String): livekit.LiveKitAuthResponse
    val activeCallsFlow: kotlinx.coroutines.flow.StateFlow<Map<String, String>>

    // matrixRTC E2EE media keys (encrypted-room calls): per-device roster for the
    // olm key exchange, room encryption probe, and exchange lifecycle control.
    fun observeCallDevices(channelId: String): kotlinx.coroutines.flow.Flow<Set<livekit.CallDevice>>
    /**
     * Tri-state, not Boolean: "could not determine" must not read as "not encrypted",
     * because the caller uses this to decide whether to publish cleartext media at
     * the SFU. See webrtc.RoomEncryptionVerdict.
     */
    suspend fun isRoomEncrypted(roomId: String): webrtc.RoomEncryptionVerdict
    fun startCallMediaKeyExchange(roomId: String, livekitIdentity: String)
    fun stopCallMediaKeyExchange()
    // Stops the 3s per-room roster poller that feeds BOTH observeCallMembers and
    // observeCallDevices. Must be called on any path that ends or aborts a call.
    fun stopCallRosterPolling(channelId: String)
}

data class ResolvedGroupCall(
    val exists: Boolean,
    val callId: String?,
    val serviceUrl: String?,
    val source: String?
) {
    companion object {
        val None = ResolvedGroupCall(exists = false, callId = null, serviceUrl = null, source = null)
    }
}

interface OrbitSyncRepository {
    suspend fun <T> setAccountData(eventType: String, data: T, serializer: kotlinx.serialization.KSerializer<T>): Result<Unit>
    fun <T> observeAccountData(eventType: String, deserializer: kotlinx.serialization.KSerializer<T>): Flow<T?>
    fun startSync()
    fun stopSync()
}

interface OrbitRepository :
    OrbitAuthRepository,
    OrbitSpacesRepository,
    OrbitChannelsRepository,
    OrbitMessagesRepository,
    OrbitUsersRepository,
    OrbitCallsRepository,
    OrbitSyncRepository

sealed class AuthStatus {
    object Authenticated : AuthStatus()
    object Unauthenticated : AuthStatus()
    data class Error(val message: String) : AuthStatus()
}

sealed class CallEvent {
    abstract val roomId: String
    abstract val callId: String
    abstract val senderId: String
    abstract val partyId: String?
    data class Invite(override val roomId: String, override val callId: String, val sdp: String, override val senderId: String, override val partyId: String? = null, val lifetimeMs: Long? = null) : CallEvent()
    data class Answer(override val roomId: String, override val callId: String, val sdp: String, override val senderId: String, override val partyId: String? = null) : CallEvent()
    data class Negotiate(override val roomId: String, override val callId: String, val sdp: String, val type: String, override val senderId: String, override val partyId: String? = null) : CallEvent()
    data class Candidate(override val roomId: String, override val callId: String, val sdp: String, val sdpMid: String, val sdpMLineIndex: Int, override val senderId: String, override val partyId: String? = null) : CallEvent()
    data class Hangup(override val roomId: String, override val callId: String, override val senderId: String, override val partyId: String? = null) : CallEvent()
    // Orbit-specific raise-hand signal (io.orbit.raise_hand room event).
    data class HandRaised(override val roomId: String, override val callId: String, override val senderId: String, val raised: Boolean, override val partyId: String? = null) : CallEvent()
}

data class AuthResult(
    val accessToken: String,
    val deviceId: String,
    val homeserverUrl: String,
    val userId: String
)

data class RegisterResult(
    val authFlow: AuthFlow,
    val nextEndpoint: String,
    /**
     * The next user-interactive-auth stage the homeserver wants, when it did not
     * register us outright. A real self-hosted Synapse answers the first
     * `/register` with a 401 and (usually) an `m.login.dummy` stage, so this is
     * the normal path, not an error.
     */
    val stage: AuthStage = AuthStage.Complete,

    /**
     * The opaque `session` the homeserver handed back, to be echoed on the next
     * `/register` attempt along with the stage answers. Null when the server did
     * not ask for another step.
     */
    val authSession: String? = null
)

data class TurnServer(
    val uris: List<String>,
    val username: String? = null,
    val password: String? = null
)

sealed class AuthFlow {
    data class Password(val user: String, val password: String) : AuthFlow()
}

