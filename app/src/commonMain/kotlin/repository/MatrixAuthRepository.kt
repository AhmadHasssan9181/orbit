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

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*



class MatrixAuthRepository(private val context: MatrixContext) : OrbitAuthRepository {

    override val authStatus: Flow<AuthStatus> = context.authStatusFlow.asStateFlow()

    override suspend fun logout() {
        context.trixnityClient?.stopSync()
        context.trixnityClient = null
        CredentialManager.clear()
        context.authStatusFlow.value = AuthStatus.Unauthenticated
    }

    override val activeDeviceVerification: Flow<Any?>?
        get() = context.trixnityClient?.verification?.activeDeviceVerification ?: flowOf(null)

    override suspend fun login(identifier: String, password: String, deviceName: String): AuthResult {
        val body = buildJsonObject {
            put("type", "m.login.password")
            put("identifier", buildJsonObject {
                put("type", "m.id.user")
                put("user", identifier.removePrefix("@"))
            })
            put("password", password)
            put("initial_device_display_name", deviceName.ifEmpty { "Orbit" })
        }

        val httpResp = context.httpClient.post(context.fullUrl("/login")) {
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

        return AuthResult(accessToken, deviceId, context.homeserverUrl, userId).also {
            context.accessToken = it.accessToken
            context.userId = it.userId
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
            context.trixnityClient = MatrixClient.create(
                repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData
            ).getOrThrow()
        }
    }

    override suspend fun loginWithSso(): AuthResult = withContext(Dispatchers.IO) {
        val loginToken = performSsoLogin(context.homeserverUrl)

        val body = buildJsonObject {
            put("type", "m.login.token")
            put("token", loginToken)
            put("initial_device_display_name", "Orbit")
        }
        val httpResp = context.httpClient.post(context.fullUrl("/login")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content ?: ""
        val deviceId = (resp["device_id"] as? JsonPrimitive)?.content ?: "orbit_device"
        val userId = (resp["user_id"] as? JsonPrimitive)?.content ?: ""

        AuthResult(accessToken, deviceId, context.homeserverUrl, userId).also {
            context.accessToken = it.accessToken
            context.userId = it.userId
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
            context.trixnityClient = MatrixClient.create(
                repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                cryptoDriverModule = CryptoDriverModule.vodozemac(),
                authProviderData = authProviderData
            ).getOrThrow()
        }
    }

    override suspend fun register(username: String, password: String): RegisterResult {
        val body = buildJsonObject {
            put("user", username)
            put("password", password)
        }
        val httpResp = context.httpClient.post(context.fullUrl("/register")) {
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
        val userId = context.userId ?: return null
        return try {
            val trixnity = context.trixnityClient ?: return null
            val profile = trixnity.api.user.getProfile(de.connect2x.trixnity.core.model.UserId(userId)).getOrThrow()

            OrbitUser(
                id = userId,
                username = userId.split(":").first().removePrefix("@"),
                displayName = profile.displayName ?: userId,
                avatarColor = context.hashToColor(userId),
                status = UserStatus.ONLINE,
                isBot = false,
                avatarUrl = profile.avatarUrl?.let { context.convertMxcUrl(it.toString()) }
            )
        } catch (e: Exception) {
            null
        }
    }
}

