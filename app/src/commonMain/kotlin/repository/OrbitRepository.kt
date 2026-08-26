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
    suspend fun loginWithSso(): AuthResult
    suspend fun login(identifier: String, password: String, deviceName: String = "Orbit"): AuthResult
    suspend fun register(username: String, password: String): RegisterResult
    suspend fun getCurrentUser(): OrbitUser?
    suspend fun logout()
}

interface OrbitSpacesRepository {
    suspend fun getSpaces(): List<OrbitServer>
    suspend fun joinSpace(roomIdOrAlias: String): Result<String>
    suspend fun createSpace(name: String): Result<String>
    suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer>
    fun observeSpaces(): Flow<List<OrbitServer>>
}

interface OrbitChannelsRepository {
    suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel>
    suspend fun createChannel(spaceId: String, name: String, type: ChannelType, topic: String = ""): Result<String>
    suspend fun getChannelById(channelId: String): OrbitChannel?
    suspend fun getRoomMembers(roomId: String): List<OrbitUser>
    fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>>
}

interface OrbitMessagesRepository {
    suspend fun getMessagesForChannel(channelId: String, limit: Int = 50, fromToken: String? = null): List<OrbitMessage>
    suspend fun sendMessage(channelId: String, body: String): Result<String>
    suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String>
    suspend fun uploadMedia(bytes: ByteArray, mimeType: String, filename: String): Result<String>
    suspend fun sendImageMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, width: Int? = null, height: Int? = null, size: Long? = null): Result<String>
    suspend fun sendFileMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, size: Long? = null): Result<String>
    suspend fun sendAudioMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, durationMs: Int? = null, size: Long? = null): Result<String>
    suspend fun editMessage(roomId: String, eventId: String, newBody: String): Result<Unit>
    suspend fun deleteMessage(roomId: String, eventId: String): Result<Unit>
    suspend fun sendReadReceipt(roomId: String, eventId: String)
    suspend fun registerPusher(pushKey: String, appId: String, url: String)
    fun observeMessages(channelId: String): Flow<List<OrbitMessage>>
    // Other users' public read receipts in a room: userId -> eventId of their latest read message.
    fun observeReadReceipts(roomId: String): Flow<Map<String, String>>
}

interface OrbitUsersRepository {
    suspend fun updateDisplayName(name: String): Result<Unit>
    suspend fun uploadAvatar(imageBytes: ByteArray): Result<String>
    suspend fun getUserProfile(userId: String): OrbitUser?
    suspend fun setPresence(status: UserStatus)
    suspend fun observePresence(userId: String): Flow<UserStatus>
    suspend fun setTyping(roomId: String, isTyping: Boolean)
    fun observeTyping(roomId: String): Flow<List<String>>
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
    suspend fun isRoomEncrypted(roomId: String): Boolean
    fun startCallMediaKeyExchange(roomId: String, livekitIdentity: String)
    fun stopCallMediaKeyExchange()
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
}

data class AuthResult(
    val accessToken: String,
    val deviceId: String,
    val homeserverUrl: String,
    val userId: String
)

data class RegisterResult(
    val authFlow: AuthFlow,
    val nextEndpoint: String
)

data class TurnServer(
    val uris: List<String>,
    val username: String? = null,
    val password: String? = null
)

sealed class AuthFlow {
    data class Password(val user: String, val password: String) : AuthFlow()
}

