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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
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
import util.OrbitLog
import ui.model.displayText
import ui.model.UserStatus
import ui.model.OrbitUser
import ui.model.OrbitServer
import ui.model.OrbitMessage
import ui.model.OrbitChannel
import ui.model.MessageContent
import ui.model.ChannelType
import util.getMediaStorePath
import util.getRoomDatabaseBuilder
import util.performSsoLogin
import kotlin.time.Duration.Companion.seconds




class MatrixCallsRepository(private val context: MatrixContext) : OrbitCallsRepository {

    override val activeCallsFlow = context.globalActiveCalls.asStateFlow()

    private val matrixRtcMemberEventTypes = listOf("m.call.member", "org.matrix.msc3401.call.member")
    private val matrixRtcVisibilityEventTypes = matrixRtcMemberEventTypes + listOf(
        "m.call",
        "org.matrix.msc3401.call",
        "org.matrix.msc4143.rtc_foci"
    )
    private val matrixRtcMembershipShapes = java.util.concurrent.ConcurrentHashMap<String, MatrixRtcMembershipShape>()


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

    // What we last published per room, so the key-exchange nudge can replay an
    // identical membership leave+rejoin without the caller passing callId again.
    private data class MembershipContext(
        val callId: String,
        val audio: Boolean,
        val video: Boolean,
        val livekitIdentity: String?
    )
    private val latestMembershipContextByRoom = java.util.concurrent.ConcurrentHashMap<String, MembershipContext>()

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
                // Reporting an auth error as "no TURN configured" hid an expired
                // session behind a successful-looking empty list.
                val status = response.status.value
                response.drain()
                if (status == 401 || status == 403) {
                    println("WARN [MatrixCallsRepository] turnServer request rejected status=$status")
                    Result.failure(IllegalStateException("TURN server request rejected (HTTP $status)"))
                } else {
                    Result.success(emptyList())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

    private val activeCallResolvers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun observeActiveCall(channelId: String): Flow<CallEvent.Invite?> {
        val flow = synchronized(context.activeCalls) {
            context.activeCalls.getOrPut(channelId) { MutableStateFlow(null) }
        }

        // One resolver per room for the session: resolves immediately, then follows
        // call events. Launching per invocation would stack un-cancelled collectors
        // in repositoryScope every time a screen re-subscribes.
        synchronized(activeCallResolvers) {
            activeCallResolvers.computeIfAbsent(channelId) {
                context.repositoryScope.launch {
                    val trixnity = context.trixnityClient ?: return@launch
                    val hungupCallIds = mutableSetOf<String>()
                    var latestInvite: CallEvent.Invite? = null

                    try {
                        val lastEventId = trixnity.room.getById(RoomId(channelId)).first()?.lastEventId
                        if (lastEventId != null) {
                            val events = trixnity.room.getTimelineEvents(RoomId(channelId), startFrom = lastEventId, direction = de.connect2x.trixnity.clientserverapi.model.room.GetEvents.Direction.BACKWARDS)
                            // takeWhile/firstOrNull replaces a `throw Exception("Break")`
                            // used as loop control: that also swallowed
                            // CancellationException and every real failure alike.
                            events.take(50)
                                .map { flowEvent -> flowEvent.first() }
                                .takeWhile { event ->
                                    if (latestInvite != null) return@takeWhile false
                                    val content = event.content?.getOrNull() ?: return@takeWhile true
                                    val sender = event.sender.full
                                    when (content) {
                                        is CallEventContent.Hangup -> {
                                            if (content.callId.isNotBlank()) hungupCallIds.add(content.callId)
                                            true
                                        }
                                        is CallEventContent.Invite -> {
                                            if (content.callId.isNotBlank() && !hungupCallIds.contains(content.callId)) {
                                                latestInvite = CallEvent.Invite(channelId, content.callId, content.offer.sdp, sender, content.partyId)
                                                false
                                            } else true
                                        }
                                        is UnknownEventContent -> {
                                            val typeStr = content.eventType
                                            val realContent = unwrapUnknownEventContent(content)
                                            val callId = realContent["call_id"]?.jsonPrimitive?.content ?: ""
                                            if (callId.isNotBlank()) {
                                                if (typeStr == "m.call.hangup") {
                                                    hungupCallIds.add(callId)
                                                    true
                                                } else if (typeStr == "m.call.invite" && !hungupCallIds.contains(callId)) {
                                                    val sdp = realContent["offer"]?.jsonObject?.get("sdp")?.jsonPrimitive?.content ?: ""
                                                    if (sdp.isNotBlank()) {
                                                        latestInvite = CallEvent.Invite(channelId, callId, sdp, sender, partyIdFrom(realContent))
                                                        false
                                                    } else true
                                                } else true
                                            } else true
                                        }
                                        else -> true
                                    }
                                }
                                .firstOrNull()
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        println("WARN [MatrixCallsRepository] active-call history scan failed for $channelId: ${e.message}")
                    }

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

    private val activeGroupCallResolvers = java.util.concurrent.ConcurrentHashMap<String, Job>()

    override fun observeActiveGroupCall(channelId: String): Flow<String?> {
        val flow = synchronized(context.activeGroupCalls) {
            context.activeGroupCalls.getOrPut(channelId) { MutableStateFlow(null) }
        }
        // One resolver per room for the session: resolves immediately, then re-resolves
        // whenever sync signals that call state changed. Subscribers only read the flow.
        activeGroupCallResolvers.computeIfAbsent(channelId) {
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

        // One resolver coroutine per room, so a read-modify-write here can lose
        // another room's entry when two rooms resolve at the same instant.
        context.globalActiveCalls.update { global ->
            if (url == null) global - channelId else global + (channelId to url)
        }
    }

    // Both rosters are produced by ONE poller (see ensureRosterPoller), so one
    // map covers both flows.
    private val activeCallRosterJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /** Stops the roster poller for a room; called on hangup and failed join. */
    override fun stopCallRosterPolling(roomId: String) {
        activeCallRosterJobs.remove(roomId)?.cancel()
        // Cancel the job is NOT the same as forgetting who was in the call. The
        // roster lives in a StateFlow the sidebar is still subscribed to, and
        // nothing else ever emptied it -- so after every call ended, every room
        // that had ever hosted one kept showing its final participant list for the
        // rest of the session. That was the "members who are not there" ghost
        // list. Clear both flows: the participant roster and the device roster
        // feeding the E2EE media-key exchange.
        //
        // Set the value but keep the flow OBJECT. Subscribers (the sidebar row,
        // the key-exchange path) captured this exact instance from
        // observeCallMembers(); dropping it from the map would make the next
        // ensureRosterPoller mint a replacement that nobody is watching, so the
        // second call in that room would render nobody.
        context.activeCallMembers[roomId]?.value = emptyList()
        context.activeCallDevices[roomId]?.value = emptySet()
        context.activeCallStartedAt.remove(roomId)
    }

    /**
     * Single 3s poller per room. It feeds BOTH the participant roster and the
     * per-device key roster from one /rooms/{id}/state fetch; two pollers meant
     * two full-state downloads (hundreds of KB) every 3s per call.
     */
    private fun ensureRosterPoller(channelId: String) {
        val membersFlow = synchronized(context.activeCallMembers) {
            context.activeCallMembers.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        }
        val devicesFlow = synchronized(context.activeCallDevices) {
            context.activeCallDevices.getOrPut(channelId) { MutableStateFlow(emptySet()) }
        }
        synchronized(this) {
            val existing = activeCallRosterJobs[channelId]
            if (existing != null && existing.isActive) return
            activeCallRosterJobs[channelId] = context.repositoryScope.launch {
                while (isActive) {
                    try {
                        // null == fetch failed. Publishing an empty roster on a
                        // transient 429/500 blanks the participant list AND the
                        // device roster that feeds the E2EE media-key exchange,
                        // so a failed tick must leave both flows untouched.
                        val events = fetchMatrixRtcVisibilityEvents(channelId)
                        if (events == null) {
                            println("WARN [MatrixCallsRepository] roster tick failed for $channelId; keeping previous roster")
                        } else {
                            // When did the current call start? Taken from the same
                            // /state payload we already downloaded.
                            val callStartedAt = callStartFromState(events, channelId)
                            val currentMembers = mutableListOf<OrbitUser>()
                            val devices = linkedSetOf<livekit.CallDevice>()
                            for (event in events) {
                                if (!isActiveMatrixRtcMember(event, callStartedAt)) continue
                                if (!isUnexpiredMatrixRtcMember(event, callStartedAt)) continue
                                val sender = event.sender
                                if (sender.isNotBlank() && currentMembers.none { it.id == sender }) {
                                    val user = context.fetchUserProfile(sender) ?: OrbitUser(
                                        id = sender,
                                        username = sender.split(":").first().removePrefix("@"),
                                        displayName = sender,
                                        avatarColor = context.hashToColor(sender),
                                        status = UserStatus.ONLINE,
                                        isBot = false,
                                        avatarUrl = null
                                    )
                                    currentMembers.add(user)
                                }
                                val deviceId = jsonString(event.content, "device_id")
                                    ?: stateKeyDeviceId(event.stateKey)
                                if (sender.isNotBlank() && deviceId != null) {
                                    devices.add(livekit.CallDevice(sender, deviceId))
                                }
                            }
                            if (membersFlow.value != currentMembers) {
                                membersFlow.value = currentMembers
                            }
                            if (devicesFlow.value != devices) {
                                devicesFlow.value = devices
                            }
                            if (callStartedAt == null) {
                                context.activeCallStartedAt.remove(channelId)
                            } else {
                                context.activeCallStartedAt[channelId] = callStartedAt
                            }

                            // ── Self-terminating poller ────────────────────
                            //
                            // This loop ran `while (isActive)` and relied
                            // entirely on every caller remembering to invoke
                            // `stopCallRosterPolling`. Anything that subscribed
                            // without a matching teardown left a job running a
                            // full `/rooms/{id}/state` download (hundreds of KB)
                            // every 3 seconds for the rest of the session.
                            //
                            // A live run caught it: after hanging up, the log
                            // kept emitting
                            //   roster tick !cXZD…: current call began
                            //   10525444943ms ago
                            // every three seconds — 10,525,444 seconds is
                            // **12 days**. A call that ended twelve days ago was
                            // still being polled because nothing had stopped the
                            // job, and heap climbed 41 MB → 89 MB while it did.
                            //
                            // If there is no live call left in this room, there
                            // is nothing to poll for. Stop. That covers leaving a
                            // call on the server, joining the wrong room, and a
                            // caller that simply never cleaned up — none of which
                            // can now leak a job.
                            if (callStartedAt == null && currentMembers.isEmpty()) {
                                util.OrbitLog.d(
                                    "MatrixCallsRepository",
                                    "roster poller stopping: no live call remains in $channelId"
                                )
                                stopCallRosterPolling(channelId)
                                return@launch
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        println("WARN [MatrixCallsRepository] observeCallMembers error: ${e.message}")
                    }
                    delay(3000)
                }
            }
        }
    }

    override fun observeCallMembers(channelId: String): Flow<List<OrbitUser>> {
        val flow = synchronized(context.activeCallMembers) {
            context.activeCallMembers.getOrPut(channelId) { MutableStateFlow(emptyList()) }
        }
        ensureRosterPoller(channelId)
        return flow.asStateFlow()
    }

    /**
     * Best-effort device extraction from `_<userId>_<deviceId>[_<callId>]` state
     * keys. The mxid is split on the LAST underscore, not the first colon:
     * splitting on the colon lands inside the localpart/domain separator and
     * yields a device id that is a truncated mxid.
     */
    private fun stateKeyDeviceId(stateKey: String): String? {
        if (!stateKey.startsWith("_")) return null
        val body = stateKey.removePrefix("_")
        val separator = body.indexOf('_')
        if (separator < 0) return null
        val device = body.substring(separator + 1)
        if (device.isBlank()) return null
        val callSuffix = device.indexOf("_m.call")
        val cleaned = if (callSuffix > 0) device.substring(0, callSuffix) else device
        return cleaned.ifBlank { null }
    }

    /**
     * Per-device roster for the matrixRTC E2EE media-key exchange. Mirrors
     * [observeCallMembers] but keeps every (sender, device_id) pair distinct —
     * Element distributes keys per DEVICE, not per user.
     */
    override fun observeCallDevices(channelId: String): Flow<Set<livekit.CallDevice>> {
        val flow = synchronized(context.activeCallDevices) {
            context.activeCallDevices.getOrPut(channelId) { MutableStateFlow(emptySet()) }
        }
        // Same poller as observeCallMembers: one /state fetch feeds both rosters.
        ensureRosterPoller(channelId)
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
        response.drain()
        encrypted
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
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
                devicesFlow = observeCallDevices(roomId),
                nudgeMembership = { nudgeOwnMembership(roomId) }
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

    // Delegated to the shared helper so the roster poller and the sync collector
    // cannot drift apart again. They previously had separate copies of this
    // logic, and the sync copy omitted the expiry check -- which let a lapsed
    // membership repopulate a roster the poller had just pruned.
    private fun getActiveUnexpiredMemberships(content: JsonObject, callStartedAt: Long? = null): List<JsonObject> =
        MatrixRtcMembership.activeUnexpiredMemberships(content, callStartedAt)


    private fun isActiveMatrixRtcMember(event: MatrixRtcVisibilityEvent, callStartedAt: Long? = null): Boolean {
        if (event.type !in matrixRtcMemberEventTypes) return false
        return getActiveUnexpiredMemberships(event.content, callStartedAt).isNotEmpty()
    }

    /**
     * `origin_server_ts` of the room's live `m.call` definition event, published to
     * [MatrixContext.activeCallStartedAt] so the sync collector can apply the same
     * "belongs to the current call" rule the poller does.
     *
     * Null when the room has no un-terminated definition event, which means the
     * call is over and every membership record in the room is historical. The
     * caller then falls back to the expiry-only rule rather than blanking a
     * roster it cannot date.
     */
    private fun callStartFromState(events: List<MatrixRtcVisibilityEvent>, roomId: String): Long? {
        val definition = events.firstOrNull { event ->
            (event.type == "m.call" || event.type == "org.matrix.msc3401.call") &&
                !livekit.MatrixRtcCalls.isDefinitionTerminated(event.content)
        } ?: return null
        val originServerTs = jsonLong(definition.raw, "origin_server_ts")
        // Aged out past its own `lifetime` is the same as terminated, and the
        // poller needs to hear about it.
        //
        // Without this the poller's only "stop" condition was "no definition at
        // all", so a room whose definition outlived its call kept a 3-second
        // `/rooms/{id}/state` poller alive forever. A run log caught it: `roster
        // tick ... current call began 10534060322ms ago` -- 121 days -- repeating
        // every three seconds for the rest of the session.
        //
        // The check is `CallStateResolver`'s, not a second implementation of it,
        // so the UI's "is this room live" and the poller's "should I keep
        // running" cannot disagree. Definitions Orbit published before
        // `DEFINITION_LIFETIME_MS` existed carry no `lifetime`, cannot be proven
        // expired, and are deliberately left alone -- "cannot prove" must not be
        // read as "expired".
        if (CallStateResolver.definitionAgedOut(definition.content, originServerTs, getTimeMillis())) {
            if (util.OrbitLog.debugEnabled) {
                util.OrbitLog.d(
                    "MatrixCallsRepository",
                    "roster tick $roomId: call definition is past its lifetime; treating the call as over",
                )
            }
            return null
        }
        val start = MatrixRtcMembership.callStartFromDefinition(
            definition.content,
            originServerTs,
        )
        if (start != null) {
            if (util.OrbitLog.debugEnabled) {
                util.OrbitLog.d(
                    "MatrixCallsRepository",
                    "roster tick $roomId: current call began ${getTimeMillis() - start}ms ago",
                )
            }
        }
        return start
    }

    private fun expiresRemainingMs(event: MatrixRtcVisibilityEvent): Long? {
        val validMemberships = getActiveUnexpiredMemberships(event.content)
        if (validMemberships.isEmpty()) return 0L
        val now = getTimeMillis()
        return validMemberships.mapNotNull { obj ->
            val createdTs = jsonLong(obj, "created_ts") ?: return@mapNotNull null
            val expires = jsonLong(obj, "expires") ?: return@mapNotNull null
            createdTs + expires - now
        }.maxOrNull() ?: 3600000L
    }

    private fun isUnexpiredMatrixRtcMember(event: MatrixRtcVisibilityEvent, callStartedAt: Long? = null): Boolean =
        getActiveUnexpiredMemberships(event.content, callStartedAt).isNotEmpty()

    private fun hasNonNullContentField(content: JsonObject, key: String): Boolean {
        val value = content[key] ?: return false
        return value.toString() != "null"
    }

    private fun nullableContentFieldString(content: JsonObject, key: String): String? {
        val value = content[key] ?: return null
        return value.toString().takeUnless { it == "null" }
    }

    /**
     * `null` means the fetch FAILED; an empty list means the room genuinely has no
     * MatrixRTC events. Callers must not conflate the two: a transient 429/500
     * used to publish an empty roster and blank the participant list plus the
     * device roster the E2EE media-key exchange depends on.
     */
    private suspend fun fetchMatrixRtcVisibilityEvents(roomId: String): List<MatrixRtcVisibilityEvent>? {
        return try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value !in 200..299) {
                response.drain()
                println("WARN [MatrixRTCVisibilityAudit] all-state fetch failed status=${response.status.value}")
                return null
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("WARN [MatrixRTCVisibilityAudit] all-state fetch exception=${e.message}")
            null
        }
    }

    private fun logMatrixRtcVisibilityEvents(label: String, events: List<MatrixRtcVisibilityEvent>) {
        // One summary line always; the per-event dump only under the debug flag.
        // This was ~20 lines per member event, several times per call attempt.
        if (!OrbitLog.debugEnabled) {
            val memberEvents = events.count { it.type == "org.matrix.msc3401.call.member" }
            println(
                "DEBUG [MatrixRTCVisibilityAudit] $label total=${events.size} memberEvents=$memberEvents " +
                    "(set -Dorbit.debug=true for the per-event dump)"
            )
            return
        }
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
        // Behind the debug flag. This printed one line PER member event in the
        // room, with the full foci/capabilities payload, on every publish and
        // every visibility audit. On a busy room that was hundreds of lines per
        // call attempt and made the log unreadable.
        if (!OrbitLog.debugEnabled) return
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

        // Behind the debug flag. This fired for EVERY m.call.member event in the
        // room -- a dozen-plus lines each, on every visibility audit -- which
        // was the bulk of the log and buried the lines that mattered.
        if (!OrbitLog.debugEnabled) {
            val mismatches = listOf(
                "event_type" to (reference.type to orbit.type),
                "state_key" to (reference.stateKey to orbit.stateKey),
                "membershipID" to (membershipId(reference.content) to membershipId(orbit.content)),
                "device_id" to (jsonString(reference.content, "device_id") to jsonString(orbit.content, "device_id")),
                "application" to (jsonString(reference.content, "application") to jsonString(orbit.content, "application")),
                "intent" to (jsonString(reference.content, "m.call.intent") to jsonString(orbit.content, "m.call.intent")),
                "scope" to (jsonString(reference.content, "scope") to jsonString(orbit.content, "scope"))
            ).filter { it.second.first != it.second.second }

            println(
                "DEBUG [MatrixRTCVisibilityAudit] compared state_key=${orbit.stateKey} " +
                    "mismatches=${if (mismatches.isEmpty()) "none" else mismatches.joinToString { it.first }}"
            )
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

    private suspend fun clearMatrixRtcMemberEvent(roomId: String, eventType: String, stateKey: String): Int {
        val response = context.httpClient.put(context.fullUrl("/rooms/$roomId/state/$eventType/${stateKey.encodeStateKey()}")) {
            header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("membership", "leave") })
        }
        response.drain()
        return response.status.value
    }

    override suspend fun resolveGroupCallJoinTarget(roomId: String): ResolvedGroupCall {
        // The all-state scan is AUTHORITATIVE and runs first.
        //
        // It is the only source here that carries `origin_server_ts`, and without
        // that a definition's `lifetime` cannot be evaluated -- so this is the
        // only place that can tell a live call from one that quietly expired
        // because whoever started it never terminated it.
        //
        // `GET /rooms/{id}/state/{type}/{key}` returns the event CONTENT only, no
        // timestamp, so when that fetch was consulted first it reported rooms as
        // having a live call for as long as the definition sat there. In practice
        // that made every room that had ever had a call show a "join the call"
        // banner, run a 3-second roster poller, and list its old participants.
        val scanVerdict = try {
            val response = context.httpClient.get(context.fullUrl("/rooms/$roomId/state")) {
                header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
            }
            if (response.status.value != 200) {
                response.drain()
                null
            } else {
                CallStateResolver.resolve(
                    entries = CallStateResolver.parseStateArray(response.bodyAsText()),
                    liveKitFocusOf = { livekit.RtcFocusParser.firstLiveKit(it)?.serviceUrl }
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DEBUG [JoinFocus] all-state scan failed: ${e.message}")
            null
        }

        if (scanVerdict != null) {
            if (util.OrbitLog.debugEnabled) {
                util.OrbitLog.d(
                    "JoinFocus",
                    "room $roomId -> exists=${scanVerdict.exists} source=${scanVerdict.source ?: "-"} " +
                        "sfu=${scanVerdict.serviceUrl ?: "-"} reason=${scanVerdict.reason ?: "-"}"
                )
            }
            return scanVerdict.toResolved()
        }

        // Fallback only, for when the scan itself failed. This path cannot judge
        // freshness, so it answers a strictly weaker question: "is there a call
        // definition, and does it point at a LiveKit SFU?" Preferring it over a
        // working scan would reintroduce the stale-call bug above.
        try {
            for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                val url = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                val response = context.httpClient.get(url) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                }
                if (response.status.value != 200) response.drain()
                if (response.status.value == 200) {
                    val body = response.body<JsonObject>()
                    if (!livekit.MatrixRtcCalls.isDefinitionTerminated(body)) {
                        val focus = livekit.RtcFocusParser.firstLiveKit(body)
                        if (focus != null) {
                            println("DEBUG [JoinFocus] (scan unavailable) resolved via definition ns=$ns sfu=${focus.serviceUrl}")
                            return ResolvedGroupCall(true, "m.call", focus.serviceUrl, "definition:$ns")
                        }
                        println("DEBUG [JoinFocus] (scan unavailable) definition ns=$ns active but has no livekit focus")
                        return ResolvedGroupCall(true, "m.call", null, "definition-no-focus:$ns")
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("DEBUG [JoinFocus] definition fetch failed: ${e.message}")
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
                for (ns in matrixRtcMemberEventTypes) {
                    clearMatrixRtcMemberEvent(roomId, ns, key)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("WARN [MatrixCallsRepository] ghost membership cleanup failed for $roomId: ${e.message}")
        }

        // 2. Publish Call Definition (The Blueprint) to 'm.call' state key for "seamless" support
        try {
            // Also publish to standard m.call
            for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                val callUrl = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                val response = context.httpClient.put(callUrl) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject {
                        put("m.intent", "m.room")
                        // Without this the definition is immortal: nothing ages
                        // it out and no client can tell a 4-month-old leftover
                        // from a live call. See
                        // MatrixRtcMembershipContent.DEFINITION_LIFETIME_MS.
                        put("lifetime", livekit.MatrixRtcMembershipContent.DEFINITION_LIFETIME_MS)
                        put("m.foci_preferred", buildJsonArray {
                            add(buildJsonObject {
                                put("livekit_service_url", context.effectiveLiveKitServiceUrl())
                                put("livekit_alias", roomId)
                                put("type", "livekit")
                            })
                        })
                    })
                }
                println("DEBUG [MatrixCallsRepository] Published call definition ns=$ns status=${response.status.value}")
                response.drain()
            }
            println("DEBUG [MatrixCallsRepository] Published seamless m.call definition with focus")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        var stateFetchFailed = false
        val otherActiveMembers = if (ownUserId != null) {
            // Both member event types count as "still in the call": filtering on
            // the legacy type only meant a modern-only publish mode looked empty
            // and hanging up terminated the call for everyone still connected.
            // A failed fetch is NOT an empty roster, so it must not terminate.
            val events = fetchMatrixRtcVisibilityEvents(roomId)
            if (events == null) {
                println("WARN [MatrixCallsRepository] endGroupCall: state fetch failed; NOT terminating the call definition")
                stateFetchFailed = true
                emptyList()
            } else {
                events
                    .filter { it.type in matrixRtcMemberEventTypes }
                    .filter { it.sender != ownUserId }
                    .filter(::isActiveMatrixRtcMember)
            }
        } else {
            emptyList()
        }

        if (otherActiveMembers.isEmpty() && !stateFetchFailed) {
            try {
                for (ns in listOf("m.call", "org.matrix.msc3401.call")) {
                    val callUrl = context.fullUrl("/rooms/$roomId/state/$ns/m.call")
                    val response = context.httpClient.put(callUrl) {
                        header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("m.intent", "m.room")
                            put("m.terminated", "m.user_hangup")
                        })
                    }
                    println("DEBUG [MatrixCallsRepository] endGroupCall terminated ns=$ns status=${response.status.value}")
                    response.drain()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WARN [MatrixCallsRepository] endGroupCall failed to terminate $roomId: ${e.message}")
            }
        } else {
            println(
                "DEBUG [MatrixCallsRepository] endGroupCall: ${otherActiveMembers.size} other active member(s) remain in $roomId, leaving call definition intact"
            )
        }

        stopCallRosterPolling(roomId)
        leaveGroupCall(roomId, callId)
    }

    override suspend fun joinGroupCall(roomId: String, callId: String) {
        println("DEBUG [MatrixCallsRepository] JOINING MSC3401 Call for $roomId (callId=$callId)")

        publishGroupCallMembership(roomId, callId, audio = true, video = false)
    }

    override suspend fun updateGroupCallMembershipCapabilities(roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String?): Boolean {
        println("DEBUG [MatrixCallsRepository] publishing MatrixRTC membership audio=$audio video=$video")
        latestMembershipContextByRoom[roomId] = MembershipContext(callId, audio, video, livekitIdentity)
        return publishGroupCallMembership(roomId, callId, audio = audio, video = video, livekitIdentity = livekitIdentity)
    }

    /**
     * Key-exchange recovery nudge: clear our membership state event (peers run
     * their leaver path -> rotate + redistribute) and republish it (peers run
     * their joiner path -> resend current key). The LiveKit media session is
     * untouched; only the Matrix room state cycles.
     */
    private suspend fun nudgeOwnMembership(roomId: String) {
        val ctx = latestMembershipContextByRoom[roomId]
        val callId = ctx?.callId ?: "m.call"
        try {
            publishGroupCallMembership(
                roomId,
                callId,
                audio = ctx?.audio ?: true,
                video = ctx?.video ?: false,
                livekitIdentity = ctx?.livekitIdentity
            )
            println("DEBUG [MatrixCallsRepository] nudge refresh published room=$roomId callId=$callId")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("WARN [MatrixCallsRepository] nudge refresh failed: ${e.message}")
        }
    }

    /** Returns true when our own m.call.member event is visible in room state after publish. */
    private suspend fun publishGroupCallMembership(roomId: String, callId: String, audio: Boolean, video: Boolean, livekitIdentity: String? = null): Boolean {
        val userId = context.userId ?: return false
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        try {
            val membershipId = livekitIdentity ?: "$userId:$deviceId"
            // A failed state fetch leaves beforeEvents empty, which would make
            // findReferenceMemberEvent pick nothing and publish a shape that
            // matches no peer. Refuse the publish instead.
            val beforeEvents = fetchMatrixRtcVisibilityEvents(roomId)
            if (beforeEvents == null) {
                println("WARN [MatrixRTCVisibilityAudit] before_publish state fetch failed; not publishing membership")
                return false
            }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

            var allPublished = true
            for (eventType in publishMode.eventTypes) {
                val response = context.httpClient.put(context.fullUrl("/rooms/$roomId/state/$eventType/${stateKey.encodeStateKey()}")) {
                    header(HttpHeaders.Authorization, "Bearer ${context.accessToken}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                if (response.status.value !in 200..299) {
                    allPublished = false
                }
                println("DEBUG [MatrixCallsRepository] Published $eventType status=${response.status.value}")
                println(
                    "DEBUG [MatrixRTCMapping] $eventType publish status=${response.status.value} state_key=$stateKey membershipID=$membershipId"
                )
                response.drain()
            }

            val afterEvents = fetchMatrixRtcVisibilityEvents(roomId).orEmpty()
            logMatrixRtcVisibilityEvents("after_publish", afterEvents)
            logElementReferenceDump("after_publish", afterEvents, userId)
            val orbitMember = findOwnMemberEvent(afterEvents, deviceId, membershipId, stateKey)
            println(
                "DEBUG [MatrixRTCVisibilityAudit] orbit member visible=${orbitMember != null} selected_state_key=$stateKey selected_mode=${publishMode.label}"
            )
            logMembershipComparison(reference, orbitMember)
            return allPublished || orbitMember != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("ERROR [MatrixCallsRepository] Failed to publish member join: ${e.message}")
            return false
        }
    }

    override suspend fun leaveGroupCall(roomId: String, callId: String) {
        println("DEBUG [MatrixCallsRepository] LEAVING MSC3401 Call for $roomId")
        stopCallRosterPolling(roomId)
        val userId = context.userId ?: return
        val deviceId = context.deviceId ?: "ORBIT_DESKTOP"
        val shape = matrixRtcMembershipShapes.remove(membershipShapeMapKey(roomId, callId))
        val stateKey = shape?.stateKey ?: "_${userId}_${deviceId}_$callId"
        val eventTypes = shape?.publishMode?.eventTypes ?: matrixRtcMemberEventTypes

        // Every leave is counted. A silent catch here left our m.call.member in
        // room state until its TTL expired, so peers kept showing us in the call
        // and endGroupCall saw a phantom member.
        val targets = eventTypes.map { it to stateKey } + matrixRtcMemberEventTypes.map { it to deviceId }
        var succeeded = 0
        for (attempt in 1..2) {
            succeeded = targets.count { (ns, key) ->
                val ok = try {
                    clearMatrixRtcMemberEvent(roomId, ns, key) in 200..299
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("WARN [MatrixRTCVisibilityAudit] leave PUT threw type=$ns state_key=$key: ${e.message}")
                    false
                }
                if (ok) {
                    println("DEBUG [MatrixRTCVisibilityAudit] leave published type=$ns state_key=$key")
                    println("DEBUG [VideoPublish] membership cleared type=$ns state_key=$key")
                }
                ok
            }
            if (succeeded == targets.size) break
            if (attempt == 1) println("WARN [MatrixRTCVisibilityAudit] ${targets.size - succeeded} leave PUT(s) failed; retrying once")
        }

        if (succeeded == targets.size) {
            context.activeGroupCalls[roomId]?.value = null
        } else {
            println(
                "ERROR [MatrixCallsRepository] leaveGroupCall could NOT clear membership for " +
                    "$roomId (${targets.size - succeeded}/${targets.size} failed); our m.call.member " +
                    "remains in room state until its TTL expires"
            )
        }
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
