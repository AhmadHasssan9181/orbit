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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import io.ktor.util.date.getTimeMillis
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
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

    private val matrixRtcMemberEventTypes = listOf("m.call.member", "org.matrix.msc3401.call.member")
    private val matrixRtcVisibilityEventTypes = matrixRtcMemberEventTypes + listOf(
        "m.call",
        "org.matrix.msc3401.call",
        "org.matrix.msc4143.rtc_foci"
    )
    private val matrixRtcMembershipShapes = mutableMapOf<String, MatrixRtcMembershipShape>()

    private enum class MatrixRtcPublishMode(val label: String, val eventTypes: List<String>) {
        ModernOnly("modern-only", listOf("m.call.member")),
        LegacyOnly("legacy-only", listOf("org.matrix.msc3401.call.member")),
        Both("both", listOf("m.call.member", "org.matrix.msc3401.call.member"))
    }

    private data class MatrixRtcVisibilityEvent(
        val type: String,
        val stateKey: String,
        val sender: String,
        val content: JsonObject,
        val raw: JsonObject
    )

    private data class MatrixRtcMembershipShape(
        val stateKey: String,
        val contentCallId: String,
        val publishMode: MatrixRtcPublishMode
    )

    override suspend fun configureCallEndpoints(sfuUrlOverride: String?) {
        context.liveKitServiceUrlOverride = sfuUrlOverride?.takeIf { it.isNotBlank() }
        context.refreshWellKnownRtcFoci()
        println("DEBUG [MatrixCallsRepository] Call endpoints configured override=${sfuUrlOverride?.takeIf { it.isNotBlank() } ?: "<none>"} wellKnown=${context.wellKnownRtcFocusUrl ?: "<none>"} effective=${context.effectiveLiveKitServiceUrl()}")
    }

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
            // js-sdk stamps client.deviceId into every VoIP event (ourPartyId); Element keys
            // hangup/candidate glare matching off it, so it must be the deviceId, not the userId.
            val myPartyId = CredentialManager.deviceId
                ?: context.trixnityClient?.userId?.full
                ?: "orbit_party_1"
            val callId = content["call_id"]?.jsonPrimitive?.content ?: return Result.failure(Exception("No call_id"))
            val versionStr = content["version"]?.jsonPrimitive?.content ?: "1"
            
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

    private val activeCallResolvers = mutableMapOf<String, Job>()

    override fun observeActiveCall(channelId: String): Flow<CallEvent.Invite?> {
        val flow = synchronized(context.activeCalls) {
            context.activeCalls.getOrPut(channelId) { MutableStateFlow(null) }
        }

        // One resolver per room for the session: resolves immediately, then follows
        // call events. Launching per invocation would stack un-cancelled collectors
        // in repositoryScope every time a screen re-subscribes.
        synchronized(activeCallResolvers) {
            activeCallResolvers.getOrPut(channelId) {
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
            }
        }
        return flow.asStateFlow()
    }

    private val activeGroupCallResolvers = mutableMapOf<String, Job>()

    override fun observeActiveGroupCall(channelId: String): Flow<String?> {
        val flow = context.activeGroupCalls.getOrPut(channelId) { MutableStateFlow(null) }
        // One resolver per room for the session: resolves immediately, then re-resolves
        // whenever sync signals that call state changed. Subscribers only read the flow.
        activeGroupCallResolvers.getOrPut(channelId) {
            context.repositoryScope.launch {
                applyGroupCallResolution(channelId, flow)
                context.groupCallStateChanges.collect { roomId ->
                    if (roomId == channelId) applyGroupCallResolution(channelId, flow)
                }
            }
        }
        return flow.asStateFlow()
    }

    private suspend fun applyGroupCallResolution(channelId: String, flow: MutableStateFlow<String?>) {
        val resolved = resolveGroupCallJoinTarget(channelId)
        val url = when {
            !resolved.exists -> null
            resolved.serviceUrl != null -> "${resolved.serviceUrl}?callId=${resolved.callId}"
            else -> "matrix-call://${resolved.callId}"
        }
        if (flow.value != url) {
            println("DEBUG: [observeActiveGroupCall] $channelId -> ${resolved.source} sfu=${resolved.serviceUrl ?: "<none>"}")
            flow.value = url
        }

        val global = context.globalActiveCalls.value
        val updated = if (url == null) global - channelId else global + (channelId to url)
        if (updated != global) context.globalActiveCalls.value = updated
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
                        if (content is UnknownEventContent && (content.eventType == "m.call.member" || content.eventType == "org.matrix.msc3401.call.member")) {
                            val sender = timelineEvent.sender.full
                            val realContent = unwrapUnknownEventContent(content)
                            // Join payloads carry no "membership" field; only the leave
                            // marker sets it to "leave" — same rule as isActiveMatrixRtcMember.
                            val membership = realContent["membership"]?.jsonPrimitive?.content
                            val createdTs = realContent["created_ts"]?.jsonPrimitive?.longOrNull
                            val expires = realContent["expires"]?.jsonPrimitive?.longOrNull
                            val expired = createdTs != null && expires != null && createdTs + expires <= getTimeMillis()
                            if (currentMembers.none { it.id == sender } && membership != "leave" && !expired) {
                                val user = context.fetchUserProfile(sender) ?: OrbitUser(
                                    id = sender, username = sender.split(":").first().removePrefix("@"),
                                    displayName = sender, avatarColor = context.hashToColor(sender), status = UserStatus.ONLINE, isBot = false, avatarUrl = null
                                )
                                currentMembers.add(user)
                            }
                        }
                    }
                    // The poll is authoritative once it yields anyone; an empty
                    // result is a transient scan gap, not "everyone left" —
                    // publishing it would blank the participant list mid-call.
                    if (currentMembers.isNotEmpty()) {
                        flow.value = currentMembers
                    }
                }
            } catch (e: Exception) {}
        }

        return flow.asStateFlow()
    }

    /**
     * Per-device roster for the matrixRTC E2EE media-key exchange. Mirrors
     * [observeCallMembers] but keeps every (sender, device_id) pair distinct —
     * Element distributes keys per DEVICE, not per user.
     */
    /** Best-effort device extraction from `_<userId>_<deviceId>[_<callId>]` state keys. */
    private fun stateKeyDeviceId(stateKey: String): String? {
        if (!stateKey.startsWith("_")) return null
        val body = stateKey.drop(1)
        val colon = body.indexOf(':')
        if (colon < 0) return null
        val rest = body.substring(colon + 1)
        val underscore = rest.indexOf('_')
        if (underscore < 0) return null
        val device = rest.substring(underscore + 1)
        val callSuffix = device.indexOf("_m.call")
        val cleaned = if (callSuffix > 0) device.substring(0, callSuffix) else device
        return cleaned.ifBlank { null }
    }

    override fun observeCallDevices(channelId: String): Flow<Set<livekit.CallDevice>> {
        val flow = context.activeCallDevices.getOrPut(channelId) { MutableStateFlow(emptySet()) }

        context.repositoryScope.launch {
            var attempt = 0
            while (attempt < 3) {
                try {
                    // Raw-JSON all-state fetch, same proven path as the visibility
                    // audit: trixnity's serializer drops unknown event types from
                    // api.room.getState(), which silently yielded an empty roster.
                    val events = fetchMatrixRtcVisibilityEvents(channelId)
                    val devices = linkedSetOf<livekit.CallDevice>()
                    for (event in events) {
                        if (!isActiveMatrixRtcMember(event)) continue
                        if (!isUnexpiredMatrixRtcMember(event)) continue
                        // A device is call-eligible once it claims any m.call member
                        // shape; device_id is what Element keys media identities on.
                        val deviceId = jsonString(event.content, "device_id")
                            ?: stateKeyDeviceId(event.stateKey)
                            ?: continue
                        devices.add(livekit.CallDevice(event.sender, deviceId))
                    }
                    if (devices.isNotEmpty()) {
                        flow.value = devices
                        return@launch
                    }
                    println("DEBUG [MatrixCallsRepository] call-device roster empty for $channelId (attempt ${attempt + 1})")
                } catch (e: Exception) {
                    println("WARN [MatrixCallsRepository] call-device roster error for $channelId: ${e.message}")
                }
                attempt++
                delay(3000)
            }
        }

        return flow.asStateFlow()
    }

    override suspend fun isRoomEncrypted(roomId: String): Boolean = try {
        // The "/state/event/<type>" URL form parses as eventType="event",
        // stateKey="<type>" on Synapse → 404 → silently treated as unencrypted,
        // which disabled the whole E2EE chain. Classic /state/<type> form works.
        val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state/m.room.encryption")) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
        }
        val encrypted = response.status.value == 200
        if (!encrypted) {
            println("DEBUG [MatrixCallsRepository] encryption probe $roomId -> HTTP ${response.status.value}")
        }
        encrypted
    } catch (e: Exception) {
        println("WARN [MatrixCallsRepository] encryption probe threw for $roomId: ${e.message}")
        false
    }

    override fun startCallMediaKeyExchange(roomId: String, livekitIdentity: String) {
        val trixnity = context.trixnityClient ?: return
        val userId = context.userId ?: return
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        try {
            livekit.CallMediaKeyExchangeManager.start(
                client = trixnity,
                scope = context.repositoryScope,
                roomId = roomId,
                ownUserId = userId,
                ownDeviceId = deviceId,
                ownLivekitIdentity = livekitIdentity.ifBlank { "$userId:$deviceId" },
                devicesFlow = observeCallDevices(roomId)
            )
        } catch (e: Exception) {
            println("ERROR [MatrixCallsRepository] media key exchange start failed: ${e.message}")
        }
    }

    override fun stopCallMediaKeyExchange() {
        livekit.CallMediaKeyExchangeManager.stop()
    }

    private fun membershipShapeMapKey(roomId: String, callId: String): String = "$roomId\u0000$callId"

    private fun String.encodeStateKey(): String =
        replace("%", "%25")
            .replace("@", "%40")
            .replace(":", "%3A")
            .replace("#", "%23")
            .replace("/", "%2F")
            .replace(" ", "%20")

    private fun jsonString(content: JsonObject, key: String): String? =
        runCatching { content[key]?.jsonPrimitive?.content }.getOrNull()

    private fun jsonLong(content: JsonObject, key: String): Long? =
        runCatching { content[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun membershipId(content: JsonObject): String? =
        jsonString(content, "membershipID") ?: jsonString(content, "membership_id")

    private fun isActiveMatrixRtcMember(event: MatrixRtcVisibilityEvent): Boolean {
        if (event.type !in matrixRtcMemberEventTypes) return false
        return jsonString(event.content, "membership") != "leave"
    }

    private fun expiresRemainingMs(event: MatrixRtcVisibilityEvent): Long? {
        val createdTs = jsonLong(event.content, "created_ts") ?: return null
        val expires = jsonLong(event.content, "expires") ?: return null
        return createdTs + expires - getTimeMillis()
    }

    private fun isUnexpiredMatrixRtcMember(event: MatrixRtcVisibilityEvent): Boolean =
        expiresRemainingMs(event)?.let { it > 0 } ?: true

    private fun hasNonNullContentField(content: JsonObject, key: String): Boolean {
        val value = content[key] ?: return false
        return value.toString() != "null"
    }

    private fun nullableContentFieldString(content: JsonObject, key: String): String? {
        val value = content[key] ?: return null
        return value.toString().takeUnless { it == "null" }
    }

    private suspend fun fetchMatrixRtcVisibilityEvents(roomId: String): List<MatrixRtcVisibilityEvent> {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value !in 200..299) {
                println("WARN [MatrixRTCVisibilityAudit] all-state fetch failed status=${response.status.value}")
                return emptyList()
            }

            Json.parseToJsonElement(response.bodyAsText()).jsonArray.mapNotNull { element ->
                val raw = element.jsonObject
                val type = jsonString(raw, "type") ?: return@mapNotNull null
                if (type !in matrixRtcVisibilityEventTypes) return@mapNotNull null
                val content = raw["content"]?.jsonObject ?: buildJsonObject { }
                MatrixRtcVisibilityEvent(
                    type = type,
                    stateKey = jsonString(raw, "state_key") ?: "",
                    sender = jsonString(raw, "sender") ?: "",
                    content = content,
                    raw = raw
                )
            }
        } catch (e: Exception) {
            println("WARN [MatrixRTCVisibilityAudit] all-state fetch exception=${e.message}")
            emptyList()
        }
    }

    private fun logMatrixRtcVisibilityEvents(label: String, events: List<MatrixRtcVisibilityEvent>) {
        println("DEBUG [MatrixRTCVisibilityAudit] $label event_count=${events.size}")
        events.forEach { event ->
            val createdTs = jsonLong(event.content, "created_ts")
            val expires = jsonLong(event.content, "expires")
            val now = getTimeMillis()
            val remaining = if (createdTs != null && expires != null) createdTs + expires - now else null
            println(
                "DEBUG [MatrixRTCVisibilityAudit] $label type=${event.type} state_key=${event.stateKey} sender=${event.sender} " +
                    "membershipID=${membershipId(event.content)} device_id=${jsonString(event.content, "device_id")} " +
                    "call_id=${jsonString(event.content, "call_id")} application=${jsonString(event.content, "application")} " +
                    "intent=${jsonString(event.content, "m.call.intent")} scope=${jsonString(event.content, "scope")} " +
                    "created_ts=$createdTs expires=$expires expires_remaining_ms=$remaining " +
                    "foci_preferred=${event.content["foci_preferred"]} focus_active=${event.content["focus_active"]} " +
                    "foci_active=${event.content["foci_active"]} capabilities=${event.content["capabilities"]}"
            )
        }
    }

    private fun logElementReferenceDump(label: String, events: List<MatrixRtcVisibilityEvent>, ownUserId: String) {
        events
            .filter { it.type == "org.matrix.msc3401.call.member" }
            .filter { it.sender != ownUserId }
            .filter(::isActiveMatrixRtcMember)
            .forEach { event ->
                println(
                    "DEBUG [ElementReferenceDump] $label " +
                        "event_type=${event.type} " +
                        "state_key=${event.stateKey} " +
                        "sender=${event.sender} " +
                        "call_id=${jsonString(event.content, "call_id")?.ifBlank { "<blank>" }} " +
                        "m.call.intent=${jsonString(event.content, "m.call.intent")} " +
                        "capabilities=${event.content["capabilities"]} " +
                        "foci_active=${event.content["foci_active"]} " +
                        "foci_preferred=${event.content["foci_preferred"]} " +
                        "focus_active=${event.content["focus_active"]} " +
                        "expires=${jsonLong(event.content, "expires")}"
                )
            }
    }

    private fun findReferenceMemberEvent(
        events: List<MatrixRtcVisibilityEvent>,
        ownUserId: String,
        ownDeviceId: String,
        ownMembershipId: String
    ): MatrixRtcVisibilityEvent? =
        events
            .filter(::isActiveMatrixRtcMember)
            .filter(::isUnexpiredMatrixRtcMember)
            .filter { event ->
                val memberId = membershipId(event.content)
                val deviceId = jsonString(event.content, "device_id")
                event.sender != ownUserId &&
                    memberId != ownMembershipId &&
                    memberId?.startsWith("$ownUserId:") != true &&
                    deviceId != ownDeviceId
            }
            .sortedWith(
                if (MatrixRtcFeatureFlags.mirrorVisibleElementShape) {
                    compareBy<MatrixRtcVisibilityEvent> { it.type != "org.matrix.msc3401.call.member" }
                        .thenByDescending { expiresRemainingMs(it) ?: 0L }
                        .thenBy { it.stateKey }
                } else {
                    compareBy<MatrixRtcVisibilityEvent> { it.type != "m.call.member" }
                        .thenByDescending { expiresRemainingMs(it) ?: 0L }
                        .thenBy { it.stateKey }
                }
            )
            .firstOrNull()

    private fun logSelectedReference(reference: MatrixRtcVisibilityEvent?, ownUserId: String) {
        val remote = reference != null && reference.sender != ownUserId &&
            membershipId(reference.content)?.startsWith("$ownUserId:") != true
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_is_remote=$remote")
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_sender=${reference?.sender}")
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_type=${reference?.type}")
        println(
            "DEBUG [MatrixRTCVisibilityAudit] selected_reference_call_id=${jsonString(reference?.content ?: buildJsonObject { }, "call_id")?.ifBlank { "<blank>" }}"
        )
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_expires=${reference?.content?.let { jsonLong(it, "expires") }}")
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_expires_remaining_ms=${reference?.let(::expiresRemainingMs)}")
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_has_foci_active=${reference?.content?.let { hasNonNullContentField(it, "foci_active") } ?: false}")
        println("DEBUG [MatrixRTCVisibilityAudit] selected_reference_has_capabilities=${reference?.content?.let { hasNonNullContentField(it, "capabilities") } ?: false}")
    }

    private fun findOwnMemberEvent(
        events: List<MatrixRtcVisibilityEvent>,
        ownDeviceId: String,
        ownMembershipId: String,
        selectedStateKey: String
    ): MatrixRtcVisibilityEvent? =
        events
            .filter(::isActiveMatrixRtcMember)
            .filter { event ->
                event.stateKey == selectedStateKey ||
                    membershipId(event.content) == ownMembershipId ||
                    jsonString(event.content, "device_id") == ownDeviceId
            }
            .sortedWith(compareBy<MatrixRtcVisibilityEvent> { it.stateKey != selectedStateKey }.thenBy { it.type != "m.call.member" })
            .firstOrNull()

    private fun selectMatrixRtcPublishMode(
        reference: MatrixRtcVisibilityEvent?,
        forceLegacyVideoShape: Boolean = false
    ): MatrixRtcPublishMode {
        if (MatrixRtcFeatureFlags.mirrorVisibleElementShape || forceLegacyVideoShape) {
            val reason = if (forceLegacyVideoShape) "post-video-membership-mode" else "mirrorVisibleElementShape"
            println("DEBUG [MatrixRTCVisibilityAudit] publish mode=legacy-only reason=$reason")
            return MatrixRtcPublishMode.LegacyOnly
        }

        val configured = MatrixRtcFeatureFlags.publishMemberMode?.lowercase()
        val mode = when (configured) {
            "modern", "modern-only", "m.call.member" -> MatrixRtcPublishMode.ModernOnly
            "legacy", "legacy-only", "org.matrix.msc3401.call.member" -> MatrixRtcPublishMode.LegacyOnly
            "both", "dual" -> MatrixRtcPublishMode.Both
            null -> when {
                MatrixRtcFeatureFlags.publishLegacyMember -> MatrixRtcPublishMode.Both
                reference?.type == "org.matrix.msc3401.call.member" -> MatrixRtcPublishMode.LegacyOnly
                else -> MatrixRtcPublishMode.ModernOnly
            }
            else -> {
                println("WARN [MatrixRTCVisibilityAudit] Unknown orbit.matrixrtc.publishMemberMode=$configured, using reference/default")
                when {
                    MatrixRtcFeatureFlags.publishLegacyMember -> MatrixRtcPublishMode.Both
                    reference?.type == "org.matrix.msc3401.call.member" -> MatrixRtcPublishMode.LegacyOnly
                    else -> MatrixRtcPublishMode.ModernOnly
                }
            }
        }
        println("DEBUG [MatrixRTCVisibilityAudit] publish mode=${mode.label} reference_type=${reference?.type}")
        return mode
    }

    private fun selectMembershipStateKey(
        reference: MatrixRtcVisibilityEvent?,
        userId: String,
        deviceId: String,
        callId: String
    ): String {
        val selected = livekit.MatrixRtcMembershipContent.selectStateKey(
            referenceStateKey = reference?.stateKey,
            referenceMembershipId = reference?.content?.let { membershipId(it) },
            referenceDeviceId = reference?.content?.let { jsonString(it, "device_id") },
            userId = userId,
            deviceId = deviceId,
            callId = callId
        )
        val mirrored = reference != null && selected != "_${userId}_${deviceId}_$callId"
        val reason = if (mirrored) "mirrored_element_reference_suffix" else "fallback_orbit_state_key"
        println(
            "DEBUG [MatrixRTCVisibilityAudit] state_key selected reason=$reason element_reference_state_key=${reference?.stateKey} orbit_state_key=$selected"
        )
        return selected
    }

    private fun selectContentCallId(
        reference: MatrixRtcVisibilityEvent?,
        callId: String,
        forceLegacyVideoShape: Boolean = false
    ): String {
        val legacyShape = MatrixRtcFeatureFlags.mirrorVisibleElementShape || forceLegacyVideoShape
        val selected = livekit.MatrixRtcMembershipContent.selectContentCallId(
            referenceContainsCallId = reference?.content?.containsKey("call_id") == true,
            referenceCallId = reference?.content?.let { jsonString(it, "call_id") },
            callId = callId,
            legacyElementShape = legacyShape
        )
        if (legacyShape) {
            val reason = if (forceLegacyVideoShape) "post-video-membership-mode" else "mirrorVisibleElementShape"
            println("DEBUG [MatrixRTCVisibilityAudit] content call_id selected reason=$reason orbit_call_id=<blank>")
        } else {
            println(
                "DEBUG [MatrixRTCVisibilityAudit] content call_id selected element_reference_call_id=${reference?.content?.let { jsonString(it, "call_id") }} orbit_call_id=$selected"
            )
        }
        return selected
    }

    private fun logMembershipComparison(reference: MatrixRtcVisibilityEvent?, orbit: MatrixRtcVisibilityEvent?) {
        if (reference == null || orbit == null) {
            println("WARN [MatrixRTCVisibilityAudit] comparison unavailable reference_present=${reference != null} orbit_present=${orbit != null}")
            return
        }

        fun compare(field: String, referenceValue: String?, orbitValue: String?) {
            println(
                "DEBUG [MatrixRTCVisibilityAudit] comparison field=$field element_value=$referenceValue orbit_value=$orbitValue match=${referenceValue == orbitValue}"
            )
        }

        compare("event_type", reference.type, orbit.type)
        compare("state_key", reference.stateKey, orbit.stateKey)
        compare("membershipID", membershipId(reference.content), membershipId(orbit.content))
        compare("device_id", jsonString(reference.content, "device_id"), jsonString(orbit.content, "device_id"))
        compare("call_id", jsonString(reference.content, "call_id"), jsonString(orbit.content, "call_id"))
        compare("application", jsonString(reference.content, "application"), jsonString(orbit.content, "application"))
        compare("scope", jsonString(reference.content, "scope"), jsonString(orbit.content, "scope"))
        compare("m.call.intent", jsonString(reference.content, "m.call.intent"), jsonString(orbit.content, "m.call.intent"))
        compare("focus_active", reference.content["focus_active"]?.toString(), orbit.content["focus_active"]?.toString())
        compare("foci_preferred", reference.content["foci_preferred"]?.toString(), orbit.content["foci_preferred"]?.toString())
        compare("foci_active", nullableContentFieldString(reference.content, "foci_active"), nullableContentFieldString(orbit.content, "foci_active"))
        compare("capabilities", nullableContentFieldString(reference.content, "capabilities"), nullableContentFieldString(orbit.content, "capabilities"))
    }

    private suspend fun clearMatrixRtcMemberEvent(roomId: String, eventType: String, stateKey: String) {
        context.httpClient.put(context.fullUrl("/rooms/$roomId/state/$eventType/${stateKey.encodeStateKey()}")) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("membership", "leave") })
        }
    }

    override suspend fun resolveGroupCallJoinTarget(roomId: String): ResolvedGroupCall {
        // 1. Canonical definition keys first (cheap direct fetch)
        try {
            for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                val url = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                val response = context.httpClient.get(url) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                }
                if (response.status.value == 200) {
                    val body = response.body<JsonObject>()
                    if (!livekit.MatrixRtcCalls.isDefinitionTerminated(body)) {
                        val focus = livekit.RtcFocusParser.firstLiveKit(body)
                        if (focus != null) {
                            println("DEBUG [JoinFocus] resolved via definition ns=$ns key=m.call sfu=${focus.serviceUrl}")
                            return ResolvedGroupCall(true, "m.call", focus.serviceUrl, "definition:$ns")
                        }
                        println("DEBUG [JoinFocus] definition ns=$ns active but has no livekit focus")
                        return ResolvedGroupCall(true, "m.call", null, "definition-no-focus:$ns")
                    }
                }
            }
        } catch (e: Exception) {
            println("DEBUG [JoinFocus] definition fetch failed: ${e.message}")
        }

        // 2. All-state scan: definitions under any state_key + member events (foci_preferred / foci_active)
        try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value == 200) {
                val events = response.body<JsonArray>()
                var memberFallback: ResolvedGroupCall? = null
                for (event in events) {
                    val obj = event.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.content ?: continue
                    val stateKey = obj["state_key"]?.jsonPrimitive?.content ?: ""
                    val content = obj["content"]?.jsonObject ?: continue

                    if (type == "m.call" || type == "org.matrix.msc3401.call") {
                        if (!livekit.MatrixRtcCalls.isDefinitionTerminated(content)) {
                            val callId = stateKey.ifBlank { "m.call" }
                            val focus = livekit.RtcFocusParser.firstLiveKit(content)
                            if (focus != null) {
                                println("DEBUG [JoinFocus] resolved via all-state definition key=$callId sfu=${focus.serviceUrl}")
                                return ResolvedGroupCall(true, callId, focus.serviceUrl, "all-state-definition")
                            }
                            if (memberFallback == null) {
                                memberFallback = ResolvedGroupCall(true, callId, null, "all-state-definition-no-focus")
                            }
                        }
                    } else if ((type == "org.matrix.msc3401.call.member" || type == "m.call.member") &&
                        content["membership"]?.jsonPrimitive?.content != "leave"
                    ) {
                        val focus = livekit.RtcFocusParser.firstLiveKit(content)
                        val callId = content["call_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            ?: stateKey.substringAfterLast("_").takeIf { it.isNotBlank() && it != stateKey }
                            ?: "m.call"
                        if (focus != null && memberFallback == null) {
                            memberFallback = ResolvedGroupCall(true, callId, focus.serviceUrl, "member-event")
                        }
                    }
                }
                memberFallback?.let {
                    println("DEBUG [JoinFocus] resolved via ${it.source} callId=${it.callId} sfu=${it.serviceUrl ?: "<none>"}")
                    return it
                }
            }
        } catch (e: Exception) {
            println("DEBUG [JoinFocus] all-state scan failed: ${e.message}")
        }

        println("DEBUG [JoinFocus] no active group call found in room state for $roomId")
        return ResolvedGroupCall.None
    }

    override suspend fun startGroupCall(roomId: String, callId: String) {
        println("DEBUG [MatrixCallsRepository] STARTING MSC3401 Creator Flow for $roomId")

        // Never clobber an active foreign call definition: publishing our default SFU over an
        // Element-created definition splits the room (joiners land on a different LiveKit room).
        val existing = resolveGroupCallJoinTarget(roomId)
        if (existing.exists) {
            println(
                "WARN [MatrixCallsRepository] startGroupCall: active call already present " +
                    "(source=${existing.source} callId=${existing.callId} sfu=${existing.serviceUrl ?: "<none>"}); reusing it"
            )
            context.activeGroupCalls.getOrPut(roomId) { MutableStateFlow(null) }.value =
                existing.serviceUrl?.let { "$it?callId=${existing.callId}" } ?: "matrix-call://${existing.callId}"
            return
        }
        
        // 1. Cleanup ALL known ghost keys
        val userId = context.userId ?: return
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        try {
            val keysToClear = listOf(
                userId, 
                "_${userId}_${deviceId}_m.call",
                "_${userId}_${deviceId}_",
                "_${userId}_${deviceId}",
                deviceId
            )
            for (key in keysToClear) {
                for (ns in listOf("m.call.member", "org.matrix.msc3401.call.member")) {
                    clearMatrixRtcMemberEvent(roomId, ns, key)
                }
            }
        } catch (e: Exception) {}

        // 2. Publish Call Definition (The Blueprint) to 'm.call' state key for "seamless" support
        try {
            // Also publish to standard m.call
            for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                val callUrl = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                context.httpClient.put(callUrl) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("m.intent", "m.room")
                        put("m.foci_preferred", buildJsonArray {
                            add(buildJsonObject {
                                put("livekit_service_url", context.effectiveLiveKitServiceUrl())
                                put("livekit_alias", roomId)
                                put("type", "livekit")
                            })
                        })
                    })
                }
            }
            println("DEBUG [MatrixCallsRepository] Published seamless m.call definition with focus")
        } catch (e: Exception) {
            println("ERROR [MatrixCallsRepository] Failed to publish m.call: ${e.message}")
        }

        // 3. Membership is published by CallManager before native media and refreshed after MediaReady.
        context.activeGroupCalls.getOrPut(roomId) { MutableStateFlow(null) }.value = "${context.effectiveLiveKitServiceUrl()}?callId=m.call"
    }

    override suspend fun endGroupCall(roomId: String, callId: String) {
        // MSC3401 calls don't really "end" via a single event, everyone just leaves.
        // Only tear down the call definition when no one else is still in the call —
        // otherwise any leaver would kill the call for everyone still connected.
        val ownUserId = context.userId
        val otherActiveMembers = if (ownUserId != null) {
            fetchMatrixRtcVisibilityEvents(roomId)
                .filter { it.type == "org.matrix.msc3401.call.member" }
                .filter { it.sender != ownUserId }
                .filter(::isActiveMatrixRtcMember)
        } else {
            emptyList()
        }

        if (otherActiveMembers.isEmpty()) {
            try {
                for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                    val callUrl = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                    context.httpClient.put(callUrl) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("m.intent", "m.room")
                            put("m.terminated", "m.user_hangup")
                        })
                    }
                }
            } catch (e: Exception) {}
        } else {
            println(
                "DEBUG [MatrixCallsRepository] endGroupCall: ${otherActiveMembers.size} other active member(s) remain in $roomId, leaving call definition intact"
            )
        }

        leaveGroupCall(roomId, callId)
    }

    override suspend fun joinGroupCall(roomId: String, callId: String) {
        println("DEBUG [MatrixCallsRepository] JOINING MSC3401 Call for $roomId (callId=$callId)")

        publishGroupCallMembership(roomId, callId, audio = true, video = false)
    }

    override suspend fun updateGroupCallMembershipCapabilities(roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String?): Boolean {
        println("DEBUG [MatrixCallsRepository] publishing MatrixRTC membership audio=$audio video=$video")
        return publishGroupCallMembership(roomId, callId, audio = audio, video = video, livekitIdentity = livekitIdentity)
    }

    /** Returns true when our own m.call.member event is visible in room state after publish. */
    private suspend fun publishGroupCallMembership(roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String? = null): Boolean {
        val userId = context.userId ?: return false
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        try {
            val membershipId = livekitIdentity ?: "$userId:$deviceId"
            val beforeEvents = fetchMatrixRtcVisibilityEvents(roomId)
            logMatrixRtcVisibilityEvents("before_publish", beforeEvents)
            logElementReferenceDump("before_publish", beforeEvents, userId)
            val reference = findReferenceMemberEvent(beforeEvents, userId, deviceId, membershipId)
            logSelectedReference(reference, userId)
            val stateKey = selectMembershipStateKey(reference, userId, deviceId, callId)
            val videoMembershipMode = livekit.LiveKitFeatureFlags.videoMembershipMode
            val forceLegacyVideoShape = video && (
                videoMembershipMode == "intent-video" ||
                    videoMembershipMode == "capabilities-video" ||
                    videoMembershipMode == "element-reference"
                )
            val jsonCallId = if (video && videoMembershipMode == "element-reference") {
                val referenceCallId = reference?.content?.let { jsonString(it, "call_id") } ?: ""
                println("DEBUG [MatrixRTCVisibilityAudit] content call_id selected reason=element-reference element_reference_call_id=${referenceCallId.ifBlank { "<blank>" }}")
                referenceCallId
            } else {
                selectContentCallId(reference, callId, forceLegacyVideoShape)
            }
            val publishMode = selectMatrixRtcPublishMode(reference, forceLegacyVideoShape)
            val shape = MatrixRtcMembershipShape(
                stateKey = stateKey,
                contentCallId = jsonCallId,
                publishMode = publishMode
            )
            matrixRtcMembershipShapes[membershipShapeMapKey(roomId, callId)] = shape

            try {
                val keysToClear = listOf(
                    userId,
                    "_${userId}_${deviceId}_m.call",
                    "_${userId}_${deviceId}_$callId",
                    "_${userId}_${deviceId}_${jsonCallId}",
                    "_${userId}_${deviceId}",
                    deviceId
                ).distinct()
                for (key in keysToClear) {
                    for (ns in matrixRtcMemberEventTypes) {
                        if (key == stateKey && ns in publishMode.eventTypes) continue
                        clearMatrixRtcMemberEvent(roomId, ns, key)
                    }
                }
            } catch (e: Exception) {
                println("WARN [MatrixRTCVisibilityAudit] stale membership cleanup failed=${e.message}")
            }

            val createdTs = getTimeMillis()
            val mirrorElementShape = MatrixRtcFeatureFlags.mirrorVisibleElementShape
            val legacyElementShape = mirrorElementShape || forceLegacyVideoShape
            val referenceIntent = reference?.content?.let { jsonString(it, "m.call.intent") }
            val videoIntent = livekit.MatrixRtcMembershipContent.resolveVideoIntent(
                video, legacyElementShape, videoMembershipMode, referenceIntent
            )
            val expiresMs = livekit.MatrixRtcMembershipContent.expiresMs(legacyElementShape)
            val payload = livekit.MatrixRtcMembershipContent.buildPayload(
                livekit.MatrixRtcMembershipContent.PayloadInputs(
                    deviceId = deviceId,
                    membershipId = membershipId,
                    createdTs = createdTs,
                    roomId = roomId,
                    defaultLiveKitServiceUrl = context.effectiveLiveKitServiceUrl(),
                    jsonCallId = jsonCallId,
                    audio = audio,
                    video = video,
                    legacyElementShape = legacyElementShape,
                    videoMembershipMode = videoMembershipMode,
                    videoIntent = videoIntent,
                    expiresMs = expiresMs,
                    referenceFociPreferred = reference?.content?.get("foci_preferred"),
                    referenceFocusActive = reference?.content?.get("focus_active"),
                    referenceFociActive = reference?.content?.get("foci_active"),
                    referenceCapabilities = reference?.content?.get("capabilities")
                )
            )
            val now = getTimeMillis()
            println(
                "DEBUG [MatrixRTCVisibilityAudit] expiry created_ts=$createdTs expires=$expiresMs now=$now expires_remaining_ms=${createdTs + expiresMs - now}"
            )
            val capabilitiesVideoLogged = (video && videoMembershipMode == "capabilities-video") || !legacyElementShape
            println("DEBUG [MatrixCallsRepository] MatrixRTC membership details userId=$userId deviceId=$deviceId membershipID=$membershipId call_id=${jsonCallId.ifBlank { "<room>" }} roomId=$roomId intent=$videoIntent capabilitiesAudio=$audio capabilitiesVideo=${if (capabilitiesVideoLogged) video else false} videoMembershipMode=$videoMembershipMode legacyElementShape=$legacyElementShape")
            println(
                "DEBUG [MatrixRTCMapping] m.call.member state_key=$stateKey membershipID=$membershipId device_id=$deviceId call_id=${jsonCallId.ifBlank { "<room>" }} room_id=$roomId"
            )
            println("DEBUG [MatrixCallsRepository] Sending m.call.member payload: $payload")

            for (eventType in publishMode.eventTypes) {
                val response = context.httpClient.put(context.fullUrl("/rooms/$roomId/state/$eventType/${stateKey.encodeStateKey()}")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                println("DEBUG [MatrixCallsRepository] Published $eventType status=${response.status.value}")
                println(
                    "DEBUG [MatrixRTCMapping] $eventType publish status=${response.status.value} state_key=$stateKey membershipID=$membershipId"
                )
            }

            val afterEvents = fetchMatrixRtcVisibilityEvents(roomId)
            logMatrixRtcVisibilityEvents("after_publish", afterEvents)
            logElementReferenceDump("after_publish", afterEvents, userId)
            val orbitMember = findOwnMemberEvent(afterEvents, deviceId, membershipId, stateKey)
            println(
                "DEBUG [MatrixRTCVisibilityAudit] orbit member visible=${orbitMember != null} selected_state_key=$stateKey selected_mode=${publishMode.label}"
            )
            logMembershipComparison(reference, orbitMember)
            return orbitMember != null
        } catch (e: Exception) {
            println("ERROR [MatrixCallsRepository] Failed to publish member join: ${e.message}")
            return false
        }
    }

    override suspend fun leaveGroupCall(roomId: String, callId: String) {
        println("DEBUG [MatrixCallsRepository] LEAVING MSC3401 Call for $roomId")
        val userId = context.userId ?: return
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        val shape = matrixRtcMembershipShapes.remove(membershipShapeMapKey(roomId, callId))
        val stateKey = shape?.stateKey ?: "_${userId}_${deviceId}_$callId"
        val eventTypes = shape?.publishMode?.eventTypes ?: matrixRtcMemberEventTypes

        try {
            for (ns in eventTypes) {
                clearMatrixRtcMemberEvent(roomId, ns, stateKey)
                println("DEBUG [MatrixRTCVisibilityAudit] leave published type=$ns state_key=$stateKey")
                println("DEBUG [VideoPublish] membership cleared type=$ns state_key=$stateKey")
            }
            
            // Also cleanup the old device-only key if it exists
            for (ns in matrixRtcMemberEventTypes) {
                clearMatrixRtcMemberEvent(roomId, ns, deviceId)
            }
        } catch (e: Exception) {}

        // Optimistically update local state
        context.activeGroupCalls[roomId]?.value = null
    }

    override suspend fun fetchLiveKitToken(roomId: String, callId: String, livekitServiceUrl: String): livekit.LiveKitAuthResponse {
        val userId = context.userId ?: throw Exception("No user ID")
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        // MatrixRTC token member.id must match the m.call.member membershipID/device_id chain.
        // In legacy Element-shape mode, the LiveKit JWT subject must also match this identity.
        val identity = "$userId:$deviceId"
        val stateKey = "_${userId}_${deviceId}_$callId"
        val forceLegacyJwtEndpoint = MatrixRtcFeatureFlags.mirrorVisibleElementShape
        println(
            "DEBUG [MatrixRTCMapping] token fetch expected m.call.member state_key=$stateKey membershipID=$identity device_id=$deviceId call_id=$callId room_id=$roomId forceLegacyJwtEndpoint=$forceLegacyJwtEndpoint"
        )
        
        val tokenResult = livekit.LiveKitTokenProvider.fetchLiveKitToken(
            roomId = roomId,
            callId = callId,
            focus = livekit.RtcFocus.LiveKit(
                serviceUrl = livekitServiceUrl,
                alias = roomId,
                raw = livekitServiceUrl
            ),
            identity = identity,
            userId = userId,
            homeserverUrl = context.homeserverUrl,
            accessToken = context.accessToken ?: throw Exception("No access token"),
            httpClient = context.httpClient,
            forceLegacyJwtEndpoint = forceLegacyJwtEndpoint
        )
        val value = tokenResult.value ?: throw Exception(tokenResult.error?.message ?: "Failed to fetch LiveKit token")
        return livekit.LiveKitAuthResponse(
            token = value.token,
            serverUrl = value.livekitUrl,
            expiresAtEpochSeconds = value.expiresAtEpochSeconds,
            roomName = value.roomName,
            identity = value.identity,
            matrixRtcMemberId = identity,
            claimedDeviceId = deviceId,
            grants = value.grants
        )
    }

    private fun unwrapUnknownEventContent(content: UnknownEventContent): JsonObject {
        val raw = content.raw.jsonObject
        val valueObj = raw["value"]?.jsonObject
        // If "value" is present and not empty, use it. Otherwise, assume the raw object IS the content.
        return if (valueObj != null && valueObj.isNotEmpty()) valueObj else raw
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
