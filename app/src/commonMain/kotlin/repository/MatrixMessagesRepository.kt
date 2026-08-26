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
import de.connect2x.trixnity.client.room.message.emote
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.RoomUserStore
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
import de.connect2x.trixnity.core.model.events.m.ReceiptType
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.room.AudioInfo
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.FileInfo
import de.connect2x.trixnity.core.model.events.m.room.ImageInfo
import de.connect2x.trixnity.core.model.events.m.room.PinnedEventsEventContent
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
import kotlinx.coroutines.flow.flatMapLatest
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



class MatrixMessagesRepository(val context: MatrixContext) : OrbitMessagesRepository {

    override suspend fun getMessagesForChannel(channelId: String, limit: Int, fromToken: String?): List<OrbitMessage> {
        val trixnity = context.trixnityClient ?: return emptyList()
        return try {
            val lastEventId = fromToken?.let { EventId(it) } ?: trixnity.room.getById(RoomId(channelId)).first()?.lastEventId
            if (lastEventId == null) return emptyList()

            val events = mutableListOf<OrbitMessage>()
            val flow = trixnity.room.getTimelineEvents(
                roomId = RoomId(channelId),
                startFrom = lastEventId,
                direction = de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS
            )
            
            flow.take(limit).collect { flowEvent ->
                val timelineEvent = flowEvent.first()
                timelineEventToOrbitMessage(timelineEvent)?.let { events.add(it) }
            }
            val pinnedIds = fetchPinnedEventIds(channelId).toHashSet()
            events.reversed().map { it.copy(isPinned = it.id in pinnedIds) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun sendMessage(channelId: String, body: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                text(body)
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendEmoteMessage(channelId: String, body: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                emote(body)
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendReplyMessage(roomId: String, replyToEventId: String, body: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(roomId)) {
                content(
                    TextBased.Text(
                        body = body,
                        relatesTo = RelatesTo.Reply(RelatesTo.ReplyTo(EventId(replyToEventId)))
                    )
                )
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pinMessage(roomId: String, eventId: String, pinned: Boolean): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val current = fetchPinnedEventIds(roomId).toMutableSet()
            if (pinned) current.add(eventId) else current.remove(eventId)
            trixnity.api.room.sendStateEvent(
                RoomId(roomId),
                PinnedEventsEventContent(pinned = current.map { EventId(it) }),
                ""
            ).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPinnedMessageIds(roomId: String): Result<List<String>> {
        return Result.success(fetchPinnedEventIds(roomId))
    }

    private suspend fun fetchPinnedEventIds(roomId: String): List<String> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state/m.room.pinned_events")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value !in 200..299) return emptyList()
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            (obj["pinned"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                content(ReactionEventContent(RelatesTo.Annotation(EventId(eventId), key)))
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadMedia(bytes: ByteArray, mimeType: String, filename: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val cacheUri = trixnity.media.prepareUploadMedia(
                kotlinx.coroutines.flow.flow { emit(bytes) },
                ContentType.parse(mimeType)
            )
            Result.success(cacheUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendImageMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, width: Int?, height: Int?, size: Long?): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.Image(
                        body = filename,
                        url = mxcUrl,
                        info = ImageInfo(mimeType = mimeType, width = width, height = height, size = size)
                    )
                }
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendFileMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, size: Long?): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.File(body = filename, url = mxcUrl, info = FileInfo(mimeType = mimeType, size = size))
                }
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendAudioMessage(channelId: String, mxcUrl: String, filename: String, mimeType: String, durationMs: Int?, size: Long?): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.Audio(body = filename, url = mxcUrl, info = AudioInfo(mimeType = mimeType, duration = durationMs?.toLong(), size = size))
                }
            }
            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editMessage(roomId: String, eventId: String, newBody: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.room.sendMessage(RoomId(roomId)) {
                this.relatesTo = RelatesTo.Replace(EventId(eventId))
                text(newBody)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(roomId: String, eventId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.redactEvent(RoomId(roomId), EventId(eventId)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendReadReceipt(roomId: String, eventId: String) {
        context.trixnityClient?.api?.room?.setReadMarkers(RoomId(roomId), EventId(eventId), EventId(eventId))
    }

    override fun observeReadReceipts(roomId: String): Flow<Map<String, String>> {
        val trixnity = context.trixnityClient ?: return flowOf(emptyMap())
        val roomUserStore = try {
            trixnity.di.get<RoomUserStore>()
        } catch (e: Exception) {
            return flowOf(emptyMap())
        }
        // Outer flow re-emits when the set of receipt-holding users changes; inner
        // flows emit per-user. combine keeps every user's latest read event id.
        return roomUserStore.getAllReceipts(RoomId(roomId)).flatMapLatest { userReceipts ->
            if (userReceipts.isEmpty()) {
                flowOf(emptyMap<String, String>())
            } else {
                combine(userReceipts.entries.map { (userId, receiptsFlow) ->
                    receiptsFlow.map { receipts ->
                        receipts?.receipts?.get(ReceiptType.Read)?.let { userId.full to it.eventId.full }
                    }
                }) { pairs -> pairs.filterNotNull().toMap() }
            }
        }
    }

    override suspend fun registerPusher(pushKey: String, appId: String, url: String) {
        val body = buildJsonObject {
            put("pushkey", pushKey)
            put("kind", "http")
            put("app_id", appId)
            put("app_display_name", "Orbit")
            put("device_display_name", "Orbit Device")
            put("lang", "en")
            put("data", buildJsonObject {
                put("url", url)
                put("format", "event_id_only")
            })
        }
        context.httpClient.post(context.fullUrl("/pushers/set")) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    override fun observeMessages(channelId: String): Flow<List<OrbitMessage>> {
        return synchronized(context.liveMessageStates) {
            context.liveMessageStates.getOrPut(channelId) {
                LiveMessageState(kotlinx.coroutines.flow.MutableSharedFlow(replay = 1, extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST))
            }.flow.asSharedFlow()
        }
    }

    suspend fun timelineEventToOrbitMessage(timelineEvent: TimelineEvent): OrbitMessage? {
        val contentResult = timelineEvent.content
        val event = timelineEvent.event as? RoomEvent<*> ?: return null
        val eventId = timelineEvent.eventId.full
        val roomId = timelineEvent.roomId.full
        val sender = timelineEvent.sender.full
        val txnId = (event as? RoomEvent.MessageEvent<*>)?.unsigned?.transactionId
        val ts = Instant.fromEpochMilliseconds(event.originTimestamp)

        if (contentResult?.isFailure == true) {
            return OrbitMessage(
                id = eventId, userId = sender, username = sender,
                body = "🔒 Waiting for keys to decrypt this message...",
                timestamp = ts, channelId = roomId,
                content = MessageContent.Text("🔒 Waiting for keys to decrypt this message..."),
                transactionId = txnId
            )
        }

        val content = contentResult?.getOrNull() ?: return null
        if (content is RoomMessageEventContent) {
            val body = (content as? TextBased)?.body ?: ""
            val relatesTo = content.relatesTo
            val orbitContent: MessageContent = when {
                relatesTo is RelatesTo.Replace -> {
                    val newContent = relatesTo.newContent
                    if (newContent is RoomMessageEventContent) {
                        MessageContent.Edit(replacesEventId = relatesTo.eventId.full, newContent = parseTrixnityMessageContent(newContent))
                    } else parseTrixnityMessageContent(content)
                }
                relatesTo is RelatesTo.Reply -> {
                    val parentId = relatesTo.replyTo?.eventId?.full ?: relatesTo.eventId?.full
                    var quotedSender = ""
                    var quotedBody = ""
                    if (!parentId.isNullOrBlank()) {
                        runCatching {
                            context.trixnityClient?.room?.getTimelineEvent(timelineEvent.roomId, EventId(parentId))?.first()?.let { parent ->
                                quotedSender = parent.sender.full
                                val parentContent = parent.content?.getOrNull()
                                if (parentContent is TextBased) quotedBody = parentContent.body.take(200)
                            }
                        }
                    }
                    MessageContent.Reply(inReplyToEventId = parentId ?: "", quotedSenderId = quotedSender, quotedBody = quotedBody, actualContent = parseTrixnityMessageContent(content))
                }
                else -> parseTrixnityMessageContent(content)
            }

            return OrbitMessage(id = eventId, userId = sender, username = sender, body = body, timestamp = ts, channelId = roomId, content = orbitContent, transactionId = txnId)
        }

        if (content is ReactionEventContent) {
            val key = content.relatesTo?.key ?: ""
            val eventIdRel = content.relatesTo?.eventId?.full ?: ""
            return OrbitMessage(id = eventId, userId = sender, username = sender, body = key, timestamp = ts, channelId = roomId, content = MessageContent.Reaction(reactsToEventId = eventIdRel, key = key), transactionId = txnId)
        }
        return null
    }

    private fun parseTrixnityMessageContent(content: RoomMessageEventContent): MessageContent {
        return when (content) {
            is TextBased.Text -> MessageContent.Text(body = content.body, formattedBody = content.formattedBody)
            is TextBased.Emote -> MessageContent.Emote(body = content.body)
            is TextBased.Notice -> MessageContent.Notice(body = content.body)
            is FileBased.Image -> {
                val url = context.convertMxcUrl(content.url ?: "")
                MessageContent.Image(
                    url = url, thumbnailUrl = content.info?.thumbnailUrl?.let { context.convertMxcUrl(it) } ?: url,
                    width = content.info?.width?.toInt(), height = content.info?.height?.toInt(),
                    body = content.body, mimeType = content.info?.mimeType, size = content.info?.size
                )
            }
            is FileBased.Audio -> {
                MessageContent.Audio(
                    url = context.convertMxcUrl(content.url ?: ""), body = content.body,
                    durationMs = content.info?.duration?.toInt(), mimeType = content.info?.mimeType,
                    size = content.info?.size, waveform = null
                )
            }
            is FileBased.File -> {
                MessageContent.File(url = context.convertMxcUrl(content.url ?: ""), filename = content.body, mimeType = content.info?.mimeType, size = content.info?.size)
            }
            is FileBased.Video -> {
                MessageContent.Video(
                    url = context.convertMxcUrl(content.url ?: ""), thumbnailUrl = content.info?.thumbnailUrl?.let { context.convertMxcUrl(it) },
                    body = content.body, durationMs = content.info?.duration?.toInt(),
                    width = content.info?.width?.toInt(), height = content.info?.height?.toInt(),
                    mimeType = content.info?.mimeType, size = content.info?.size
                )
            }
            else -> MessageContent.Unsupported(type = content::class.simpleName ?: "unknown", fallbackBody = content.body)
        }
    }
}


