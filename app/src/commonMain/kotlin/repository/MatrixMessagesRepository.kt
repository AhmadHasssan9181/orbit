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
import de.connect2x.trixnity.clientserverapi.model.user.avatarUrl
import de.connect2x.trixnity.clientserverapi.model.user.displayName
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

            // fromToken IS the cursor event, so it arrives first; drop it to
            // avoid showing the boundary message twice.
            val source = if (fromToken != null) flow.drop(1) else flow
            // Overall bound, not just the per-event one: DECRYPTION_WAIT is
            // per event, so 50 events that never decrypt would hold the chat's
            // loading state for 500s with no spinner timeout. Show whatever has
            // been collected so far rather than appearing to hang.
            withTimeoutOrNull(HISTORY_LOAD_TIMEOUT_MS) {
                source.take(limit).collect { flowEvent ->
                    awaitDecryptedEvent(flowEvent)?.let { timelineEvent ->
                        timelineEventToOrbitMessage(timelineEvent)?.let { events.add(it) }
                    }
                }
            }
            if (events.isEmpty() && fromToken == null) {
                // Nothing came back within the bound. Return an empty list rather
                // than pretending, so the UI shows its empty state.
                return emptyList()
            }
            val pinnedIds = awaitPinnedEventIds(channelId)
            // Edits and replies supersede their targets; the aggregation pass then
            // folds reactions into whatever message actually renders.
            val resolved = applyEdits(events.reversed())
            aggregateReactions(resolved).map { it.copy(isPinned = it.id in pinnedIds) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Trixnity emits each timeline event once undecrypted and then again with the
     * decrypted content. Taking `.first()` latched the pre-decryption snapshot, so
     * every E2EE message rendered as the "Waiting for keys" placeholder forever.
     * Collect until the content has actually decrypted, bounded so an event we
     * can never decrypt (no session, withheld key) does not hang history.
     */
    private suspend fun awaitDecryptedEvent(flowEvent: Flow<TimelineEvent>): TimelineEvent? {
        var latest: TimelineEvent? = null
        val decrypted = withTimeoutOrNull(DECRYPTION_WAIT_TIMEOUT_MS) {
            // isSuccess, NOT `isFailure != true`: TimelineEvent.content is null
            // while an encrypted event is still waiting for its megolm session,
            // and `null?.isFailure != true` is true -- so the wait returned
            // immediately with the undecrypted event and the message was dropped
            // outright further down.
            flowEvent.firstOrNull { event ->
                latest = event
                event.content?.isSuccess == true
            }
        }
        // Timed out or the flow completed while still undecrypted: fall back to
        // the last snapshot so the placeholder still renders instead of the
        // message disappearing.
        return decrypted ?: latest
    }

    private companion object {
        const val DECRYPTION_WAIT_TIMEOUT_MS = 10_000L
        const val HISTORY_LOAD_TIMEOUT_MS = 20_000L
        const val PINNED_EVENTS_TTL_MS = 30_000L

        /**
         * How recent an undecrypted event must be for "waiting for keys" to be an
         * honest label.
         *
         * Inside this window the event arrived over sync, and its megolm session
         * is travelling in the same to-device batch -- so it genuinely may still
         * decrypt. Outside it, the event came from backfill and the session
         * predates this device; nothing is coming.
         *
         * 5 minutes is generous on purpose. A megolm session that has not arrived
         * five minutes after its message has effectively failed, and labelling it
         * "waiting" forever is what made a whole DM look broken. Generosity here
         * costs one message occasionally reading "waiting" slightly too long; the
         * opposite choice costs a genuinely-decryptable message being written off.
         */
        const val DECRYPTION_PENDING_WINDOW_MS = 5 * 60_000L

        /** Rooms already reported as having undecryptable history. */
        val undecryptableHistoryLogged =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
            // Read-modify-write against shared room state: two concurrent
            // pin/unpin calls each used to start from a fresh server read and the
            // second PUT silently dropped the first change. The per-room mutex
            // serialises the whole read-modify-write, not just the read.
            return pinnedMutationMutex.withLock {
                val pinnedFlow = pinnedEntry(roomId).ids
                val current = awaitPinnedEventIds(roomId).toMutableSet()
                if (pinned) current.add(eventId) else current.remove(eventId)
                trixnity.api.room.sendStateEvent(
                    RoomId(roomId),
                    PinnedEventsEventContent(pinned = current.map { EventId(it) }),
                    ""
                ).getOrThrow()
                pinnedFlow.value = current
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPinnedMessageIds(roomId: String): Result<List<String>> {
        return Result.success(awaitPinnedEventIds(roomId).toList())
    }

    /**
     * Pinned event ids per room, fetched once per room instead of inside every
     * history load. `isPinned` used to be computed only during
     * getMessagesForChannel, so a message that arrived live could never be pinned.
     */
    private data class PinnedEventsEntry(
        val ids: MutableStateFlow<Set<String>>,
        var inFlight: kotlinx.coroutines.Job?,
        var lastFetchedAtMs: Long
    )

    private val pinnedEventsCache = java.util.concurrent.ConcurrentHashMap<String, PinnedEventsEntry>()

    private fun pinnedEntry(roomId: String): PinnedEventsEntry =
        pinnedEventsCache.computeIfAbsent(roomId) {
            PinnedEventsEntry(MutableStateFlow(emptySet()), null, 0L)
        }

    /** Refreshes the cached pinned set for a room; concurrent callers share one request. */
    private fun refreshPinnedEvents(roomId: String) {
        val entry = pinnedEntry(roomId)
        synchronized(entry) {
            if (entry.inFlight?.isActive == true) return
            entry.inFlight = context.repositoryScope.launch {
                val ids = fetchPinnedEventIds(roomId)
                entry.ids.value = ids
                synchronized(entry) {
                    entry.lastFetchedAtMs = System.currentTimeMillis()
                    // Clear the handle, or it stays a COMPLETED job forever and
                    // every later await() joins it instantly and returns the
                    // cached set -- pins made elsewhere would never be seen and
                    // one transient failure would blank every pin for the session.
                    entry.inFlight = null
                }
            }
        }
    }

    /**
     * Suspending read of the pinned set: joins an in-flight refresh so the first
     * history load of a room is not rendered with every message unpinned, and
     * re-fetches once the cached set has aged out so pins from another client
     * eventually show up.
     */
    private suspend fun awaitPinnedEventIds(roomId: String): Set<String> {
        val entry = pinnedEntry(roomId)
        val stale = System.currentTimeMillis() - entry.lastFetchedAtMs > PINNED_EVENTS_TTL_MS
        if (entry.inFlight?.isActive != true && (entry.lastFetchedAtMs == 0L || stale)) {
            refreshPinnedEvents(roomId)
        }
        synchronized(entry) { entry.inFlight }?.join()
        return entry.ids.value
    }

    /**
     * The pinned set already cached for [roomId], without fetching.
     *
     * The live sync path needs this and must NOT block the sync collector on an
     * HTTP request per arriving message. An empty result just means the room has
     * not been opened yet; the next history load fills the cache in.
     */
    fun pinnedIdsCached(roomId: String): Set<String> =
        pinnedEventsCache[roomId]?.ids?.value ?: emptySet()

    private val pinnedMutationMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun fetchPinnedEventIds(roomId: String): Set<String> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state/m.room.pinned_events")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value !in 200..299) {
                response.drain()
                return emptySet()
            }
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            (obj["pinned"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.toSet() ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }


    override suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val ownUserId = context.userId ?: trixnity.userId.full
            // Per the spec an annotation with an EMPTY key removes the reaction.
            // There was no un-annotate path, so a tap could only ever add.
            val alreadyReacted = hasReacted(channelId, eventId, key, ownUserId)
            val annotationKey = if (alreadyReacted) "" else key
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                content(ReactionEventContent(RelatesTo.Annotation(EventId(eventId), annotationKey)))
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
        val response = context.httpClient.post(context.fullUrl("/pushers/set")) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        response.drain()
        if (response.status.value !in 200..299) {
            println("WARN [MatrixMessagesRepository] registerPusher failed status=${response.status.value}")
        }
    }

    override fun observeMessages(channelId: String): Flow<List<OrbitMessage>> {
        val flow = synchronized(context.liveMessageStates) {
            context.liveMessageStates.getOrPut(channelId) {
                kotlinx.coroutines.flow.MutableSharedFlow(
                    replay = 1,
                    extraBufferCapacity = 64,
                    onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
                )
            }
        }
        return flow.asSharedFlow()
    }

    override fun observeIncomingMessages(): Flow<Pair<String, OrbitMessage>> {
        return context.globalMessageArrivals.asSharedFlow()
    }

    suspend fun timelineEventToOrbitMessage(timelineEvent: TimelineEvent): OrbitMessage? {
        val contentResult = timelineEvent.content
        val event = timelineEvent.event as? RoomEvent<*> ?: return null
        val eventId = timelineEvent.eventId.full
        val roomId = timelineEvent.roomId.full
        val sender = timelineEvent.sender.full
        val txnId = (event as? RoomEvent.MessageEvent<*>)?.unsigned?.transactionId
        val ts = Instant.fromEpochMilliseconds(event.originTimestamp)

        // null content means the event is encrypted and has not been decrypted
        // yet; isFailure means we tried and could not. Both render as an
        // explicit placeholder -- returning null here silently dropped the
        // message, leaving holes in E2EE history -- but they are NOT the same
        // situation and must not claim to be:
        //
        //   pending  -> the megolm session may still arrive, so more messages
        //               could decrypt. "Waiting" is a promise worth making.
        //   failed   -> nothing is ever coming. Saying "waiting" there is a
        //               promise nobody keeps.
        //
        // `null` is NOT by itself evidence of pending. It is what trixnity
        // returns for an event it has no key for and will not re-emit, which is
        // exactly what a BACKFILLED historical message looks like: the session
        // was established months ago on a device that no longer exists, so no
        // to-device key is ever coming.
        //
        // That distinction was half-made. The `isFailure` branch correctly says
        // "couldn't be decrypted" for a message from before this device joined,
        // and the comment above it calls the old always-"waiting" placeholder out
        // as the bug it was. But backfill produces `null`, not a failure, so the
        // fix never reached the case that actually happens -- and a DM's entire
        // history rendered as "Encrypted - waiting for keys", forever, for every
        // message in it. Screenshot-confirmed: two messages, 31 May and 12 June,
        // both promised keys that were never coming.
        //
        // So the age of the event decides, because that is the only honest signal
        // available here. An undecrypted event from inside the sync window may
        // still get its session (the key arrives over to-device as the message
        // does). One older than that is history, and history does not decrypt
        // later.
        if (contentResult == null || contentResult.isFailure) {
            val ageMs = io.ktor.util.date.getTimeMillis() - ts.toEpochMilliseconds()
            val pending = contentResult == null && ageMs <= DECRYPTION_PENDING_WINDOW_MS
            if (contentResult == null && !pending) {
                // Logged once per load rather than per message, so a room full of
                // undecryptable history is diagnosable without flooding the log.
                if (undecryptableHistoryLogged.add(roomId)) {
                    util.OrbitLog.d(
                        "MatrixMessagesRepository",
                        "history in $roomId is undecryptable and never will be " +
                            "(oldest ${ageMs / 86_400_000}d); labelled as undecryptable, not pending. " +
                            "This device was not in the room when those megolm sessions existed.",
                    )
                }
            }
            val placeholder = if (pending) {
                "🔒 Encrypted — waiting for keys"
            } else {
                "🔒 This message couldn't be decrypted"
            }
            return OrbitMessage(
                id = eventId, userId = sender, username = sender,
                body = placeholder,
                timestamp = ts, channelId = roomId,
                content = MessageContent.Undecryptable(pending = pending),
                transactionId = txnId
            )
        }

        val content = contentResult.getOrNull() ?: return null

        // Trixnity rewrites a redacted event to RedactedEventContent, which is a
        // RoomEventContent but NOT a RoomMessageEventContent, so the branch below
        // never matched and deleted messages simply vanished from the timeline.
        if (content is de.connect2x.trixnity.core.model.events.RedactedEventContent) {
            return OrbitMessage(
                id = eventId, userId = sender, username = sender,
                body = "Message deleted",
                timestamp = ts, channelId = roomId,
                content = MessageContent.Redacted(),
                transactionId = txnId
            )
        }

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

            return OrbitMessage(
                id = eventId, userId = sender, username = sender, body = body,
                timestamp = ts, channelId = roomId, content = orbitContent,
                replyToEventId = (orbitContent as? MessageContent.Reply)?.inReplyToEventId,
                transactionId = txnId
            )
        }

        if (content is ReactionEventContent) {
            val key = content.relatesTo?.key ?: ""
            val eventIdRel = content.relatesTo?.eventId?.full ?: ""
            recordObservedReaction(roomId, eventIdRel, key, sender)
            return OrbitMessage(id = eventId, userId = sender, username = sender, body = key, timestamp = ts, channelId = roomId, content = MessageContent.Reaction(reactsToEventId = eventIdRel, key = key), transactionId = txnId)
        }
        return null
    }

    /**
     * Folds every reaction event in [messages] into its parent message's
     * `reactions` map and drops the reaction pseudo-messages from the list.
     * Without this, reactions were standalone bubbles and `OrbitMessage.reactions`
     * stayed empty, so the reaction chips in the UI could never render.
     */
    suspend fun aggregateReactions(messages: List<OrbitMessage>): List<OrbitMessage> {
        val reactionByParent = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        val kept = mutableListOf<OrbitMessage>()
        for (message in messages) {
            val reaction = message.content as? MessageContent.Reaction
            if (reaction == null) {
                kept.add(message)
                continue
            }
            if (reaction.reactsToEventId.isEmpty()) continue
            val perEmoji = reactionByParent.getOrPut(reaction.reactsToEventId) { mutableMapOf() }
            if (reaction.key.isEmpty()) {
                // An empty key is the spec's un-annotate marker: it has to
                // REMOVE the sender, not be skipped. Skipping it left the chip
                // showing a reaction the user had already taken back, and the
                // UI's already-reacted check then blocked re-adding it.
                perEmoji.values.forEach { it.remove(message.userId) }
                perEmoji.entries.removeAll { it.value.isEmpty() }
            } else {
                perEmoji.getOrPut(reaction.key) { mutableSetOf() }.add(message.userId)
            }
        }
        if (reactionByParent.isEmpty()) return messages
        return kept.map { message ->
            val chips = reactionByParent[message.id] ?: return@map message
            message.copy(reactions = chips.mapValues { (_, senders) -> senders.toList() })
        }
    }

    /**
     * Whether [userId] already reacted [key] on [eventId], per what this client
     * last observed. Server truth would need the state event; the observed index
     * is enough to make repeated taps toggle instead of only ever adding.
     */
    private fun hasReacted(roomId: String, eventId: String, key: String, userId: String): Boolean =
        observedReactions[roomId]?.get(eventId)?.get(key)?.contains(userId) == true

    /** roomId -> (parentEventId -> emoji -> senderIds), updated as events are converted. */
    private val observedReactions =
        java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, MutableMap<String, MutableSet<String>>>>()

    private fun recordObservedReaction(roomId: String, parentEventId: String, key: String, sender: String) {
        if (parentEventId.isEmpty()) return
        if (key.isEmpty()) {
            // An empty key is the spec's un-annotate marker: it carries no emoji,
            // so it clears this sender's reactions on the parent entirely.
            forgetObservedReactions(roomId, parentEventId, sender)
            return
        }
        val perParent = observedReactions.getOrPut(roomId) { java.util.concurrent.ConcurrentHashMap() }
        val perEmoji = perParent.getOrPut(parentEventId) { java.util.concurrent.ConcurrentHashMap() }
        val senders = perEmoji.getOrPut(key) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        synchronized(senders) { senders.add(sender) }
    }

    private fun forgetObservedReactions(roomId: String, parentEventId: String, sender: String) {
        val perEmoji = observedReactions[roomId]?.get(parentEventId) ?: return
        synchronized(perEmoji) {
            perEmoji.values.forEach { senders ->
                synchronized(senders) { senders.remove(sender) }
            }
        }
    }

    /**
     * Applies m.replace edits in place: the target message takes the edit's
     * content, and standalone edit events are dropped.
     */
    private fun applyEdits(messages: List<OrbitMessage>): List<OrbitMessage> {
        val editsByTarget = messages
            .mapNotNull { message ->
                val edit = message.content as? MessageContent.Edit ?: return@mapNotNull null
                edit.replacesEventId to edit.newContent
            }
            .toMap()
        if (editsByTarget.isEmpty()) return messages
        return messages
            .filterNot { it.content is MessageContent.Edit }
            .map { message ->
                val replacement = editsByTarget[message.id] ?: return@map message
                message.copy(
                    body = replacement.displayText,
                    content = replacement,
                    isEdited = true
                )
            }
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

