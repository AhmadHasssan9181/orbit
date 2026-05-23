package repository

import de.connect2x.trixnity.client.*
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.store.repository.room.room as createRoomRepositoriesModule
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.roomId
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.okio.okio
import okio.Path.Companion.toPath
import de.connect2x.trixnity.client.store.repository.room.room

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ui.model.ChannelType
import ui.model.MessageContent
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
import util.openUrlInBrowser

// ─────────────────────────────────────────────────────────────
// Matrix API DTOs (minimal, for the endpoints we use)
// ─────────────────────────────────────────────────────────────

@Serializable
data class MatrixRoomSummary(
    @SerialName("room_id") val roomId: String?,
    @SerialName("name") val name: String?,
    @SerialName("topic") val topic: String?,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("canonical_alias") val canonicalAlias: String? = null,
    @SerialName("joined_members") val joinedMembers: Long? = null,
    @SerialName("encryption") val encryption: String? = null,
)

@Serializable
data class RoomStateResponse(
    @SerialName("state") val stateEvents: List<MatrixEvent> = emptyList()
)

@Serializable
data class MatrixEvent(
    @SerialName("type") val type: String,
    @SerialName("state_key") val stateKey: String? = null,
    @SerialName("content") val content: JsonElement?,
    @SerialName("sender") val sender: String? = null,
    @SerialName("origin_server_ts") val originServerTs: Long? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("room_id") val roomId: String? = null
)

@Serializable
data class TimelineResponse(
    @SerialName("chunk") val events: List<MatrixEvent> = emptyList(),
    @SerialName("start") val start: String? = null,
    @SerialName("end") val end: String? = null
)

@Serializable
data class SendResponse(
    @SerialName("event_id") val eventId: String? = null
)

@Serializable
data class JoinResponse(
    @SerialName("room_id") val roomId: String? = null
)

