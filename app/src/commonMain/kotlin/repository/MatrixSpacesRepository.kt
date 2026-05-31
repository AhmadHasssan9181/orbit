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

class MatrixSpacesRepository(private val context: MatrixContext) : OrbitSpacesRepository {

    override suspend fun getSpaces(): List<OrbitServer> {
        val allRooms = context.getAllRoomsSnapshot()
        if (allRooms.isEmpty()) return emptyList()

        val servers = mutableListOf<OrbitServer>()
        val trixnity = context.trixnityClient

        // 1. Identify Spaces
        val spaces = allRooms.filter { room ->
            val typeStr = room.createEventContent?.type?.toString()
            var isSpace = room.createEventContent?.type == CreateEventContent.RoomType.Space || 
                          typeStr?.contains("space", ignoreCase=true) == true
            if (!isSpace && trixnity != null) {
                try {
                    // Check for child events if type is not explicit
                    val children = trixnity.room.getAllState(room.roomId, ChildEventContent::class).first()
                    if (children.isNotEmpty()) isSpace = true
                } catch(e: Exception) {}
            }
            isSpace
        }
        
        // 2. Map DMs and Categorize Flat Rooms
        val dms = mutableListOf<de.connect2x.trixnity.client.store.Room>()
        val flatRooms = mutableListOf<de.connect2x.trixnity.client.store.Room>()
        val allChildrenIds = mutableSetOf<String>()

        if (trixnity != null) {
            spaces.forEach { space ->
                try {
                    val children = trixnity.room.getAllState(space.roomId, ChildEventContent::class).first()
                    allChildrenIds.addAll(children.keys)
                } catch(e: Exception) {}
            }
        }

        allRooms.forEach { room ->
            if (spaces.any { it.roomId == room.roomId }) return@forEach
            
            if (context.isDMRoom(room)) {
                dms.add(room)
            } else if (!allChildrenIds.contains(room.roomId.full)) {
                flatRooms.add(room)
            }
        }

        // 3. Build OrbitServer objects
        if (dms.isNotEmpty()) {
            servers.add(OrbitServer(
                id = "dm",
                name = "Direct Messages",
                iconColor = 0xFF7C6AF0.toInt(),
                description = "Your private conversations",
                isDM = true
            ))
        }

        spaces.forEach { space ->
            servers.add(OrbitServer(
                id = space.roomId.full,
                name = context.roomDisplayName(space),
                iconColor = context.hashToColor(space.roomId.full),
                description = space.name?.explicitName ?: "",
                isDM = false,
                avatarUrl = space.avatarUrl?.let { context.convertMxcUrl(it) }
            ))
        }

        if (flatRooms.isNotEmpty()) {
            servers.add(OrbitServer(
                id = "rooms",
                name = "Rooms",
                iconColor = 0xFF34D399.toInt(),
                description = "General rooms",
                isDM = false
            ))
        }

        // Update Metadata Cache for other repositories to use
        allRooms.forEach { room ->
            val isSpace = spaces.any { it.roomId == room.roomId }
            val isDM = dms.any { it.roomId == room.roomId }
            context.roomMetadataCache[room.roomId.full] = RoomMetadata(
                id = room.roomId.full,
                name = context.roomDisplayName(room),
                topic = "", // Topic requires extra state fetch if not in snapshot
                isDM = isDM,
                isSpace = isSpace,
                avatarUrl = room.avatarUrl?.let { context.convertMxcUrl(it) }
            )
        }

        return servers
    }

    override suspend fun joinSpace(roomIdOrAlias: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = trixnity.api.room.joinRoom(RoomId(roomIdOrAlias)).getOrThrow()
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSpace(name: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = trixnity.api.room.createRoom(
                name = name,
                creationContent = CreateEventContent(type = CreateEventContent.RoomType.Space)
            ).getOrThrow()
            context.roomListFlow.emit(getSpaces())
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/hierarchy/$spaceId", v1 = true)) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value == 200) {
                val body = response.body<JsonObject>()
                val rooms = body["rooms"]?.jsonArray ?: return emptyList()
                rooms.map { it.jsonObject }.map { room ->
                    val roomId = room["room_id"]?.jsonPrimitive?.content ?: ""
                    OrbitServer(
                        id = roomId,
                        name = room["name"]?.jsonPrimitive?.content ?: roomId.takeLast(8),
                        iconColor = context.hashToColor(roomId),
                        description = room["topic"]?.jsonPrimitive?.content ?: "",
                        isDM = false,
                        avatarUrl = room["avatar_url"]?.jsonPrimitive?.content?.let { context.convertMxcUrl(it) }
                    )
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun observeSpaces(): Flow<List<OrbitServer>> {
        return context.roomListFlow.asSharedFlow()
    }
}
