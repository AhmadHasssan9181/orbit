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
import ui.model.ServerInvite
import ui.screen.server.DiscoverServer
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
import de.connect2x.trixnity.core.MatrixServerException
import de.connect2x.trixnity.core.model.events.m.room.AudioInfo
import de.connect2x.trixnity.core.model.events.m.room.AvatarEventContent
import de.connect2x.trixnity.core.model.events.m.room.CreateEventContent
import de.connect2x.trixnity.core.model.events.m.room.FileInfo
import de.connect2x.trixnity.core.model.events.m.room.ImageInfo
import de.connect2x.trixnity.core.model.events.m.room.NameEventContent

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
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


class MatrixSpacesRepository(private val context: MatrixContext) : OrbitSpacesRepository {

    /**
     * Space-ness per room, with a TTL.
     *
     * A room only becomes a space if its create-event says so or it has child
     * events, and neither changes often, so re-asking the server on every sync
     * burst was pure waste. Entries are per room id and bounded by the number of
     * joined rooms, which the app already holds in memory anyway.
     */
    private val spaceProbeCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private val roomAvatarCache =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    private val roomBannerCache =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun detectImageContentType(bytes: ByteArray): ContentType {
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return ContentType.Image.JPEG
        }
        if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return ContentType.Image.PNG
        }
        if (bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
            return ContentType("image", "webp")
        }
        if (bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()) {
            return ContentType.Image.GIF
        }
        return ContentType.Image.PNG
    }

    private suspend fun concurrentSpaces(
        allRooms: List<de.connect2x.trixnity.client.store.Room>,
        trixnity: de.connect2x.trixnity.client.MatrixClient?
    ): List<de.connect2x.trixnity.client.store.Room> {
        val now = System.currentTimeMillis()
        val stale = allRooms.filter { room ->
            val cached = spaceProbeCache[room.roomId.full]
            cached == null || now - cached.second > SPACE_PROBE_TTL_MS
        }
        if (trixnity != null && stale.isNotEmpty()) {
            // Concurrent, and capped: a 500-room account is 500 probes, and
            // firing them all at once would itself trip the rate limiter.
            val limit = SPACE_PROBE_CONCURRENCY
            stale.chunked(limit).forEach { batch ->
                coroutineScope {
                    batch.map { room ->
                        async {
                            val explicit = room.createEventContent?.type ==
                                CreateEventContent.RoomType.Space ||
                                room.createEventContent?.type?.toString()
                                    ?.contains("space", ignoreCase = true) == true

                            // CancellationException IS an Exception, so the
                            // runCatching below swallows it and reports false.
                            // getSpaces() is driven by collectLatest, so it is
                            // cancelled on nearly every sync emission -- writing
                            // that false into the cache would mis-classify the
                            // room as "not a space" for the whole TTL. So only
                            // cache a verdict we actually earned.
                            val isSpace: Boolean = if (explicit) {
                                true
                            } else {
                                currentCoroutineContext().ensureActive()
                                runCatching {
                                    trixnity.room.getAllState(room.roomId, ChildEventContent::class)
                                        .first().isNotEmpty()
                                }.getOrDefault(false)
                            }

                            // BUG, and it hid the entire Spaces feature.
                            //
                            // This used to be `if (!explicit) { cache… }`, on the
                            // reasoning that an explicit room needed no probe and
                            // therefore no cache entry. But `spaceProbeCache` is
                            // the ONLY source of truth for the line below:
                            //
                            //     return allRooms.filter { spaceProbeCache[…]?.first == true }
                            //
                            // So every room that declared itself a space — the
                            // cheap, unambiguous, spec-blessed case, and the only
                            // case matrix.org produces — was never cached and
                            // therefore never returned.
                            //
                            // The visible effect: `spaces` came back empty, so the
                            // server rail fell through to the synthetic "Rooms"
                            // bucket, and `getChannelsForSpace("rooms")` — which
                            // returns only rooms belonging to NO space — rendered
                            // a flat, shuffled list. Three rooms all named
                            // "General" with no structure to disambiguate them,
                            // a fabricated "Voice Channels" section, and clicking
                            // one row landing on another.
                            //
                            // Verified against Element on the same account: four
                            // spaces exist (rail shows N, H, S, T) and
                            // `saaaasas` is a "Private space · 2 members"
                            // containing General and Random. All four were
                            // invisible here.
                            //
                            // The explicit check costs nothing, so there is no
                            // reason to skip caching it.
                            spaceProbeCache[room.roomId.full] = isSpace to System.currentTimeMillis()
                        }
                    }.awaitAll()
                }
            }
        } else if (trixnity == null) {
            // No client: fall back to the explicit type only.
            stale.forEach { room ->
                val explicit = room.createEventContent?.type == CreateEventContent.RoomType.Space ||
                    room.createEventContent?.type?.toString()?.contains("space", ignoreCase = true) == true
                spaceProbeCache[room.roomId.full] = explicit to System.currentTimeMillis()
            }
        }
        val resolved = allRooms.filter { spaceProbeCache[it.roomId.full]?.first == true }
        // "No spaces found" was indistinguishable from "the probe failed" and
        // from "the verdict was never cached" — which is exactly how the bug
        // above stayed invisible for a whole test cycle. This line makes the
        // count and the names visible on every load.
        util.OrbitLog.d(
            "Spaces",
            "rooms=${allRooms.size} spaces=${resolved.size} " +
                "names=[${resolved.joinToString { context.roomDisplayName(it) }}]"
        )
        return resolved
    }

    private companion object {
        const val SPACE_PROBE_TTL_MS = 5 * 60_000L
        const val SPACE_PROBE_CONCURRENCY = 8
    }

    override suspend fun getSpaces(): List<OrbitServer> {
        val allRooms = context.getAllRoomsSnapshot()
        if (allRooms.isEmpty()) return emptyList()

        val servers = mutableListOf<OrbitServer>()
        val trixnity = context.trixnityClient

        // 1. Identify Spaces
        //
        // The per-room /state probe is the expensive part: it ran for EVERY
        // joined room whose create-event type does not literally say "space",
        // and getSpaces() is called from the sync collector on every emission
        // of the room list. On a 500-room account that is 500 /state requests
        // per sync burst, which is what gets an account rate-limited.
        //
        // Two changes:
        //  - the probe is concurrent rather than serial, so wall-clock is one
        //    round-trip rather than N;
        //  - the verdict is cached per room and re-probed on a TTL, so a steady
        //    sync stream does not re-probe an unchanged room at all.
        val spaces = concurrentSpaces(allRooms, trixnity)
        
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
            val displayName = context.roomDisplayName(space)
            val canonicalAlias = context.roomCanonicalAlias(space.roomId.full)
            servers.add(OrbitServer(
                id = space.roomId.full,
                name = displayName,
                iconColor = context.hashToColor(space.roomId.full),
                description = space.name?.explicitName ?: "",
                isDM = false,
                avatarUrl = (space.avatarUrl ?: roomAvatarCache[space.roomId.full])?.let { context.convertMxcUrl(it) },
                bannerUrl = (context.cachedRoomBanner(space.roomId.full) ?: roomBannerCache[space.roomId.full]?.let { context.convertMxcUrl(it) }),
                canonicalAlias = canonicalAlias
            ))

            // Ensure public spaces have their alias published on Matrix
            autoPublishSpaceAlias(space.roomId.full, displayName)
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

    private fun normalizeServerAlias(name: String): String {
        val withoutQuotes = name.lowercase().trim().filter { it != '\'' && it != '"' && it != '’' && it != '`' }
        val cleaned = withoutQuotes
            .replace(Regex("[^a-z0-9_.-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
        return if (cleaned.isBlank()) "orbit-server" else cleaned
    }

    private fun autoPublishSpaceAlias(spaceId: String, name: String) {
        context.repositoryScope.launch(Dispatchers.IO) {
            try {
                val domain = getHomeserverDomain()
                val slug = normalizeServerAlias(name)
                val primaryAlias = "#$slug:$domain"
                val encodedPrimary = java.net.URLEncoder.encode(primaryAlias, "UTF-8")
                
                // Try registering primary clean alias (e.g. #dexters-laboratory:matrix.org)
                context.httpClient.put(context.fullUrl("/directory/room/$encodedPrimary")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"room_id": "$spaceId"}""")
                }

                // Also register legacy alias (e.g. #dexter-s-laboratory:matrix.org) for backward compat
                val legacySlug = name.lowercase().trim().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-', '.')
                if (legacySlug != slug) {
                    val legacyAlias = "#$legacySlug:$domain"
                    val encodedLegacy = java.net.URLEncoder.encode(legacyAlias, "UTF-8")
                    context.httpClient.put(context.fullUrl("/directory/room/$encodedLegacy")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"room_id": "$spaceId"}""")
                    }
                }

                // Set canonical alias
                context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.canonical_alias")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("alias", primaryAlias) }.toString())
                }
            } catch (e: Exception) {
                // Silently ignore if already set or not permitted
            }
        }
    }

    private fun getHomeserverDomain(): String {
        return context.userId?.substringAfter(':')?.trim()?.ifBlank { null }
            ?: context.homeserverUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
    }

    override suspend fun joinSpace(roomIdOrAlias: String): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val trimmed = roomIdOrAlias.trim()
            val targetId: String = when {
                trimmed.startsWith("!") -> trimmed
                trimmed.startsWith("#") -> trimmed
                else -> {
                    val domain = getHomeserverDomain()
                    val slug = normalizeServerAlias(trimmed)
                    val candidates = listOf(
                        "#$slug:$domain",
                        if (slug.contains("_")) "#${slug.replace('_', '-')}:$domain" else null,
                        if (slug.contains("-")) "#${slug.replace('-', '_')}:$domain" else null,
                        if (!slug.endsWith("s")) "#${slug}s:$domain" else "#${slug.removeSuffix("s")}:$domain"
                    ).filterNotNull().distinct()

                    var resolved: String? = null
                    for (cand in candidates) {
                        val encoded = java.net.URLEncoder.encode(cand, "UTF-8")
                        val resp = runCatching {
                            context.httpClient.get(context.fullUrl("/directory/room/$encoded"))
                        }.getOrNull()
                        if (resp != null && resp.status.value in 200..299) {
                            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                            val candRoomId = body["room_id"]?.jsonPrimitive?.content
                            if (candRoomId != null && isSpacePublic(candRoomId)) {
                                resolved = candRoomId
                                break
                            }
                        }
                    }

                    if (resolved != null) {
                        resolved
                    } else {
                        val searchMatches = getPublicServers(trimmed)
                        val match = searchMatches.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                            ?: searchMatches.firstOrNull()
                        match?.id ?: "#$slug:$domain"
                    }
                }
            }
            val response = trixnity.api.room.joinRoom(RoomId(targetId)).getOrThrow()
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSpace(name: String, isPublic: Boolean, category: String, avatarBytes: ByteArray?): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val preset = if (isPublic) {
                de.connect2x.trixnity.clientserverapi.model.room.CreateRoom.Request.Preset.PUBLIC
            } else {
                de.connect2x.trixnity.clientserverapi.model.room.CreateRoom.Request.Preset.PRIVATE
            }
            val visibility = if (isPublic) DirectoryVisibility.PUBLIC else DirectoryVisibility.PRIVATE
            val response = trixnity.api.room.createRoom(
                name = name,
                preset = preset,
                visibility = visibility,
                topic = "[$category] Community server",
                creationContent = CreateEventContent(type = CreateEventContent.RoomType.Space)
            ).getOrThrow()
            val spaceId = response.full

            // Tag as Orbit server
            runCatching {
                context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/space.orbit.server/")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orbit": true, "version": 1}""")
                }
            }

            // Upload and set Space avatar if provided
            if (avatarBytes != null && avatarBytes.isNotEmpty()) {
                runCatching {
                    val optimizedBytes = util.compressImage(avatarBytes, maxDimension = 512)
                    val contentType = detectImageContentType(optimizedBytes)
                    val mxcUrl = trixnity.media.uploadMedia(
                        trixnity.media.prepareUploadMedia(kotlinx.coroutines.flow.flowOf(optimizedBytes), contentType)
                    ).getOrThrow()

                    roomAvatarCache[spaceId] = mxcUrl

                    trixnity.api.room.sendStateEvent(
                        RoomId(spaceId),
                        AvatarEventContent(url = mxcUrl)
                    ).getOrThrow()
                }.onFailure { err ->
                    util.OrbitLog.w("MatrixSpacesRepository", "Failed to upload space avatar: ${err.message}")
                }
            }

            val joinRule = if (isPublic) JoinRulesEventContent.JoinRule.Public else JoinRulesEventContent.JoinRule.Invite
            runCatching {
                trixnity.api.room.sendStateEvent(
                    RoomId(spaceId),
                    JoinRulesEventContent(joinRule)
                ).getOrThrow()
            }
            if (isPublic) {
                var canonicalAlias: String? = null
                val domain = getHomeserverDomain()
                val baseLocal = normalizeServerAlias(name)
                var aliasToTry = "#$baseLocal:$domain"
                val encodedInitial = java.net.URLEncoder.encode(aliasToTry, "UTF-8")
                val putResp = runCatching {
                    context.httpClient.put(context.fullUrl("/directory/room/$encodedInitial")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"room_id": "$spaceId"}""")
                    }
                }.getOrNull()

                if (putResp?.status?.value in 200..299) {
                    canonicalAlias = aliasToTry
                } else {
                    val suffix = (1000..9999).random()
                    aliasToTry = "#$baseLocal-$suffix:$domain"
                    val encodedSuffixed = java.net.URLEncoder.encode(aliasToTry, "UTF-8")
                    val retryResp = runCatching {
                        context.httpClient.put(context.fullUrl("/directory/room/$encodedSuffixed")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody("""{"room_id": "$spaceId"}""")
                        }
                    }.getOrNull()
                    if (retryResp?.status?.value in 200..299) {
                        canonicalAlias = aliasToTry
                    }
                }

                if (canonicalAlias != null) {
                    runCatching {
                        context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.canonical_alias")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody(buildJsonObject { put("alias", canonicalAlias) }.toString())
                        }
                    }
                }

                runCatching {
                    val encodedSpace = java.net.URLEncoder.encode(spaceId, "UTF-8")
                    context.httpClient.put(context.fullUrl("/directory/list/room/$encodedSpace")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"visibility": "public"}""")
                    }
                }
            }
            context.roomListFlow.emit(getSpaces())
            Result.success(spaceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun isSpacePublic(spaceId: String): Boolean {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$spaceId/state/m.room.join_rules")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value in 200..299) {
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body["join_rule"]?.jsonPrimitive?.content == "public"
            } else false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getIncomingInvites(): List<ServerInvite> {
        return try {
            val trixnity = context.trixnityClient ?: return emptyList()
            val allRoomsMap = trixnity.room.getAll().first()
            val invites = mutableListOf<ServerInvite>()
            for (roomFlow in allRoomsMap.values) {
                val room = roomFlow.first() ?: continue
                if (room.membership == de.connect2x.trixnity.core.model.events.m.room.Membership.INVITE) {
                    val displayName = context.roomDisplayName(room)
                    val avatarUrl = room.avatarUrl?.let { context.convertMxcUrl(it) }
                    invites.add(
                        ServerInvite(
                            spaceId = room.roomId.full,
                            name = displayName,
                            avatarUrl = avatarUrl,
                            inviterId = null
                        )
                    )
                }
            }
            invites
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun acceptInvite(spaceId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.joinRoom(RoomId(spaceId)).getOrThrow()
            runCatching {
                val allRoomsMap = trixnity.room.getAll().first()
                for (roomFlow in allRoomsMap.values) {
                    val r = roomFlow.first() ?: continue
                    if (r.membership == de.connect2x.trixnity.core.model.events.m.room.Membership.INVITE) {
                        runCatching {
                            trixnity.api.room.joinRoom(r.roomId).getOrThrow()
                        }
                    }
                }
            }
            context.roomMetadataCache.remove(spaceId)
            context.roomListFlow.emit(getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun declineInvite(spaceId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.leaveRoom(RoomId(spaceId)).getOrThrow()
            context.roomMetadataCache.remove(spaceId)
            context.roomListFlow.emit(getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getPublicServers(searchTerm: String): List<DiscoverServer> = coroutineScope {
        val trimmed = searchTerm.trim()
        val globalDeferred = async {
            try {
                val response = if (trimmed.isBlank()) {
                    context.httpClient.get(context.fullUrl("/publicRooms?limit=100")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    }
                } else {
                    context.httpClient.post(context.fullUrl("/publicRooms")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("limit", 100)
                            put("filter", buildJsonObject {
                                put("generic_search_term", trimmed)
                            })
                        }.toString())
                    }
                }
                if (response.status.value !in 200..299) emptyList<DiscoverServer>()
                else {
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    val chunk = body["chunk"]?.jsonArray ?: JsonArray(emptyList())
                    chunk.mapNotNull { it.jsonObject }.mapNotNull { room ->
                        val roomId = room["room_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val name = room["name"]?.jsonPrimitive?.content ?: roomId
                        val topic = room["topic"]?.jsonPrimitive?.content ?: "Public community server"
                        val memberCount = room["num_joined_members"]?.jsonPrimitive?.intOrNull ?: 1
                        val avatarMxc = room["avatar_url"]?.jsonPrimitive?.content
                        val roomType = room["room_type"]?.jsonPrimitive?.content

                        val inferredCategory = when {
                            topic.contains("[Gaming]", ignoreCase = true) || topic.contains("#gaming", ignoreCase = true) || name.contains("game", ignoreCase = true) || name.contains("gaming", ignoreCase = true) -> "Gaming"
                            topic.contains("[Tech]", ignoreCase = true) || topic.contains("#tech", ignoreCase = true) || name.contains("tech", ignoreCase = true) || name.contains("code", ignoreCase = true) || name.contains("dev", ignoreCase = true) -> "Tech"
                            topic.contains("[Music]", ignoreCase = true) || topic.contains("#music", ignoreCase = true) || name.contains("music", ignoreCase = true) -> "Music"
                            topic.contains("[Creative]", ignoreCase = true) || topic.contains("#creative", ignoreCase = true) || name.contains("art", ignoreCase = true) -> "Creative"
                            topic.contains("[Science]", ignoreCase = true) || topic.contains("#science", ignoreCase = true) -> "Science"
                            roomType == "m.space" -> "Community"
                            else -> "Community"
                        }

                        val tags = mutableListOf("public", "matrix", inferredCategory.lowercase())
                        if (inferredCategory == "Gaming") tags.addAll(listOf("games", "esports", "gaming"))
                        if (inferredCategory == "Tech") tags.addAll(listOf("coding", "programming", "software"))

                        DiscoverServer(
                            id = roomId,
                            name = name,
                            description = topic.replace(Regex("\\[[A-Za-z0-9_]+\\]\\s*"), ""),
                            category = inferredCategory,
                            tags = tags,
                            memberCount = if (memberCount >= 1000) "${(memberCount / 100) / 10.0}k" else "$memberCount",
                            onlineCount = "Active",
                            avatarUrl = avatarMxc?.let { context.convertMxcUrl(it) },
                            isVerified = true,
                            rawMemberCount = memberCount
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList<DiscoverServer>()
            }
        }

        val aliasDeferred = async {
            if (trimmed.isBlank()) emptyList<DiscoverServer>()
            else {
                try {
                    val domain = getHomeserverDomain()
                    val slug = normalizeServerAlias(trimmed)
                    val candidates = if (trimmed.startsWith("#")) {
                        listOf(trimmed)
                    } else {
                        val list = mutableListOf<String>()
                        list.add("#$slug:$domain")
                        if (slug.contains("_")) list.add("#${slug.replace('_', '-')}:$domain")
                        if (slug.contains("-")) list.add("#${slug.replace('-', '_')}:$domain")
                        val legacySlug = trimmed.lowercase().trim().replace(Regex("[^a-z0-9_.-]+"), "-").trim('-', '.')
                        if (legacySlug != slug) {
                            list.add("#$legacySlug:$domain")
                        }
                        if (!slug.endsWith("s")) {
                            list.add("#${slug}s:$domain")
                            list.add("#${slug}_clans:$domain")
                            list.add("#${slug}-clans:$domain")
                        } else {
                            list.add("#${slug.removeSuffix("s")}:$domain")
                        }
                        list.distinct()
                    }

                    val candidateProbes = candidates.map { cand ->
                        async {
                            try {
                                val encoded = java.net.URLEncoder.encode(cand, "UTF-8")
                                val resolveResp = context.httpClient.get(context.fullUrl("/directory/room/$encoded"))
                                if (resolveResp.status.value in 200..299) {
                                    val body = Json.parseToJsonElement(resolveResp.bodyAsText()).jsonObject
                                    val resolvedRoomId = body["room_id"]?.jsonPrimitive?.content ?: return@async null
                                    if (!isSpacePublic(resolvedRoomId)) return@async null
                                    val hList = getSpaceHierarchy(resolvedRoomId)
                                    val spaceRoot = hList.firstOrNull { it.id == resolvedRoomId }
                                    val serverName = spaceRoot?.name?.ifBlank { null } ?: trimmed
                                    val desc = spaceRoot?.description?.ifBlank { null } ?: "Public community server"

                                    val inferredCategory = when {
                                        desc.contains("[Gaming]", ignoreCase = true) || desc.contains("#gaming", ignoreCase = true) || serverName.contains("game", ignoreCase = true) || serverName.contains("gaming", ignoreCase = true) -> "Gaming"
                                        desc.contains("[Tech]", ignoreCase = true) || desc.contains("#tech", ignoreCase = true) || serverName.contains("tech", ignoreCase = true) || serverName.contains("code", ignoreCase = true) || serverName.contains("dev", ignoreCase = true) -> "Tech"
                                        desc.contains("[Music]", ignoreCase = true) || desc.contains("#music", ignoreCase = true) || serverName.contains("music", ignoreCase = true) -> "Music"
                                        desc.contains("[Creative]", ignoreCase = true) || desc.contains("#creative", ignoreCase = true) || serverName.contains("art", ignoreCase = true) -> "Creative"
                                        desc.contains("[Science]", ignoreCase = true) || desc.contains("#science", ignoreCase = true) -> "Science"
                                        else -> "Community"
                                    }

                                    val tags = mutableListOf("public", "matrix", inferredCategory.lowercase(), slug)
                                    if (inferredCategory == "Gaming") tags.addAll(listOf("games", "esports", "gaming"))
                                    if (inferredCategory == "Tech") tags.addAll(listOf("coding", "programming", "software"))

                                    DiscoverServer(
                                        id = resolvedRoomId,
                                        name = serverName,
                                        description = desc.replace(Regex("\\[[A-Za-z0-9_]+\\]\\s*"), ""),
                                        category = inferredCategory,
                                        tags = tags,
                                        memberCount = "Active",
                                        onlineCount = "Online",
                                        avatarUrl = spaceRoot?.avatarUrl,
                                        isVerified = true,
                                        rawMemberCount = 999
                                    )
                                } else null
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }

                    candidateProbes.awaitAll().filterNotNull()
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        val globalResults = globalDeferred.await()
        val aliasResults = aliasDeferred.await()

        val merged = (aliasResults + globalResults).distinctBy { it.id }
        merged.sortedWith(
            compareByDescending<DiscoverServer> { it.name.equals(trimmed, ignoreCase = true) }
                .thenByDescending { it.rawMemberCount }
        )
    }

    override suspend fun makeSpacePublic(spaceId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.sendStateEvent(
                RoomId(spaceId),
                JoinRulesEventContent(JoinRulesEventContent.JoinRule.Public)
            ).getOrThrow()
            val channels = MatrixChannelsRepository(context).getChannelsForSpace(spaceId)
            for (ch in channels) {
                runCatching {
                    trixnity.api.room.sendStateEvent(
                        RoomId(ch.id),
                        JoinRulesEventContent(JoinRulesEventContent.JoinRule.Public)
                    ).getOrThrow()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun inviteUserToSpace(spaceId: String, userId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.inviteUser(RoomId(spaceId), UserId(userId)).getOrThrow()
            val channels = MatrixChannelsRepository(context).getChannelsForSpace(spaceId)
            for (ch in channels) {
                runCatching {
                    trixnity.api.room.inviteUser(RoomId(ch.id), UserId(userId)).getOrThrow()
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun leaveSpace(spaceId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val channels = MatrixChannelsRepository(context).getChannelsForSpace(spaceId)
            for (ch in channels) {
                runCatching {
                    trixnity.api.room.leaveRoom(RoomId(ch.id)).getOrThrow()
                }
                runCatching {
                    trixnity.api.room.forgetRoom(RoomId(ch.id)).getOrThrow()
                }
                context.roomMetadataCache.remove(ch.id)
            }
            trixnity.api.room.leaveRoom(RoomId(spaceId)).getOrThrow()
            runCatching {
                trixnity.api.room.forgetRoom(RoomId(spaceId)).getOrThrow()
            }
            context.roomMetadataCache.remove(spaceId)
            context.roomListFlow.emit(getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteSpace(spaceId: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val channels = MatrixChannelsRepository(context).getChannelsForSpace(spaceId)
            for (ch in channels) {
                // Kick members so other accounts don't remain trapped in orphan rooms
                runCatching {
                    val members = trixnity.api.room.getJoinedMembers(RoomId(ch.id)).getOrNull()?.joined?.keys ?: emptySet()
                    for (m in members) {
                        if (m.full != context.userId) {
                            runCatching { trixnity.api.room.kickUser(RoomId(ch.id), m, "Server deleted") }
                        }
                    }
                }
                // Unpublish from public room directory
                runCatching {
                    context.httpClient.put(context.fullUrl("/directory/list/room/${ch.id}")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"visibility": "private"}""")
                    }
                }
                runCatching {
                    trixnity.api.room.sendStateEvent(
                        RoomId(spaceId),
                        ChildEventContent(via = emptySet()),
                        ch.id
                    ).getOrThrow()
                }
                runCatching {
                    trixnity.api.room.leaveRoom(RoomId(ch.id)).getOrThrow()
                }
                runCatching {
                    trixnity.api.room.forgetRoom(RoomId(ch.id)).getOrThrow()
                }
                context.roomMetadataCache.remove(ch.id)
            }

            // Clean up the space itself
            runCatching {
                val spaceMembers = trixnity.api.room.getJoinedMembers(RoomId(spaceId)).getOrNull()?.joined?.keys ?: emptySet()
                for (m in spaceMembers) {
                    if (m.full != context.userId) {
                        runCatching { trixnity.api.room.kickUser(RoomId(spaceId), m, "Server deleted") }
                    }
                }
            }
            runCatching {
                context.httpClient.put(context.fullUrl("/directory/list/room/$spaceId")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"visibility": "private"}""")
                }
            }
            trixnity.api.room.leaveRoom(RoomId(spaceId)).getOrThrow()
            runCatching {
                trixnity.api.room.forgetRoom(RoomId(spaceId)).getOrThrow()
            }
            context.roomMetadataCache.remove(spaceId)
            context.roomListFlow.emit(getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$spaceId/hierarchy", v1 = true)) {
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

    private suspend fun <T> withRateLimitRetry(
        maxRetries: Int = 3,
        actionName: String = "Operation",
        block: suspend () -> T
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val is429 = when (e) {
                    is MatrixServerException -> e.statusCode.value == 429
                    else -> e.message?.contains("429") == true ||
                        e.message?.contains("Too Many Requests", ignoreCase = true) == true ||
                        e.message?.contains("M_LIMIT_EXCEEDED", ignoreCase = true) == true
                }
                if (is429 && attempt < maxRetries) {
                    attempt++
                    val retryAfterMs = when (e) {
                        is MatrixServerException -> e.retryAfter ?: (1500L * attempt)
                        else -> 1500L * attempt
                    }
                    util.OrbitLog.w("MatrixSpacesRepository", "$actionName rate-limited (429). Retrying in ${retryAfterMs}ms (attempt $attempt/$maxRetries)...")
                    kotlinx.coroutines.delay(retryAfterMs + 400L)
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun uploadSpaceBanner(spaceId: String, imageBytes: ByteArray): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val optimizedBytes = util.compressImage(imageBytes, maxDimension = 1280)
            val contentType = detectImageContentType(optimizedBytes)

            val mxcUrl = withRateLimitRetry(actionName = "Upload space banner") {
                trixnity.media.uploadMedia(
                    trixnity.media.prepareUploadMedia(kotlinx.coroutines.flow.flowOf(optimizedBytes), contentType)
                ).getOrThrow()
            }

            withRateLimitRetry(actionName = "Set banner state") {
                var resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.banner")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("url", mxcUrl) }.toString())
                }
                if (resp.status.value == 429) {
                    val retryMs = runCatching {
                        Json.parseToJsonElement(resp.bodyAsText()).jsonObject["retry_after_ms"]?.jsonPrimitive?.longOrNull
                    }.getOrNull() ?: 2000L
                    kotlinx.coroutines.delay(retryMs + 400L)
                    resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.banner")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("url", mxcUrl) }.toString())
                    }
                }
                if (resp.status.value !in 200..299) {
                    // Fallback to trailing slash
                    val fallback = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.banner/")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("url", mxcUrl) }.toString())
                    }
                    if (fallback.status.value !in 200..299) {
                        throw Exception("Failed to update banner: ${fallback.status}")
                    }
                }
            }

            roomBannerCache[spaceId] = mxcUrl
            context.invalidateRoomState(spaceId)
            context.roomListFlow.emit(getSpaces())

            val httpUrl = context.convertMxcUrl(mxcUrl)
            Result.success(httpUrl)
        } catch (e: Exception) {
            val message = if (e.message?.contains("429") == true || e.message?.contains("Too Many Requests", ignoreCase = true) == true) {
                "Server is busy with too many requests. Please wait a few seconds and try again."
            } else {
                e.message ?: "Failed to update banner"
            }
            Result.failure(Exception(message, e))
        }
    }

    override suspend fun uploadSpaceAvatar(spaceId: String, imageBytes: ByteArray): Result<String> {
        return try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val optimizedBytes = util.compressImage(imageBytes, maxDimension = 512)
            val contentType = detectImageContentType(optimizedBytes)

            val mxcUrl = withRateLimitRetry(actionName = "Upload space avatar") {
                trixnity.media.uploadMedia(
                    trixnity.media.prepareUploadMedia(kotlinx.coroutines.flow.flowOf(optimizedBytes), contentType)
                ).getOrThrow()
            }

            withRateLimitRetry(actionName = "Set avatar state") {
                val result = trixnity.api.room.sendStateEvent(
                    RoomId(spaceId),
                    AvatarEventContent(url = mxcUrl)
                )
                if (result.isFailure) {
                    // Fallback to HTTP PUT
                    var resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.avatar")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("url", mxcUrl) }.toString())
                    }
                    if (resp.status.value !in 200..299) {
                        resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.avatar/")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody(buildJsonObject { put("url", mxcUrl) }.toString())
                        }
                    }
                    if (resp.status.value !in 200..299) {
                        result.getOrThrow()
                    }
                }
            }

            roomAvatarCache[spaceId] = mxcUrl
            context.invalidateRoomState(spaceId)
            context.roomListFlow.emit(getSpaces())

            val httpUrl = context.convertMxcUrl(mxcUrl)
            Result.success(httpUrl)
        } catch (e: Exception) {
            val message = if (e.message?.contains("429") == true || e.message?.contains("Too Many Requests", ignoreCase = true) == true) {
                "Server is busy with too many requests. Please wait a few seconds and try again."
            } else {
                e.message ?: "Failed to update icon"
            }
            Result.failure(Exception(message, e))
        }
    }

    override suspend fun updateSpaceName(spaceId: String, name: String): Result<Unit> {
        return try {
            val trixnity = context.trixnityClient
            withRateLimitRetry(actionName = "Update space name") {
                val trixResult = if (trixnity != null) {
                    trixnity.api.room.sendStateEvent(
                        RoomId(spaceId),
                        NameEventContent(name = name)
                    )
                } else null

                if (trixResult == null || trixResult.isFailure) {
                    var resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.name")) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("name", name) }.toString())
                    }
                    if (resp.status.value !in 200..299) {
                        resp = context.httpClient.put(context.fullUrl("/rooms/$spaceId/state/m.room.name/")) {
                            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                            contentType(ContentType.Application.Json)
                            setBody(buildJsonObject { put("name", name) }.toString())
                        }
                    }
                    if (resp.status.value !in 200..299) {
                        throw Exception("Failed to update name: ${resp.status}")
                    }
                }
            }
            context.invalidateRoomState(spaceId)
            context.roomListFlow.emit(getSpaces())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

