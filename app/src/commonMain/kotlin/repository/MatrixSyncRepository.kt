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



class MatrixSyncRepository(
    private val context: MatrixContext,
    private val messagesRepository: MatrixMessagesRepository,
    private val spacesRepository: MatrixSpacesRepository
) : OrbitSyncRepository {

    override fun <T> observeAccountData(eventType: String, deserializer: kotlinx.serialization.KSerializer<T>): Flow<T?> {
        @Suppress("UNCHECKED_CAST")
        val flow = context.accountDataCache.getOrPut(eventType) { MutableStateFlow<Any?>(null) } as MutableStateFlow<T?>
        context.repositoryScope.launch {
            try {
                val userId = context.userId ?: return@launch
                val response = context.httpClient.get(context.fullUrl("/user/$userId/account_data/$eventType")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                }
                if (response.status.value == 200) {
                    val rawJson = response.bodyAsText()
                    val data = context.jsonConfig.decodeFromString(deserializer, rawJson)
                    flow.value = data
                }
            } catch (e: Exception) {
                println("DEBUG: Failed to fetch account data $eventType: ${e.message}")
            }
        }
        return flow
    }

    override suspend fun <T> setAccountData(eventType: String, data: T, serializer: kotlinx.serialization.KSerializer<T>): Result<Unit> {
        return try {
            val userId = context.userId ?: return Result.failure(IllegalStateException("Not logged in"))
            val response = context.httpClient.put(context.fullUrl("/user/$userId/account_data/$eventType")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                contentType(ContentType.Application.Json)
                val rawJson = context.jsonConfig.encodeToString(serializer, data)
                setBody(rawJson)
            }
            if (response.status.value == 200 || response.status.value == 201) {
                @Suppress("UNCHECKED_CAST")
                val flow = context.accountDataCache.getOrPut(eventType) { MutableStateFlow<Any?>(null) } as MutableStateFlow<T?>
                flow.value = data
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to set account data: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun startSync() {
        context.repositoryScope.launch {
            if (context.trixnityClient == null && context.accessToken != null && context.userId != null) {
                try {
                    context.trixnityClient = MatrixClient.create(
                        repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                        mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                        cryptoDriverModule = CryptoDriverModule.vodozemac(),
                        authProviderData = null
                    ).getOrThrow()
                } catch (e: Exception) {
                    println("Failed to restore Trixnity client from store: ${e.message}")
                    val authProviderData = de.connect2x.trixnity.clientserverapi.client.ClassicMatrixClientAuthProviderData(
                        baseUrl = Url(context.homeserverUrl),
                        accessToken = context.accessToken!!,
                        accessTokenExpiresInMs = null,
                        refreshToken = null
                    )
                    context.trixnityClient = MatrixClient.create(
                        repositoriesModule = RepositoriesModule.room(getRoomDatabaseBuilder()),
                        mediaStoreModule = MediaStoreModule.okio(getMediaStorePath()),
                        cryptoDriverModule = CryptoDriverModule.vodozemac(),
                        authProviderData = authProviderData
                    ).getOrNull()
                }
            }

            val trixnity = context.trixnityClient ?: run {
                context.authStatusFlow.value = AuthStatus.Error("Failed to initialize Matrix client.")
                return@launch
            }

            try {
                trixnity.api.authentication.whoAmI().getOrThrow()
                context.authStatusFlow.value = AuthStatus.Authenticated
            } catch (e: Exception) {
                println("Session invalid: ${e.message}")
                context.authStatusFlow.value = AuthStatus.Error("Previous session is invalid. Please login again.")
                return@launch
            }

            launch {
                trixnity.room.getAll().collectLatest { allRoomsMap ->
                    context.roomListFlow.emit(spacesRepository.getSpaces())
                    for (roomId in allRoomsMap.keys) {
                        // Note: To avoid cyclic dependency on CallsRepo for observeActiveGroupCall,
                        // we'll rely on the calls repo being observed elsewhere, or move the logic.
                        // Actually, observeActiveGroupCall is simple.
                        // For now, let's keep the core syncing logic
                    }
                }
            }

            launch {
                trixnity.room.getTimelineEventsFromNowOn(decryptionTimeout = 5.seconds).collect { timelineEvent ->
                    val roomId = timelineEvent.roomId.full
                    val sender = timelineEvent.sender.full
                    val content = timelineEvent.content?.getOrNull()

                    when (content) {
                        is CallEventContent.Answer -> context.callEventsFlow.emit(CallEvent.Answer(roomId, content.callId, content.answer.sdp, sender, content.partyId))
                        is CallEventContent.Candidates -> content.candidates.forEach {
                            context.callEventsFlow.emit(CallEvent.Candidate(roomId, content.callId, it.candidate, it.sdpMid ?: "", it.sdpMLineIndex?.toInt() ?: 0, sender, content.partyId))
                        }
                        is CallEventContent.Hangup -> context.callEventsFlow.emit(CallEvent.Hangup(roomId, content.callId, sender, content.partyId))
                        is CallEventContent.Invite -> context.callEventsFlow.emit(CallEvent.Invite(roomId, content.callId, content.offer.sdp, sender, content.partyId, content.lifetime))
                        is CallEventContent.Negotiate -> context.callEventsFlow.emit(CallEvent.Negotiate(roomId, content.callId, content.description.sdp, 
                            when(content.description.type) { CallEventContent.Negotiate.DescriptionType.OFFER -> "offer"; CallEventContent.Negotiate.DescriptionType.ANSWER -> "answer" }, sender, content.partyId))
                        is UnknownEventContent -> {
                            val typeStr = content.eventType
                            val raw = content.raw.jsonObject
                            val realContent = raw["value"]?.jsonObject ?: raw
                            val partyId = realContent["party_id"]?.jsonPrimitive?.content ?: realContent["partyId"]?.jsonPrimitive?.content

                            if (typeStr.startsWith("m.call.") || typeStr.startsWith("org.matrix.msc3401.call")) {
                                val callId = realContent["call_id"]?.jsonPrimitive?.content ?: (timelineEvent.event as? RoomEvent.StateEvent<*>)?.stateKey ?: ""
                                if (callId.isNotBlank() && typeStr.startsWith("m.call.")) {
                                    when (typeStr) {
                                        "m.call.invite" -> context.callEventsFlow.emit(CallEvent.Invite(roomId, callId, realContent["offer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: "", sender, partyId, realContent["lifetime"]?.jsonPrimitive?.longOrNull))
                                        "m.call.answer" -> context.callEventsFlow.emit(CallEvent.Answer(roomId, callId, realContent["answer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: "", sender, partyId))
                                        "m.call.negotiate" -> {
                                            val desc = realContent["description"]?.jsonObject
                                            val sdp = desc?.get("sdp")?.jsonPrimitive?.content ?: ""
                                            val type = desc?.get("type")?.jsonPrimitive?.content ?: ""
                                            if (sdp.isNotBlank() && type in setOf("offer", "answer")) context.callEventsFlow.emit(CallEvent.Negotiate(roomId, callId, sdp, type, sender, partyId))
                                        }
                                        "m.call.candidates", "m.call.candidate" -> {
                                            val cands = realContent["candidates"]?.jsonArray ?: realContent["candidate"]?.jsonObject?.let { buildJsonArray { add(it) } } ?: kotlinx.serialization.json.JsonArray(emptyList())
                                            cands.forEach { cand ->
                                                val sdp = cand.jsonObject["candidate"]?.jsonPrimitive?.content ?: cand.jsonObject["sdp"]?.jsonPrimitive?.content ?: ""
                                                if (sdp.isNotBlank()) context.callEventsFlow.emit(CallEvent.Candidate(roomId, callId, sdp, cand.jsonObject["sdpMid"]?.jsonPrimitive?.content ?: "", cand.jsonObject["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0, sender, partyId))
                                            }
                                        }
                                        "m.call.hangup" -> context.callEventsFlow.emit(CallEvent.Hangup(roomId, callId, sender, partyId))
                                    }
                                }
                                
                                if (typeStr == "m.call.member" || typeStr == "org.matrix.msc3401.call.member" || realContent["membership"] != null) {
                                    // Join payloads carry no "membership" field (Orbit and Element
                                    // legacy shapes alike); only the leave marker sets it. Keying on
                                    // presence of "join" removed every real participant.
                                    val membership = realContent["membership"]?.jsonPrimitive?.content
                                    val membersFlow = context.activeCallMembers.getOrPut(roomId) { MutableStateFlow(emptyList()) }
                                    val currentMembers = membersFlow.value.toMutableList()

                                    if (membership != "leave") {
                                        if (currentMembers.none { it.id == sender }) {
                                            val user = context.fetchUserProfile(sender) ?: OrbitUser(id = sender, username = sender.split(":").first().removePrefix("@"), displayName = sender, avatarColor = context.hashToColor(sender), status = UserStatus.ONLINE, isBot = false, avatarUrl = null)
                                            currentMembers.add(user)
                                        }
                                    } else {
                                        currentMembers.removeAll { it.id == sender }
                                    }
                                    membersFlow.value = currentMembers
                                    if (typeStr == "m.call.member" || typeStr == "org.matrix.msc3401.call.member") {
                                        context.groupCallStateChanges.tryEmit(roomId)
                                    }
                                }
                            }

                            // Group-call discovery is centralized in MatrixCallsRepository.resolveGroupCallJoinTarget;
                            // sync only signals that call state changed so observers re-resolve.
                            if (typeStr == "org.matrix.msc3401.call" || typeStr == "m.call") {
                                context.groupCallStateChanges.tryEmit(roomId)
                            }
                        }
                        else -> {}
                    }

                    messagesRepository.timelineEventToOrbitMessage(timelineEvent)?.let { orbitMessage ->
                        synchronized(context.liveMessageStates) {
                            val state = context.liveMessageStates.getOrPut(roomId) {
                                LiveMessageState(kotlinx.coroutines.flow.MutableSharedFlow(replay = 1, extraBufferCapacity = 64, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST))
                            }
                            state.messages.add(orbitMessage)
                            state.flow.tryEmit(listOf(orbitMessage))
                        }
                    }
                }
            }

            try {
                trixnity.startSync()
            } catch (e: Exception) {
                println("Sync loop failed: ${e.message}")
                if (e is de.connect2x.trixnity.core.MatrixServerException && e.statusCode == io.ktor.http.HttpStatusCode.Unauthorized) {
                    context.authStatusFlow.value = AuthStatus.Error("Session expired. Please login again.")
                }
            }
        }
    }

    override fun stopSync() {
        synchronized(context.liveMessageStates) {
            context.liveMessageStates.clear()
        }
        GlobalScope.launch {
            context.trixnityClient?.stopSync()
        }
    }
}