@Serializable
data class ProfileResponse(
    val displayname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

@Serializable
data class PresenceStatus(
    @SerialName("presence") val presence: String?,
    val status_msg: String? = null
)

@Serializable
data class SyncResponse(
    val rooms: JsonElement? = null,
    val presence: JsonElement? = null,
    @SerialName("next_batch") val nextBatch: String? = null
)

@Serializable
data class SpaceHierarchyResponse(
    @SerialName("chunk") val rooms: List<MatrixRoomSummary> = emptyList(),
    @SerialName("next_batch") val nextBatch: String? = null
)

// ─────────────────────────────────────────────────────────────
// ─────────────────────────────────────────────────────────────
// Live message observer state
// ─────────────────────────────────────────────────────────────
private class LiveMessageState(
    val flow: MutableSharedFlow<List<OrbitMessage>>,
    val messages: MutableList<OrbitMessage> = mutableListOf()
)

// ─────────────────────────────────────────────────────────────
// Room cache state
// ─────────────────────────────────────────────────────────────
data class RoomMetadata(
    val id: String,
    val name: String,
    val topic: String,
    val isDM: Boolean,
    val isSpace: Boolean,
    val memberCount: Int,
    val avatarUrl: String? = null,
    val prevBatch: String? = null
)

/**
 * Matrix-backed implementation of OrbitRepository.
 *
 * Uses Ktor HTTP client for REST API calls and polling /sync for live events.
 * All endpoints follow the standard Matrix Client-Server API v3.
 */
class MatrixOrbitRepository(
    private val homeserverUrl: String,
) : OrbitRepository {

    companion object {
        private const val CLIENT_PREFIX = "/_matrix/client/v3"
    }

    // ─── State ─────────────────────────────────────────────────
    @Volatile
    private var _accessToken: String? = null
    @Volatile
    private var _userId: String? = null
    @Volatile
    private var _globalNextBatch: String? = null

    // ─── Trixnity Client ───────────────────────────────────────
    private var trixnityClient: de.connect2x.trixnity.client.MatrixClient? = null

    // Cache of room metadata extracted from sync
    private val _roomCache = mutableMapOf<String, RoomMetadata>()
    private val _roomListFlow = MutableSharedFlow<List<OrbitServer>>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _channelListFlow = MutableSharedFlow<List<OrbitChannel>>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun setAccessToken(token: String) {
        _accessToken = token
    }
    
    fun setUserId(id: String) {
        _userId = id
    }

    fun getLoggedInUserId(): String? = _userId

    // ─── Internal HTTP client ──────────────────────────────────
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    private val client: HttpClient = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(jsonConfig)
        }
        install(Logging) {
            level = LogLevel.NONE
        }
    }

    // ─── Auth helpers ──────────────────────────────────────────
    private fun authHeader(): String? = _accessToken?.let { "Bearer $it" }

    private fun fullUrl(path: String, v1: Boolean = false): String {
        val prefix = if (v1) "/_matrix/client/v1" else CLIENT_PREFIX
        return "${homeserverUrl.trimEnd('/')}${prefix}$path"
    }

    private suspend inline fun <reified T : Any> matrixGet(path: String): T {
        val url = fullUrl(path)
        return client.get(url) {
            authHeader()?.let { header(HttpHeaders.Authorization, it) }
        }.body()
    }

    private suspend inline fun <reified T : Any> matrixPost(
        path: String, body: Any? = null
    ): T {
        val url = fullUrl(path)
        return client.post(url) {
            authHeader()?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    private suspend inline fun <reified T : Any> matrixPut(
        path: String, body: Any? = null
    ): T {
        val url = fullUrl(path)
        return client.put(url) {
            authHeader()?.let { header(HttpHeaders.Authorization, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    // ─── Authentication ────────────────────────────────────────
    override suspend fun login(
        identifier: String,
        password: String,
        deviceName: String
    ): AuthResult {
        val body = buildJsonObject {
            put("type", "m.login.password")
            put("identifier", buildJsonObject {
                when {
                    identifier.contains(":") && identifier.startsWith("@") -> {
                        put("type", "m.id.user")
                        put("user", identifier.removePrefix("@"))
                    }
                    else -> {
                        put("type", "m.id.user")
                        put("user", identifier)
                    }
                }
            })
            put("password", password)
        }

        val httpResp = client.post(fullUrl("/login")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()

        // Check for error responses
        if (httpResp.status.value != 200) {
            val errMsg = (resp["error"] as? JsonPrimitive)?.content ?: "Login failed (HTTP ${httpResp.status.value})"
            throw IllegalStateException(errMsg)
        }

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("No access_token in response")
        val deviceId = ((resp["device_id"] as? JsonPrimitive)?.content) ?: "orbit_device"
        val userId = (resp["user_id"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("No user_id in response")

        return AuthResult(
            accessToken = accessToken,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            userId = userId
        ).also { 
            _accessToken = it.accessToken
            _userId = it.userId
            CredentialManager.accessToken = it.accessToken
            CredentialManager.deviceId = it.deviceId
            CredentialManager.homeserverUrl = it.homeserverUrl
            CredentialManager.userId = it.userId

            // Initialize Trixnity client
            val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                baseUrl = io.ktor.http.Url(it.homeserverUrl),
                accessToken = it.accessToken,
                accessTokenExpiresInMs = null,
                refreshToken = null
            )
            trixnityClient = MatrixClient.create(
                repositoriesModule = RepositoriesModule.createRoomRepositoriesModule(util.getRoomDatabaseBuilder()),
                mediaStoreModule = MediaStoreModule.okio("orbit_media".toPath()),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData
            ).getOrThrow()
        }
    }

    /**
     * SSO login: opens the system browser for Matrix SSO authentication,
     * starts a temporary local HTTP server to receive the login token callback,
     * then exchanges the token for an access_token.
     */
    suspend fun loginWithSso(): AuthResult = withContext(Dispatchers.IO) {
        val serverSocket = java.net.ServerSocket(0) // random available port
        val port = serverSocket.localPort
        val redirectUrl = "http://localhost:$port"

        // Open browser to Matrix SSO redirect
        val ssoUrl = "${homeserverUrl.trimEnd('/')}${CLIENT_PREFIX}/login/sso/redirect?redirectUrl=${
            java.net.URLEncoder.encode(redirectUrl, "UTF-8")
        }"
        openUrlInBrowser(ssoUrl)

        // Wait for the callback (blocking on IO dispatcher)
        val loginToken: String
        serverSocket.soTimeout = 300_000 // 5 min timeout
        try {
            val socket = serverSocket.accept()
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: throw IllegalStateException("No request received")

            // Parse "GET /?loginToken=xxxxx HTTP/1.1"
            val queryString = requestLine.split(" ").getOrNull(1)?.substringAfter("?") ?: ""
            loginToken = queryString.split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "loginToken" }
                ?.getOrNull(1)
                ?: throw IllegalStateException("No loginToken in callback")

            // Send a nice HTML response to the browser
            val htmlResponse = """
                <html><body style="background:#1a1a2e;color:#e0e0e0;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
                <div style="text-align:center">
                <h1 style="color:#7C6AF0">&#10004; Login Successful</h1>
                <p>You can close this tab and return to Orbit.</p>
                </div></body></html>
            """.trimIndent()
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$htmlResponse"
            socket.getOutputStream().write(response.toByteArray())
            socket.close()
        } finally {
            serverSocket.close()
        }

        // Exchange loginToken for access_token
        val body = buildJsonObject {
            put("type", "m.login.token")
            put("token", loginToken)
            put("initial_device_display_name", "Orbit")
        }
        val httpResp = client.post(fullUrl("/login")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()

        if (httpResp.status.value != 200) {
            val errMsg = (resp["error"] as? JsonPrimitive)?.content ?: "SSO token exchange failed"
            throw IllegalStateException(errMsg)
        }

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("No access_token in SSO response")
        val deviceId = (resp["device_id"] as? JsonPrimitive)?.content ?: "orbit_device"
        val userId = (resp["user_id"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("No user_id in SSO response")

        AuthResult(
            accessToken = accessToken,
            deviceId = deviceId,
            homeserverUrl = homeserverUrl,
            userId = userId
        ).also { _accessToken = it.accessToken; _userId = it.userId }
    }

    override suspend fun register(
        username: String,
        password: String
    ): RegisterResult {
        val body = buildJsonObject {
            put("user", username)
            put("password", password)
            put("initial_device_display_name", "Orbit")
        }

        val httpResp = client.post(fullUrl("/register")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val resp: JsonObject = httpResp.body()

        // Success — some homeservers return access_token directly
        if (httpResp.status.value == 200 && resp.containsKey("access_token")) {
            val accessToken = (resp["access_token"] as? JsonPrimitive)?.content
                ?: throw IllegalStateException("No access_token in response")
            val userId = (resp["user_id"] as? JsonPrimitive)?.content ?: "@$username"
            _accessToken = accessToken
            return RegisterResult(
                authFlow = AuthFlow.Password(username, password),
                nextEndpoint = ""
            )
        }

        // 401 means UIA is required (captcha, terms, etc.)
        if (httpResp.status.value == 401) {
            val flows = resp["flows"]
            throw IllegalStateException(
                "This homeserver requires interactive registration (captcha/terms). " +
                "Please register via the homeserver's web interface, then use Login."
            )
        }

        // Other error
        val errMsg = (resp["error"] as? JsonPrimitive)?.content ?: "Registration failed"
        throw IllegalStateException(errMsg)
    }

    override suspend fun getCurrentUser(): OrbitUser? {
        val userId = _userId ?: return null
        return getUserProfile(userId)
    }

    // ─── Servers / Spaces ──────────────────────────────────────
    override suspend fun getSpaces(): List<OrbitServer> {
        _accessToken ?: return emptyList()
        val rooms = synchronized(_roomCache) { _roomCache.values.toList() }
        
        val servers = mutableListOf<OrbitServer>()
        val dms = rooms.filter { it.isDM }
        val spaces = rooms.filter { it.isSpace }
        val flatRooms = rooms.filter { !it.isSpace && !it.isDM }
        
        if (dms.isNotEmpty()) {
            servers.add(OrbitServer(
                id = "dm",
                name = "Direct Messages",
                iconColor = 0xFF7C6AF0.toInt(),
                description = "Your private conversations",
                isDM = true
            ))
        }
        
        // Add spaces as servers
        spaces.forEach { space ->
            servers.add(OrbitServer(
                id = space.id,
                name = space.name,
                iconColor = hashToColor(space.id),
                description = space.topic,
                isDM = false,
                avatarUrl = space.avatarUrl?.let { convertMxcUrl(it) }
            ))
        }
        
        // Add flat rooms as a default server if there are any
        if (flatRooms.isNotEmpty()) {
            servers.add(OrbitServer(
                id = "rooms",
                name = "Rooms",
                iconColor = 0xFF34D399.toInt(),
                description = "General rooms",
                isDM = false
            ))
        }
        
        return servers
    }

    override suspend fun joinSpace(roomIdOrAlias: String): Result<String> {
        return try {
            val encodedId = roomIdOrAlias.replace("#", "%23").replace("!", "%21").replace(":", "%3A")
            val resp: JoinResponse = client.post(fullUrl("/join/$encodedId")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            Result.success(resp.roomId ?: roomIdOrAlias)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSpace(name: String): Result<String> {
        return try {
            val body = buildJsonObject {
                put("name", name)
                put("topic", "")
                put("type", "m.space")
            }
            val resp: SendResponse = client.post(fullUrl("/createRoom")) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer> {
        return try {
            val resp: SpaceHierarchyResponse = client.get(fullUrl("/spaces/$spaceId")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()
            resp.rooms.map { room ->
                OrbitServer(
                    id = room.roomId ?: "",
                    name = room.name ?: "Unnamed",
                    iconColor = hashToColor(room.roomId ?: ""),
                    description = room.topic ?: ""
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Channels / Rooms ──────────────────────────────────────
    override suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel> {
        val rooms = synchronized(_roomCache) { _roomCache.values.toList() }
        
        val channels = mutableListOf<OrbitChannel>()
        
        when (spaceId) {
            "dm" -> {
                rooms.filter { it.isDM }.forEach { room ->
                    channels.add(OrbitChannel(
                        id = room.id,
                        name = extractChannelName(room.name.ifBlank { room.id }),
                        type = ChannelType.TEXT,
                        categoryId = "dm",
                        topic = room.topic
                    ))
                }
            }
            "rooms" -> {
                rooms.filter { !it.isDM && !it.isSpace }.forEach { room ->
                    channels.add(OrbitChannel(
                        id = room.id,
                        name = extractChannelName(room.name.ifBlank { room.id }),
                        type = ChannelType.TEXT,
                        categoryId = "rooms",
                        topic = room.topic
                    ))
                }
            }
            else -> {
                // If it's a real matrix space, ideally we fetch hierarchy. 
                // For now, if we don't have hierarchy relationships mapped properly, 
                // we'll fetch them from the /hierarchy endpoint.
                try {
                    val hierarchy: SpaceHierarchyResponse = client.get(fullUrl("/hierarchy/$spaceId", v1 = true)) {
                        authHeader()?.let { header(HttpHeaders.Authorization, it) }
                    }.body()
        
                    hierarchy.rooms.forEach { room ->
                        if (room.roomId == spaceId) return@forEach
                        val roomId = room.roomId ?: return@forEach
                        val name = extractChannelName(room.name ?: room.canonicalAlias ?: roomId)
                        val topic = room.topic ?: ""
        
                        val isVoice = room.encryption != null ||
                            (room.name?.contains("voice", ignoreCase = true) == true) ||
                            (room.name?.contains("call", ignoreCase = true) == true)
        
                        channels.add(OrbitChannel(
                            id = roomId,
                            name = name,
                            type = if (isVoice) ChannelType.VOICE else ChannelType.TEXT,
                            categoryId = spaceId,
                            topic = topic
                        ))
                    }
                } catch (e: Exception) {
                    // Fallback to cache if hierarchy fails
                    rooms.filter { !it.isDM && !it.isSpace }.forEach { room ->
                        channels.add(OrbitChannel(
                            id = room.id,
                            name = room.name ?: "#${room.id.takeLast(8)}",
                            type = ChannelType.TEXT,
                            categoryId = "rooms",
                            topic = room.topic ?: "",
                            avatarUrl = room.avatarUrl?.let { convertMxcUrl(it) }
                        ))
                    }
                }
            }
        }
        return channels
    }

    override suspend fun createChannel(
        spaceId: String,
        name: String,
        type: ChannelType,
        topic: String
    ): Result<String> {
        return try {
            val body = buildJsonObject {
                put("name", name)
                put("topic", topic)
                put("preset", if (type == ChannelType.VOICE) "private_chat" else "public_chat")
            }
            val resp: SendResponse = client.post(fullUrl("/createRoom")) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChannelById(channelId: String): OrbitChannel? {
        return try {
            val encodedId = channelId.replace("!", "%21").replace(":", "%3A")
            // Room state is returned as a JSON array of events, not wrapped in an object
            val stateEvents: List<MatrixEvent> = client.get(fullUrl("/rooms/$encodedId/state")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            var roomName: String? = null
            var roomTopic = ""

            for (event in stateEvents) {
                when (event.type) {
                    "m.room.name" -> {
                        val nameEl = event.content?.let {
                            (it as? JsonObject)?.get("name")
                        }
                        roomName = nameEl?.let { (it as JsonPrimitive).content }
                    }
                    "m.room.topic" -> {
                        val topicEl = event.content?.let {
                            (it as? JsonObject)?.get("body")
                        }
                        roomTopic = topicEl?.let { (it as JsonPrimitive).content } ?: ""
                    }
                }
            }

            return OrbitChannel(
                id = channelId,
                name = roomName ?: extractChannelName(channelId),
                type = ChannelType.TEXT,
                categoryId = null,
                topic = roomTopic
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getRoomMembers(roomId: String): List<OrbitUser> {
        return try {
            val encodedId = roomId.replace("!", "%21").replace(":", "%3A")
            val response = matrixGet<TimelineResponse>("/rooms/$encodedId/members?membership=join")
            val eventsList = response.events
            eventsList.mapNotNull { event ->
                if (event.type != "m.room.member") return@mapNotNull null
                val userId = event.stateKey ?: return@mapNotNull null
                val displayName = (event.content as? JsonObject)?.get("displayname")?.let {
                    (it as? JsonPrimitive)?.content
                } ?: userId
                val avatarUrl = (event.content as? JsonObject)?.get("avatar_url")?.let {
                    (it as? JsonPrimitive)?.content
                }

                OrbitUser(
                    id = userId,
                    username = userId,
                    displayName = displayName,
                    avatarColor = hashToColor(userId),
                    status = UserStatus.ONLINE, // Fallback status
                    avatarUrl = avatarUrl?.let { convertMxcUrl(it) }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ─── Messages ──────────────────────────────────────────────
    override suspend fun getMessagesForChannel(
        channelId: String,
        limit: Int,
        fromToken: String?
    ): List<OrbitMessage> {
        return try {
            var actualFromToken = fromToken
            if (fromToken == null) {
                synchronized(liveMessageStates) {
                    val state = liveMessageStates[channelId]
                    if (state != null && state.messages.isNotEmpty()) {
                        return state.messages.takeLast(limit)
                    }
                }
                actualFromToken = synchronized(_roomCache) { _roomCache[channelId]?.prevBatch }
                if (actualFromToken == null) {
                    actualFromToken = _globalNextBatch
                }
            }

            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val urlPath = buildString {
                append("/rooms/$encodedChannelId/messages")
                append("?limit=$limit&dir=b")
                if (actualFromToken != null) {
                    append("&from=$actualFromToken")
                }
            }

            val resp: TimelineResponse = client.get(fullUrl(urlPath)) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            resp.events.mapNotNull { event ->
                matrixEventToOrbitMessage(event, channelId)
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun sendMessage(channelId: String, body: String): Result<String> {
        return try {
            if (trixnityClient != null) {
                trixnityClient?.room?.sendMessage(de.connect2x.trixnity.core.model.RoomId(channelId)) {
                    text(body)
                }
                return Result.success("sent")
            }

            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val txnId = "orbit-msg-${System.currentTimeMillis()}"
            val resp: SendResponse = client.put(
                fullUrl("/rooms/$encodedChannelId/send/m.room.message/$txnId")
            ) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("msgtype", "m.text")
                    put("body", body)
                })
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String> {
        return try {
            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val txnId = "orbit-react-${System.currentTimeMillis()}"
            val resp: SendResponse = client.put(
                fullUrl("/rooms/$encodedChannelId/send/m.reaction/$txnId")
            ) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("m.relates_to", buildJsonObject {
                        put("rel_type", "m.annotation")
                        put("event_id", eventId)
                        put("key", key)
                    })
                })
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadMedia(bytes: ByteArray, mimeType: String, filename: String): Result<String> {
        return try {
            val encodedName = java.net.URLEncoder.encode(filename, "UTF-8")
            val mediaUrl = "${homeserverUrl.trimEnd('/')}/_matrix/media/v3/upload?filename=$encodedName"
            val respText: String = client.post(mediaUrl) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                header(HttpHeaders.ContentType, mimeType)
                setBody(bytes)
            }.body()
            // Parse the content_uri from the JSON response
            val json = Json.parseToJsonElement(respText) as? JsonObject
            val contentUri = (json?.get("content_uri") as? JsonPrimitive)?.content
                ?: return Result.failure(IllegalStateException("No content_uri in upload response"))
            Result.success(contentUri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendImageMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String,
        width: Int?, height: Int?, size: Long?
    ): Result<String> {
        return try {
            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val txnId = "orbit-img-${System.currentTimeMillis()}"
            val resp: SendResponse = client.put(
                fullUrl("/rooms/$encodedChannelId/send/m.room.message/$txnId")
            ) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("msgtype", "m.image")
                    put("body", filename)
                    put("url", mxcUrl)
                    put("info", buildJsonObject {
                        put("mimetype", mimeType)
                        if (width != null) put("w", width)
                        if (height != null) put("h", height)
                        if (size != null) put("size", size)
                    })
                })
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendFileMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String, size: Long?
    ): Result<String> {
        return try {
            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val txnId = "orbit-file-${System.currentTimeMillis()}"
            val resp: SendResponse = client.put(
                fullUrl("/rooms/$encodedChannelId/send/m.room.message/$txnId")
            ) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("msgtype", "m.file")
                    put("body", filename)
                    put("url", mxcUrl)
                    put("info", buildJsonObject {
                        put("mimetype", mimeType)
                        if (size != null) put("size", size)
                    })
                })
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendAudioMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String,
        durationMs: Int?, size: Long?
    ): Result<String> {
        return try {
            val encodedChannelId = channelId.replace("!", "%21").replace(":", "%3A")
            val txnId = "orbit-audio-${System.currentTimeMillis()}"
            val resp: SendResponse = client.put(
                fullUrl("/rooms/$encodedChannelId/send/m.room.message/$txnId")
            ) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("msgtype", "m.audio")
                    put("body", filename)
                    put("url", mxcUrl)
                    put("info", buildJsonObject {
                        put("mimetype", mimeType)
                        if (durationMs != null) put("duration", durationMs)
                        if (size != null) put("size", size)
                    })
                })
            }.body()
            Result.success(resp.eventId ?: "")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editMessage(eventId: String, newBody: String): Result<Unit> {
        return try {
            val txnId = "orbit-edit-${System.currentTimeMillis()}"
            val roomId = extractRoomFromEventId(eventId)
            client.put(fullUrl("/rooms/$roomId/send/m.room.message/$txnId")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("msgtype", "m.text")
                    put("body", newBody)
                    put("org.matrix.msgid", eventId)
                })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(eventId: String): Result<Unit> {
        return try {
            val txnId = "orbit-del-${System.currentTimeMillis()}"
            val roomId = extractRoomFromEventId(eventId)
            client.put(fullUrl("/rooms/$roomId/redact/$eventId/$txnId")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── User Profile ──────────────────────────────────────────
    override suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            val userId = _accessToken?.let { extractUserIdFromToken() }
                ?: return Result.failure(IllegalStateException("Not logged in"))
            client.put(fullUrl("/profile/$userId/displayname")) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("displayname", name) })
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> {
        return try {
            val mediaResp: SendResponse = client.post(fullUrl("/upload")) {
                setBody(imageBytes)
                header(HttpHeaders.ContentType, "image/png")
            }.body()
            Result.success("mxc://${mediaResp.eventId}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserProfile(userId: String): OrbitUser? {
        return try {
            val profile: ProfileResponse = client.get(fullUrl("/profile/$userId/displayname")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            OrbitUser(
                id = userId,
                username = userId.split(":").first().removePrefix("@"),
                displayName = profile.displayname ?: userId,
                avatarColor = hashToColor(userId),
                status = UserStatus.ONLINE,
                avatarUrl = profile.avatarUrl?.let { convertMxcUrl(it) }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ─── Presence / Status ─────────────────────────────────────
    override suspend fun setPresence(status: UserStatus) {
        try {
            val matrixStatus = when (status) {
                UserStatus.ONLINE -> "online"
                UserStatus.IDLE -> "unavailable"
                UserStatus.DO_NOT_DISTURB -> "dnd"
                UserStatus.OFFLINE -> "offline"
            }
            val userId = extractUserIdFromToken() ?: return
            client.put(fullUrl("/presence/$userId/status")) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("presence", matrixStatus) })
            }
        } catch (_: Exception) {}
    }

    override suspend fun observePresence(userId: String): Flow<UserStatus> {
        return MutableSharedFlow<UserStatus>(replay = 1).apply {
            try {
                val resp: PresenceStatus = client.get(fullUrl("/presence/$userId/status")) {
                    authHeader()?.let { header(HttpHeaders.Authorization, it) }
                }.body()
                val status = when (resp.presence) {
                    "online" -> UserStatus.ONLINE
                    "unavailable", "offline" -> UserStatus.OFFLINE
                    "dnd" -> UserStatus.DO_NOT_DISTURB
                    else -> UserStatus.OFFLINE
                }
                tryEmit(status)
            } catch (_: Exception) {
                tryEmit(UserStatus.OFFLINE)
            }
        }
    }

    // ─── Real-time Events ──────────────────────────────────────
    private val liveMessageStates = mutableMapOf<String, LiveMessageState>()

    override fun observeMessages(channelId: String): Flow<List<OrbitMessage>> {
        return synchronized(liveMessageStates) {
            liveMessageStates.getOrPut(channelId) {
                LiveMessageState(MutableSharedFlow(replay = 0))
            }.flow.asSharedFlow()
        }
    }

    override fun observeChannelList(spaceId: String): Flow<List<OrbitChannel>> {
        return _roomListFlow.map { getChannelsForSpace(spaceId) }
    }
    
    fun observeSpaces(): Flow<List<OrbitServer>> {
        return _roomListFlow.asSharedFlow()
    }

    /**
     * Start a sync listener for live events using Trixnity SDK.
     */
    fun startSync() {
        GlobalScope.launch {
            if (trixnityClient == null && _accessToken != null && _userId != null) {
                val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                    baseUrl = io.ktor.http.Url(homeserverUrl),
                    accessToken = _accessToken!!,
                    accessTokenExpiresInMs = null,
                    refreshToken = null
                )
                trixnityClient = MatrixClient.create(
                    repositoriesModule = RepositoriesModule.createRoomRepositoriesModule(util.getRoomDatabaseBuilder()),
                    mediaStoreModule = MediaStoreModule.okio("orbit_media".toPath()),
                    cryptoDriverModule = CryptoDriverModule.vodozemac(),
                    authProviderData = authProviderData
                ).getOrThrow()
            }

            // Move startSync down because it suspends indefinitely
            // 1. Maintain Room Cache from Trixnity
            launch {
                val trixnity = trixnityClient ?: return@launch
                trixnity.room.getAll().flattenValues().collect { rooms ->
                    var updated = false
                    for (room in rooms) {
                        if (room == null) continue
                        val metadata = RoomMetadata(
                            id = room.roomId.full,
                            name = room.name?.explicitName ?: room.roomId.full.takeLast(8),
                            topic = "", // Trixnity doesn't expose topic in Room easily, fallback to empty
                            isSpace = false, // Temporarily mark all as normal channels
                            isDM = room.isDirect,
                            avatarUrl = room.avatarUrl,
                            memberCount = 0,
                            prevBatch = null
                        )
                        if (_roomCache[room.roomId.full] != metadata) {
                            _roomCache[room.roomId.full] = metadata
                            updated = true
                        }
                    }
                    if (updated) {
                        _roomListFlow.emit(getSpaces())
                    }
                }
            }

            // 2. Listen to Live Timeline Events
            launch {
                val trixnity = trixnityClient ?: return@launch
                trixnity.room.getTimelineEventsFromNowOn(decryptionTimeout = kotlin.time.Duration.parse("PT5S")).collect { timelineEvent ->
                    val content = timelineEvent.content?.getOrNull()
                    val roomId = timelineEvent.roomId.full
                    
                    val event = timelineEvent.event
                    if (event is de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent<*>) {
                        val eventId = event.id.full
                        if (content is de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent.TextBased.Text) {
                            val orbitMessage = OrbitMessage(
                                id = eventId,
                                userId = event.sender.full,
                                username = event.sender.full,
                                body = content.body,
                                channelId = roomId,
                                content = MessageContent.Text(body = content.body),
                                timestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(event.originTimestamp)
                            )
                            val messages = listOf(orbitMessage)
                            synchronized(liveMessageStates) {
                                val state = liveMessageStates.getOrPut(roomId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                                state.messages.addAll(messages)
                                state.flow.tryEmit(messages)
                            }
                        }
                    }
                }
            }
            // Finally, start the sync loop which will suspend indefinitely
            trixnityClient?.startSync()
        }
    }

    private fun processSyncEvents(roomsObj: JsonElement?) {
        if (roomsObj !is JsonObject) return

        val joinRooms = roomsObj.get("join") as? JsonObject ?: return
        
        var roomsUpdated = false

        for ((roomId, roomDataEl) in joinRooms) {
            val roomData = roomDataEl as? JsonObject ?: continue
            val prevBatch = (roomData["timeline"] as? JsonObject)?.get("prev_batch")?.let { (it as? JsonPrimitive)?.content }
            val state = (roomData["state"] as? JsonObject)?.get("events") as? JsonArray
            val timelineObj = roomData["timeline"] as? JsonObject
            val timelineEvents = timelineObj?.get("events") as? JsonArray

            // Process State to update Cache
            var name = _roomCache[roomId]?.name ?: roomId.takeLast(8)
            var topic = _roomCache[roomId]?.topic ?: ""
            var isSpace = _roomCache[roomId]?.isSpace ?: false
            val isDM = _roomCache[roomId]?.isDM ?: false
            var avatarUrl = _roomCache[roomId]?.avatarUrl

            val allStateEvents = mutableListOf<JsonObject>()
            state?.forEach { if (it is JsonObject) allStateEvents.add(it) }
            timelineEvents?.forEach { if (it is JsonObject && it["state_key"] != null) allStateEvents.add(it) }

            for (event in allStateEvents) {
                val type = (event["type"] as? JsonPrimitive)?.content ?: continue
                val content = event["content"] as? JsonObject
                when (type) {
                    "m.room.name" -> name = (content?.get("name") as? JsonPrimitive)?.content ?: name
                    "m.room.topic" -> topic = (content?.get("topic") as? JsonPrimitive)?.content ?: topic
                    "m.room.create" -> {
                        if ((content?.get("type") as? JsonPrimitive)?.content == "m.space") isSpace = true
                    }
                    "m.space.child" -> isSpace = true
                    "m.room.avatar" -> avatarUrl = (content?.get("url") as? JsonPrimitive)?.content ?: avatarUrl
                }
            }
            
            // Check DM via m.direct (For now, simplified, but we keep the current isDM flag)
            
            val updatedMetadata = RoomMetadata(roomId, name, topic, isDM, isSpace, 0, avatarUrl, prevBatch)
            if (_roomCache[roomId] != updatedMetadata) {
                synchronized(_roomCache) { _roomCache[roomId] = updatedMetadata }
                roomsUpdated = true
            }

            if (timelineEvents == null) continue
            val messages = mutableListOf<OrbitMessage>()
            for (eventEl in timelineEvents) {
                val eventObj = eventEl as? JsonObject ?: continue
                val type = (eventObj["type"] as? JsonPrimitive)?.content ?: continue
                val sender = (eventObj["sender"] as? JsonPrimitive)?.content ?: continue
                val originTs = (eventObj["origin_server_ts"] as? JsonPrimitive)?.content?.toLongOrNull() ?: continue
                val eventId = (eventObj["event_id"] as? JsonPrimitive)?.content
                val content = eventObj["content"]
                val matrixEvent = MatrixEvent(
                    type = type,
                    stateKey = (eventObj["state_key"] as? JsonPrimitive)?.content,
                    content = content,
                    sender = sender,
                    originServerTs = originTs,
                    eventId = eventId,
                    roomId = roomId
                )
                matrixEventToOrbitMessage(matrixEvent, roomId)?.let { messages.add(it) }
            }

            synchronized(liveMessageStates) {
                val state = liveMessageStates.getOrPut(roomId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                state.messages.addAll(messages)
                state.flow.tryEmit(messages)
            }
        }
        
        if (roomsUpdated) {
            GlobalScope.launch {
                _roomListFlow.emit(getSpaces())
            }
        }
    }

    /**
     * Stop the sync listener.
     */
    fun stopSync() {
        synchronized(liveMessageStates) {
            liveMessageStates.clear()
        }
    }

    // ─── Utility functions ─────────────────────────────────────
    private fun extractChannelName(raw: String): String {
        return when {
            raw.startsWith("!") -> raw.split(":").first().removePrefix("!")
            raw.startsWith("#") -> raw.split(":").first().removePrefix("#")
            else -> raw
        }
    }

    /**
     * Convert a raw Matrix event to an OrbitMessage, handling all msgtype variants
     * plus reactions, redactions, and edits.
     */
    private fun matrixEventToOrbitMessage(event: MatrixEvent, channelId: String): OrbitMessage? {
        val sender = event.sender ?: return null
        val ts = Instant.fromEpochMilliseconds(event.originServerTs ?: 0L)
        val eventId = event.eventId ?: return null
        val content = event.content as? JsonObject

        when (event.type) {
            "m.room.message" -> {
                val msgtype = (content?.get("msgtype") as? JsonPrimitive)?.content ?: ""
                val body = (content?.get("body") as? JsonPrimitive)?.content ?: ""

                // Check for relation (reply or edit)
                val relatesTo = content?.get("m.relates_to") as? JsonObject
                val relType = (relatesTo?.get("rel_type") as? JsonPrimitive)?.content
                val inReplyTo = (relatesTo?.get("m.in_reply_to") as? JsonObject)?.get("event_id")
                    ?.let { (it as? JsonPrimitive)?.content }

                val parsedContent: MessageContent = when {
                    relType == "m.replace" -> {
                        // Edit event — get the new content
                        val newContentObj = content?.get("m.new_content") as? JsonObject
                        val newBody = (newContentObj?.get("body") as? JsonPrimitive)?.content ?: body
                        val newMsgtype = (newContentObj?.get("msgtype") as? JsonPrimitive)?.content ?: msgtype
                        val replacesId = (relatesTo?.get("event_id") as? JsonPrimitive)?.content ?: ""
                        MessageContent.Edit(
                            replacesEventId = replacesId,
                            newContent = parseMessageContent(newMsgtype, newBody, newContentObj)
                        )
                    }
                    inReplyTo != null -> {
                        // Reply — strip the fallback quote lines from body
                        val cleanBody = body.lines()
                            .dropWhile { it.startsWith(">") }
                            .dropWhile { it.isBlank() }
                            .joinToString("\n")
                            .trim()
                        val quotedBody = body.lines()
                            .takeWhile { it.startsWith(">") }
                            .joinToString("\n") { it.removePrefix("> ") }
                            .trim()
                        val quotedSender = (content?.get("m.relates_to") as? JsonObject)
                            ?.get("m.in_reply_to")?.let { it as? JsonObject }
                            ?.get("sender")?.let { (it as? JsonPrimitive)?.content } ?: ""
                        MessageContent.Reply(
                            inReplyToEventId = inReplyTo,
                            quotedSenderId = quotedSender,
                            quotedBody = quotedBody,
                            actualContent = parseMessageContent(msgtype, cleanBody.ifBlank { body }, content)
                        )
                    }
                    else -> parseMessageContent(msgtype, body, content)
                }

                return OrbitMessage(
                    id = eventId,
                    userId = sender,
                    username = sender,
                    body = body,
                    timestamp = ts,
                    channelId = channelId,
                    content = parsedContent,
                    replyToEventId = inReplyTo
                )
            }
            "m.reaction" -> {
                val relatesTo = content?.get("m.relates_to") as? JsonObject
                val reactsToId = (relatesTo?.get("event_id") as? JsonPrimitive)?.content ?: return null
                val key = (relatesTo?.get("key") as? JsonPrimitive)?.content ?: return null
                return OrbitMessage(
                    id = eventId,
                    userId = sender,
                    username = sender,
                    body = key,
                    timestamp = ts,
                    channelId = channelId,
                    content = MessageContent.Reaction(reactsToEventId = reactsToId, key = key)
                )
            }
            "m.room.redaction" -> {
                val reason = (content?.get("reason") as? JsonPrimitive)?.content
                return OrbitMessage(
                    id = eventId,
                    userId = sender,
                    username = sender,
                    body = reason ?: "Message deleted",
                    timestamp = ts,
                    channelId = channelId,
                    content = MessageContent.Redacted(reason)
                )
            }
            else -> return null
        }
    }

    /**
     * Parse the core message content for a given msgtype.
     */
    private fun parseMessageContent(
        msgtype: String,
        body: String,
        content: JsonObject?
    ): MessageContent {
        val info = content?.get("info") as? JsonObject
        return when (msgtype) {
            "m.text" -> {
                val formattedBody = (content?.get("formatted_body") as? JsonPrimitive)?.content
                MessageContent.Text(body = body, formattedBody = formattedBody)
            }
            "m.image" -> {
                val rawUrl = (content?.get("url") as? JsonPrimitive)?.content ?: ""
                val url = convertMxcUrl(rawUrl)
                val thumbUrl = (info?.get("thumbnail_url") as? JsonPrimitive)?.content
                    ?.let { convertMxcUrl(it) }
                MessageContent.Image(
                    url = url,
                    thumbnailUrl = thumbUrl ?: url,
                    width = (info?.get("w") as? JsonPrimitive)?.content?.toIntOrNull(),
                    height = (info?.get("h") as? JsonPrimitive)?.content?.toIntOrNull(),
                    body = body,
                    mimeType = (info?.get("mimetype") as? JsonPrimitive)?.content,
                    size = (info?.get("size") as? JsonPrimitive)?.content?.toLongOrNull()
                )
            }
            "m.audio" -> {
                val rawUrl = (content?.get("url") as? JsonPrimitive)?.content ?: ""
                val waveformArray = (content?.get("org.matrix.msc3245.voice") as? JsonObject)
                    ?.let { _ -> content["org.matrix.msc1767.audio"] as? JsonObject }
                    ?.let { audio -> audio["waveform"] as? JsonArray }
                    ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                MessageContent.Audio(
                    url = convertMxcUrl(rawUrl),
                    body = body,
                    durationMs = (info?.get("duration") as? JsonPrimitive)?.content?.toIntOrNull(),
                    mimeType = (info?.get("mimetype") as? JsonPrimitive)?.content,
                    size = (info?.get("size") as? JsonPrimitive)?.content?.toLongOrNull(),
                    waveform = waveformArray
                )
            }
            "m.video" -> {
                val rawUrl = (content?.get("url") as? JsonPrimitive)?.content ?: ""
                val thumbUrl = (info?.get("thumbnail_url") as? JsonPrimitive)?.content
                    ?.let { convertMxcUrl(it) }
                MessageContent.Video(
                    url = convertMxcUrl(rawUrl),
                    thumbnailUrl = thumbUrl,
                    body = body,
                    durationMs = (info?.get("duration") as? JsonPrimitive)?.content?.toIntOrNull(),
                    width = (info?.get("w") as? JsonPrimitive)?.content?.toIntOrNull(),
                    height = (info?.get("h") as? JsonPrimitive)?.content?.toIntOrNull(),
                    mimeType = (info?.get("mimetype") as? JsonPrimitive)?.content,
                    size = (info?.get("size") as? JsonPrimitive)?.content?.toLongOrNull()
                )
            }
            "m.file" -> {
                val rawUrl = (content?.get("url") as? JsonPrimitive)?.content ?: ""
                MessageContent.File(
                    url = convertMxcUrl(rawUrl),
                    filename = (content?.get("filename") as? JsonPrimitive)?.content ?: body,
                    mimeType = (info?.get("mimetype") as? JsonPrimitive)?.content,
                    size = (info?.get("size") as? JsonPrimitive)?.content?.toLongOrNull()
                )
            }
            "m.notice" -> MessageContent.Notice(body = body)
            "m.emote" -> MessageContent.Emote(body = body)
            "m.sticker" -> {
                val rawUrl = (content?.get("url") as? JsonPrimitive)?.content ?: ""
                MessageContent.Sticker(
                    url = convertMxcUrl(rawUrl),
                    body = body,
                    width = (info?.get("w") as? JsonPrimitive)?.content?.toIntOrNull(),
                    height = (info?.get("h") as? JsonPrimitive)?.content?.toIntOrNull()
                )
            }
            else -> MessageContent.Unsupported(type = msgtype, fallbackBody = body)
        }
    }

    private fun extractMessageBody(content: JsonElement?): String? {
        return content?.let {
            val obj = it as? JsonObject
            if (obj?.get("msgtype")?.let { t -> (t as JsonPrimitive).content } == "m.text") {
                obj["body"]?.let { b -> (b as JsonPrimitive).content }
            } else null
        }
    }

    private fun hashToColor(input: String): Int {
        val hash = input.hashCode()
        val r = (hash and 0xFF)
        val g = ((hash shr 8) and 0xFF)
        val b = ((hash shr 16) and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun extractUserIdFromToken(): String? {
        return _userId
    }

    private fun convertMxcUrl(mxcUrl: String): String {
        if (!mxcUrl.startsWith("mxc://")) return mxcUrl
        val parts = mxcUrl.removePrefix("mxc://").split("/")
        if (parts.size != 2) return mxcUrl
        return "${homeserverUrl.trimEnd('/')}/_matrix/media/r3/download/${parts[0]}/${parts[1]}"
    }

    private fun extractRoomFromEventId(eventId: String): String {
        val parts = eventId.split(":")
        return parts.first().removePrefix("!")
    }
}