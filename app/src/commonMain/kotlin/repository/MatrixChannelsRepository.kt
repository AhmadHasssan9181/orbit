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
import kotlinx.coroutines.flow.combine
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



class MatrixChannelsRepository(private val context: MatrixContext) : OrbitChannelsRepository {

    override suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel> {
        val allRooms = context.getAllRoomsSnapshot()
        if (spaceId == "dm") {
            return allRooms.filter { it.isDirect }.map { room ->
                val otherMember = context.directInviteeForRoom(room.roomId) ?: ""
                val profile = context.fetchUserProfile(otherMember)
                val explicitName = room.name?.explicitName ?: profile?.displayName ?: otherMember.takeLast(8)
                val avatar = profile?.avatarUrl
                
                OrbitChannel(
                    id = room.roomId.full,
                    name = explicitName,
                    type = ChannelType.TEXT,
                    categoryId = "dm",
                    matrixRoomId = room.roomId.full,
                    avatarUrl = avatar,
                    activeCallUrl = context.globalActiveCalls.value[room.roomId.full]
                )
            }
        }

        val trixnity = context.trixnityClient
        var spaceChildren = setOf<String>()
        if (trixnity != null && spaceId != "rooms") {
            try {
                spaceChildren = trixnity.room.getAllState(RoomId(spaceId), ChildEventContent::class).first().keys
            } catch(e: Exception) {}
        }

        val channels = mutableListOf<OrbitChannel>()
        for (room in allRooms) {
            if (room.isDirect) continue

            val isSpace = room.createEventContent?.type == CreateEventContent.RoomType.Space ||
                          room.createEventContent?.type?.toString()?.contains("space", ignoreCase=true) == true ||
                          (trixnity != null && try { trixnity.room.getAllState(room.roomId, ChildEventContent::class).first().isNotEmpty() } catch(e:Exception){false})
            if (isSpace) continue

            val belongsToSpace = spaceChildren.contains(room.roomId.full)
            if ((spaceId == "rooms" && !belongsToSpace) || (spaceId != "rooms" && belongsToSpace)) {
                val rawName = context.roomDisplayName(room)
                val type = context.inferChannelType(rawName)
                val cleanName = context.orbitChannelName(rawName, type)

                channels.add(OrbitChannel(
                    id = room.roomId.full,
                    name = cleanName,
                    type = type,
                    categoryId = "general",
                    matrixRoomId = room.roomId.full,
                    activeCallUrl = context.globalActiveCalls.value[room.roomId.full]
                ))
            }
        }

        // Discord-like: Always ensure a "General Voice" channel exists if it's a real space
        if (spaceId != "rooms" && spaceId != "dm" && channels.none { it.type == ChannelType.VOICE }) {
            val firstTextChannel = channels.firstOrNull { it.type == ChannelType.TEXT }
            channels.add(0, OrbitChannel(
                id = "general_voice_$spaceId",
                name = "General Voice",
                type = ChannelType.VOICE,
                categoryId = "general",
                topic = "Join here for voice and video",
                matrixRoomId = firstTextChannel?.matrixRoomId ?: spaceId
            ))
        }

        return channels.sortedBy { it.name }
    }

    override suspend fun createChannel(
        spaceId: String,
        name: String,
        type: ChannelType,
        topic: String
    ): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val actualName = if (type == ChannelType.VOICE) "$name-voice-channel" else name

            val response = trixnity.api.room.createRoom(
                name = actualName,
                topic = topic.ifBlank { if (type == ChannelType.VOICE) "Voice channel" else "" },
                preset = de.connect2x.trixnity.clientserverapi.model.room.CreateRoom.Request.Preset.TRUSTED_PRIVATE,
                powerLevelContentOverride = if (type == ChannelType.VOICE) {
                    de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent(
                        users = mapOf<de.connect2x.trixnity.core.model.UserId, Long>(trixnity.userId to 100L),
                        usersDefault = 0L,
                        eventsDefault = 0L,
                        stateDefault = 0L,
                        events = mapOf<de.connect2x.trixnity.core.model.events.EventType, Long>(
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "m.call.member") to 0L,
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "org.matrix.msc3401.call") to 0L
                        )
                    )
                } else null
            ).getOrThrow()
            
            val newRoomId = response.full

            if (spaceId != "rooms" && spaceId != "dm") {
                trixnity.api.room.sendStateEvent(
                    RoomId(spaceId),
                    ChildEventContent(via = setOf(context.homeserverUrl.removePrefix("https://").removePrefix("http://"))),
                    newRoomId
                ).getOrThrow()
            }

            context.roomListFlow.emit(context.roomListFlow.replayCache.firstOrNull() ?: emptyList())
            Result.success(newRoomId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChannelById(channelId: String): OrbitChannel? {
        val trixnity = context.trixnityClient ?: return null
        val room = trixnity.room.getById(RoomId(channelId)).first() ?: return null
        val rawName = context.roomDisplayName(room)
        val type = context.inferChannelType(rawName)
        val cleanName = context.orbitChannelName(rawName, type)

        return OrbitChannel(
            id = room.roomId.full,
            name = cleanName,
            type = type,
            categoryId = "general",
            matrixRoomId = room.roomId.full,
            activeCallUrl = context.globalActiveCalls.value[room.roomId.full]
        )
    }

    override suspend fun getRoomMembers(roomId: String): List<OrbitUser> {
        val trixnity = context.trixnityClient ?: return emptyList()
        return try {
            val members = trixnity.api.room.getJoinedMembers(RoomId(roomId)).getOrThrow()
            members.joined.keys.mapNotNull { context.fetchUserProfile(it.full) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>> {
        val repository = this
        return combine(
            context.roomListFlow,
            context.globalActiveCalls
        ) { _, activeCalls ->
            val channels = repository.getChannelsForSpace(spaceId)
            val result = mutableListOf<OrbitChannel>()
            for (ch in channels) {
                val callUrl = activeCalls[ch.matrixRoomId] ?: activeCalls[ch.id]
                val channelWithCall = ch.copy(activeCallUrl = callUrl)
                result.add(channelWithCall)
                
                if (callUrl != null && channelWithCall.type == ChannelType.TEXT) {
                    result.add(
                        OrbitChannel(
                            id = "voice_${ch.id}",
                            name = "${ch.name} (Call)",
                            type = ChannelType.VOICE,
                            categoryId = ch.categoryId,
                            activeCallUrl = callUrl,
                            matrixRoomId = ch.id
                        )
                    )
                }
            }
            result
        }
    }
}

