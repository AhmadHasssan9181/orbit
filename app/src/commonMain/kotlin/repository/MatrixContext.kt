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
import io.ktor.util.date.getTimeMillis
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.serialization.json.contentOrNull
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




data class RoomMetadata(
    val id: String,
    val name: String,
    val topic: String,
    val isDM: Boolean,
    val isSpace: Boolean,
    val spaceParent: String? = null,
    val memberCount: Int = 0,
    val avatarUrl: String? = null
)

/**
 * Normalises a homeserver URL typed by the user into the canonical base the
 * client and every raw request are built from:
 *  - a missing scheme defaults to https (never silently plain http)
 *  - non http/https schemes are rejected
 *  - trailing slashes, query and fragment are stripped
 *  - the path is preserved (reverse-proxied Synapse at /matrix must work)
 *
 * Done at construction so the long-lived access token is only ever put on the
 * wire over a validated origin.
 */
object HomeserverUrlNormalizer {
    fun normalize(input: String): String {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Homeserver URL is empty" }

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = io.ktor.http.Url(withScheme)
        val scheme = url.protocol.name.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Homeserver URL must use http or https, got '${url.protocol.name}'"
        }
        if (scheme == "http") {
            println(
                "WARN [MatrixContext] homeserver '$withScheme' is plain HTTP: the Matrix access " +
                    "token will be sent in cleartext to $scheme://${url.host}"
            )
        }
        val host = url.host.ifBlank {
            throw IllegalArgumentException("Homeserver URL has no host: '$withScheme'")
        }
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (url.port != defaultPort) ":${url.port}" else ""
        // Keep the path. Synapse behind a reverse proxy is commonly hosted at
        // https://example.org/matrix, and dropping it would send every request
        // to the origin root and 404. Only trailing slashes are stripped.
        val path = url.encodedPath.trimEnd('/')
        return "$scheme://$host$port$path"
    }
}

class MatrixContext(homeserverUrl: String) {
    var accessToken: String? = CredentialManager.accessToken
    var userId: String? = CredentialManager.userId
    var deviceId: String? = CredentialManager.deviceId
    var trixnityClient: MatrixClient? = null

    val homeserverUrl: String = HomeserverUrlNormalizer.normalize(homeserverUrl)

    private val scopeJob = SupervisorJob()
    val repositoryScope = CoroutineScope(scopeJob + Dispatchers.Default)

    @Volatile
    private var closed = false

    /**
     * Releases everything this context owns: the repository scope and the CIO
     * HTTP client (which holds a selector thread plus its own dispatcher).
     * Idempotent. A context that is never closed leaks both for the life of the
     * process, which is why every failed login attempt closes its own.
     */
    fun close() {
        if (closed) return
        closed = true
        scopeJob.cancel("MatrixContext closed")
        runCatching { httpClient.close() }
            .onFailure { println("WARN [MatrixContext] closing httpClient failed: ${it.message}") }
    }

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

