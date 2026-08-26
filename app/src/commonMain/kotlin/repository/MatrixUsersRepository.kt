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



class MatrixUsersRepository(private val context: MatrixContext) : OrbitUsersRepository {

    override suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.setProfileField(ProfileField.DisplayName(name)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val mxcUrl = trixnity.media.uploadMedia(
                trixnity.media.prepareUploadMedia(kotlinx.coroutines.flow.flowOf(imageBytes), ContentType.Image.PNG)
            ).getOrThrow()
            trixnity.setProfileField(ProfileField.AvatarUrl(mxcUrl)).getOrThrow()
            Result.success(mxcUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserProfile(userId: String): OrbitUser? {
        return context.fetchUserProfile(userId)
    }

    override suspend fun setPresence(status: UserStatus) {
        try {
            val trixnity = context.trixnityClient ?: return
            val presence = when (status) {
                UserStatus.ONLINE -> de.connect2x.trixnity.core.model.events.m.Presence.ONLINE
                UserStatus.IDLE -> de.connect2x.trixnity.core.model.events.m.Presence.UNAVAILABLE
                UserStatus.DO_NOT_DISTURB -> de.connect2x.trixnity.core.model.events.m.Presence.UNAVAILABLE
                UserStatus.OFFLINE -> de.connect2x.trixnity.core.model.events.m.Presence.OFFLINE
            }
            trixnity.api.user.setPresence(trixnity.userId, presence).getOrThrow()
        } catch (_: Exception) {}
    }

    override suspend fun observePresence(userId: String): Flow<UserStatus> {
        val trixnity = context.trixnityClient ?: return flowOf(UserStatus.OFFLINE)
        return trixnity.user.getPresence(UserId(userId)).map { presence ->
            when (presence?.presence) {
                de.connect2x.trixnity.core.model.events.m.Presence.ONLINE -> UserStatus.ONLINE
                de.connect2x.trixnity.core.model.events.m.Presence.UNAVAILABLE -> UserStatus.IDLE
                de.connect2x.trixnity.core.model.events.m.Presence.OFFLINE -> UserStatus.OFFLINE
                else -> UserStatus.ONLINE
            }
        }
    }

    override suspend fun setTyping(roomId: String, isTyping: Boolean) {
        context.trixnityClient?.api?.room?.setTyping(
            RoomId(roomId),
            context.trixnityClient!!.userId,
            isTyping,
            30_000
        )
    }

    override fun observeTyping(roomId: String): Flow<List<String>> {
        val trixnity = context.trixnityClient ?: return flowOf(emptyList())
        return trixnity.room.usersTyping.map { map ->
            val content = map[RoomId(roomId)]
            if (content != null) {
                try {
                    trixnity.api.json.encodeToJsonElement(content)
                        .jsonObject["user_ids"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
        }
    }

    override suspend fun getMemberRoles(roomId: String, memberIds: List<String>): Map<String, MemberRoleInfo> {
        val trixnity = context.trixnityClient ?: return emptyMap()
        val service = trixnity.user
        val result = mutableMapOf<String, MemberRoleInfo>()
        for (id in memberIds) {
            val powerLevel = runCatching { service.getPowerLevel(RoomId(roomId), UserId(id)).first() }.getOrNull()
            val pl = when (powerLevel) {
                is de.connect2x.trixnity.client.user.PowerLevel.User -> powerLevel.level
                is de.connect2x.trixnity.client.user.PowerLevel.Creator -> 100L
                else -> 0L
            }
            val canKick = runCatching { service.canKickUser(RoomId(roomId), UserId(id)).first() }.getOrDefault(false)
            val canBan = runCatching { service.canBanUser(RoomId(roomId), UserId(id)).first() }.getOrDefault(false)
            val maxSettable = runCatching {
                (service.canSetPowerLevelToMax(RoomId(roomId), UserId(id)).first()
                        as? de.connect2x.trixnity.client.user.PowerLevel.User)?.level
            }.getOrNull() ?: 0L
            result[id] = MemberRoleInfo(
                userId = id,
                powerLevel = pl,
                canKick = canKick,
                canBan = canBan,
                maxSettablePowerLevel = maxSettable
            )
        }
        return result
    }

    override suspend fun kickUser(roomId: String, userId: String, reason: String?): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.kickUser(RoomId(roomId), UserId(userId), reason).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun banUser(roomId: String, userId: String, reason: String?): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.banUser(RoomId(roomId), UserId(userId), reason).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setUserPowerLevel(roomId: String, userId: String, level: Long): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val service = trixnity.user
            val maxSettable = runCatching {
                (service.canSetPowerLevelToMax(RoomId(roomId), UserId(userId)).first()
                        as? de.connect2x.trixnity.client.user.PowerLevel.User)?.level
            }.getOrNull()
            if (maxSettable != null && level > maxSettable) {
                return Result.failure(IllegalStateException("Insufficient permission to set power level $level"))
            }
            val content = fetchPowerLevelsContent(roomId)
            val newContent = content.copy(users = content.users + (UserId(userId) to level))
            trixnity.api.room.sendStateEvent(RoomId(roomId), newContent, "").getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // Read-modify-write source for m.room.power_levels. Missing event → all-defaults
    // content so a first write still preserves spec defaults.
    private suspend fun fetchPowerLevelsContent(roomId: String): PowerLevelsEventContent {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state/m.room.power_levels")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value !in 200..299) return PowerLevelsEventContent()
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            fun longAt(key: String, default: Long): Long = obj[key]?.jsonPrimitive?.longOrNull ?: default
            val users = obj["users"]?.jsonObject?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L } ?: emptyMap()
            val events = obj["events"]?.jsonObject?.mapKeys {
                EventType(UnknownEventContent::class, it.key)
            }?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L } ?: emptyMap()
            val notifications = obj["notifications"]?.jsonObject?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L } ?: emptyMap()
            PowerLevelsEventContent(
                ban = longAt("ban", PowerLevelsEventContent.BAN_DEFAULT),
                events = events,
                eventsDefault = longAt("events_default", PowerLevelsEventContent.EVENTS_DEFAULT),
                invite = longAt("invite", PowerLevelsEventContent.INVITE_DEFAULT),
                kick = longAt("kick", PowerLevelsEventContent.KICK_DEFAULT),
                redact = longAt("redact", PowerLevelsEventContent.REDACT_DEFAULT),
                stateDefault = longAt("state_default", PowerLevelsEventContent.STATE_DEFAULT),
                users = users.mapKeys { UserId(it.key) },
                usersDefault = longAt("users_default", PowerLevelsEventContent.USERS_DEFAULT),
                notifications = notifications,
                externalUrl = obj["external_url"]?.jsonPrimitive?.content ?: ""
            )
        } catch (e: Exception) {
            PowerLevelsEventContent()
        }
    }
}

