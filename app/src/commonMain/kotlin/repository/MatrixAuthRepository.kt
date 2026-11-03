package repository


import de.connect2x.trixnity.client.CryptoDriverModule
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.MediaStoreModule
import de.connect2x.trixnity.client.RepositoriesModule
import de.connect2x.trixnity.client.create
import livekit.configureResilientCrypto
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
import de.connect2x.trixnity.core.model.events.m.key.verification.VerificationMethod
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
import ui.model.OrbitProfileExtras
import ui.model.UserStatus
import ui.model.displayText
import util.getMediaStorePath
import util.getRoomDatabaseBuilder
import util.performSsoLogin
import kotlin.time.Duration.Companion.seconds




class MatrixAuthRepository(private val context: MatrixContext) : OrbitAuthRepository {

    override val authStatus: Flow<AuthStatus> = context.authStatusFlow.asStateFlow()

    override suspend fun logout() {
        // Order matters: stop sync first so no new events land in stores, then
        // close the Trixnity client (which also stops its sync + closes the
        // crypto/media stores), and only then tear down our own scope and HTTP
        // client. Without this every logout/login cycle leaked a supervisor job
        // plus a CIO selector thread and kept 401-storming the server.
        val client = context.trixnityClient
        context.trixnityClient = null

        // SECURITY: revoke server-side FIRST, before anything local is destroyed.
        //
        // This used to call `client?.close()` and nothing else. `close()` only tears
        // down sync and the API layer -- it issues no POST
        // /_matrix/client/v3/logout -- so the access token stayed valid on the
        // homeserver indefinitely after the user clicked "Log out". Anyone holding a
        // leaked token kept working. Trixnity's `MatrixClient.logout()` does both
        // halves: it cancels sync, calls api.authentication.logout() (so the token is
        // dead server-side) and then deleteAll() (so the Olm account, cross-signing
        // keys and every megolm session in ~/.orbit/trixnity.db are destroyed).
        //
        // deleteAll() being skipped is the worse of the two: a later user of the same
        // Windows account could read the E2EE key material out of the database that
        // logout was supposed to destroy.
        //
        // Revocation must come before the local wipe because it needs a live session.
        // A failure (already-expired token, no network, homeserver down) must NOT
        // block the local cleanup -- a user on a plane still has to be able to log
        // out -- so it is reported and the teardown continues.
        val revokeFailure = runCatching {
            client?.logout()?.getOrThrow()
        }.exceptionOrNull()
        if (revokeFailure != null) {
            util.OrbitLog.w(
                "MatrixAuthRepository",
                "server-side logout failed (${revokeFailure.message}); the access token may " +
                    "still be valid on ${context.homeserverUrl}. Local credentials are still cleared."
            )
        } else {
            util.OrbitLog.d("MatrixAuthRepository", "session revoked server-side and key store deleted")
        }

        // Belt and braces: logout() above cancels sync, but a partially-initialised
        // client can still be syncing, and close() must run either way.
        runCatching { client?.stopSync() }
            .onFailure { util.OrbitLog.w("MatrixAuthRepository", "stopSync on logout failed: ${it.message}") }
        runCatching { client?.close() }
            .onFailure { util.OrbitLog.w("MatrixAuthRepository", "closing Trixnity client failed: ${it.message}") }
        CredentialManager.clear()
        // Cached messages are readable with no session at all, which is what makes
        // them useful when the network is down -- and is exactly why they have to
        // go when the session does. Otherwise logging out and logging in as someone
        // else shows the previous account's recent messages.
        util.messageCache().clearAll()
        context.close()
        context.authStatusFlow.value = AuthStatus.Unauthenticated
    }

    override val activeDeviceVerification: Flow<Any?>?
        get() = context.trixnityClient?.verification?.activeDeviceVerification ?: flowOf(null)

