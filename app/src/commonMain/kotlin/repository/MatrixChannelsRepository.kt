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
import de.connect2x.trixnity.clientserverapi.model.user.avatarUrl
import de.connect2x.trixnity.clientserverapi.model.user.displayName
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.EventType
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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


class MatrixChannelsRepository(private val context: MatrixContext) : OrbitChannelsRepository {

    override suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel> {
        val allRooms = context.getAllRoomsSnapshot()
        
        // 1. Handle DMs
        if (spaceId == "dm") {
            return allRooms.filter { context.isDMRoom(it) }.map { room ->
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

        // 2. Identify Children for Space
        val trixnity = context.trixnityClient
        var spaceChildren = setOf<String>()
        if (trixnity != null && spaceId != "rooms") {
            try {
                spaceChildren = trixnity.room.getAllState(RoomId(spaceId), ChildEventContent::class).first().keys
            } catch(e: Exception) {}
        }

        // 2b. Real category assignment.
        //
        // This used to be the constant "general" for every room, so the drawer had
        // nothing to group by and rendered a single flat "Rooms" header no matter
        // how the server was actually arranged. The category map is empty when the
        // homeserver does not implement the endpoint, and categoryId then stays
        // null so the drawer falls back to grouping by channel type -- which is what
        // Discord shows when nobody has made categories.
        val categoryMap = context.categories.load()
        val categoryNames = context.categories.categoryNames(categoryMap)

        // 3. Map Channels
        val channels = mutableListOf<OrbitChannel>()
        for (room in allRooms) {
            val metadata = context.roomMetadataCache[room.roomId.full]
            
            // Skip DMs and Spaces in channel list
            if (metadata?.isDM == true || metadata?.isSpace == true) continue
            if (context.isDMRoom(room)) continue

            val belongsToSpace = spaceChildren.contains(room.roomId.full)
            val shouldInclude = when (spaceId) {
                "rooms" -> !belongsToSpace
                else -> belongsToSpace
            }

            if (shouldInclude) {
                val rawName = context.roomDisplayName(room)
                val roomId = room.roomId.full
                // m.room.topic, read back from the state this room was already
                // fetched for. See MatrixRoomTopics: `createRoom` writes the
                // topic, `ChatHeader` renders `channel.topic`, and nothing in
                // between ever parsed it, so the header subtitle was permanently
                // blank. This adds no request -- `isVoiceRoom` has already pulled
                // and cached this room's `m.state` by the time we get here.
                val topic = context.roomTopics.topicOf(roomId)
                // Voice typing is now decided by the room's OWN Matrix state, not
                // by whether its name happens to contain "voice".
                //
                // The old rule typed a room VOICE iff its name contained "voice",
                // which on matrix.org put a text room called
                // "voice-chat-announcements" under Voice Channels and a genuine
                // voice room called "general" under Text Channels. The whole
                // Voice Channels section was therefore fiction, and the
                // fabricated "General Voice" row below existed to hide that.
                //
                // A room with a live call counts as voice even before the state
                // probe lands, so the room you are standing in cannot flicker.
                val type = if (context.isVoiceRoom(roomId, context.globalActiveCalls.value.keys)) {
                    ChannelType.VOICE
                } else {
                    context.inferChannelType(rawName)
                }
                val cleanName = context.orbitChannelName(rawName, type)
                // Keyed on the REAL room id, not the display id.
                val category = categoryMap[room.roomId.full]

                channels.add(OrbitChannel(
                    id = room.roomId.full,
                    name = cleanName,
                    type = type,
                    categoryId = category?.categoryId,
                    categoryName = category?.let { categoryNames[it.categoryId] },
                    categoryPosition = category?.position ?: 0.0,
                    matrixRoomId = room.roomId.full,
                    topic = topic,
                    activeCallUrl = context.globalActiveCalls.value[room.roomId.full]
                ))
            }
        }

        // Voice channels sort after text ones inside their group, so a category
        // reads "chat stuff, then talking" without the server needing to say so.
        // Mutable because the synthetic General Voice row is inserted below.
        val ordered = channels.sortedWith(
            compareBy({ if (it.type == ChannelType.TEXT) 0 else 1 }, { it.name.lowercase() })
        ).toMutableList()

        // 4. The fabricated "General Voice" channel is gone.
        //
        // When a space had no room whose name contained "voice", the list invented
        // one: an entry named "General Voice" whose `matrixRoomId` pointed at the
        // first TEXT room. It looked like a voice channel, joined a call in a room
        // that had none, and it happened for every space — which is why the
        // screenshot showed exactly one voice channel that was really the
        // ordinary text room, while the actual MatrixRTC voice rooms were missing
        // from the list entirely.
        //
        // Voice typing now comes from each room's own `m.call` state, so real
        // voice rooms appear on their own and nothing needs to be invented.
        // Creating a voice room is the Create Channel dialog's job.

        // Log the classification rather than trusting it. "Not all call rooms are
        // shown" was previously indistinguishable between "the server has no more
        // voice rooms" and "the probe failed" — and this log is what tells the two
        // apart next time.
        util.OrbitLog.d(
            "Channels",
            "space=$spaceId rooms=${ordered.size} " +
                "voice=${ordered.count { it.type == ChannelType.VOICE }} " +
                "text=${ordered.count { it.type == ChannelType.TEXT }} " +
                "voiceNames=[${ordered.filter { it.type == ChannelType.VOICE }.joinToString { it.name }}]"
        )

        return ordered
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
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "m.call") to 0L,
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "m.call.member") to 0L,
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "org.matrix.msc3401.call") to 0L,
                            de.connect2x.trixnity.core.model.events.EventType(de.connect2x.trixnity.core.model.events.UnknownEventContent::class, "org.matrix.msc3401.call.member") to 0L
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

