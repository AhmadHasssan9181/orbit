package repository

import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.client.cryptodriver.vodozemac.vodozemac
import de.connect2x.trixnity.client.media.okio.okio
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.client.room.message.roomMessageBuilder
import de.connect2x.trixnity.client.store.Room
import de.connect2x.trixnity.client.store.RoomOutboxMessage
import de.connect2x.trixnity.client.store.TimelineEvent
import de.connect2x.trixnity.client.store.eventId
import de.connect2x.trixnity.client.store.repository.room.room
import de.connect2x.trixnity.client.store.roomId
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.client.store.type
import de.connect2x.trixnity.client.user
import de.connect2x.trixnity.clientserverapi.model.media.Media
import de.connect2x.trixnity.clientserverapi.model.room.CreateRoom
import de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction
import de.connect2x.trixnity.clientserverapi.model.user.ProfileField
import de.connect2x.trixnity.clientserverapi.model.user.avatarUrl
import de.connect2x.trixnity.clientserverapi.model.user.displayName
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent.RoomEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.m.Presence
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
import de.connect2x.trixnity.client.verification
import de.connect2x.trixnity.client.verification.ActiveDeviceVerification
import de.connect2x.trixnity.client.room
import de.connect2x.trixnity.client.user
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ui.model.ChannelType
import ui.model.MessageContent
import ui.model.displayText
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
import util.getMediaStorePath
import util.getRoomDatabaseBuilder
import util.performSsoLogin
import kotlin.time.Duration.Companion.seconds


@Serializable
data class TurnServerResponse(
    val uris: List<String>,
    val username: String? = null,
    val password: String? = null,
    val ttl: Long? = null
)

private class LiveMessageState(
    val flow: MutableSharedFlow<List<OrbitMessage>>,
    val messages: MutableList<OrbitMessage> = mutableListOf()
)

/**
 * Matrix-backed implementation of OrbitRepository.
 */
