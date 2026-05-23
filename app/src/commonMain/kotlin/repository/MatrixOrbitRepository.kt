package repository

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
                // Return cached recent messages from sync if available
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

            val eventsList = resp.events
            eventsList.mapNotNull { event ->
                if (event.type != "m.room.message") return@mapNotNull null
                val body = extractMessageBody(event.content) ?: return@mapNotNull null
                val sender = event.sender ?: return@mapNotNull null

                OrbitMessage(
                    id = event.eventId ?: "",
                    userId = sender,
                    username = sender,
                    body = body,
                    timestamp = Instant.fromEpochMilliseconds(event.originServerTs ?: 0L),
                    channelId = channelId
                )
            }.sortedBy { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun sendMessage(channelId: String, body: String): Result<String> {
        return try {
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
        return _channelListFlow.asSharedFlow()
    }
    
    fun observeSpaces(): Flow<List<OrbitServer>> {
        return _roomListFlow.asSharedFlow()
    }

    /**
     * Start a sync listener for live events. Call after login.
     */
    fun startSync() {
        GlobalScope.launch {
            var since: String? = null
            while (isActive) {
                try {
                    val urlPath = if (since != null) {
                        "/sync?timeout=30000&since=$since"
                    } else {
                        "/sync?timeout=30000&full_state=true"
                    }
                    val resp: SyncResponse = client.get(fullUrl(urlPath)) {
                        authHeader()?.let { header(HttpHeaders.Authorization, it) }
                    }.body()
                    since = resp.nextBatch
                    _globalNextBatch = since

                    processSyncEvents(resp.rooms)

                    delay(5000)
                } catch (e: Exception) {
                    delay(5000)
                }
            }
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
                val event = eventEl as? JsonObject ?: continue
                val type = (event["type"]?.let { (it as JsonPrimitive).content }) ?: continue
                if (type != "m.room.message") continue

                val content = event["content"]
                val body = extractMessageBody(content) ?: continue
                val sender = (event["sender"]?.let { (it as JsonPrimitive).content }) ?: continue
                val originTsStr = event["origin_server_ts"]?.let {
                    (it as? JsonPrimitive)?.content
                }
                val originTs = originTsStr?.toLongOrNull() ?: continue

                messages.add(OrbitMessage(
                    id = (event["event_id"]?.let { (it as JsonPrimitive).content }) ?: "",
                    userId = sender,
                    username = sender,
                    body = body,
                    timestamp = Instant.fromEpochMilliseconds(originTs),
                    channelId = roomId
                ))
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