    val callEventsFlow = MutableSharedFlow<CallEvent>(
        extraBufferCapacity = 10,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val globalActiveCalls = MutableStateFlow<Map<String, String>>(emptyMap())
    val authStatusFlow = MutableStateFlow<AuthStatus>(AuthStatus.Authenticated)

    // Room ids whose call-related state changed; group-call observers re-resolve on tick.
    // tryEmit-only producer, so SUSPEND overflow would silently drop every change
    // once 64 were pending; these are coalescible nudges, not events to replay.
    val groupCallStateChanges = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // The sync collector emits here from its own scope while UI coroutines
    // collect; SUSPEND overflow would make a slow UI backpressure the sync loop.
    val roomListFlow = MutableSharedFlow<List<OrbitServer>>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // Live chat-message arrivals for EVERY room as (matrixRoomId, message);
    // the app shell consumes this to maintain unread counts per channel.
    val globalMessageArrivals = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, OrbitMessage>>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    // roomId -> live message flow. Value is the flow only: an accumulating list
    // alongside it was never read and grew without bound for the session.
    // All of these are written from the sync collector (Dispatchers.Default)
    // AND from the Compose/EDT dispatcher, so they are ConcurrentHashMaps, not
    // LinkedHashMaps. The previous code wrapped SOME of the accesses in
    // `synchronized` and left others bare -- which is worse than no lock at all,
    // because it looks safe. A plain HashMap corrupted by concurrent put/get
    // can lose entries or (Java 8+ cyclic bin chains) spin forever inside
    // get(), which presents as an unreproducible app freeze.
    //
    // Kotlin's ConcurrentMap.getOrPut is atomic (putIfAbsent), so the callers
    // that used to synchronize can just do that.
    val liveMessageStates = java.util.concurrent.ConcurrentHashMap<String, MutableSharedFlow<List<OrbitMessage>>>()
    val activeCalls = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<CallEvent.Invite?>>()
    val activeGroupCalls = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<String?>>()
    val activeCallMembers = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<OrbitUser>>>()
    // Per-device call roster ((userId, deviceId) pairs) driving the matrixRTC
    // E2EE media-key exchange; mirrors activeCallMembers but keeps devices distinct.
    val activeCallDevices = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Set<livekit.CallDevice>>>()

    /**
     * roomId -> `origin_server_ts` of the room's `m.call` definition event, i.e.
     * when the CURRENT call began. Membership records older than this belong to a
     * call that already ended; see [MatrixRtcMembership] for why the `expires`
     * window alone cannot tell the two apart. Written by the roster poller, which
     * already downloads the full room state, so it costs nothing extra.
     */
    val activeCallStartedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Room -> category assignment from `/_matrix/client/v1/categories`, paired with
     * the time it was fetched. Null until the first load. See [MatrixCategories].
     */
    @Volatile
    internal var categoriesCache: Pair<Map<String, RoomCategory>, Long>? = null

    /** Lazily built; shares the repository's HttpClient, so it costs nothing extra. */
    internal val categories: MatrixCategories by lazy { MatrixCategories(this) }

    /**
     * `m.room.topic` per room, for the channel-header subtitle.
     *
     * Created for rooms written before this existed and never read back, so the
     * header subtitle was permanently blank. Cached with a TTL because it is one
     * state fetch per room and the drawer asks for every room on every space
     * switch.
     */
    internal val roomTopics: MatrixRoomTopics by lazy { MatrixRoomTopics(this) }
    val accountDataCache = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Any?>>()

    // Event types whose account data has already been requested. The request used
    // to be fired on every collect(), so re-composition re-hit the network.
    val accountDataFetchStarted = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    val roomMetadataCache = java.util.concurrent.ConcurrentHashMap<String, RoomMetadata>()

    // userId -> (profile, fetched-at). The call rosters polled a profile
    // request per member per 3s tick, which is what triggered rate limiting.
    private val userProfileCache = java.util.concurrent.ConcurrentHashMap<String, CachedProfile>()

    private data class CachedProfile(val user: OrbitUser, val fetchedAtMillis: Long)

    // roomId -> (joined count, fetched-at). Without an expiry a room that gained
    // or lost members kept its original count for the whole session, so DM
    // classification stayed wrong until restart.
    private val joinedMemberCountCache = java.util.concurrent.ConcurrentHashMap<String, CachedJoinedCount>()

    private data class CachedJoinedCount(val count: Int, val fetchedAtMillis: Long)

    private companion object {
        const val USER_PROFILE_TTL_MS = 5 * 60 * 1000L
        const val JOINED_MEMBER_COUNT_TTL_MS = 2 * 60 * 1000L
    }

    // Part I: the public matrix.org SFU is a DEV FALLBACK only. Resolution order:
    // settings override → homeserver .well-known org.matrix.msc4143.rtc_foci → this.
    val defaultLiveKitServiceUrl = "https://livekit-jwt.call.matrix.org"

    @Volatile
    var liveKitServiceUrlOverride: String? = null

    @Volatile
    var wellKnownRtcFocusUrl: String? = null
        private set

    /** Non-suspend read of the current endpoint chain; call [refreshWellKnownRtcFoci] at startup. */
    fun effectiveLiveKitServiceUrl(): String =
        liveKitServiceUrlOverride?.takeIf { it.isNotBlank() }
            ?: wellKnownRtcFocusUrl?.takeIf { it.isNotBlank() }
            ?: defaultLiveKitServiceUrl

    /**
     * Fetches `GET /.well-known/matrix/client` and extracts the first LiveKit
     * focus from `org.matrix.msc4143.rtc_foci` (the mechanism Element uses to
     * point clients at a self-hosted SFU). Failures keep the previous value.
     */
    suspend fun refreshWellKnownRtcFoci() {
        try {
            // Build the well-known URL from the homeserver's ORIGIN, taken from
            // the already-parsed and validated URL. The old code stripped the
            // scheme with string surgery and then hardcoded https, which broke
            // the two deployments a self-hoster is most likely to run:
            //   - http://localhost:8008  -> requested https://localhost:8008/...
            //   - https://host:8448      -> requested https://host/...  (port lost)
            // Either way the fetch failed, the failure was swallowed, and the
            // app silently fell back to the public matrix.org SFU -- so a
            // self-hosted call would try to reach the wrong LiveKit.
            val parsed = io.ktor.http.Url(homeserverUrl)
            val origin = buildString {
                append(parsed.protocol.name)
                append("://")
                append(parsed.host)
                val defaultPort = if (parsed.protocol.name == "https") 443 else 80
                if (parsed.port != defaultPort) {
                    append(':')
                    append(parsed.port)
                }
            }
            val response = httpClient.get("$origin/.well-known/matrix/client")
            if (response.status.value !in 200..299) {
                println("DEBUG [MatrixContext] .well-known/matrix.client status=${response.status.value}; keeping current SFU config")
                return
            }
            val body = response.body<JsonObject>()
            val foci = body["org.matrix.msc4143.rtc_foci"] as? JsonArray
            val focusUrl = foci?.firstNotNullOfOrNull { entry ->
                val obj = entry as? JsonObject ?: return@firstNotNullOfOrNull null
                if (obj["type"]?.jsonPrimitive?.content == "livekit") {
                    obj["livekit_service_url"]?.jsonPrimitive?.content
                        ?: obj["livekit_service_url"]?.toString()
                } else null
            }
            if (!focusUrl.isNullOrBlank()) {
                println("DEBUG [MatrixContext] .well-known rtc_foci LiveKit focus: $focusUrl")
                wellKnownRtcFocusUrl = focusUrl
            } else {
                println("DEBUG [MatrixContext] .well-known has no livekit rtc_focus; keeping current SFU config")
            }
        } catch (e: Exception) {
            println("DEBUG [MatrixContext] .well-known fetch failed: ${e.message}")
        }
    }

    fun fullUrl(path: String, v1: Boolean = false): String {
        val version = if (v1) "v1" else "v3"
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "${homeserverUrl.trimEnd('/')}/_matrix/client/$version$cleanPath"
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
            val room = roomFlow.first() ?: continue
            // Left/left-behind rooms and pure invites are not channels: listing
            // them let the user "open" a room they are not in, which then 403s
            // on every timeline load.
            if (room.membership != de.connect2x.trixnity.core.model.events.m.room.Membership.JOIN) continue
            snapshot.add(room)
        }
        return snapshot
    }