    override suspend fun startDeviceVerification(theirUserId: String): Result<Unit> {
        val trixnity = context.trixnityClient
            ?: return Result.failure(IllegalStateException("Not logged in"))
        val userId = try {
            UserId(theirUserId.trim())
        } catch (e: Exception) {
            // A malformed id fails inside Trixnity's own UserId parser, and the
            // message it produces names its own class rather than saying what is
            // wrong with what the user typed. Say it here instead.
            return Result.failure(
                IllegalArgumentException("\"$theirUserId\" is not a Matrix ID — expected @name:server")
            )
        }
        return try {
            // SAS only. Offering QR would need the scan UI, and silently starting a
            // method with no way to complete it is worse than not offering it.
            trixnity.verification.createDeviceVerificationRequest(userId, setOf(VerificationMethod.Sas.value))
                .map { }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun login(identifier: String, password: String, deviceName: String): AuthResult = try {
        loginInternal(identifier, password, deviceName)
    } catch (e: Exception) {
        // The repository is built BEFORE the login attempt, so a wrong password
        // or a wrong homeserver used to leave its HttpClient (own selector
        // thread) and supervisor job running for the life of the process. The
        // repository is unusable after a failed login, so release it here; the
        // caller builds a fresh one for the next attempt.
        runCatching { context.close() }
            .onFailure { println("WARN [MatrixAuthRepository] closing context after failed login: ${it.message}") }
        throw e
    }

    private suspend fun loginInternal(identifier: String, password: String, deviceName: String): AuthResult {
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

        // A 200 can still carry no access token: M_USER_INTERACTIVE returns
        // {"flows": [...]} and the user has to complete another auth step.
        // Persisting "" here used to produce a "logged in" session whose every
        // request 401s, and then failed forever on the next launch.
        if (accessToken.isBlank() || userId.isBlank()) {
            val flows = resp["flows"]?.let { it.toString().take(200) }
            throw IllegalStateException(
                if (flows != null) {
                    "The homeserver wants an additional sign-in step before it will issue a token."
                } else {
                    "Sign-in succeeded but the homeserver returned no access token."
                }
            )
        }

        val result = AuthResult(accessToken, deviceId, context.homeserverUrl, userId)
        context.accessToken = result.accessToken
        context.userId = result.userId

        val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
            baseUrl = Url(result.homeserverUrl),
            accessToken = result.accessToken,
            accessTokenExpiresInMs = null,
            refreshToken = null
        )
        val client = MatrixClient.create(
            repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
            mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
            cryptoDriverModule = CryptoDriverModule.vodozemac(),
            authProviderData = authProviderData,
            configuration = { configureResilientCrypto() }
        ).getOrThrow()

        // Persist only after the client actually came up. If create() throws
        // (whoami 401, crypto driver failure, DB migration failure) the caller
        // must not be left holding a token with no working client, or the next
        // launch auto-restores into a dead session and dead-ends on the error
        // screen with no way out but an explicit logout.
        context.trixnityClient = client
        CredentialManager.accessToken = result.accessToken
        CredentialManager.deviceId = result.deviceId
        CredentialManager.homeserverUrl = result.homeserverUrl
        CredentialManager.userId = result.userId
        return result
    }

    override suspend fun loginWithSso(): AuthResult = try {
        withContext(Dispatchers.IO) { loginWithSsoInternal() }
    } catch (e: Exception) {
        // Same reasoning as login(): a failed or abandoned SSO round-trip must
        // not leave the CIO client and its scope behind.
        runCatching { context.close() }
            .onFailure { println("WARN [MatrixAuthRepository] closing context after failed SSO: ${it.message}") }
        throw e
    }

    private suspend fun loginWithSsoInternal(): AuthResult = withContext(Dispatchers.IO) {
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

        // Without this check a non-200 yields accessToken == "", which is then
        // persisted as a working session: every later request 401s and the blank
        // token survives a restart. Check BEFORE writing any credential.
        if (httpResp.status.value != 200) {
            val errCode = (resp["errcode"] as? JsonPrimitive)?.content
            val errMsg = (resp["error"] as? JsonPrimitive)?.content ?: "SSO login failed"
            println("WARN [MatrixAuthRepository] SSO login rejected status=${httpResp.status.value} errcode=$errCode error=$errMsg")
            throw IllegalStateException(errMsg)
        }

        val accessToken = (resp["access_token"] as? JsonPrimitive)?.content
        val deviceId = (resp["device_id"] as? JsonPrimitive)?.content
        val userId = (resp["user_id"] as? JsonPrimitive)?.content
        if (accessToken.isNullOrBlank() || userId.isNullOrBlank()) {
            throw IllegalStateException("SSO login succeeded but the server returned no access token")
        }

        AuthResult(accessToken, deviceId ?: "orbit_device", context.homeserverUrl, userId).also {
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
                authProviderData = authProviderData,
                configuration = { configureResilientCrypto() }
            ).getOrThrow()
        }
    }

