package livekit

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import livekit.proto.Ffi
import livekit.proto.Ffi.FfiRequest
import livekit.proto.Ffi.FfiResponse
import livekit.proto.Ffi.FfiEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object LiveKitClient {
    private val initialized = AtomicBoolean(false)
    private val nextAsyncId = AtomicLong(1)
    private val pendingConnects = ConcurrentHashMap<Long, CancellableContinuation<livekit.proto.Room.OwnedRoom>>()
    private val pendingPublishes = ConcurrentHashMap<Long, CancellableContinuation<livekit.proto.Track.OwnedTrackPublication>>()
    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val eventQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>(200)
    private var eventProcessorJob: Job? = null

    private class StableFfiCallback(val queue: java.util.concurrent.BlockingQueue<ByteArray>) : LiveKitFfiLibrary.FfiCallbackFn {
        override fun invoke(dataPtr: Pointer?, len: Long) {
            if (dataPtr != null && len > 0) {
                try {
                    val eventData = dataPtr.getByteArray(0, len.toInt())
                    queue.offer(eventData)
                } catch (t: Throwable) {
                    println("DEBUG [LiveKitClient] Callback error: ${t.message}")
                }
            }
        }
    }

    private val PINNED_CALLBACK = StableFfiCallback(eventQueue)

    fun initialize() {
        if (initialized.getAndSet(true)) return

        println("DEBUG [LiveKitClient] Initializing FFI bridge...")
        webrtc.WebrtcNativeDepsLoader.load()

        com.sun.jna.Native.setCallbackThreadInitializer(PINNED_CALLBACK, com.sun.jna.CallbackThreadInitializer(true, false))

        eventProcessorJob = eventScope.launch(Dispatchers.IO) {
            println("DEBUG [LiveKitClient] Event processor thread started")
            while (isActive) {
                try {
                    val eventData = eventQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val ffiEvent = FfiEvent.parser().parsePartialFrom(eventData)
                    handleFfiEvent(ffiEvent)
                } catch (t: Throwable) {
                    if (t !is CancellationException) {
                        println("DEBUG [LiveKitClient] ERROR in event processor: ${t.message}")
                    }
                }
            }
        }

        LiveKitFfiLibrary.INSTANCE.livekit_ffi_initialize(
            PINNED_CALLBACK,
            false,
            "orbit-kotlin",
            "1.0.0"
        )
        println("DEBUG [LiveKitClient] FFI bridge initialized")
    }

    var activeRoomHandle: Long? = null
    var activeLocalParticipantHandle: Long? = null

    private val activeVideoStreamHandles = ConcurrentHashMap.newKeySet<Long>()
    private val activeAudioStreamHandles = ConcurrentHashMap.newKeySet<Long>()
    private val activeTrackPublicationHandles = ConcurrentHashMap.newKeySet<Long>()

    private val _participants = MutableStateFlow<Map<String, LkParticipant>>(emptyMap())
    val participants: StateFlow<Map<String, LkParticipant>> = _participants

    private val _activeSpeakers = MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: StateFlow<Set<String>> = _activeSpeakers

    data class LkParticipant(
        val sid: String,
        val identity: String,
        val name: String,
        val isSpeaking: Boolean = false
    )

    private fun handleFfiEvent(event: FfiEvent) {
        val now = System.currentTimeMillis()

        val isFrequent = event.messageCase == FfiEvent.MessageCase.LOGS ||
                        event.messageCase == FfiEvent.MessageCase.CAPTURE_AUDIO_FRAME ||
                        event.messageCase == FfiEvent.MessageCase.AUDIO_STREAM_EVENT

        if (!isFrequent) {
            println("DEBUG [LiveKitClient] [$now] Received FfiEvent: ${event.messageCase} (id=${event.messageCase.number})")
        }

        when (event.messageCase) {
            FfiEvent.MessageCase.CONNECT -> {
                val connectCb = event.connect
                val continuation = pendingConnects.remove(connectCb.asyncId)
                if (continuation != null) {
                    if (connectCb.hasError()) {
                        println("DEBUG [LiveKitClient] [$now] Connect failed: ${connectCb.error}")
                        continuation.resumeWithException(Exception("Connect failed: ${connectCb.error}"))
                    } else if (connectCb.hasResult()) {
                        val result = connectCb.result
                        activeRoomHandle = result.room.handle.id
                        activeLocalParticipantHandle = result.localParticipant.handle.id

                        println("DEBUG [LiveKitClient] [$now] Connect SUCCESS! roomHandle=$activeRoomHandle")

                        val initialParticipants = mutableMapOf<String, LkParticipant>()
                        result.participantsList.forEach { p ->
                            val info = p.participant.info
                            initialParticipants[info.sid] = LkParticipant(
                                sid = info.sid, identity = info.identity, name = info.name
                            )
                        }
                        _participants.value = initialParticipants

                        val readyReq = livekit.proto.Room.ReadyForRoomEventRequest.newBuilder()
                            .setRoomHandle(activeRoomHandle!!)
                            .build()
                        request(FfiRequest.newBuilder().setReadyForRoomEvent(readyReq).build())

                        continuation.resume(result.room)
                    }
                }
            }
            FfiEvent.MessageCase.VIDEO_STREAM_EVENT -> {
                val streamEvent = event.videoStreamEvent
                if (streamEvent.messageCase == livekit.proto.VideoFrame.VideoStreamEvent.MessageCase.FRAME_RECEIVED) {
                    val received = streamEvent.frameReceived
                    val info = received.buffer.info
                    val components = info.componentsList
                    if (components.size >= 3) {
                        LiveKitVideoSink.pushFrame(
                            info.width, info.height,
                            components[0].dataPtr, components[0].stride, components[0].size,
                            components[1].dataPtr, components[1].stride, components[1].size,
                            components[2].dataPtr, components[2].stride, components[2].size
                        )
                    }
                }
            }
            FfiEvent.MessageCase.AUDIO_STREAM_EVENT -> {
                val streamEvent = event.audioStreamEvent
                if (streamEvent.messageCase == livekit.proto.AudioFrame.AudioStreamEvent.MessageCase.FRAME_RECEIVED) {
                    val received = streamEvent.frameReceived
                    val info = received.frame.info
                    val dataPtr = info.dataPtr
                    val sampleRate = info.sampleRate
                    val numChannels = info.numChannels
                    val samplesPerChannel = info.samplesPerChannel

                    val streamId = streamEvent.streamHandle.toString()
                    LiveKitAudioSink.pushFrame(
                        streamId,
                        sampleRate,
                        numChannels,
                        samplesPerChannel,
                        dataPtr
                    )
                }
            }
            // *** PUBLISH_TRACK must be at the outer FfiEvent level - NOT inside RoomEvent ***
            FfiEvent.MessageCase.PUBLISH_TRACK -> {
                val cb = event.publishTrack
                val continuation = pendingPublishes.remove(cb.asyncId)
                if (continuation != null) {
                    if (cb.error.isNotEmpty()) {
                        println("ERROR [LiveKitClient] PublishTrackCallback failed: ${cb.error}")
                        continuation.resumeWithException(Exception("Publish failed: ${cb.error}"))
                    } else {
                        println("DEBUG [LiveKitClient] PublishTrackCallback SUCCESS! Publication SID: ${cb.publication.info.sid}")
                        continuation.resume(cb.publication)
                    }
                } else {
                    if (cb.error.isNotEmpty()) {
                        println("ERROR [LiveKitClient] PublishTrackCallback failed (no continuation): ${cb.error}")
                    } else {
                        println("DEBUG [LiveKitClient] PublishTrackCallback SUCCESS (no continuation)! SID: ${cb.publication.info.sid}")
                    }
                }
            }
            FfiEvent.MessageCase.ROOM_EVENT -> {
                val roomEvent = event.roomEvent
                if (!isFrequent) {
                    println("DEBUG [LiveKitClient] [$now] RoomEvent: ${roomEvent.messageCase} (id=${roomEvent.messageCase.number})")
                }
                when (roomEvent.messageCase) {
                    livekit.proto.Room.RoomEvent.MessageCase.PARTICIPANT_CONNECTED -> {
                        val info = roomEvent.participantConnected.info.info
                        println("DEBUG [LiveKitClient] [$now] Participant connected: ${info.identity}")
                        val current = _participants.value.toMutableMap()
                        current[info.sid] = LkParticipant(sid = info.sid, identity = info.identity, name = info.name)
                        _participants.value = current
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.PARTICIPANT_DISCONNECTED -> {
                        val identity = roomEvent.participantDisconnected.participantIdentity
                        println("DEBUG [LiveKitClient] [$now] Participant disconnected: $identity")
                        val current = _participants.value.toMutableMap()
                        current.values.find { it.identity == identity }?.let { current.remove(it.sid) }
                        _participants.value = current
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.ACTIVE_SPEAKERS_CHANGED -> {
                        val identities = roomEvent.activeSpeakersChanged.participantIdentitiesList
                        _activeSpeakers.value = _participants.value.values
                            .filter { it.identity in identities }
                            .map { it.sid }
                            .toSet()
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.TRACK_SUBSCRIBED -> {
                        val track = roomEvent.trackSubscribed.track
                        val trackHandle = track.handle.id
                        println("DEBUG [LiveKitClient] [$now] Track subscribed: ${track.info.sid} (kind=${track.info.kind})")
                        if (track.info.kind == livekit.proto.Track.TrackKind.KIND_VIDEO) {
                            val streamReq = livekit.proto.VideoFrame.NewVideoStreamRequest.newBuilder()
                                .setTrackHandle(trackHandle)
                                .setType(livekit.proto.VideoFrame.VideoStreamType.VIDEO_STREAM_NATIVE)
                                .setFormat(livekit.proto.VideoFrame.VideoBufferType.I420)
                                .setNormalizeStride(true)
                                .build()
                            val res = request(FfiRequest.newBuilder().setNewVideoStream(streamReq).build())
                            if (res?.hasNewVideoStream() == true) {
                                val streamHandle = res.newVideoStream.stream.handle.id
                                activeVideoStreamHandles.add(streamHandle)
                                println("DEBUG [LiveKitClient] [$now] Video stream created: $streamHandle")
                            }
                        } else if (track.info.kind == livekit.proto.Track.TrackKind.KIND_AUDIO) {
                            val streamReq = livekit.proto.AudioFrame.NewAudioStreamRequest.newBuilder()
                                .setTrackHandle(trackHandle)
                                .setType(livekit.proto.AudioFrame.AudioStreamType.AUDIO_STREAM_NATIVE)
                                .build()
                            val res = request(FfiRequest.newBuilder().setNewAudioStream(streamReq).build())
                            if (res?.hasNewAudioStream() == true) {
                                val streamHandle = res.newAudioStream.stream.handle.id
                                activeAudioStreamHandles.add(streamHandle)
                                println("DEBUG [LiveKitClient] [$now] Audio stream created: $streamHandle")
                            }
                        }
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.TRACK_PUBLISHED -> {
                        val pubHandle = roomEvent.trackPublished.publication.handle.id
                        activeTrackPublicationHandles.add(pubHandle)
                        println("DEBUG [LiveKitClient] [$now] Track published handle: $pubHandle")
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.DISCONNECTED -> {
                        println("DEBUG [LiveKitClient] [$now] Room disconnected: ${roomEvent.disconnected.reason}")
                        activeVideoStreamHandles.clear()
                        activeAudioStreamHandles.clear()
                        activeTrackPublicationHandles.clear()
                        activeRoomHandle = null
                        activeLocalParticipantHandle = null
                        _participants.value = emptyMap()
                    }
                    else -> {}
                }
            }
            else -> {}
        }
    }

    suspend fun connect(url: String, token: String, iceServers: List<repository.TurnServer> = emptyList()): livekit.proto.Room.OwnedRoom {
        initialize()
        val asyncId = nextAsyncId.getAndIncrement()

        val rtcConfigBuilder = livekit.proto.Room.RtcConfig.newBuilder()
        iceServers.forEach { server ->
            server.uris.forEach { uri ->
                val iceBuilder = livekit.proto.Room.IceServer.newBuilder().addUrls(uri)
                server.username?.let { iceBuilder.setUsername(it) }
                server.password?.let { iceBuilder.setPassword(it) }
                rtcConfigBuilder.addIceServers(iceBuilder.build())
            }
        }

        val roomOptions = livekit.proto.Room.RoomOptions.newBuilder()
            .setAutoSubscribe(true)
            .setRtcConfig(rtcConfigBuilder.build())
            .build()

        val connectReq = livekit.proto.Room.ConnectRequest.newBuilder()
            .setUrl(url).setToken(token)
            .setOptions(roomOptions)
            .setRequestAsyncId(asyncId).build()

        println("DEBUG [LiveKitClient] Sending ConnectRequest url=$url asyncId=$asyncId")
        return suspendCancellableCoroutine { cont: CancellableContinuation<livekit.proto.Room.OwnedRoom> ->
            pendingConnects[asyncId] = cont
            cont.invokeOnCancellation { pendingConnects.remove(asyncId) }
            request(FfiRequest.newBuilder().setConnect(connectReq).build())
        }
    }

    fun request(req: FfiRequest): FfiResponse? {
        val reqData = req.toByteArray()
        val mem = Memory(reqData.size.toLong())
        mem.write(0, reqData, 0, reqData.size)
        val resPtr = PointerByReference()
        val resLen = LongByReference()
        val handleId = LiveKitFfiLibrary.INSTANCE.livekit_ffi_request(mem, reqData.size.toLong(), resPtr, resLen)
        var ffiResponse: FfiResponse? = null
        if (resPtr.value != null && resLen.value > 0) {
            try {
                ffiResponse = FfiResponse.parser().parsePartialFrom(resPtr.value.getByteArray(0, resLen.value.toInt()))
            } catch (t: Throwable) {}
        }
        if (handleId > 0L) LiveKitFfiLibrary.INSTANCE.livekit_ffi_drop_handle(handleId)
        return ffiResponse
    }

    suspend fun publishVideoTrack(sourceHandle: Long): livekit.proto.Track.OwnedTrackPublication? {
        val localParticipantHandle = activeLocalParticipantHandle ?: run {
            println("ERROR [LiveKitClient] No active local participant to publish video track")
            return null
        }

        val trackReq = livekit.proto.Track.CreateVideoTrackRequest.newBuilder()
            .setName("camera")
            .setSourceHandle(sourceHandle)
            .build()
        val trackRes = request(FfiRequest.newBuilder().setCreateVideoTrack(trackReq).build())
        val trackHandle = trackRes?.createVideoTrack?.track?.handle?.id ?: run {
            println("ERROR [LiveKitClient] Failed to create video track from source")
            return null
        }

        println("DEBUG [LiveKitClient] Publishing video track: $trackHandle (LocalParticipant: $localParticipantHandle)")
        return suspendCancellableCoroutine { cont ->
            try {
                val publishReq = livekit.proto.Room.PublishTrackRequest.newBuilder()
                    .setLocalParticipantHandle(localParticipantHandle)
                    .setTrackHandle(trackHandle)
                    .setOptions(livekit.proto.Room.TrackPublishOptions.newBuilder()
                        .setSource(livekit.proto.Track.TrackSource.SOURCE_CAMERA)
                        .build())
                    .build()
                val res = request(FfiRequest.newBuilder().setPublishTrack(publishReq).build())
                val asyncId = res?.publishTrack?.asyncId ?: return@suspendCancellableCoroutine
                println("DEBUG [LiveKitClient] PublishVideoTrack response: hasRes=${res.hasPublishTrack()} asyncId=$asyncId")
                pendingPublishes[asyncId] = cont
                cont.invokeOnCancellation { pendingPublishes.remove(asyncId) }
            } catch (e: Exception) {
                println("ERROR [LiveKitClient] Exception in publishVideoTrack: ${e.message}")
                e.printStackTrace()
                cont.resumeWithException(e)
            }
        }
    }

    suspend fun publishAudioTrack(sourceHandle: Long): livekit.proto.Track.OwnedTrackPublication? {
        val localParticipantHandle = activeLocalParticipantHandle ?: run {
            println("ERROR [LiveKitClient] No active local participant to publish audio track")
            return null
        }

        val trackReq = livekit.proto.Track.CreateAudioTrackRequest.newBuilder()
            .setName("microphone")
            .setSourceHandle(sourceHandle)
            .build()
        val trackRes = request(FfiRequest.newBuilder().setCreateAudioTrack(trackReq).build())
        val trackHandle = trackRes?.createAudioTrack?.track?.handle?.id ?: run {
            println("ERROR [LiveKitClient] Failed to create audio track from source")
            return null
        }

        println("DEBUG [LiveKitClient] Publishing audio track: $trackHandle (LocalParticipant: $localParticipantHandle)")
        return suspendCancellableCoroutine { cont ->
            try {
                val publishReq = livekit.proto.Room.PublishTrackRequest.newBuilder()
                    .setLocalParticipantHandle(localParticipantHandle)
                    .setTrackHandle(trackHandle)
                    .setOptions(livekit.proto.Room.TrackPublishOptions.newBuilder()
                        .setSource(livekit.proto.Track.TrackSource.SOURCE_MICROPHONE)
                        .build())
                    .build()
                val res = request(FfiRequest.newBuilder().setPublishTrack(publishReq).build())
                val asyncId = res?.publishTrack?.asyncId ?: return@suspendCancellableCoroutine
                println("DEBUG [LiveKitClient] PublishAudioTrack response: hasRes=${res.hasPublishTrack()} asyncId=$asyncId")
                pendingPublishes[asyncId] = cont
                cont.invokeOnCancellation { pendingPublishes.remove(asyncId) }
            } catch (e: Exception) {
                println("ERROR [LiveKitClient] Exception in publishAudioTrack: ${e.message}")
                e.printStackTrace()
                cont.resumeWithException(e)
            }
        }
    }
}