class MatrixOrbitRepository(
    private val homeserverUrl: String
) : OrbitRepository {

    companion object {
        private const val CLIENT_PREFIX = "/_matrix/client/v3"
    }

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var _accessToken: String? = CredentialManager.accessToken
    private var _userId: String? = CredentialManager.userId
    private var trixnityClient: MatrixClient? = null

    private val _roomListFlow = MutableSharedFlow<List<OrbitServer>>(replay = 1)
    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 10)

    private val _authStatus = MutableStateFlow<AuthStatus>(AuthStatus.Authenticated)
    override val authStatus: Flow<AuthStatus> = _authStatus.asStateFlow()

    override suspend fun logout() {
        trixnityClient?.stopSync()
        trixnityClient = null
        CredentialManager.clear()
        _authStatus.value = AuthStatus.Unauthenticated
    }

    override val activeDeviceVerification: Flow<de.connect2x.trixnity.client.verification.ActiveDeviceVerification?>
        get() = trixnityClient?.verification?.activeDeviceVerification ?: kotlinx.coroutines.flow.flowOf(null)

    fun setAccessToken(token: String) {
        _accessToken = token
    }

    fun setUserId(id: String) {
        _userId = id
    }

    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
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

    private fun fullUrl(path: String, v1: Boolean = false): String {
        val prefix = if (v1) "/_matrix/client/v1" else CLIENT_PREFIX
        return "${homeserverUrl.trimEnd('/')}${prefix}$path"
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
                put("type", "m.id.user")
                put("user", identifier.removePrefix("@"))
            })
            put("password", password)
        }

        val httpResp = client.post(fullUrl("/login")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()

        if (httpResp.status.value != 200) {
            val errMsg = (resp["error"] as? JsonPrimitive)?.content ?: "Login failed"
            throw IllegalStateException(errMsg)
        }

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content ?: ""
        val deviceId = (resp["device_id"] as? JsonPrimitive)?.content ?: "orbit_device"
        val userId = (resp["user_id"] as? JsonPrimitive)?.content ?: ""

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

            val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                baseUrl = Url(it.homeserverUrl),
                accessToken = it.accessToken,
                accessTokenExpiresInMs = null,
                refreshToken = null
            )
            trixnityClient = MatrixClient.create(
                repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData
            ).getOrThrow()
        }
    }

    suspend fun loginWithSso(): AuthResult = withContext(Dispatchers.IO) {
        val loginToken = performSsoLogin(homeserverUrl)

        val body = buildJsonObject {
            put("type", "m.login.token")
            put("token", loginToken)
        }
        val httpResp = client.post(fullUrl("/login")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content ?: ""
        val deviceId = (resp["device_id"] as? JsonPrimitive)?.content ?: "orbit_device"
        val userId = (resp["user_id"] as? JsonPrimitive)?.content ?: ""

        AuthResult(accessToken, deviceId, homeserverUrl, userId).also {
            _accessToken = it.accessToken
            _userId = it.userId
            CredentialManager.accessToken = it.accessToken
            CredentialManager.deviceId = it.deviceId
            CredentialManager.homeserverUrl = it.homeserverUrl
            CredentialManager.userId = it.userId

            val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                baseUrl = Url(it.homeserverUrl),
                accessToken = it.accessToken,
                accessTokenExpiresInMs = null,
                refreshToken = null
            )
            trixnityClient = MatrixClient.create(
                repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData
            ).getOrThrow()
        }
    }

    override suspend fun register(
        username: String,
        password: String
    ): RegisterResult {
        val body = buildJsonObject {
            put("user", username)
            put("password", password)
        }
        val httpResp = client.post(fullUrl("/register")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()
        if (httpResp.status.value == 200 && resp.containsKey("access_token")) {
            return RegisterResult(
                authFlow = AuthFlow.Password(username, password),
                nextEndpoint = ""
            )
        }
        throw IllegalStateException("Registration failed")
    }

    override suspend fun getCurrentUser(): OrbitUser? {
        val userId = _userId ?: return null
        return getUserProfile(userId)
    }

    private suspend fun getAllRoomsSnapshot(): List<Room> {
        val trixnity = trixnityClient ?: return emptyList()
        val allRoomsMap = trixnity.room.getAll().first()
        val snapshot = mutableListOf<Room>()
        for (roomFlow in allRoomsMap.values) {
            roomFlow.first()?.let { snapshot.add(it) }
        }
        return snapshot
    }

    // ─── Servers / Spaces ──────────────────────────────────────
    override suspend fun getSpaces(): List<OrbitServer> {
        val allRooms = getAllRoomsSnapshot()
        val servers = mutableListOf<OrbitServer>()
        val dms = allRooms.filter { it.isDirect }
        val spaces = allRooms.filter { it.type == CreateEventContent.RoomType.Space }
        val flatRooms = allRooms.filter { it.type != CreateEventContent.RoomType.Space && !it.isDirect }

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
            servers.add(OrbitServer(
                id = space.roomId.full,
                name = space.name?.explicitName ?: space.roomId.full.takeLast(8),
                iconColor = hashToColor(space.roomId.full),
                description = "",
                isDM = false,
                avatarUrl = space.avatarUrl?.let { convertMxcUrl(it) }
            ))
        }

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
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = trixnity.api.room.joinRoom(RoomId(roomIdOrAlias)).getOrThrow()
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createSpace(name: String): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = trixnity.api.room.createRoom(
                name = name,
                creationContent = CreateEventContent(type = CreateEventContent.RoomType.Space)
            ).getOrThrow()
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSpaceHierarchy(spaceId: String): List<OrbitServer> {
        return emptyList()
    }

    override suspend fun getTurnServers(): Result<List<TurnServer>> = kotlin.runCatching {
        val httpResp = client.get(fullUrl("/voip/turnServer")) {
            header(HttpHeaders.Authorization, "Bearer ${_accessToken}")
        }
        val resp: TurnServerResponse = httpResp.body()
        resp.uris.map { TurnServer(listOf(it), resp.username, resp.password) }
    }

    // ─── Channels / Rooms ──────────────────────────────────────
    override suspend fun getChannelsForSpace(spaceId: String): List<OrbitChannel> {
        val allRooms = getAllRoomsSnapshot()
        val channels = mutableListOf<OrbitChannel>()

        when (spaceId) {
            "dm" -> {
                allRooms.filter { it.isDirect }.forEach { room ->
                    channels.add(OrbitChannel(
                        id = room.roomId.full,
                        name = room.name?.explicitName ?: room.roomId.full.takeLast(8),
                        type = ChannelType.TEXT,
                        categoryId = "dm",
                        topic = ""
                    ))
                }
            }
            "rooms" -> {
                allRooms.filter { !it.isDirect && it.type != CreateEventContent.RoomType.Space }.forEach { room ->
                    channels.add(OrbitChannel(
                        id = room.roomId.full,
                        name = room.name?.explicitName ?: room.roomId.full.takeLast(8),
                        type = ChannelType.TEXT,
                        categoryId = "rooms",
                        topic = ""
                    ))
                }
            }
            else -> {
                val trixnity = trixnityClient ?: return emptyList()
                val spaceRoomId = RoomId(spaceId)
                val children = trixnity.room.getAllState(spaceRoomId, ChildEventContent::class).first()

                children.forEach { (childRoomIdStr, childEventFlow) ->
                    val childRoomId = try { RoomId(childRoomIdStr) } catch (_: Exception) { null }
                    if (childRoomId != null) {
                        val room = allRooms.find { it.roomId == childRoomId }
                        if (room != null) {
                            channels.add(OrbitChannel(
                                id = room.roomId.full,
                                name = room.name?.explicitName ?: room.roomId.full.takeLast(8),
                                type = if (room.name?.explicitName?.contains("voice", ignoreCase = true) == true) ChannelType.VOICE else ChannelType.TEXT,
                                categoryId = spaceId,
                                topic = ""
                            ))
                        }
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
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = trixnity.api.room.createRoom(
                name = name,
                topic = topic,
                preset = if (type == ChannelType.VOICE) {
                    CreateRoom.Request.Preset.TRUSTED_PRIVATE
                } else {
                    CreateRoom.Request.Preset.TRUSTED_PRIVATE // Using TRUSTED_PRIVATE as fallback to PUBLIC_CHAT
                }
            ).getOrThrow()

            if (spaceId != "rooms" && spaceId != "dm") {
                trixnity.api.room.sendStateEvent(
                    RoomId(spaceId),
                    ChildEventContent(via = setOf(trixnity.api.baseUrl.host)),
                    response.full
                )
            }

            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getChannelById(channelId: String): OrbitChannel? {
        return try {
            val trixnity = trixnityClient ?: return null
            val room = trixnity.room.getById(RoomId(channelId)).first() ?: return null

            return OrbitChannel(
                id = channelId,
                name = room.name?.explicitName ?: channelId.takeLast(8),
                type = if (room.name?.explicitName?.contains("voice", ignoreCase = true) == true) ChannelType.VOICE else ChannelType.TEXT,
                categoryId = null,
                topic = ""
            )
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getRoomMembers(roomId: String): List<OrbitUser> {
        return try {
            val trixnity = trixnityClient ?: return emptyList()
            val response = trixnity.api.room.getJoinedMembers(RoomId(roomId)).getOrThrow()
            response.joined.map { (userId, member) ->
                OrbitUser(
                    id = userId.full,
                    username = userId.full,
                    displayName = member.displayName ?: userId.full,
                    avatarColor = hashToColor(userId.full),
                    status = UserStatus.ONLINE,
                    avatarUrl = member.avatarUrl?.let { convertMxcUrl(it.toString()) }
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
        val trixnity = trixnityClient ?: return emptyList()
        val roomIdObj = RoomId(channelId)

        try {
            val lastEventId = trixnity.room.getById(roomIdObj).first()?.lastEventId
                ?: return emptyList()

            val eventsFlow = trixnity.room.getTimelineEvents(
                roomIdObj,
                startFrom = lastEventId,
                direction = Direction.BACKWARDS
            )

            val timelineMessages = eventsFlow.take(limit).toList().mapNotNull { eventFlow ->
                val timelineEvent = eventFlow.first()
                timelineEventToOrbitMessage(timelineEvent)
            }.reversed()

            val outboxList = trixnity.room.getOutbox(roomIdObj).first()
            val outboxMessages = outboxList.mapNotNull { it.first() }
                .map { outboxMessageToOrbitMessage(it) }

            return (timelineMessages + outboxMessages)
                .distinctBy { it.transactionId ?: it.id }
                .sortedBy { it.timestamp }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun outboxMessageToOrbitMessage(outboxMessage: RoomOutboxMessage<*>): OrbitMessage {
        val content = outboxMessage.content
        val txnId = outboxMessage.transactionId
        val roomId = outboxMessage.roomId.full
        val sender = _userId ?: "me"
        val ts = Instant.fromEpochMilliseconds(outboxMessage.createdAt.toEpochMilliseconds())

        val orbitContent: MessageContent = if (content is RoomMessageEventContent) {
            parseTrixnityMessageContent(content)
        } else {
            MessageContent.Text("Sending message...")
        }

        return OrbitMessage(
            id = txnId,
            userId = sender,
            username = sender,
            body = orbitContent.displayText,
            timestamp = ts,
            channelId = roomId,
            content = orbitContent,
            transactionId = txnId
        )
    }

    override suspend fun sendMessage(channelId: String, body: String): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                text(body)
            }

            // Manually emit a local echo
            val orbitMessage = OrbitMessage(
                id = txnId,
                userId = _userId ?: "me",
                username = _userId ?: "me",
                body = body,
                timestamp = Instant.fromEpochMilliseconds(1716500000000L),
                channelId = channelId,
                content = MessageContent.Text(body),
                transactionId = txnId
            )
            synchronized(liveMessageStates) {
                val state = liveMessageStates.getOrPut(channelId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                state.messages.add(orbitMessage)
                state.flow.tryEmit(listOf(orbitMessage))
            }

            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendReaction(channelId: String, eventId: String, key: String): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.api.room.sendMessageEvent(
                RoomId(channelId),
                ReactionEventContent(
                    RelatesTo.Annotation(
                        EventId(eventId),
                        key
                    )
                )
            ).getOrThrow()
            Result.success("sent")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendCallEvent(channelId: String, eventType: String, content: JsonObject): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val unknownContent = trixnity.api.json.decodeFromJsonElement<UnknownEventContent>(
                buildJsonObject {
                    put("type", eventType)
                    put("value", content)
                }
            )
            val response = trixnity.api.room.sendMessageEvent(
                RoomId(channelId),
                unknownContent as de.connect2x.trixnity.core.model.events.MessageEventContent
            ).getOrThrow()
            Result.success(response.full)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadMedia(bytes: ByteArray, mimeType: String, filename: String): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val media = Media(
                content = ByteReadChannel(bytes),
                contentLength = bytes.size.toLong(),
                contentType = ContentType.parse(mimeType),
                contentDisposition = null
            )
            val mxcUrl = trixnity.api.media.upload(media).getOrThrow()
            Result.success(mxcUrl.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendImageMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String,
        width: Int?, height: Int?, size: Long?
    ): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.Image(
                        body = filename,
                        url = mxcUrl,
                        info = ImageInfo(
                            mimeType = mimeType,
                            width = width,
                            height = height,
                            size = size
                        )
                    )
                }
            }

            // Manually emit a local echo
            val orbitMessage = OrbitMessage(
                id = txnId,
                userId = _userId ?: "me",
                username = _userId ?: "me",
                body = filename,
                timestamp = Instant.fromEpochMilliseconds(1716500000000L),
                channelId = channelId,
                content = MessageContent.Image(
                    url = convertMxcUrl(mxcUrl),
                    body = filename,
                    mimeType = mimeType,
                    size = size,
                    width = width,
                    height = height
                ),
                transactionId = txnId
            )
            synchronized(liveMessageStates) {
                val state = liveMessageStates.getOrPut(channelId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                state.messages.add(orbitMessage)
                state.flow.tryEmit(listOf(orbitMessage))
            }

            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendFileMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String, size: Long?
    ): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.File(
                        body = filename,
                        url = mxcUrl,
                        info = FileInfo(
                            mimeType = mimeType,
                            size = size
                        )
                    )
                }
            }

            // Manually emit a local echo
            val orbitMessage = OrbitMessage(
                id = txnId,
                userId = _userId ?: "me",
                username = _userId ?: "me",
                body = filename,
                timestamp = Instant.fromEpochMilliseconds(1716500000000L),
                channelId = channelId,
                content = MessageContent.File(
                    url = convertMxcUrl(mxcUrl),
                    filename = filename,
                    mimeType = mimeType,
                    size = size
                ),
                transactionId = txnId
            )
            synchronized(liveMessageStates) {
                val state = liveMessageStates.getOrPut(channelId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                state.messages.add(orbitMessage)
                state.flow.tryEmit(listOf(orbitMessage))
            }

            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendAudioMessage(
        channelId: String, mxcUrl: String, filename: String, mimeType: String,
        durationMs: Int?, size: Long?
    ): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val txnId = trixnity.room.sendMessage(RoomId(channelId)) {
                roomMessageBuilder(filename, null, null) {
                    FileBased.Audio(
                        body = filename,
                        url = mxcUrl,
                        info = AudioInfo(
                            mimeType = mimeType,
                            duration = durationMs?.toLong(),
                            size = size
                        )
                    )
                }
            }

            // Manually emit a local echo
            val orbitMessage = OrbitMessage(
                id = txnId,
                userId = _userId ?: "me",
                username = _userId ?: "me",
                body = filename,
                timestamp = Instant.fromEpochMilliseconds(1716500000000L),
                channelId = channelId,
                content = MessageContent.Audio(
                    url = convertMxcUrl(mxcUrl),
                    body = filename,
                    mimeType = mimeType,
                    size = size,
                    durationMs = durationMs
                ),
                transactionId = txnId
            )
            synchronized(liveMessageStates) {
                val state = liveMessageStates.getOrPut(channelId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                state.messages.add(orbitMessage)
                state.flow.tryEmit(listOf(orbitMessage))
            }

            Result.success(txnId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editMessage(eventId: String, newBody: String): Result<Unit> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val roomId = extractRoomFromEventId(eventId)
            trixnity.room.sendMessage(RoomId(roomId)) {
                this.relatesTo = RelatesTo.Replace(EventId(eventId))
                text(newBody)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(eventId: String): Result<Unit> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val roomId = extractRoomFromEventId(eventId)
            trixnity.api.room.redactEvent(RoomId(roomId), EventId(eventId)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendReadReceipt(roomId: String, eventId: String) {
        trixnityClient?.api?.room?.setReadMarkers(RoomId(roomId), EventId(eventId), EventId(eventId))
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
        client.post(fullUrl("/pushers/set")) {
            header(HttpHeaders.Authorization, "Bearer ${_accessToken}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    // ─── User Profile ──────────────────────────────────────────
    override suspend fun updateDisplayName(name: String): Result<Unit> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            trixnity.setProfileField(ProfileField.DisplayName(name)).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadAvatar(imageBytes: ByteArray): Result<String> {
        return try {
            val trixnity = trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val media = Media(
                content = ByteReadChannel(imageBytes),
                contentLength = imageBytes.size.toLong(),
                contentType = ContentType.Image.PNG,
                contentDisposition = null
            )
            val mxcUrl = trixnity.api.media.upload(media).getOrThrow()
            trixnity.setProfileField(ProfileField.AvatarUrl(mxcUrl.toString())).getOrThrow()
            Result.success(mxcUrl.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUserProfile(userId: String): OrbitUser? {
        return try {
            val trixnity = trixnityClient ?: return null
            val profile = trixnity.api.user.getProfile(UserId(userId)).getOrThrow()

            OrbitUser(
                id = userId,
                username = userId.split(":").first().removePrefix("@"),
                displayName = profile.displayName ?: userId,
                avatarColor = hashToColor(userId),
                status = UserStatus.ONLINE,
                isBot = false,
                avatarUrl = profile.avatarUrl?.let { convertMxcUrl(it.toString()) }
            )
        } catch (e: Exception) {
            null
        }
    }

    // ─── Presence / Status ─────────────────────────────────────
    override suspend fun setPresence(status: UserStatus) {
        try {
            val trixnity = trixnityClient ?: return
            val presence = when (status) {
                UserStatus.ONLINE -> Presence.ONLINE
                UserStatus.IDLE -> Presence.UNAVAILABLE
                UserStatus.DO_NOT_DISTURB -> Presence.UNAVAILABLE
                UserStatus.OFFLINE -> Presence.OFFLINE
            }
            trixnity.api.user.setPresence(trixnity.userId, presence).getOrThrow()
        } catch (_: Exception) {}
    }

    override suspend fun observePresence(userId: String): Flow<UserStatus> {
        val trixnity = trixnityClient ?: return flowOf(UserStatus.OFFLINE)
        return trixnity.user.getPresence(UserId(userId)).map { presence ->
            when (presence?.presence) {
                Presence.ONLINE -> UserStatus.ONLINE
                Presence.UNAVAILABLE -> UserStatus.IDLE
                Presence.OFFLINE -> UserStatus.OFFLINE
                else -> UserStatus.ONLINE
            }
        }
    }

    override suspend fun setTyping(roomId: String, isTyping: Boolean) {
        trixnityClient?.api?.room?.setTyping(
            RoomId(roomId),
            trixnityClient!!.userId,
            isTyping,
            30_000
        )
    }

    override fun observeTyping(roomId: String): Flow<List<String>> {
        val trixnity = trixnityClient ?: return flowOf(emptyList())
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

    override fun observeCallEvents(): Flow<CallEvent> = _callEvents.asSharedFlow()

    /**
     * Start a sync listener for live events using Trixnity SDK.
     */
    fun startSync() {
        GlobalScope.launch {
            if (trixnityClient == null && _accessToken != null && _userId != null) {
                try {
                    trixnityClient = MatrixClient.create(
                        repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                        mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                        cryptoDriverModule = CryptoDriverModule.vodozemac(),
                        authProviderData = null // Trixnity will load from local store
                    ).getOrThrow()
                } catch (e: Exception) {
                    println("Failed to restore Trixnity client from store: ${e.message}")
                    // Fallback to providing auth data explicitly if local store is empty or corrupted
                    val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                        baseUrl = Url(homeserverUrl),
                        accessToken = _accessToken!!,
                        accessTokenExpiresInMs = null,
                        refreshToken = null
                    )
                    trixnityClient = MatrixClient.create(
                        repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                        mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                        cryptoDriverModule = CryptoDriverModule.vodozemac(),
                        authProviderData = authProviderData
                    ).getOrNull()
                }
            }

            val trixnity = trixnityClient ?: run {
                _authStatus.value = AuthStatus.Error("Failed to initialize Matrix client.")
                return@launch
            }

            // Verify session validity
            try {
                trixnity.api.authentication.whoAmI().getOrThrow()
                _authStatus.value = AuthStatus.Authenticated
            } catch (e: Exception) {
                println("Session invalid: ${e.message}")
                _authStatus.value = AuthStatus.Error("Previous session is invalid. Please login again.")
                return@launch
            }

            // Sync presence explicitly if needed, Trixnity handles syncing loop automatically when startSync is called
            launch { 
                try {
                    trixnity.startSync() 
                } catch (e: Exception) {
                    println("Sync loop failed: ${e.message}")
                    if (e is de.connect2x.trixnity.core.MatrixServerException && e.statusCode == io.ktor.http.HttpStatusCode.Unauthorized) {
                        _authStatus.value = AuthStatus.Error("Session expired. Please login again.")
                    }
                }
            }

            launch {
                trixnity.room.getAll().collectLatest { allRoomsMap ->
                    _roomListFlow.emit(getSpaces())
                    allRoomsMap.values.forEach { roomFlow ->
                        launch {
                            roomFlow.collect {
                                _roomListFlow.emit(getSpaces())
                            }
                        }
                    }
                }
            }

            launch {
                trixnity.room.getTimelineEventsFromNowOn(decryptionTimeout = 5.seconds).collect { timelineEvent ->
                    val content = timelineEvent.content?.getOrNull()
                    val event = timelineEvent.event as? RoomEvent<*> ?: return@collect
                    val roomId = timelineEvent.roomId.full

                    if (content is UnknownEventContent) {
                        val json = trixnity.api.json.encodeToJsonElement(content).jsonObject["value"]?.jsonObject ?: JsonObject(emptyMap())
                        val typeStr = content.eventType
                        if (typeStr == "m.call.invite") {
                            val callId = json["call_id"]?.jsonPrimitive?.content ?: ""
                            val sdp = json["offer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: ""
                            if (callId.isNotBlank() && sdp.isNotBlank()) _callEvents.emit(CallEvent.Invite(roomId, callId, sdp))
                        } else if (typeStr == "m.call.answer") {
                            val callId = json["call_id"]?.jsonPrimitive?.content ?: ""
                            val sdp = json["answer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: ""
                            if (callId.isNotBlank() && sdp.isNotBlank()) _callEvents.emit(CallEvent.Answer(roomId, callId, sdp))
                        } else if (typeStr == "m.call.candidates") {
                            val callId = json["call_id"]?.jsonPrimitive?.content ?: ""
                            val candidates = json["candidates"]?.jsonArray
                            candidates?.forEach { cand ->
                                val cObj = cand.jsonObject
                                val sdp = cObj["candidate"]?.jsonPrimitive?.content ?: ""
                                val sdpMid = cObj["sdpMid"]?.jsonPrimitive?.content ?: ""
                                val sdpMLineIndex = cObj["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0
                                _callEvents.emit(CallEvent.Candidate(roomId, callId, sdp, sdpMid, sdpMLineIndex))
                            }
                        } else if (typeStr == "m.call.hangup") {
                            val callId = json["call_id"]?.jsonPrimitive?.content ?: ""
                            if (callId.isNotBlank()) _callEvents.emit(CallEvent.Hangup(roomId, callId))
                        }
                    }

                    timelineEventToOrbitMessage(timelineEvent)?.let { orbitMessage ->
                        val rId = timelineEvent.roomId.full
                        synchronized(liveMessageStates) {
                            val state = liveMessageStates.getOrPut(rId) { LiveMessageState(MutableSharedFlow(replay = 0)) }
                            state.messages.add(orbitMessage)
                            state.flow.tryEmit(listOf(orbitMessage))
                        }
                    }
                }
            }
            trixnity.startSync()
        }
    }

    fun stopSync() {
        synchronized(liveMessageStates) {
            liveMessageStates.clear()
        }
        GlobalScope.launch {
            trixnityClient?.stopSync()
        }
    }

    // ─── Utility functions ─────────────────────────────────────
    private fun hashToColor(input: String): Int {
        val hash = input.hashCode()
        val r = (hash and 0xFF)
        val g = ((hash shr 8) and 0xFF)
        val b = ((hash shr 16) and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
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

    private suspend fun timelineEventToOrbitMessage(timelineEvent: TimelineEvent): OrbitMessage? {
        val contentResult = timelineEvent.content
        val event = timelineEvent.event as? RoomEvent<*> ?: return null
        val eventId = timelineEvent.eventId.full
        val roomId = timelineEvent.roomId.full
        val sender = timelineEvent.sender.full
        val txnId = (event as? RoomEvent.MessageEvent<*>)?.unsigned?.transactionId
        val ts = Instant.fromEpochMilliseconds(event.originTimestamp)

        if (contentResult?.isFailure == true) {
            return OrbitMessage(
                id = eventId,
                userId = sender,
                username = sender,
                body = "🔒 Waiting for keys to decrypt this message...",
                timestamp = ts,
                channelId = roomId,
                content = MessageContent.Text("🔒 Waiting for keys to decrypt this message..."),
                transactionId = txnId
            )
        }

        val content = contentResult?.getOrNull() ?: return null
        if (content is RoomMessageEventContent) {
            val body = (content as? RoomMessageEventContent.TextBased)?.body ?: ""

            val relatesTo = content.relatesTo
            val orbitContent: MessageContent = when {
                relatesTo is RelatesTo.Replace -> {
                    val newContent = relatesTo.newContent
                    if (newContent is RoomMessageEventContent) {
                        MessageContent.Edit(
                            replacesEventId = relatesTo.eventId.full,
                            newContent = parseTrixnityMessageContent(newContent)
                        )
                    } else {
                        parseTrixnityMessageContent(content)
                    }
                }
                relatesTo is RelatesTo.Reply -> {
                    MessageContent.Reply(
                        inReplyToEventId = relatesTo.eventId.full,
                        quotedSenderId = "",
                        quotedBody = "",
                        actualContent = parseTrixnityMessageContent(content)
                    )
                }
                else -> parseTrixnityMessageContent(content)
            }

            return OrbitMessage(
                id = eventId,
                userId = sender,
                username = sender,
                body = body,
                timestamp = ts,
                channelId = roomId,
                content = orbitContent,
                transactionId = txnId
            )
        }

        if (content is ReactionEventContent) {
            val key = content.relatesTo?.key ?: ""
            val eventIdRel = content.relatesTo?.eventId?.full ?: ""
            return OrbitMessage(
                id = eventId,
                userId = sender,
                username = sender,
                body = key,
                timestamp = ts,
                channelId = roomId,
                content = MessageContent.Reaction(reactsToEventId = eventIdRel, key = key),
                transactionId = txnId
            )
        }

        return null
    }

    private fun parseTrixnityMessageContent(content: RoomMessageEventContent): MessageContent {
        return when (content) {
            is TextBased.Text -> {
                MessageContent.Text(body = content.body, formattedBody = content.formattedBody)
            }
            is FileBased.Image -> {
                val url = convertMxcUrl(content.url ?: "")
                MessageContent.Image(
                    url = url,
                    thumbnailUrl = content.info?.thumbnailUrl?.let { convertMxcUrl(it) } ?: url,
                    width = content.info?.width?.toInt(),
                    height = content.info?.height?.toInt(),
                    body = content.body,
                    mimeType = content.info?.mimeType,
                    size = content.info?.size
                )
            }
            is FileBased.Audio -> {
                MessageContent.Audio(
                    url = convertMxcUrl(content.url ?: ""),
                    body = content.body,
                    durationMs = content.info?.duration?.toInt(),
                    mimeType = content.info?.mimeType,
                    size = content.info?.size,
                    waveform = null
                )
            }
            is FileBased.File -> {
                MessageContent.File(
                    url = convertMxcUrl(content.url ?: ""),
                    filename = content.body,
                    mimeType = content.info?.mimeType,
                    size = content.info?.size
                )
            }
            is FileBased.Video -> {
                MessageContent.Video(
                    url = convertMxcUrl(content.url ?: ""),
                    thumbnailUrl = content.info?.thumbnailUrl?.let { convertMxcUrl(it) },
                    body = content.body,
                    durationMs = content.info?.duration?.toInt(),
                    width = content.info?.width?.toInt(),
                    height = content.info?.height?.toInt(),
                    mimeType = content.info?.mimeType,
                    size = content.info?.size
                )
            }
            else -> MessageContent.Unsupported(type = content::class.simpleName ?: "unknown", fallbackBody = content.body)
        }
    }
}