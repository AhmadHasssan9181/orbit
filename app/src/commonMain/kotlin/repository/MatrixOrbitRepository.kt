package repository

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.datetime.Instant
import ui.model.*

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
// Live message observer state
// ─────────────────────────────────────────────────────────────
private class LiveMessageState(
    val flow: MutableSharedFlow<List<OrbitMessage>>
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

    fun setAccessToken(token: String) {
        _accessToken = token
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

    private fun fullUrl(path: String): String {
        return "${homeserverUrl.trimEnd('/')}${CLIENT_PREFIX}$path"
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
        ).also { _accessToken = it.accessToken; _userId = it.userId }
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
        java.awt.Desktop.getDesktop().browse(java.net.URI(ssoUrl))

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
        try {
            val resp: JsonObject = client.get(fullUrl("/spaces")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            val rooms = mutableListOf<OrbitServer>()
            val roomSummaries = (resp["rooms"] as? JsonArray)?.toList() ?: emptyList()

            for (roomEl in roomSummaries) {
                val obj = roomEl as? JsonObject ?: continue
                val roomId = (obj["room_id"] as? JsonPrimitive)?.content ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.content ?: "Unnamed Space"
                val topic = (obj["topic"] as? JsonPrimitive)?.content ?: ""

                rooms.add(OrbitServer(
                    id = roomId,
                    name = name,
                    iconColor = hashToColor(roomId),
                    description = topic,
                    isDM = false
                ))
            }
            return rooms
        } catch (e: Exception) {
            return try {
                val resp: JsonObject = client.get(fullUrl("/joined_rooms")) {
                    authHeader()?.let { header(HttpHeaders.Authorization, it) }
                }.body()
                val roomIds = ((resp["joined_rooms"] as? JsonArray)?.map {
                    (it as? JsonPrimitive)?.content
                }.orEmpty().filterNotNull())

                roomIds.map { roomId ->
                    OrbitServer(
                        id = roomId,
                        name = "Room ${roomId.takeLast(8)}",
                        iconColor = hashToColor(roomId),
                        description = "",
                        isDM = false
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
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
        return try {
            val hierarchy: SpaceHierarchyResponse = client.get(fullUrl("/spaces/$spaceId")) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            hierarchy.rooms.mapNotNull { room ->
                if (room.roomId == spaceId) return@mapNotNull null
                val roomId = room.roomId ?: return@mapNotNull null
                val name = extractChannelName(room.name ?: room.canonicalAlias ?: roomId)
                val topic = room.topic ?: ""

                val isVoice = room.encryption != null ||
                    (room.name?.contains("voice", ignoreCase = true) == true) ||
                    (room.name?.contains("call", ignoreCase = true) == true)

                OrbitChannel(
                    id = roomId,
                    name = name,
                    type = if (isVoice) ChannelType.VOICE else ChannelType.TEXT,
                    categoryId = spaceId,
                    topic = topic
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
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
            // Room state is returned as a JSON array of events, not wrapped in an object
            val stateEvents: List<MatrixEvent> = client.get(fullUrl("/rooms/$channelId/state")) {
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

    // ─── Messages ──────────────────────────────────────────────
    override suspend fun getMessagesForChannel(
        channelId: String,
        limit: Int,
        fromToken: String?
    ): List<OrbitMessage> {
        return try {
            val urlPath = buildString {
                append("/rooms/$channelId/messages")
                append("?limit=$limit")
                if (fromToken != null) {
                    append("&dir=b&from=$fromToken")
                } else {
                    append("&dir=f")
                }
            }

            val resp: TimelineResponse = client.get(fullUrl(urlPath)) {
                authHeader()?.let { header(HttpHeaders.Authorization, it) }
            }.body()

            resp.events.mapNotNull { event ->
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
            val txnId = "orbit-${channelId}-${System.currentTimeMillis()}"
            val resp: SendResponse = client.post(
                fullUrl("/rooms/$channelId/send/m.room.message/$txnId")
            ) {
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
                status = UserStatus.ONLINE
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
        val _flow = MutableSharedFlow<List<OrbitChannel>>(replay = 0, extraBufferCapacity = 1)
        GlobalScope.launch {
            try {
                _flow.emit(getChannelsForSpace(spaceId))
            } catch (_: Exception) {}
        }
        return _flow.asSharedFlow()
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
                        "/sync?since=$since"
                    } else {
                        "/sync"
                    }
                    val resp: SyncResponse = client.get(fullUrl(urlPath)) {
                        authHeader()?.let { header(HttpHeaders.Authorization, it) }
                    }.body()
                    since = resp.nextBatch

                    processSyncEvents(resp.rooms)

                    delay(5000)
                } catch (e: Exception) {
                    delay(5000)
                }
            }
        }
    }

    private fun processSyncEvents(events: JsonElement?) {
        val roomsObj = events?.let { it as? JsonObject }
            ?.get("rooms") as? JsonObject ?: return

        val joinRooms = roomsObj.get("join") as? JsonArray ?: return

        for (roomEl in joinRooms) {
            val roomData = (roomEl as? JsonObject)?.get("state") as? JsonObject ?: continue
            val roomId = (roomData["room_id"]?.let { (it as JsonPrimitive).content }) ?: continue

            val timeline = (roomData.get("timeline") as? JsonObject)
                ?.get("events") as? JsonArray ?: continue

            val messages = mutableListOf<OrbitMessage>()
            for (eventEl in timeline) {
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
                liveMessageStates[roomId]?.flow?.tryEmit(messages)
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
            (it as? JsonObject)?.get("body")?.let { it2 -> (it2 as JsonPrimitive).content }
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

    private fun extractRoomFromEventId(eventId: String): String {
        val parts = eventId.split(":")
        return parts.first().removePrefix("!")
    }
}