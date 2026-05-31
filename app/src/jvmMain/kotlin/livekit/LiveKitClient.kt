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
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object LiveKitClient {
    private val initialized = AtomicBoolean(false)
    private val nextAsyncId = AtomicLong(1)
    private val pendingConnects = ConcurrentHashMap<Long, CancellableContinuation<livekit.proto.Room.OwnedRoom>>()
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
                    // Ignore parsing errors here
                } finally {
                    // FORCE DETACH: Native threads on Windows must be detached to keep JVM stable
                    com.sun.jna.Native.detach(true)
                }
            }
        }
    }

    private val PINNED_CALLBACK = StableFfiCallback(eventQueue)

    fun initialize() {
        if (initialized.getAndSet(true)) return

        println("DEBUG [LiveKitClient] Initializing FFI bridge...")
        webrtc.WebrtcNativeDepsLoader.load()

        // Start the event processor thread
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

        // Call C-API Initialize
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
    private val activeLocalTrackHandles = ConcurrentHashMap.newKeySet<Long>()

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
                println("DEBUG [LiveKitClient] [$now] Handling ConnectCallback for asyncId=${connectCb.asyncId}")
                val continuation = pendingConnects.remove(connectCb.asyncId)
                if (continuation != null) {
                    if (connectCb.hasError()) {
                        println("DEBUG [LiveKitClient] [$now] Connect failed: ${connectCb.error}")
                        continuation.resumeWithException(Exception("Connect failed: ${connectCb.error}"))
                    } else if (connectCb.hasResult()) {
                        val result = connectCb.result
                        val roomHandleId = result.room.handle.id
                        val localParticipantHandleId = result.localParticipant.handle.id
                        println("DEBUG [LiveKitClient] [$now] Connect SUCCESS! roomHandle=$roomHandleId")
                        
                        activeRoomHandle = roomHandleId
                        activeLocalParticipantHandle = localParticipantHandleId

                        val initialParticipants = mutableMapOf<String, LkParticipant>()
                        result.participantsList.forEach { p ->
                            val info = p.participant.info
                            initialParticipants[info.sid] = LkParticipant(
                                sid = info.sid,
                                identity = info.identity,
                                name = info.name
                            )
                        }
                        _participants.value = initialParticipants
                        
                        val readyReq = livekit.proto.Room.ReadyForRoomEventRequest.newBuilder()
                            .setRoomHandle(roomHandleId)
                            .build()
                        
                        val ffiReq = FfiRequest.newBuilder()
                            .setReadyForRoomEvent(readyReq)
                            .build()
                        
                        request(ffiReq)
                        continuation.resume(result.room)
                    }
                }
            }
            FfiEvent.MessageCase.VIDEO_STREAM_EVENT -> {
                val streamEvent = event.videoStreamEvent
                when (streamEvent.messageCase) {
                    livekit.proto.VideoFrame.VideoStreamEvent.MessageCase.FRAME_RECEIVED -> {
                        val received = streamEvent.frameReceived
                        val buffer = received.buffer
                        val info = buffer.info
                        val components = info.componentsList
                        
                        if (components.size >= 3) {
                            val y = components[0]
                            val u = components[1]
                            val v = components[2]
                            
                            LiveKitVideoSink.pushFrame(
                                info.width, info.height,
                                y.dataPtr, y.stride, y.size,
                                u.dataPtr, u.stride, u.size,
                                v.dataPtr, v.stride, v.size
                            )
                        }
                    }
                    else -> {}
                }
            }
            FfiEvent.MessageCase.ROOM_EVENT -> {
                val roomEvent = event.roomEvent
                if (!isFrequent) {
                    println("DEBUG [LiveKitClient] [$now] RoomEvent: ${roomEvent.messageCase} for room=${roomEvent.roomHandle}")
                }
                
                when (roomEvent.messageCase) {
                    livekit.proto.Room.RoomEvent.MessageCase.PARTICIPANT_CONNECTED -> {
                        val p = roomEvent.participantConnected.info
                        val info = p.info
                        val sid = info.sid
                        println("DEBUG [LiveKitClient] [$now] Participant connected: ${info.identity} ($sid)")
                        val current = _participants.value.toMutableMap()
                        current[sid] = LkParticipant(
                            sid = sid, identity = info.identity, name = info.name
                        )
                        _participants.value = current
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.PARTICIPANT_DISCONNECTED -> {
                        val identity = roomEvent.participantDisconnected.participantIdentity
                        println("DEBUG [LiveKitClient] [$now] Participant disconnected: $identity")
                        val sid = _participants.value.values.find { it.identity == identity }?.sid ?: identity
                        val current = _participants.value.toMutableMap()
                        current.remove(sid)
                        _participants.value = current
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.ACTIVE_SPEAKERS_CHANGED -> {
                        val identities = roomEvent.activeSpeakersChanged.participantIdentitiesList
                        val sids = _participants.value.values.filter { it.identity in identities }.map { it.sid }.toSet()
                        _activeSpeakers.value = sids
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.TRACK_SUBSCRIBED -> {
                        val track = roomEvent.trackSubscribed.track.info
                        val trackSid = track.sid
                        val trackHandle = roomEvent.trackSubscribed.track.handle.id
                        println("DEBUG [LiveKitClient] [$now] Track subscribed: $trackSid (kind=${track.kind}, handle=$trackHandle)")
                        
                        if (track.kind == livekit.proto.Track.TrackKind.KIND_VIDEO) {
                            val streamReq = livekit.proto.VideoFrame.NewVideoStreamRequest.newBuilder()
                                .setTrackHandle(trackHandle)
                                .setType(livekit.proto.VideoFrame.VideoStreamType.VIDEO_STREAM_NATIVE)
                                .setFormat(livekit.proto.VideoFrame.VideoBufferType.I420)
                                .setNormalizeStride(true)
                                .build()
                            val ffiReq = FfiRequest.newBuilder().setNewVideoStream(streamReq).build()
                            val res = request(ffiReq)
                            if (res?.hasNewVideoStream() == true) {
                                val streamHandleId = res.newVideoStream.stream.handle.id
                                activeVideoStreamHandles.add(streamHandleId)
                            }
                        } else if (track.kind == livekit.proto.Track.TrackKind.KIND_AUDIO) {
                            val streamReq = livekit.proto.AudioFrame.NewAudioStreamRequest.newBuilder()
                                .setTrackHandle(trackHandle)
                                .setType(livekit.proto.AudioFrame.AudioStreamType.AUDIO_STREAM_NATIVE)
                                .build()
                            val ffiReq = FfiRequest.newBuilder().setNewAudioStream(streamReq).build()
                            val res = request(ffiReq)
                            if (res?.hasNewAudioStream() == true) {
                                val streamHandleId = res.newAudioStream.stream.handle.id
                                activeAudioStreamHandles.add(streamHandleId)
                            }
                        }
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.TRACK_PUBLISHED -> {
                        println("DEBUG [LiveKitClient] [$now] Track published: ${roomEvent.trackPublished.publication.info.sid}")
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.LOCAL_TRACK_PUBLISHED -> {
                        println("DEBUG [LiveKitClient] [$now] Local track published: ${roomEvent.localTrackPublished.trackSid}")
                    }
                    livekit.proto.Room.RoomEvent.MessageCase.DISCONNECTED -> {
                        println("DEBUG [LiveKitClient] [$now] Room disconnected: ${roomEvent.disconnected.reason}")
                        activeVideoStreamHandles.clear()
                        activeAudioStreamHandles.clear()
                        activeLocalTrackHandles.clear()
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

    suspend fun connect(url: String, token: String): livekit.proto.Room.OwnedRoom {
        initialize()
        val asyncId = nextAsyncId.getAndIncrement()
        
        val connectReq = livekit.proto.Room.ConnectRequest.newBuilder()
            .setUrl(url)
            .setToken(token)
            .setOptions(livekit.proto.Room.RoomOptions.newBuilder()
                .setAutoSubscribe(true)
                .build())
            .setRequestAsyncId(asyncId)
            .build()

        val req = FfiRequest.newBuilder()
            .setConnect(connectReq)
            .build()

        println("DEBUG [LiveKitClient] Sending ConnectRequest url=$url asyncId=$asyncId")
        return suspendCancellableCoroutine { cont ->
            pendingConnects[asyncId] = cont
            cont.invokeOnCancellation { pendingConnects.remove(asyncId) }
            request(req)
        }
    }

    fun request(req: FfiRequest): FfiResponse? {
        val reqData = req.toByteArray()
        val mem = Memory(reqData.size.toLong())
        mem.write(0, reqData, 0, reqData.size)

        val resPtr = PointerByReference()
        val resLen = LongByReference()

        val handleId = LiveKitFfiLibrary.INSTANCE.livekit_ffi_request(mem, reqData.size.toLong(), resPtr, resLen)

        val responsePtr = resPtr.value
        var ffiResponse: FfiResponse? = null
        if (responsePtr != null && resLen.value > 0) {
            try {
                val responseData = responsePtr.getByteArray(0, resLen.value.toInt())
                ffiResponse = FfiResponse.parser().parsePartialFrom(responseData)
            } catch (t: Throwable) {
                // Parse error
            }
        }

        if (handleId > 0L) {
            LiveKitFfiLibrary.INSTANCE.livekit_ffi_drop_handle(handleId)
        }

        return ffiResponse
    }

    fun publishVideoTrack(sourceHandle: Long) {
        val localParticipantHandle = activeLocalParticipantHandle ?: return
        println("DEBUG [LiveKitClient] Publishing video track for source=$sourceHandle")
        
        val publishReq = livekit.proto.Room.PublishTrackRequest.newBuilder()
            .setLocalParticipantHandle(localParticipantHandle)
            .setTrackHandle(sourceHandle)
            .setOptions(livekit.proto.Room.TrackPublishOptions.newBuilder()
                .setVideoCodec(livekit.proto.VideoFrame.VideoCodec.H264)
                .setSource(livekit.proto.Track.TrackSource.SOURCE_CAMERA)
                .build())
            .build()
            
        val request = FfiRequest.newBuilder()
            .setPublishTrack(publishReq)
            .build()
            
        val res = request(request)
        if (res?.hasPublishTrack() == true) {
            activeLocalTrackHandles.add(res.publishTrack.publication.handle.id)
        }
    }

    fun publishAudioTrack(sourceHandle: Long) {
        val localParticipantHandle = activeLocalParticipantHandle ?: return
        println("DEBUG [LiveKitClient] Publishing audio track for source=$sourceHandle")
        
        val publishReq = livekit.proto.Room.PublishTrackRequest.newBuilder()
            .setLocalParticipantHandle(localParticipantHandle)
            .setTrackHandle(sourceHandle)
            .setOptions(livekit.proto.Room.TrackPublishOptions.newBuilder()
                .setSource(livekit.proto.Track.TrackSource.SOURCE_MICROPHONE)
                .build())
            .build()
            
        val request = FfiRequest.newBuilder()
            .setPublishTrack(publishReq)
            .build()
            
        val res = request(request)
        if (res?.hasPublishTrack() == true) {
            activeLocalTrackHandles.add(res.publishTrack.publication.handle.id)
        }
    }
}