    override suspend fun register(
        username: String,
        password: String,
        auth: Map<String, Map<String, String>>,
        session: String?
    ): RegisterResult {
        val body = buildJsonObject {
            put("username", username)
            put("password", password)
            if (!auth.isEmpty()) {
                put("auth", buildJsonObject {
                    auth.forEach { (type, params) ->
                        put(type, buildJsonObject {
                            params.forEach { (k, v) -> put(k, v) }
                        })
                    }
                })
            }
            if (!session.isNullOrBlank()) put("session", session)
        }
        val httpResp = context.httpClient.post(context.fullUrl("/register")) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val resp: JsonObject = httpResp.body()
        if (resp.containsKey("access_token")) {
            return RegisterResult(
                authFlow = AuthFlow.Password(username, password),
                nextEndpoint = ""
            )
        }

        // A 401 with `flows` is the NORMAL first response against a real
        // self-hosted Synapse: it almost always wants m.login.dummy, often plus
        // m.login.terms. Throwing a bare "Registration failed" here is why
        // self-hosting appeared broken.
        val session = (resp["session"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        val stage = nextAuthStage(
            flows = resp["flows"] as? kotlinx.serialization.json.JsonArray,
            completed = resp["completed"] as? kotlinx.serialization.json.JsonArray,
            session = session,
            hasCredentials = false
        )
        return RegisterResult(
            authFlow = AuthFlow.Password(username, password),
            nextEndpoint = "",
            stage = stage,
            authSession = session
        )
    }

    override suspend fun discoverHomeserver(serverNameOrId: String): ServerDiscoveryResult = withContext(Dispatchers.IO) {
        val serverName = matrixServerNameFrom(serverNameOrId)
            ?: return@withContext ServerDiscoveryResult.Malformed("Could not read a server name from \"$serverNameOrId\".")
        val origin = "https://$serverName"

        val wellKnown = try {
            context.httpClient.get("$origin/.well-known/matrix/client")
        } catch (e: Exception) {
            return@withContext ServerDiscoveryResult.Unreachable(e.message ?: "Could not reach $origin.")
        }

        if (wellKnown.status.value == 404 || wellKnown.status.value == 405) {
            // Spec: a 404 means the server does not delegate, so the domain
            // itself is the client. This is the common case for a homeserver
            // hosted at its own origin.
            return@withContext ServerDiscoveryResult.NotDelegated
        }
        if (wellKnown.status.value !in 200..299) {
            return@withContext ServerDiscoveryResult.Unreachable(
                "$origin returned HTTP ${wellKnown.status.value} for .well-known/matrix/client."
            )
        }

        val body = runCatching { wellKnown.bodyAsText() }.getOrElse {
            return@withContext ServerDiscoveryResult.Unreachable(it.message ?: "Could not read the well-known response.")
        }
        val baseUrl = parseWellKnownClient(body)
            ?: return@withContext ServerDiscoveryResult.Malformed(
                "$origin has a .well-known/matrix/client with no usable m.homeserver.base_url."
            )

        // Validate, per the spec. A well-known that points somewhere dead is the
        // single most confusing failure mode here, so it gets its own message.
        val validated = try {
            context.httpClient.get("$baseUrl/_matrix/client/versions")
        } catch (e: Exception) {
            return@withContext ServerDiscoveryResult.Unreachable(
                "$baseUrl could not be reached: ${e.message}"
            )
        }
        if (validated.status.value !in 200..299) {
            return@withContext ServerDiscoveryResult.Unreachable(
                "$baseUrl answered HTTP ${validated.status.value} for /_matrix/client/versions."
            )
        }
        val versionsBody = runCatching { validated.bodyAsText() }.getOrDefault("")
        if (!looksLikeMatrixVersions(versionsBody)) {
            return@withContext ServerDiscoveryResult.Malformed(
                "$baseUrl does not look like a Matrix homeserver."
            )
        }
        ServerDiscoveryResult.Delegated(baseUrl)
    }

    /**
     * The login flows this homeserver supports.
     *
     * Queried so the UI can hide SSO when it is not configured (rather than
     * offering a button that always fails) and can explain a server that does
     * not offer password login.
     */
    override suspend fun getLoginFlows(): List<String> = try {
        val resp: JsonObject = context.httpClient.get(context.fullUrl("/login"))
            .body()
        (resp["flows"] as? kotlinx.serialization.json.JsonArray)
            .orEmpty()
            .mapNotNull { flow ->
                (flow as? kotlinx.serialization.json.JsonObject)
                    ?.get("type")
                    ?.toString()
                    ?.trim('"')
            }
    } catch (e: Exception) {
        println("DEBUG [MatrixAuthRepository] login flow discovery failed: ${e.message}")
        emptyList()
    }

    override suspend fun getCurrentUser(): OrbitUser? {
        val userId = context.userId ?: return null
        return try {
            val trixnity = context.trixnityClient ?: return null
            val profile = trixnity.api.user.getProfile(de.connect2x.trixnity.core.model.UserId(userId)).getOrThrow()

            val bannerUrl = try {
                val cached = context.accountDataCache["im.orbit.profile_extras"]?.value as? OrbitProfileExtras
                cached?.bannerUrl ?: run {
                    val response = context.httpClient.get(context.fullUrl("/user/$userId/account_data/im.orbit.profile_extras")) {
                        header(io.ktor.http.HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    }
                    if (response.status.value == 200) {
                        val extras = context.jsonConfig.decodeFromString(OrbitProfileExtras.serializer(), response.bodyAsText())
                        val flow = context.accountDataCache.computeIfAbsent("im.orbit.profile_extras") {
                            kotlinx.coroutines.flow.MutableStateFlow<Any?>(null)
                        }
                        flow.value = extras
                        extras.bannerUrl
                    } else null
                }
            } catch (e: Exception) {
                null
            }

            OrbitUser(
                id = userId,
                username = userId.split(":").first().removePrefix("@"),
                displayName = profile.displayName ?: userId,
                avatarColor = context.hashToColor(userId),
                status = UserStatus.ONLINE,
                isBot = false,
                avatarUrl = profile.avatarUrl?.let { context.convertMxcUrl(it.toString()) },
                bannerUrl = bannerUrl
            )
        } catch (e: Exception) {
            null
        }
    }
}