    fun roomDisplayName(room: de.connect2x.trixnity.client.store.Room): String {
        return room.name?.explicitName ?: room.name?.heroes?.firstOrNull()?.localpart ?: room.roomId.full.takeLast(8)
    }

    /**
     * Rooms whose MatrixRTC call state says they are a voice room.
     *
     * Why this exists: channel typing used to be `roomName.contains("voice")`.
     * On matrix.org — which is where this was first noticed — a room called
     * "general" that is a perfectly ordinary voice room was typed TEXT, and a
     * text room called "voice-chat-announcements" was typed VOICE. The drawer's
     * entire Voice Channels section was therefore fiction, and a fabricated
     * "General Voice" row was added to paper over the fact that the real voice
     * rooms were missing.
     *
     * The authoritative signal is the room's own `m.call` / `m.call.member`
     * state — specifically the `m.kind` field, which MatrixRTC sets to `"voice"`
     * for voice rooms and which the spec leaves absent for 1:1 calls. This is
     * read from the room state already synced into the client store, so it costs
     * no extra HTTP request.
     *
     * Cached per room and refreshed only when the room's call state actually
     * changes, because `getChannelsForSpace` runs on every `roomListFlow`
     * emission and a per-room HTTP probe there would be dozens of requests per
     * sync.
     */
    private val voiceRoomCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Force a re-probe. Called after a call is joined or left. */
    fun invalidateVoiceRoomCache(roomId: String? = null) {
        if (roomId == null) voiceRoomCache.clear() else voiceRoomCache.remove(roomId)
    }