            // Re-emitting roomListFlow.replayCache re-published the SAME stale
            // list, so a newly created channel never appeared until something
            // else happened to push a fresh snapshot. getSpaces() lives on the
            // spaces repository, not on MatrixContext.
            context.roomListFlow.emit(MatrixSpacesRepository(context).getSpaces())
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
            // Real category when the server has one, null otherwise. The hard-coded
            // "general" this replaced put every room in a bucket the server had
            // never heard of.
            categoryId = context.categories.load()[room.roomId.full]?.categoryId,
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

    override suspend fun resolveDmContacts(roomIds: Collection<String>): Map<String, OrbitUser> {
        val trixnity = context.trixnityClient ?: return emptyMap()
        if (roomIds.isEmpty()) return emptyMap()
        val ownId = trixnity.userId.full

        // Concurrent, bounded: a serial loop issued one /joined_members round-trip
        // per conversation, so a 50-chat list meant 50 sequential requests, and
        // the effect re-runs whenever the channel list changes -- including on
        // every new message in any DM, because the topic carries the preview.
        //
        // `supervisorScope` with a per-row `runCatching`, NOT `coroutineScope` +
        // `awaitAll`. One conversation failing used to take the entire list with
        // it: `awaitAll` rethrows the first child failure, the enclosing scope
        // cancelled the rest, and the caller's effect died with nothing assigned
        // -- so the DM list stayed permanently unresolved. A single unreachable
        // room must not blank every other conversation.
        //
        // The semaphore matters too: unbounded concurrency is not "fast". Fifty
        // simultaneous authenticated requests get rate-limited by most
        // homeservers, and a 429 then fails whichever rows it happened to hit.
        return supervisorScope {
            val permits = Semaphore(DM_LOOKUP_CONCURRENCY)
            roomIds.toList()
                .map { roomId ->
                    async {
                        permits.withPermit {
                            roomId to runCatching { resolveSingleDmContact(roomId, ownId) }.getOrNull()
                        }
                    }
                }
                .mapNotNull { it.await()?.let { (roomId, user) -> user?.let { roomId to it } } }
                .toMap()
        }
    }

    private companion object {
        /**
         * How many `/joined_members` lookups may be in flight at once.
         *
         * Chosen to keep first paint quick without asking the homeserver for a
         * burst it is likely to refuse.
         */
        const val DM_LOOKUP_CONCURRENCY = 8
    }

    private suspend fun resolveSingleDmContact(roomId: String, ownId: String): OrbitUser? {
        // One room failing must not blank the whole list.
        val others = try {
            context.trixnityClient?.api?.room?.getJoinedMembers(RoomId(roomId))?.getOrThrow()?.joined?.keys
                ?.map { it.full }
                ?.filter { it != ownId }
                .orEmpty()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("WARN [MatrixChannelsRepository] DM contact lookup failed for $roomId: ${e.message}")
            return null
        }
        // Only a true 1:1 has exactly one other member; groups resolve to nobody
        // so a group room can never be labelled with one person's name.
        if (others.size != 1) return null
        val other = others.first()
        // fetchUserProfile is TTL-cached, so a long list does not turn into one
        // profile request per row per rebuild.
        //
        // On failure, return null rather than a synthetic OFFLINE user: the UI
        // reads contact?.status and omits the presence dot when it is null, so
        // inventing OFFLINE here showed a grey "offline" dot -- a false claim
        // about the other person -- instead of admitting we do not know.
        return context.fetchUserProfile(other)
    }

    override fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>> {
        val repository = this
        return combine(
            context.roomListFlow,
            context.globalActiveCalls
        ) { _, activeCalls ->
            // One entry per room. Exactly one.
            //
            // This used to emit a SECOND synthetic channel — "General (Call)",
            // id `voice_<roomId>`, typed VOICE — for every text room with a live
            // call. That existed so a call would have somewhere to appear under
            // the "Voice Channels" heading. With the type split gone there is no
            // heading for it to live under, so it is now pure duplication: the
            // screenshot showed `General` in Text Channels AND in Voice Channels
            // at the same time.
            //
            // It was also actively harmful beyond the duplicate row. The copy had
            // a different `id` from the room it stood for but shared its
            // `matrixRoomId`, so selecting it and selecting the real room were
            // different selections pointing at the same place — which is how
            // "General" could be both your open channel and not.
            repository.getChannelsForSpace(spaceId).map { ch ->
                ch.copy(activeCallUrl = activeCalls[ch.matrixRoomId] ?: activeCalls[ch.id])
            }
        }
    }
}
