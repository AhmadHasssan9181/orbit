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



class MatrixCallsRepository(private val context: MatrixContext) : OrbitCallsRepository {

    override val activeCallsFlow = context.globalActiveCalls.asStateFlow()

    override suspend fun getTurnServers(): Result<List<TurnServer>> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/voip/turnServer", v1 = true)) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value == 200) {
                val data: TurnServerResponse = response.body()
                val servers = data.uris.map { TurnServer(listOf(it), data.username, data.password) }
                Result.success(servers)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendCallEvent(channelId: String, eventType: String, content: JsonObject): Result<String> {
        try {
            val trixnity = context.trixnityClient ?: return Result.failure(IllegalStateException("Not logged in"))
            val roomId = RoomId(channelId)
            val myPartyId = context.trixnityClient?.userId?.full ?: "orbit_party_1"
            val callId = content["call_id"]?.jsonPrimitive?.content ?: return Result.failure(Exception("No call_id"))
            val versionStr = content["version"]?.jsonPrimitive?.content ?: "0"
            
            val eventContent = when (eventType) {
                "m.call.invite" -> {
                    val offer = content["offer"]?.jsonObject ?: return Result.failure(Exception("No offer"))
                    val sdp = offer["sdp"]?.jsonPrimitive?.content ?: ""
                    val metadata = sdpStreamMetadataFrom(sdp)
                    CallEventContent.Invite(
                        version = versionStr, callId = callId, partyId = myPartyId, lifetime = content["lifetime"]?.jsonPrimitive?.longOrNull ?: 30000L,
                        offer = CallEventContent.Invite.Offer(sdp = sdp, type = de.connect2x.trixnity.core.model.events.m.call.CallEventContent.Invite.OfferType.OFFER), invitee = null, sdpStreamMetadata = metadata
                    )
                }
                "m.call.answer" -> {
                    val answer = content["answer"]?.jsonObject ?: return Result.failure(Exception("No answer"))
                    val sdp = answer["sdp"]?.jsonPrimitive?.content ?: ""
                    val metadata = sdpStreamMetadataFrom(sdp)
                    CallEventContent.Answer(
                        version = versionStr, callId = callId, partyId = myPartyId, answer = CallEventContent.Answer.Answer(sdp = sdp, type = de.connect2x.trixnity.core.model.events.m.call.CallEventContent.Answer.AnswerType.ANSWER), sdpStreamMetadata = metadata
                    )
                }
                "m.call.negotiate" -> {
                    val description = content["description"]?.jsonObject ?: return Result.failure(Exception("No description"))
                    val sdp = description["sdp"]?.jsonPrimitive?.content ?: ""
                    val metadata = sdpStreamMetadataFrom(sdp)
                    val descriptionType = when (description["type"]?.jsonPrimitive?.content) {
                        "offer" -> CallEventContent.Negotiate.DescriptionType.OFFER
                        "answer" -> CallEventContent.Negotiate.DescriptionType.ANSWER
                        else -> return Result.failure(Exception("Unknown negotiate description type"))
                    }
                    CallEventContent.Negotiate(
                        version = versionStr, callId = callId, partyId = myPartyId,
                        description = CallEventContent.Negotiate.Description(sdp = sdp, type = descriptionType),
                        lifetime = content["lifetime"]?.jsonPrimitive?.longOrNull ?: 30000L, sdpStreamMetadata = metadata
                    )
                }
                "m.call.candidates" -> {
                    val candidatesJson = content["candidates"]?.jsonArray ?: emptyList()
                    val candidatesList = candidatesJson.mapNotNull {
                        val candObj = it.jsonObject
                        val sdp = candObj["candidate"]?.jsonPrimitive?.content ?: return@mapNotNull null
                        val sdpMid = candObj["sdpMid"]?.jsonPrimitive?.content
                        val sdpMLineIndex = candObj["sdpMLineIndex"]?.jsonPrimitive?.intOrNull?.toLong()
                        CallEventContent.Candidates.Candidate(candidate = sdp, sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex)
                    }
                    CallEventContent.Candidates(version = versionStr, callId = callId, candidates = candidatesList, partyId = myPartyId)
                }
                "m.call.hangup" -> {
                    CallEventContent.Hangup(version = versionStr, callId = callId, reason = CallEventContent.Hangup.Reason.USER_HANGUP, partyId = myPartyId)
                }
                "m.call.select_answer" -> {
                    val selectedPartyId = content["selected_party_id"]?.jsonPrimitive?.content ?: return Result.failure(Exception("No selected_party_id"))
                    CallEventContent.SelectAnswer(callId = callId, partyId = myPartyId, selectedPartyId = selectedPartyId)
                }
                else -> return Result.failure(Exception("Unknown call event type: $eventType"))
            }

            val txnId = trixnity.room.sendMessage(roomId) {
                content(eventContent)
            }
            return Result.success(txnId)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    override fun observeCallEvents(): Flow<CallEvent> = context.callEventsFlow.asSharedFlow()

    override fun observeActiveCall(channelId: String): Flow<CallEvent.Invite?> {
        val flow = synchronized(context.activeCalls) {
            context.activeCalls.getOrPut(channelId) { MutableStateFlow(null) }
        }

        context.repositoryScope.launch {
            val trixnity = context.trixnityClient ?: return@launch
            val hungupCallIds = mutableSetOf<String>()
            var latestInvite: CallEvent.Invite? = null

            try {
                val lastEventId = trixnity.room.getById(RoomId(channelId)).first()?.lastEventId
                if (lastEventId != null) {
                    val events = trixnity.room.getTimelineEvents(RoomId(channelId), startFrom = lastEventId, direction = de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS)
                    events.take(50).collect { flowEvent ->
                        val timelineEvent = flowEvent.first()
                        val content = timelineEvent.content?.getOrNull() ?: return@collect
                        val sender = timelineEvent.sender.full

                        when (content) {
                            is CallEventContent.Hangup -> if (content.callId.isNotBlank()) hungupCallIds.add(content.callId)
                            is CallEventContent.Invite -> {
                                if (content.callId.isNotBlank() && !hungupCallIds.contains(content.callId)) {
                                    latestInvite = CallEvent.Invite(channelId, content.callId, content.offer.sdp, sender, content.partyId)
                                    throw Exception("Break")
                                }
                            }
                            is UnknownEventContent -> {
                                val typeStr = content.eventType
                                val realContent = unwrapUnknownEventContent(content)
                                val callId = realContent["call_id"]?.jsonPrimitive?.content ?: ""
                                if (callId.isNotBlank()) {
                                    if (typeStr == "m.call.hangup") hungupCallIds.add(callId)
                                    else if (typeStr == "m.call.invite" && !hungupCallIds.contains(callId)) {
                                        val sdp = realContent["offer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: ""
                                        if (sdp.isNotBlank()) {
                                            latestInvite = CallEvent.Invite(channelId, callId, sdp, sender, partyIdFrom(realContent))
                                            throw Exception("Break")
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) { /* Ignore break */ }

            flow.value = latestInvite

            context.callEventsFlow.collect { event ->
                if (event.roomId == channelId) {
                    when (event) {
                        is CallEvent.Invite -> flow.value = event
                        is CallEvent.Hangup -> if (flow.value?.callId == event.callId) flow.value = null
                        else -> {}
                    }
                }
            }
        }
        return flow.asStateFlow()
    }

    override fun observeActiveGroupCall(channelId: String): Flow<String?> {
        val flow = context.activeGroupCalls.getOrPut(channelId) { MutableStateFlow(null) }

        context.repositoryScope.launch {
            val trixnity = context.trixnityClient ?: return@launch
            try {
                val lastEventId = trixnity.room.getById(RoomId(channelId)).first()?.lastEventId
                if (lastEventId != null) {
                    val events = trixnity.room.getTimelineEvents(RoomId(channelId), startFrom = lastEventId, direction = de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS)
                    events.take(100).collect { flowEvent ->
                        val timelineEvent = flowEvent.first()
                        val content = timelineEvent.content?.getOrNull()
                        var typeStr = ""
                        var realContent: JsonObject? = null

                        if (content is UnknownEventContent) {
                            typeStr = content.eventType
                            realContent = unwrapUnknownEventContent(content)
                        }

                        if (typeStr == "im.vector.modular.widgets" || typeStr == "m.widget" || typeStr == "org.matrix.msc3401.call") {
                            if (realContent != null && realContent.isNotEmpty()) {
                                var url: String? = null
                                if (realContent["type"]?.jsonPrimitive?.content == "jitsi" || realContent["type"]?.jsonPrimitive?.content == "m.custom") {
                                    url = realContent["url"]?.jsonPrimitive?.content
                                } else if (typeStr == "org.matrix.msc3401.call") {
                                    url = "matrix-call://$channelId"
                                }

                                if (url != null) {
                                    flow.value = url
                                    throw Exception("Break")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { /* Ignore break */ }
        }
        return flow
    }

    override fun observeCallMembers(channelId: String): Flow<List<OrbitUser>> {
        val flow = context.activeCallMembers.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        
        context.repositoryScope.launch {
            val trixnity = context.trixnityClient ?: return@launch
            try {
                val lastEventId = trixnity.room.getById(RoomId(channelId)).first()?.lastEventId
                if (lastEventId != null) {
                    val currentMembers = mutableListOf<OrbitUser>()
                    val events = trixnity.room.getTimelineEvents(RoomId(channelId), startFrom = lastEventId, direction = de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS)
                    events.take(200).collect { flowEvent ->
                        val timelineEvent = flowEvent.first()
                        val content = timelineEvent.content?.getOrNull()
                        if (content is UnknownEventContent && content.eventType == "m.call.member") {
                            val sender = timelineEvent.sender.full
                            val realContent = unwrapUnknownEventContent(content)
                            val membership = realContent["membership"]?.jsonPrimitive?.content
                            if (currentMembers.none { it.id == sender } && membership == "join") {
                                val user = context.fetchUserProfile(sender) ?: OrbitUser(
                                    id = sender, username = sender.split(":").first().removePrefix("@"),
                                    displayName = sender, avatarColor = context.hashToColor(sender), status = UserStatus.ONLINE, isBot = false, avatarUrl = null
                                )
                                currentMembers.add(user)
                            }
                        }
                    }
                    flow.value = currentMembers
                }
            } catch (e: Exception) {}
        }
        
        return flow.asStateFlow()
    }

    override suspend fun startGroupCall(roomId: String, callId: String) {
        val content = buildJsonObject {
            put("call_id", callId)
            put("version", "1")
            put("lifetime", 30000L)
            put("offer", buildJsonObject {
                put("sdp", "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=GroupCall\r\nt=0 0\r\nm=audio 0 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0")
                put("type", "offer")
            })
            put("org.orbit.group_call", true)
        }
        sendCallEvent(roomId, "m.call.invite", content)
    }

    override suspend fun endGroupCall(roomId: String, callId: String) {
        sendCallEvent(roomId, "m.call.hangup", buildJsonObject { 
            put("call_id", callId)
            put("version", "1")
        })
    }

    override suspend fun joinGroupCall(roomId: String, callId: String) {
        val content = buildJsonObject {
            put("call_id", callId)
            put("version", "1")
            put("candidates", buildJsonArray {})
            put("membership", "join")
            put("org.orbit.group_call", true)
        }
        sendCallEvent(roomId, "m.call.candidates", content)
    }

    override suspend fun leaveGroupCall(roomId: String, callId: String) {
        val content = buildJsonObject {
            put("call_id", callId)
            put("version", "1")
            put("candidates", buildJsonArray {})
            put("membership", "leave")
            put("org.orbit.group_call", true)
        }
        sendCallEvent(roomId, "m.call.candidates", content)
    }

    private fun unwrapUnknownEventContent(content: UnknownEventContent): JsonObject {
        val raw = content.raw.jsonObject
        return raw["value"]?.jsonObject ?: raw
    }

    private fun partyIdFrom(content: JsonObject): String? {
        return content["party_id"]?.jsonPrimitive?.content ?: content["partyId"]?.jsonPrimitive?.content
    }

    private fun sdpStreamMetadataFrom(sdp: String): Map<String, StreamMetadata>? {
        if (sdp.isBlank()) return null

        val streamIds = buildSet {
            Regex("""(?m)^a=msid:([^\s]+)""").findAll(sdp).forEach { match ->
                add(match.groupValues[1])
            }
            Regex("""(?m)^a=ssrc:\d+\s+msid:([^\s]+)""").findAll(sdp).forEach { match ->
                add(match.groupValues[1])
            }
        }

        val normalizedStreamIds = streamIds.ifEmpty { setOf("mediaStream") }
        val hasAudio = sdpHasSendingMedia(sdp, "audio")
        val hasVideo = sdpHasSendingMedia(sdp, "video")
        
        val isScreenShare = sdp.contains("SCREEN_TRACK_ID") || sdp.contains("screenTrack")

        return normalizedStreamIds.associateWith {
            StreamMetadata(
                purpose = if (isScreenShare) Purpose.SCREENSHARE else Purpose.USERMEDIA,
                audioMuted = !hasAudio,
                videoMuted = !hasVideo
            )
        }
    }

    private fun sdpHasSendingMedia(sdp: String, media: String): Boolean {
        val section = sdpMediaSection(sdp, media) ?: return false
        val mLine = section.firstOrNull() ?: return false
        val port = mLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return false
        if (port == 0) return false

        return when (sdpMediaDirection(sdp, media)) {
            null, "sendrecv", "sendonly" -> true
            "recvonly", "inactive" -> false
            else -> true
        }
    }

    private fun sdpMediaDirection(sdp: String, media: String): String? {
        val mediaDirection = sdpMediaSection(sdp, media)
            ?.firstOrNull { it in sdpDirectionLines }
            ?.removePrefix("a=")
        if (mediaDirection != null) return mediaDirection

        return sdp.lineSequence()
            .takeWhile { !it.startsWith("m=") }
            .firstOrNull { it in sdpDirectionLines }
            ?.removePrefix("a=")
    }

    private fun sdpMediaSection(sdp: String, media: String): List<String>? {
        val lines = sdp.lineSequence().map { it.trim() }.toList()
        val start = lines.indexOfFirst { it.startsWith("m=$media ") }
        if (start == -1) return null
        val end = lines.drop(start + 1).indexOfFirst { it.startsWith("m=") }
        return if (end == -1) lines.drop(start) else lines.subList(start, start + 1 + end)
    }

    private val sdpDirectionLines = setOf("a=sendrecv", "a=sendonly", "a=recvonly", "a=inactive")
}



@kotlinx.serialization.Serializable
data class TurnServerResponse(val uris: List<String>, val username: String? = null, val password: String? = null, val ttl: Long? = null)
