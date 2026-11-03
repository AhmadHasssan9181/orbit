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
import de.connect2x.trixnity.clientserverapi.model.room.DirectoryVisibility
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
import de.connect2x.trixnity.core.model.events.m.room.JoinRulesEventContent
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
import kotlinx.serialization.json.contentOrNull
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

    private suspend fun resolveChannelType(roomId: String, rawName: String, fallbackRoomType: String? = null): ChannelType {
        context.channelTypeOverrides[roomId]?.let { return it }
        if (context.isVoiceRoom(roomId)) return ChannelType.VOICE
        val events = context.roomStateEvents(roomId)
        val hasExplicitText = events.any { el ->
            val raw = runCatching { el.jsonObject }.getOrNull() ?: return@any false
            if ((raw["type"] as? JsonPrimitive)?.contentOrNull == "space.orbit.channel.type") {
                val cType = (raw["content"]?.jsonObject?.get("type") as? JsonPrimitive)?.contentOrNull
                cType.equals("text", ignoreCase = true)
            } else false
        }
        if (hasExplicitText) return ChannelType.TEXT
        if (fallbackRoomType in setOf("org.matrix.msc3417.call", "org.matrix.msc3401.call", "voice", "video")) {
            return ChannelType.VOICE
        }
        return context.inferChannelType(rawName)
    }

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

        if (spaceId != "dm" && allRooms.none { it.roomId.full == spaceId }) {
            return emptyList()
        }

        // 2. Identify Children for Space
        val trixnity = context.trixnityClient
        var spaceChildren = setOf<String>()
        if (trixnity != null && spaceId != "dm") {
            try {
                spaceChildren = trixnity.room.getAllState(RoomId(spaceId), ChildEventContent::class).first().keys
            } catch(e: Exception) {}
        }

        // 2b. Query Space Hierarchy via Matrix API
        // This discovers ALL channels in the space, even ones the current user hasn't joined yet!
        val hierarchyRooms = mutableListOf<SpaceHierarchyChannel>()
        if (spaceId != "dm") {
            try {
                val url = "${context.homeserverUrl}/_matrix/client/v1/rooms/$spaceId/hierarchy"
                val response = context.httpClient.get(url) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                }
                if (response.status.value == 200) {
                    val body = response.body<JsonObject>()
                    val rooms = body["rooms"]?.jsonArray ?: JsonArray(emptyList())
                    for (elem in rooms) {
                        val obj = runCatching { elem.jsonObject }.getOrNull() ?: continue
                        val rid = (obj["room_id"] as? JsonPrimitive)?.content ?: continue
                        if (rid == spaceId) continue // Skip the parent space itself
                        val rType = (obj["room_type"] as? JsonPrimitive)?.content
                        if (rType == "m.space") continue // Skip sub-spaces in channel list
                        val rawName = (obj["name"] as? JsonPrimitive)?.content
                        val isVoiceRoom = rType in setOf("org.matrix.msc3417.call", "org.matrix.msc3401.call", "voice", "video")
                        val name = if (!rawName.isNullOrBlank() && rawName != rid.takeLast(8)) {
                            rawName
                        } else {
                            val localRoom = trixnity?.room?.getById(RoomId(rid))?.first()
                            val localName = localRoom?.let { context.roomDisplayName(it) }
                            if (!localName.isNullOrBlank() && localName != rid.takeLast(8)) {
                                localName
                            } else {
                                if (isVoiceRoom) "General Voice" else "general"
                            }
                        }
                        val topic = (obj["topic"] as? JsonPrimitive)?.content ?: ""
                        val avatarRaw = (obj["avatar_url"] as? JsonPrimitive)?.content
                        val avatar = if (!avatarRaw.isNullOrBlank()) context.convertMxcUrl(avatarRaw) else null
                        hierarchyRooms.add(SpaceHierarchyChannel(rid, name, topic, rType, avatar))
                    }
                }
            } catch (e: Exception) {
                util.OrbitLog.w("Channels", "Hierarchy fetch failed for $spaceId: ${e.message}")
            }
        }

        // 2c. Real category assignment.
        val categoryMap = context.categories.load()
        val categoryNames = context.categories.categoryNames(categoryMap)
        val isOrbitServer = context.isOrbitServer(spaceId)

        // 3. Map Channels
        val channels = mutableListOf<OrbitChannel>()
        val processedRoomIds = mutableSetOf<String>()

        for (room in allRooms) {
            val metadata = context.roomMetadataCache[room.roomId.full]
            
            // Skip DMs and Spaces in channel list
            if (metadata?.isDM == true || metadata?.isSpace == true) continue
            if (context.isDMRoom(room)) continue

            val belongsToSpace = spaceChildren.contains(room.roomId.full) ||
                hierarchyRooms.any { it.roomId == room.roomId.full }
            val shouldInclude = belongsToSpace

            if (shouldInclude) {
                val roomId = room.roomId.full
                val hRoom = hierarchyRooms.find { it.roomId == roomId }
                val initialName = context.channelNameOverrides[roomId] ?: context.roomDisplayName(room)
                // If initialName is an 8-char fallback of room ID tail, prefer hRoom.name
                val rawName = if (initialName.length == 8 && hRoom != null && hRoom.name.isNotBlank() && hRoom.name != roomId.takeLast(8)) {
                    hRoom.name
                } else {
                    initialName
                }
                val topic = context.roomTopics.topicOf(roomId)

                val type = context.channelTypeOverrides[roomId] ?: resolveChannelType(roomId, rawName, hRoom?.roomType)
                val cleanName = context.orbitChannelName(rawName, type)
                val category = categoryMap[room.roomId.full]

                val isOrbitManaged = isOrbitServer || context.isOrbitChannel(roomId)
                channels.add(OrbitChannel(
                    id = room.roomId.full,
                    name = cleanName,
                    type = type,
                    categoryId = category?.categoryId,
                    categoryName = category?.let { categoryNames[it.categoryId] },
                    categoryPosition = category?.position ?: 0.0,
                    matrixRoomId = room.roomId.full,
                    topic = topic.ifBlank { hRoom?.topic ?: "" },
                    activeCallUrl = context.globalActiveCalls.value[room.roomId.full],
                    isOrbitManaged = isOrbitManaged
                ))
                processedRoomIds.add(roomId)
            }
        }

        if (spaceId != "dm") {
            for (hRoom in hierarchyRooms) {
                if (hRoom.roomId in processedRoomIds) continue

                val overrideName = context.channelNameOverrides[hRoom.roomId]
                val rawName = overrideName ?: hRoom.name
                val type = context.channelTypeOverrides[hRoom.roomId] ?: resolveChannelType(hRoom.roomId, rawName, hRoom.roomType)
                val cleanName = context.orbitChannelName(rawName, type)
                val category = categoryMap[hRoom.roomId]

                val isOrbitManaged = isOrbitServer || context.isOrbitChannel(hRoom.roomId)
                channels.add(OrbitChannel(
                    id = hRoom.roomId,
                    name = cleanName,
                    type = type,
                    categoryId = category?.categoryId,
                    categoryName = category?.let { categoryNames[it.categoryId] },
                    categoryPosition = category?.position ?: 0.0,
                    matrixRoomId = hRoom.roomId,
                    topic = hRoom.topic,
                    avatarUrl = hRoom.avatarUrl,
                    activeCallUrl = context.globalActiveCalls.value[hRoom.roomId],
                    isOrbitManaged = isOrbitManaged
                ))
                processedRoomIds.add(hRoom.roomId)
            }

            for (childId in spaceChildren) {
                if (childId in processedRoomIds) continue
                
                val localRoom = trixnity?.room?.getById(RoomId(childId))?.first()
                val localName = localRoom?.let { context.roomDisplayName(it) }
                val overrideName = context.channelNameOverrides[childId]

                val rawName = when {
                    !overrideName.isNullOrBlank() -> overrideName
                    !localName.isNullOrBlank() && localName != childId.takeLast(8) -> localName
                    else -> {
                        val isVoice = context.channelTypeOverrides[childId] == ChannelType.VOICE || context.isVoiceRoom(childId)
                        if (isVoice) "General Voice" else "general"
                    }
                }



                val type = context.channelTypeOverrides[childId] ?: resolveChannelType(childId, rawName)
                val cleanName = context.orbitChannelName(rawName, type)
                val category = categoryMap[childId]

                val isOrbitManaged = isOrbitServer || context.isOrbitChannel(childId)
                channels.add(OrbitChannel(
                    id = childId,
                    name = cleanName,
                    type = type,
                    categoryId = category?.categoryId,
                    categoryName = category?.let { categoryNames[it.categoryId] },
                    categoryPosition = category?.position ?: 0.0,
                    matrixRoomId = childId,
                    topic = "",
                    activeCallUrl = context.globalActiveCalls.value[childId],
                    isOrbitManaged = isOrbitManaged
                ))
                processedRoomIds.add(childId)
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
        topic: String,
        isPublic: Boolean
    ): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val creationContent = if (type == ChannelType.VOICE) {
                CreateEventContent(type = CreateEventContent.RoomType.Unknown("org.matrix.msc3417.call"))
            } else null

            // Do not override power levels with empty users map, which strips creator admin power
            val powerLevelContentOverride: de.connect2x.trixnity.core.model.events.m.room.PowerLevelsEventContent? = null

            val preset = if (isPublic) {
                de.connect2x.trixnity.clientserverapi.model.room.CreateRoom.Request.Preset.PUBLIC
            } else {
                de.connect2x.trixnity.clientserverapi.model.room.CreateRoom.Request.Preset.PRIVATE
            }
            val visibility = if (isPublic) DirectoryVisibility.PUBLIC else DirectoryVisibility.PRIVATE

            val response = trixnity.api.room.createRoom(
                name = name,
                topic = topic.ifBlank { if (type == ChannelType.VOICE) "Voice channel" else "" },
                preset = preset,
                visibility = visibility,
                creationContent = creationContent,
                powerLevelContentOverride = powerLevelContentOverride
            ).getOrThrow()
            
            val newRoomId = response.full

            val cleanName = name.trim()
            if (cleanName.isNotBlank()) {
                runCatching {
                    context.httpClient.put(context.fullUrl("/rooms/$newRoomId/state/m.room.name/")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("name", cleanName) }.toString())
                    }
                }
            }

            // Tag as Orbit channel
            val typeStr = if (type == ChannelType.VOICE) "voice" else "text"
            runCatching {
                context.httpClient.put(context.fullUrl("/rooms/$newRoomId/state/space.orbit.channel/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("type", typeStr) }.toString())
                }
                context.httpClient.put(context.fullUrl("/rooms/$newRoomId/state/space.orbit.channel.type/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("type", typeStr) }.toString())
                }
            }

            // For VOICE channels, configure power levels so regular members (power level 0) can publish call state
            if (type == ChannelType.VOICE) {
                runCatching {
                    val plResp = context.httpClient.get(context.fullUrl("/rooms/$newRoomId/state/m.room.power_levels")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    }
                    if (plResp.status.value in 200..299) {
                        val plObj = Json.parseToJsonElement(plResp.bodyAsText()).jsonObject
                        val events = plObj["events"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                        events["org.matrix.msc3401.call.member"] = JsonPrimitive(0)
                        events["m.call.member"] = JsonPrimitive(0)
                        val updated = buildJsonObject {
                            plObj.forEach { (k, v) -> if (k != "events") put(k, v) }
                            put("events", JsonObject(events))
                        }
                        context.httpClient.put(context.fullUrl("/rooms/$newRoomId/state/m.room.power_levels/")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody(updated.toString())
                        }
                    }
                }

                runCatching {
                    context.httpClient.put(context.fullUrl("/rooms/$newRoomId/state/org.matrix.msc3401.call/")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("m.intent", "m.room")
                            put("m.kind", "voice")
                        }.toString())
                    }
                }
            }

            val joinRule = if (isPublic) JoinRulesEventContent.JoinRule.Public else JoinRulesEventContent.JoinRule.Invite
            runCatching {
                trixnity.api.room.sendStateEvent(
                    RoomId(newRoomId),
                    JoinRulesEventContent(joinRule)
                ).getOrThrow()
            }

            if (spaceId != "dm") {
                trixnity.api.room.sendStateEvent(
                    RoomId(spaceId),
                    ChildEventContent(via = setOf(context.homeserverUrl.removePrefix("https://").removePrefix("http://"))),
                    newRoomId
                ).getOrThrow()
            }

            context.roomListFlow.emit(MatrixSpacesRepository(context).getSpaces())
            Result.success(newRoomId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChannelById(channelId: String): OrbitChannel? {
        val trixnity = context.trixnityClient ?: return null
        val room = trixnity.room.getById(RoomId(channelId)).first()
        val overrideName = context.channelNameOverrides[channelId]
        val rawName = overrideName ?: room?.let { context.roomDisplayName(it) } ?: "general"
        val type = context.channelTypeOverrides[channelId] ?: resolveChannelType(channelId, rawName)
        val cleanName = context.orbitChannelName(rawName, type)

        return OrbitChannel(
            id = channelId,
            name = cleanName,
            type = type,
            // Real category when the server has one, null otherwise. The hard-coded
            // "general" this replaced put every room in a bucket the server had
            // never heard of.
            categoryId = context.categories.load()[channelId]?.categoryId,
            matrixRoomId = channelId,
            activeCallUrl = context.globalActiveCalls.value[channelId]
        )
    }

    override suspend fun updateChannel(
        spaceId: String,
        channelId: String,
        name: String,
        type: ChannelType
    ): Result<Unit> {
        val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            val cleanName = name.trim()

            // 1. Immediately store in-memory overrides so UI responds instantly
            if (cleanName.isNotBlank()) {
                context.channelNameOverrides[channelId] = cleanName
            }
            context.channelTypeOverrides[channelId] = type
            context.invalidateVoiceRoomCache(channelId)
            context.invalidateRoomState(channelId)



            // 3. Update m.room.name
            if (cleanName.isNotBlank()) {
                val resp = context.httpClient.put(context.fullUrl("/rooms/$channelId/state/m.room.name/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("name", cleanName) }.toString())
                }
                if (resp.status.value !in 200..299) {
                    context.httpClient.put(context.fullUrl("/rooms/$channelId/state/m.room.name")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("name", cleanName) }.toString())
                    }
                }
            }

            // 4. Update space.orbit.channel and space.orbit.channel.type
            val typeStr = if (type == ChannelType.VOICE) "voice" else "text"
            runCatching {
                context.httpClient.put(context.fullUrl("/rooms/$channelId/state/space.orbit.channel/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("type", typeStr) }.toString())
                }
                context.httpClient.put(context.fullUrl("/rooms/$channelId/state/space.orbit.channel.type/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("type", typeStr) }.toString())
                }
            }

            // 5. Update call state & power levels for voice vs text
            if (type == ChannelType.VOICE) {
                // Ensure power levels allow regular members (power level 0) to publish call state
                runCatching {
                    val plResp = context.httpClient.get(context.fullUrl("/rooms/$channelId/state/m.room.power_levels")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    }
                    if (plResp.status.value in 200..299) {
                        val plObj = Json.parseToJsonElement(plResp.bodyAsText()).jsonObject
                        val events = plObj["events"]?.jsonObject?.toMutableMap() ?: mutableMapOf()
                        events["org.matrix.msc3401.call.member"] = JsonPrimitive(0)
                        events["m.call.member"] = JsonPrimitive(0)
                        val updated = buildJsonObject {
                            plObj.forEach { (k, v) -> if (k != "events") put(k, v) }
                            put("events", JsonObject(events))
                        }
                        context.httpClient.put(context.fullUrl("/rooms/$channelId/state/m.room.power_levels/")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody(updated.toString())
                        }
                    }
                }

                context.httpClient.put(context.fullUrl("/rooms/$channelId/state/org.matrix.msc3401.call/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("m.intent", "m.room")
                        put("m.kind", "voice")
                    }.toString())
                }
            } else {
                context.httpClient.put(context.fullUrl("/rooms/$channelId/state/org.matrix.msc3401.call/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("m.terminated", "call_ended")
                    }.toString())
                }
                context.httpClient.put(context.fullUrl("/rooms/$channelId/state/m.call/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("m.terminated", "call_ended")
                    }.toString())
                }
            }

            context.roomListFlow.emit(MatrixSpacesRepository(context).getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            util.OrbitLog.w("Channels", "Failed to update channel $channelId: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun deleteChannel(spaceId: String, channelId: String): Result<Unit> {
        val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
        return try {
            // 1. Kick other members if creator/moderator
            runCatching {
                val members = trixnity.api.room.getJoinedMembers(RoomId(channelId)).getOrNull()?.joined?.keys ?: emptySet()
                for (m in members) {
                    if (m.full != context.userId) {
                        runCatching { trixnity.api.room.kickUser(RoomId(channelId), m, "Channel deleted") }
                    }
                }
            }

            // 2. Unpublish from directory
            runCatching {
                context.httpClient.put(context.fullUrl("/directory/list/room/$channelId")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"visibility": "private"}""")
                }
            }

            // 3. Unlink from space children
            if (spaceId.isNotBlank() && spaceId != "dm") {
                runCatching {
                    trixnity.api.room.sendStateEvent(
                        RoomId(spaceId),
                        ChildEventContent(via = emptySet()),
                        channelId
                    ).getOrThrow()
                }
            }

            // 4. Leave & forget room
            runCatching {
                trixnity.api.room.leaveRoom(RoomId(channelId)).getOrThrow()
            }
            runCatching {
                trixnity.api.room.forgetRoom(RoomId(channelId)).getOrThrow()
            }

            // 5. Clean up caches & emit updated spaces
            context.channelNameOverrides.remove(channelId)
            context.channelTypeOverrides.remove(channelId)
            context.roomMetadataCache.remove(channelId)
            context.invalidateVoiceRoomCache(channelId)
            context.roomListFlow.emit(MatrixSpacesRepository(context).getSpaces())

            Result.success(Unit)
        } catch (e: Exception) {
            util.OrbitLog.w("Channels", "Failed to delete channel $channelId: ${e.message}")
            Result.failure(e)
        }
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

    override suspend fun joinChannelIfNeeded(channelId: String): Result<String> {
        val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
        val joined = context.getAllRoomsSnapshot().any { it.roomId.full == channelId }
        if (joined) return Result.success(channelId)
        return try {
            val response = trixnity.api.room.joinRoom(RoomId(channelId)).getOrThrow()
            context.roomListFlow.emit(MatrixSpacesRepository(context).getSpaces())
            Result.success(response.full)
        } catch (e: Exception) {
            util.OrbitLog.w("Channels", "Auto-join for $channelId failed: ${e.message}")
            Result.failure(e)
        }
    }
}

internal data class SpaceHierarchyChannel(
    val roomId: String,
    val name: String,
    val topic: String,
    val roomType: String?,
    val avatarUrl: String?
)