    /**
     * Whether this room is a voice room, per its own Matrix state.
     *
     * Reads `GET /rooms/{id}/state` — the same all-state scan
     * `MatrixCallsRepository` uses for call definitions, because that is the only
     * endpoint that carries `origin_server_ts` alongside the content. One request
     * per room, cached, so it is not repeated on every sync.
     *
     * [knownCallRoomIds] short-circuits for rooms with a live call so a room you
     * are standing in never flickers between the two headings.
     */
    suspend fun isVoiceRoom(roomId: String, knownCallRoomIds: Set<String> = emptySet()): Boolean {
        if (roomId in knownCallRoomIds) return true
        voiceRoomCache[roomId]?.let { return it }

        val detected = roomStateEvents(roomId).any { element ->
            val raw = element.jsonObject
            val type = raw["type"]?.jsonPrimitive?.contentOrNull
            type in MATRIXRTC_VOICE_STATE_TYPES &&
                callKindOf(raw["content"]?.jsonPrimitive?.contentOrNull)
                    ?.equals("voice", ignoreCase = true) == true
        }
        voiceRoomCache[roomId] = detected
        return detected
    }

    /**
     * The room's full `m.state` array, fetched at most once per TTL and shared.
     *
     * ## Why this is shared
     *
     * `isVoiceRoom` used to issue its own `GET /rooms/{id}/state` and throw the
     * response away. Anything else wanting a state event had no choice but to
     * fetch the whole thing again -- which is how `m.room.topic` came to need a
     * request per room per drawer open, or (as actually happened) to not exist
     * at all: `createRoom` writes the topic and nothing ever read it back.
     *
     * One fetch, cached, every state reader takes what it needs. That strictly
     * REDUCES the request count rather than adding to it.
     *
     * ## Encrypted rooms
     *
     * `m.state` over plain HTTP returns ciphertext for encrypted event types, so
     * in an encrypted room a topic is not readable this way and resolves to `""`
     * -- exactly what `OrbitChannel.topic` defaulted to before, so this can only
     * ever improve. Reading it properly needs the decrypted store, which is its
     * own piece of work rather than a fix here.
     */
    suspend fun roomStateEvents(roomId: String): Array<kotlinx.serialization.json.JsonElement> {
        roomStateCache[roomId]?.let { (events, at) ->
            if (getTimeMillisCompat() - at < ROOM_STATE_TTL_MS) return events
        }
        val events = try {
            val response = httpClient.get(fullUrl("/rooms/$roomId/state")) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
            if (response.status.value !in 200..299) {
                response.drain()
                emptyArray()
            } else {
                Json.parseToJsonElement(response.bodyAsText()).jsonArray.toTypedArray()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyArray()
        }
        roomStateCache[roomId] = events to getTimeMillisCompat()
        return events
    }

    private val roomStateCache = java.util.Collections.synchronizedMap(
        mutableMapOf<String, Pair<Array<kotlinx.serialization.json.JsonElement>, Long>>()
    )

    /**
     * A minute. Long enough that a room list built from several sources does not
     * refetch, short enough that a call started in the room is noticed while the
     * drawer is still open -- `isVoiceRoom` also short-circuits on the call-room
     * set, so this TTL is not what makes a live room light up.
     */
    private val ROOM_STATE_TTL_MS = 60_000L

    /**
     * `m.room.topic` from the cached `m.state`, or `""`.
     *
     * Non-suspending on purpose: this is called while building a channel list, and
     * the state for a room has already been fetched by [isVoiceRoom] on the way
     * there. Returns `""` when the room is not cached yet, which is the value the
     * field defaulted to anyway, so the next drawer open picks it up.
     */
    fun cachedRoomTopic(roomId: String): String {
        val events = roomStateCache[roomId]?.first ?: return ""
        return events.firstNotNullOfOrNull { element ->
            val raw = element.jsonObject
            if (raw["type"]?.jsonPrimitive?.contentOrNull != "m.room.topic") return@firstNotNullOfOrNull null
            (raw["content"] as? kotlinx.serialization.json.JsonObject)
                ?.get("topic") as? JsonPrimitive
        }?.contentOrNull?.trim().orEmpty()
    }

    fun invalidateRoomState(roomId: String) {
        roomStateCache.remove(roomId)
        voiceRoomCache.remove(roomId)
    }

    fun clearRoomStateCache() {
        roomStateCache.clear()
    }

    private fun getTimeMillisCompat(): Long = io.ktor.util.date.getTimeMillis()

    /** `m.call` / `org.matrix.msc3401.call`, definition and member alike. */
    private val MATRIXRTC_VOICE_STATE_TYPES = setOf(
        "m.call", "org.matrix.msc3401.call", "m.call.member", "org.matrix.msc3401.call.member"
    )

    /**
     * Pull `"m.kind"` out of a serialised call-state content object.
     *
     * `m.kind` is `"voice"` for a voice room and absent for a 1:1 call, which is
     * exactly the distinction this needs. Read with a regex rather than a typed
     * decode because the content is attacker-shaped input — anyone in the room
     * can publish room state — and being wrong here only puts a room under the
     * wrong heading.
     */
    private fun callKindOf(contentJson: String?): String? {
        if (contentJson == null || !contentJson.contains("m.kind")) return null
        return Regex("\"m\\.kind\"\\s*:\\s*\"([^\"]+)\"")
            .find(contentJson)?.groupValues?.get(1)
    }

    /**
     * Name-based fallback, used ONLY for rooms this app created itself.
     *
     * `createChannel` names voice rooms `"<name>-voice-channel"`, so a room we
     * made is identifiable without a network round trip. Applying this to
     * arbitrary rooms is what produced the bug above, so it must not be reached
     * for one we did not create — hence the suffix requirement.
     */
    fun inferChannelType(roomName: String): ui.model.ChannelType {
        val looksLikeOurOwnVoiceRoom = Regex(
            "(?i)[-_\\s]+voice(?:[-_\\s]+channel)?$"
        ).containsMatchIn(roomName)
        return if (looksLikeOurOwnVoiceRoom) ui.model.ChannelType.VOICE else ui.model.ChannelType.TEXT
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
        val now = getTimeMillis()
        userProfileCache[targetUserId]?.let { cached ->
            if (now - cached.fetchedAtMillis < USER_PROFILE_TTL_MS) return cached.user
        }
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
            ).also { userProfileCache[targetUserId] = CachedProfile(it, now) }
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

    suspend fun isDMRoom(room: de.connect2x.trixnity.client.store.Room): Boolean {
        if (room.isDirect) return true
        
        val typeStr = room.createEventContent?.type?.toString()
        val isSpace = room.createEventContent?.type == CreateEventContent.RoomType.Space || typeStr?.contains("space", ignoreCase=true) == true
        if (isSpace) return false

        // Heuristic: No explicit name, but has heroes (other participants)
        val hasHeroes = !room.name?.heroes.isNullOrEmpty()
        val noExplicitName = room.name?.explicitName.isNullOrBlank()
        
        if (noExplicitName && hasHeroes) {
            return true
        }

        // Another heuristic: If it has exactly 2 members
        val trixnity = trixnityClient
        if (trixnity != null) {
            try {
                val now = getTimeMillis()
                val cached = joinedMemberCountCache[room.roomId.full]
                if (cached != null && now - cached.fetchedAtMillis < JOINED_MEMBER_COUNT_TTL_MS) {
                    if (cached.count == 2 && noExplicitName) return true
                } else {
                    val joined = trixnity.api.room.getJoinedMembers(room.roomId).getOrNull()?.joined?.size ?: 0
                    if (joined > 0) {
                        joinedMemberCountCache[room.roomId.full] = CachedJoinedCount(joined, now)
                    }
                    if (joined == 2 && noExplicitName) return true
                }
            } catch (e: Exception) {}
        }

        return false
    }
}
