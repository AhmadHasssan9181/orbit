package repository


import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.media
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.room.message.roomMessageBuilder
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.eventId
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.store.roomId
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.clientserverapi.model.media.Media
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction
import de.connect2x.trixnity.clientserverapi.model.user.ProfileField
import de.connect2x.trixnity.clientserverapi.model.user.avatarUrl
import de.connect2x.trixnity.clientserverapi.model.user.displayName
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.m.call.CallEventContent
import de.connect2x.trixnity.core.model.events.m.call.Purpose
import de.connect2x.trixnity.core.model.events.m.call.StreamMetadata
import de.connect2x.trixnity.core.model.events.m.ReactionEventContent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.AudioInfo
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.FileInfo
import de.connect2x.trixnity.core.model.events.m.room.ImageInfo
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent.FileBased
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent.TextBased
import de.connect2x.trixnity.core.model.events.m.space.ChildEventContent
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ui.model.ChannelType
import ui.model.MessageContent
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.model.displayText
import util.getMediaStorePath
import util.getRoomDatabaseBuilder
import util.performSsoLogin
import kotlin.time.Duration.Companion.seconds

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*



class LiveMessageState(
    val flow: MutableSharedFlow<List<OrbitMessage>>,
    val messages: MutableList<OrbitMessage> = mutableListOf()
)

class MatrixContext(val homeserverUrl: String) {
    var accessToken: String? = CredentialManager.accessToken
    var userId: String? = CredentialManager.userId
    var trixnityClient: MatrixClient? = null

    val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(Logging) {
            level = LogLevel.NONE
        }
    }

    val roomListFlow = MutableSharedFlow<List<OrbitServer>>(replay = 1)
    val callEventsFlow = MutableSharedFlow<CallEvent>(extraBufferCapacity = 10)
    val globalActiveCalls = MutableStateFlow<Map<String, String>>(emptyMap())
    val authStatusFlow = MutableStateFlow<AuthStatus>(AuthStatus.Authenticated)

    val liveMessageStates = mutableMapOf<String, LiveMessageState>()
    val activeCalls = mutableMapOf<String, MutableStateFlow<CallEvent.Invite?>>()
    val activeGroupCalls = mutableMapOf<String, MutableStateFlow<String?>>()
    val activeCallMembers = mutableMapOf<String, MutableStateFlow<List<OrbitUser>>>()
    val accountDataCache = mutableMapOf<String, MutableStateFlow<Any?>>()

    fun fullUrl(path: String, v1: Boolean = false): String {
        val prefix = if (v1) "/_matrix/client/v1" else "/_matrix/client/v3"
        return "${homeserverUrl.trimEnd('/')}${prefix}$path"
    }

    fun hashToColor(input: String): Int {
        val hash = input.hashCode()
        val r = (hash and 0xFF)
        val g = ((hash shr 8) and 0xFF)
        val b = ((hash shr 16) and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun convertMxcUrl(mxcUrl: String): String {
        if (mxcUrl.isBlank()) return ""
        // upload:// URIs are Trixnity internal cache references that haven't been
        // uploaded to the server yet. Return empty so Coil shows nothing gracefully
        // until the real mxc:// URL arrives via the next sync/timeline refresh.
        if (mxcUrl.startsWith("upload://")) return ""

        val pathParts = if (mxcUrl.startsWith("mxc://")) {
            mxcUrl.removePrefix("mxc://").split("/")
        } else if (mxcUrl.contains("/_matrix/media/v3/download/")) {
            mxcUrl.substringAfter("/_matrix/media/v3/download/").split("?").first().split("/")
        } else if (mxcUrl.contains("/_matrix/client/v1/media/download/")) {
            mxcUrl.substringAfter("/_matrix/client/v1/media/download/").split("?").first().split("/")
        } else {
            return mxcUrl
        }

        if (pathParts.size != 2) return mxcUrl

        val hs = if (homeserverUrl.startsWith("http")) homeserverUrl.trimEnd('/') else "https://${homeserverUrl.trimEnd('/')}"
        return "$hs/_matrix/client/v1/media/download/${pathParts[0]}/${pathParts[1]}"
    }

    fun extractRoomFromEventId(eventId: String): String {
        val parts = eventId.split(":")
        return parts.first().removePrefix("!")
    }

    suspend fun getAllRoomsSnapshot(): List<de.connect2x.trixnity.client.store.Room> {
        val trixnity = trixnityClient ?: return emptyList()
        val allRoomsMap = trixnity.room.getAll().first()
        val snapshot = mutableListOf<de.connect2x.trixnity.client.store.Room>()
        for (roomFlow in allRoomsMap.values) {
            roomFlow.first()?.let { snapshot.add(it) }
        }
        return snapshot
    }

    fun roomDisplayName(room: de.connect2x.trixnity.client.store.Room): String {
        return room.name?.explicitName ?: room.name?.heroes?.firstOrNull()?.localpart ?: room.roomId.full.takeLast(8)
    }

    fun inferChannelType(roomName: String): ui.model.ChannelType {
        return if (roomName.contains("voice", ignoreCase = true)) ui.model.ChannelType.VOICE else ui.model.ChannelType.TEXT
    }

    fun orbitChannelName(roomName: String, type: ui.model.ChannelType): String {
        if (type != ui.model.ChannelType.VOICE) return roomName
        val cleaned = roomName.replace(
            Regex("(?i)([-_\\s]+voice(?:[-_\\s]+channel)?)$"),
            ""
        ).trim('-', '_', ' ')
        return cleaned.ifBlank { roomName }
    }

    suspend fun fetchUserProfile(targetUserId: String): OrbitUser? {
        return try {
            val trixnity = trixnityClient ?: return null
            val profile = trixnity.api.user.getProfile(UserId(targetUserId)).getOrThrow()
            OrbitUser(
                id = targetUserId,
                username = targetUserId.split(":").first().removePrefix("@"),
                displayName = profile.displayName ?: targetUserId,
                avatarColor = hashToColor(targetUserId),
                status = UserStatus.ONLINE,
                isBot = false,
                avatarUrl = profile.avatarUrl?.let { convertMxcUrl(it.toString()) }
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun directInviteeForRoom(roomId: RoomId): String? {
        val trixnity = trixnityClient ?: return null
        return runCatching {
            val ownUserIds = setOfNotNull(userId, trixnity.userId.full)
            val joined = trixnity.api.room.getJoinedMembers(roomId).getOrThrow().joined.keys
                .map { it.full }
                .filterNot { it in ownUserIds }
            joined.singleOrNull()
        }.getOrNull()
    }
}

