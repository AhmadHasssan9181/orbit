package webrtc

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceConnectionState
import dev.onvoid.webrtc.RTCIceGatheringState
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCRtpReceiver
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.RTCRtpTransceiver
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCSignalingState
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.MediaStream
import dev.onvoid.webrtc.media.MediaStreamTrack
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioOptions
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer
import dev.onvoid.webrtc.media.video.VideoDesktopSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import repository.TurnServer
import repository.sdpMediaSections
import repository.sdpScreenShareStreamIds
import kotlin.math.abs

/**
 * How long a desktop-source enumeration stays valid.
 *
 * Long enough that the share picker and the share that follows it share one
 * enumeration -- they are seconds apart at most, and those two were the most
 * expensive moments on the share-start path. Short enough that a window opened a
 * couple of minutes later still shows up in the picker.
 */
private const val DESKTOP_SOURCE_CACHE_TTL_MS = 120_000L

/**
 * Resolves a chosen source id to the (id, isWindow) the capture source needs,
 * from a previously enumerated list instead of enumerating again.
 *
 * Returns null for every case the caller must fall back to a fresh enumeration
 * for -- a cold cache, no id, an unknown id, an id that is not a number. That is
 * deliberate and is the important part of the contract: **this function never
 * guesses.** Picking the wrong source would share somebody's window to the whole
 * call, so "I do not know" has to be a real, reachable answer, and the caller's
 * fallback is a correct enumeration rather than nothing.
 */
internal fun resolveDesktopSource(
    sources: List<DesktopSourceModel>?,
    sourceId: String?
): Pair<Long, Boolean>? {
    val list = sources ?: return null
    val wanted = sourceId?.trim().orEmpty()
    if (wanted.isEmpty()) return null
    val match = list.firstOrNull { it.id == wanted } ?: return null
    val id = match.id.toLongOrNull() ?: return null
    return id to match.isWindow
}

class JvmWebRtcClient : WebRtcClient {
    private val _onIceCandidate = MutableSharedFlow<IceCandidateModel>(extraBufferCapacity = 10)
    override val onIceCandidate: SharedFlow<IceCandidateModel> = _onIceCandidate

    private val _onRemoteDescription = MutableSharedFlow<SessionDescriptionModel>(extraBufferCapacity = 10)
    override val onRemoteDescription: SharedFlow<SessionDescriptionModel> = _onRemoteDescription

    private val _onStreamAdded = MutableSharedFlow<Boolean>(extraBufferCapacity = 10)
    override val onStreamAdded: SharedFlow<Boolean> = _onStreamAdded

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: OrbitAudioDeviceModule? = null
    private var peerConnection: RTCPeerConnection? = null

    private var localAudioSource: dev.onvoid.webrtc.media.audio.AudioTrackSource? = null
    private var localAudioTrack: AudioTrack? = null
    
    private var attenuationStrength = 0.0f
    private var localAudioSender: RTCRtpSender? = null
    
    private var cameraVideoTrack: VideoTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSender: RTCRtpSender? = null
    override var isVirtualBackgroundEnabled: Boolean = false
    private var localVideoSource: VideoDeviceSource? = null
    private var isVideoCaptureRunning = false

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteScreenVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var screenSource: VideoDesktopSource? = null
    private var screenVideoTrack: VideoTrack? = null
    private var shareWindowTrackingJob: Job? = null
    override var isGroupCall: Boolean = false

    private var selectedAudioInput: String = "Default"
    private val _isScreenSharing = MutableStateFlow(false)
    override val isScreenSharing: StateFlow<Boolean> = _isScreenSharing

    private val _isRemoteScreenSharing = MutableStateFlow(false)

    /**
     * Track ids the negotiated SDP attributed to a screen share.
     *
     * Empty until a remote description arrives, which is the correct answer for a
     * call where nobody is sharing.
     */
    private val _remoteScreenTrackIds = MutableStateFlow<Set<String>>(emptySet())

    private fun remoteScreenTrackIds(): Set<String> = _remoteScreenTrackIds.value

    /**
     * Read a remote SDP and remember which of its video streams are shares.
     *
     * Deliberately lenient about finding the share, and strict about not
     * inventing one. A miss means the share renders as a camera tile, which is
     * wrong but visible and recoverable. A false positive turns somebody's face
     * into a letterboxed panel on top of the call, which is worse and looks like
     * a bug in our layout rather than in our signalling.
     */
    private fun noteRemoteScreenShareTracks(sdp: String) {
        if (sdp.isBlank()) return

        // The video sections, in full, the first time a remote description lands.
        //
        // Two runs have now ended at `fromSdp=false` with the answer still unknown,
        // and the marker spelling is not something that can be guessed: run 45 used
        // a UUID track with no hint at all. The sections are where the truth is --
        // the msid lines, the attributes, the m-line order -- and none of it is
        // visible from the outside.
        //
        // Session level only, and only for the first remote description of a call.
        // Renegotiation repeats this, and a full SDP dump on every one of them
        // would bury the lines that matter.
        if (!_haveLoggedRemoteVideoSections && !_remoteScreenTrackIds.value.isNotEmpty()) {
            val videoSections = sdpMediaSections(sdp)
                .filter { it.firstOrNull()?.startsWith("m=video ") == true }
            if (videoSections.isNotEmpty()) {
                _haveLoggedRemoteVideoSections = true
                println("DEBUG: [WebRTC] ===== REMOTE VIDEO SECTIONS (${videoSections.size}) =====")
                videoSections.forEachIndexed { index, lines ->
                    println("DEBUG: [WebRTC] --- video section $index ---")
                    lines.forEach { line ->
                        // Attributes only. The session-level block and the ice
                        // candidates are noise here and bury the handful of lines
                        // that identify a track.
                        if (line.startsWith("a=") || line.startsWith("m=")) {
                            println("DEBUG: [WebRTC]   $line")
                        }
                    }
                }
                println("DEBUG: [WebRTC] ===== END REMOTE VIDEO SECTIONS =====")
            }
        }

        val streamIds = sdpScreenShareStreamIds(sdp)
        if (streamIds.isEmpty()) return
        val trackIds = sdpVideoTrackIdsFor(sdp, streamIds)
        if (trackIds.isEmpty()) {
            println("DEBUG: [WebRTC] SDP marked streams=$streamIds but no a=msid track ids to match on")
            return
        }
        _remoteScreenTrackIds.value = trackIds
        println("DEBUG: [WebRTC] SDP marked screen-share streams=$streamIds tracks=$trackIds")
    }

    /**
     * Track ids belonging to [streamIds], per `a=msid:<stream> <track>`.
     *
     * The track id is the second token. That is the value the native layer
     * reports back from `onTrack`, which is why it is the one compared against
     * rather than the stream id.
     */
    private fun sdpVideoTrackIdsFor(sdp: String, streamIds: Set<String>): Set<String> {
        val ids = LinkedHashSet<String>()
        Regex("""(?m)^a=msid:([^\s]+)\s+([^\s]+)""").findAll(sdp).forEach { match ->
            val stream = match.groupValues[1]
            val track = match.groupValues[2]
            if (stream in streamIds) ids.add(track)
        }
        return ids
    }
    override val isRemoteScreenSharing: StateFlow<Boolean> = _isRemoteScreenSharing

    private var selectedAudioOutput: String = "Default"

    /** One SDP dump per call, so renegotiation does not bury it. */
    private var _haveLoggedRemoteVideoSections = false

    /**
     * The sender for the call's single video m-line, or null to add one.
     *
     * Matrix 1:1 negotiates ONE video m-line per participant. A camera and a screen
     * share are not two streams -- they are the same stream carrying different
     * content, and one replaces the other. Run 47 shows the peer side of that
     * plainly: their offer for a shared screen is a single `m=video` with a
     * bare-UUID msid and no screen-share marker anywhere, because there is only
     * ever one video stream to label.
     *
     * So `addTrack` is the wrong call whenever a video m-line already exists, even
     * though the null check around it reads as if it were the careful one. It asks
     * for a SECOND m-line that the peer never negotiated, and setLocalDescription
     * then fails outright:
     *
     *   Failed to set local video description recv parameters for m-section with mid='1'
     *
     * Run 48 hit that three times, and it is not cosmetic. A failed offer means the
     * renegotiation is never sent, so the track on the wire never changes: Element
     * saw no video from Orbit at all, while the UI drew a share tile for a share
     * that had not been published. The capture guard was also engaged by then, so
     * Orbit had blanked itself out of every capture on the strength of a
     * negotiation that never happened.
     *
     * Reusing the transceiver's own sender is the Unified Plan answer and the one
     * Element is already using, since it cannot do otherwise either.
     */
    /** True for a transceiver carrying video, and false rather than throwing on a null track. */
    private fun isVideoTransceiver(transceiver: RTCRtpTransceiver): Boolean =
        try {
            transceiver.receiver?.track?.kind == "video"
        } catch (e: Throwable) {
            false
        }

    private fun videoSenderFor(pConn: RTCPeerConnection): RTCRtpSender? {
        localVideoSender?.let { return it }

        val reused = runCatching {
            val transceivers: Array<RTCRtpTransceiver>? = pConn.transceivers
            transceivers?.filterNotNull()?.firstOrNull { isVideoTransceiver(it) }?.sender
        }.getOrNull()

        if (reused != null) {
            println("DEBUG: [WebRTC] reusing negotiated video m-line instead of adding one")
            return reused
        }

        println("DEBUG: [WebRTC] no video m-line to reuse; adding one")
        return pConn.addTrack(currentVideoTrackForNewSender(), listOf("mediaStream"))
    }

    /**
     * The track to put on a newly added video sender.
     *
     * Only reached when there is genuinely no video m-line, which in a 1:1 means
     * we have nothing to send yet. Null is a valid answer and is left to the
     * caller to handle, because adding a null track throws inside WebRTC.
     */
    private fun currentVideoTrackForNewSender(): MediaStreamTrack? =
        localVideoTrack ?: screenVideoTrack
    private var selectedCamera: String = "Default"

    private var localRenderer: DesktopVideoRenderer? = DesktopVideoRenderer(mirror = true)
    private var remoteRenderer: DesktopVideoRenderer? = DesktopVideoRenderer()
    private var remoteScreenRenderer: DesktopVideoRenderer? = DesktopVideoRenderer()

    override fun initialize(iceServers: List<TurnServer>) {
        try {
            if (peerConnection != null) return

            if (factory == null) {
                factory = createFactory()
            }

            applyAudioDevices()

            val rtcIceServers = iceServers.map { server ->
                RTCIceServer().apply {
                    urls = server.uris
                    username = server.username
                    password = server.password
                }
            }

            val rtcConfig = RTCConfiguration()
            rtcConfig.iceServers.addAll(if (rtcIceServers.isNotEmpty()) {
                rtcIceServers
            } else {
                listOf(RTCIceServer().apply { urls = listOf("stun:stun.l.google.com:19302") })
            })

            WindowsAudioDucker.duckVolumes(attenuationStrength)
        
            peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnectionObserver {
                override fun onIceCandidate(candidate: RTCIceCandidate) {
                    _onIceCandidate.tryEmit(
                        IceCandidateModel(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                    )
                }

                override fun onAddStream(stream: MediaStream) {
                    _onStreamAdded.tryEmit(true)
                    stream.audioTracks.firstOrNull()?.let { attachRemoteAudioTrack(it) }
                    val sink = remoteRenderer as? VideoTrackSink
                    if (sink != null) {
                        stream.videoTracks.firstOrNull()?.addSink(sink)
                    }
                }

                override fun onSignalingChange(state: RTCSignalingState) {}
                override fun onConnectionChange(state: RTCPeerConnectionState) {}
                override fun onIceConnectionChange(state: RTCIceConnectionState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: RTCIceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out RTCIceCandidate>) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(dataChannel: RTCDataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RTCRtpReceiver, mediaStreams: Array<out MediaStream>) {
                    runCatching {
                        attachRemoteTrack(receiver.track)
                    }.onFailure { e ->
                        println("DEBUG: [WebRTC] Failed to attach remote track via onAddTrack: ${e.message}")
                    }
                }
                override fun onTrack(transceiver: RTCRtpTransceiver) {
                    runCatching {
                        attachRemoteTrack(transceiver.receiver.track)
                    }.onFailure { e ->
                        println("DEBUG: [WebRTC] Failed to attach remote track via onTrack: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }

    private fun applyAudioDevices() {
        val adm = audioDeviceModule ?: return
        
        if (selectedAudioInput != "Default") {
            val devices = MediaDevices.getAudioCaptureDevices()
            devices.find { it.getName() == selectedAudioInput }?.let {
                runCatching { adm.setRecordingDevice(it) }
            }
        }
        
        if (selectedAudioOutput != "Default") {
            val devices = MediaDevices.getAudioRenderDevices()
            devices.find { it.getName() == selectedAudioOutput }?.let {
                runCatching { adm.setPlayoutDevice(it) }
            }
        }
    }

    private var isLiveKitMediaStarted = false

    private var captureWidth = 640
    private var captureHeight = 480

    override fun startLocalMedia(isVideoEnabled: Boolean) {
        try {
            if (isGroupCall) {
                if (isLiveKitMediaStarted) {
                    println("DEBUG: [WebRTC] LiveKit media already started, ignoring redundant startLocalMedia")
                    return
                }
                isLiveKitMediaStarted = true
                println("DEBUG: [WebRTC] Redirecting local media to LiveKit Data Plane")

                val usingNativeAudio = livekit.LiveKitNativeAudioSession.isConnected

                if (isVideoEnabled && usingNativeAudio) {
                    println("DEBUG: [WebRTC] Native C++ audio mode active; LiveKit video publish stays disabled for now")
                }

                if (usingNativeAudio) {
                    if (livekit.LiveKitNativeAudioSession.startPublishing()) {
                        _onStreamAdded.tryEmit(true)
                    } else {
                        println("ERROR: [WebRTC] Failed to start native C++ LiveKit audio")
                    }
                } else {
                    println("DEBUG: [WebRTC] No native audio session connected; skipping LiveKit media publish")
                }
                
                println("DEBUG: [WebRTC] Group call local media started and published to LiveKit")
                return
            }
            
            val pConn = peerConnection ?: return
            val f = factory ?: return

            if (localAudioTrack == null) {
                val options = AudioOptions()
                options.noiseSuppression = currentNs
                options.echoCancellation = currentEc
                options.autoGainControl = currentAgc
                options.highpassFilter = true
                val audioSource = f.createAudioSource(options)
                localAudioSource = audioSource
                val audioTrack = f.createAudioTrack("audioTrack", audioSource)
                audioTrack.setEnabled(true)
                localAudioTrack = audioTrack
                localAudioSender = pConn.addTrack(audioTrack, listOf("mediaStream"))
                println("DEBUG: [WebRTC] Desktop local audio track enabled=${audioTrack.isEnabled()} id=${audioTrack.getId()}")
            }

            if (isVideoEnabled) {
                startVideoCapture()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startVideoCapture() {
        if (factory == null) {
            println("DEBUG: [WebRTC] factory is NULL in startVideoCapture, attempting lazy init")
            factory = createFactory()
        }
        val f = factory
        if (f == null) {
            println("ERROR: [WebRTC] factory is STILL NULL after lazy init!")
            return
        }
        
        if (!isGroupCall && peerConnection == null) {
            println("DEBUG: [WebRTC] Not a group call and peerConnection is null, skipping capture")
            return
        }

        if (localVideoSource == null || cameraVideoTrack == null) {
            println("DEBUG: [WebRTC] Initializing video source and track")
            val devices = MediaDevices.getVideoCaptureDevices()
            if (devices.isEmpty()) {
                println("ERROR: [WebRTC] No video capture devices found!")
                return
            }

            val device = devices.find { it.name == selectedCamera } ?: devices.first()
            println("DEBUG: [WebRTC] Using camera device: ${device.name}")
            
            val capabilities = MediaDevices.getVideoCaptureCapabilities(device)
            val capability = chooseVideoCapability(capabilities)
            println("DEBUG: [WebRTC] Chosen capability: ${capability?.width}x${capability?.height}@${capability?.frameRate}")

            if (capability != null) {
                captureWidth = capability.width
                captureHeight = capability.height
            }

            val videoSource = VideoDeviceSource().apply {
                setVideoCaptureDevice(device)
                if (capability != null) {
                    setVideoCaptureCapability(capability)
                }
            }

            try {
                videoSource.start()
                isVideoCaptureRunning = true
                println("DEBUG: [WebRTC] videoSource started")
                
                // Inject our custom video processor if enabled.
                // Guarded on its own: injectVideoProcessor has no implementation
                // in any shipped DLL, so it throws UnsatisfiedLinkError. Letting
                // that reach the surrounding catch treated it as a source-start
                // failure -- disposing the source and returning, which turned on
                // background blur into a dead camera on the P2P path.
                if (isVirtualBackgroundEnabled) {
                    val modelPath = extractModelToTempFile("selfie_segmentation.onnx")
                    if (modelPath != null) {
                        println("DEBUG: [WebRTC] Injecting video processor with model: $modelPath")
                        runCatching { OrbitVideoEngine.injectVideoProcessor(videoSource, modelPath) }
                            .onSuccess { println("DEBUG: [WebRTC] virtual background processor injected") }
                            .onFailure {
                                println("WARN [WebRTC] virtual background unavailable, capturing unblurred: ${it.message}")
                            }
                    }
                }
            } catch (t: Throwable) {
                println("ERROR: [WebRTC] Failed to start videoSource: ${t.message}")
                t.printStackTrace()
                try { videoSource.dispose() } catch (e: Exception) {}
                return
            }

            val videoTrack = f.createVideoTrack("videoTrack", videoSource)
            localVideoSource = videoSource
            cameraVideoTrack = videoTrack
            println("DEBUG: [WebRTC] cameraVideoTrack created: ${videoTrack.getId()}")
            
            // If we are screen sharing, don't set this as localVideoTrack yet
            if (screenVideoTrack == null) {
                localVideoTrack = videoTrack
                localRenderer?.isScreenShare = false
                (localRenderer as? VideoTrackSink)?.let { sink ->
                    videoTrack.addSink(sink)
                }
                if (localVideoSender != null) {
                    localVideoSender?.replaceTrack(videoTrack)
                } else if (peerConnection != null) {
                    localVideoSender = peerConnection?.addTrack(videoTrack, listOf("mediaStream"))
                }
            }
        }

        cameraVideoTrack?.setEnabled(true)
        if (!isVideoCaptureRunning && localVideoSource != null) {
            println("DEBUG: [WebRTC] Resuming videoSource")
            runCatching {
                localVideoSource?.start()
                isVideoCaptureRunning = true
            }
        }
    }

    private var shareCaptureFactory: PeerConnectionFactory? = null

    /**
     * Reused by [trackSharedWindow] for the life of a share.
     *
     * Constructing a WindowCapturer registers desktop-capture callbacks with the
     * OS; the per-tick version built and disposed one every two seconds while a
     * share was running. One instance, disposed when the tracker stops.
     */
    @Volatile
    private var shareWindowCapturer: dev.onvoid.webrtc.media.video.desktop.WindowCapturer? = null

    /**
     * Desktop sources, enumerated once and reused.
     *
     * ## Why this is cached
     *
     * `ScreenCapturer` and `WindowCapturer` each walk every monitor and every
     * top-level window through COM on Windows. That is not a cheap query, and it
     * used to happen four times in this file:
     *
     *   1. `getDesktopSources`, to populate the share picker
     *   2. `startScreenShare`, to turn the chosen id back into a capturable one
     *   3. `trackSharedWindow`, every two seconds, for the whole share
     *   4. `trackSharedWindow`, again on its own validation pass
     *
     * Items 1 and 2 happen back to back, separated only by the user clicking a
     * source, and between them a whole `PeerConnectionFactory` is constructed.
     * Measured symptom: a clear ~1 second audio dropout the instant sharing
     * starts, then normal audio. The mixer has a 10 ms deadline and the share
     * path was taking the whole machine for a second to do nothing useful.
     *
     * ## Why a TTL rather than "until the call ends"
     *
     * A window opened after the first enumeration would not appear in the picker.
     * The TTL is the refresh: long enough that the picker and the share that
     * follows it share one enumeration, short enough that a window opened a couple
     * of minutes later still shows up. There is deliberately no manual invalidation
     * hook -- it would be a public method with no caller, which is the shape this
     * file has been cleaned of all session.
     */
    private var cachedDesktopSources: List<DesktopSourceModel>? = null
    private var cachedDesktopSourcesAt: Long = 0L

    private fun cachedSources(nowMs: Long, ttlMs: Long): List<DesktopSourceModel>? {
        val list = cachedDesktopSources ?: return null
        return if (nowMs - cachedDesktopSourcesAt < ttlMs) list else null
    }

    // Group calls run the native C++ engine and never build the P2P factory, but
    // startScreenShare needs a factory purely to pump local capture frames into
    // the native share sink. Create a lightweight audio-less one on first use.
    private fun obtainShareFactory(): PeerConnectionFactory {
        factory?.let { return it }
        shareCaptureFactory?.let { return it }
        WebrtcNativeDepsLoader.load()
        val created = PeerConnectionFactory()
        shareCaptureFactory = created
        println("DEBUG [WebRTC] created capture-only PeerConnectionFactory for group screen share")
        return created
    }

    private fun createFactory(): PeerConnectionFactory {
        return runCatching {
            WebrtcNativeDepsLoader.load()
            WindowsAudioDucker.disableWindowsDucking()
            
            // JNI Injection hack removed because cross-ABI (vtable) mismatch causes 
            // EXCEPTION_ACCESS_VIOLATION during PeerConnectionFactory native init.
            
            val audioModule = OrbitAudioDeviceModule()
            audioDeviceModule = audioModule

            val captureDevice = runCatching { MediaDevices.getDefaultAudioCaptureDevice() }.getOrNull()
            val renderDevice = runCatching { MediaDevices.getDefaultAudioRenderDevice() }.getOrNull()

            println(
                "DEBUG: [WebRTC] Desktop audio devices capture=${captureDevice?.getName() ?: "default"} " +
                    "render=${renderDevice?.getName() ?: "default"}"
            )
            PeerConnectionFactory(audioModule)
        }.getOrElse { error ->
            println("DEBUG: [WebRTC] Falling back to default desktop audio module: ${error.message}")
            runCatching { audioDeviceModule?.dispose() }
            audioDeviceModule = null
            PeerConnectionFactory()
        }
    }

    // Mutated from WebRTC signaling threads via attachRemoteTrack, so it must be
    // concurrent; it is also cleared in endCall() so ids do not leak across calls.
    private val activeRemoteVideoTracks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        when (track) {
            is AudioTrack -> attachRemoteAudioTrack(track)
            is VideoTrack -> {
                // add() is the atomic contains+add; a separate contains() check
                // would let two signaling threads attach the same track twice.
                if (!activeRemoteVideoTracks.add(track.getId())) {
                    println("DEBUG: [WebRTC] Ignoring duplicate attachRemoteTrack for VideoTrack ID: ${track.getId()}")
                    return
                }

                println("DEBUG: [WebRTC] Attaching VideoTrack sink! track ID: ${track.getId()}")
                _onStreamAdded.tryEmit(true)
                
                // What the negotiated SDP said about this track, which is the only
                // authoritative answer available here.
                //
                // The three name-based guesses below are all unreliable, and the
                // one that carried the weight -- "a second video track must be a
                // screen share" -- only works if the camera happened to arrive
                // first. Element's track ids are bare UUIDs, so `contains("screen")`
                // and `contains("desktop")` are both false, and a share that arrives
                // as the FIRST remote track has `remoteVideoTrack == null`, so every
                // clause is false and the share is filed as a camera.
                //
                // That is not hypothetical. Run 45 caught it exactly: one remote
                // track, id 528d6310-a864-4e5a-ab1d-989b6c6d8c15, filed as
                // "Remote Camera VideoTrack", and the grid reporting shares=0 while
                // the screen plainly showed somebody sharing.
                val id = track.getId()
                val fromSdp = remoteScreenTrackIds().contains(id)
                val byName = id.contains("screen", ignoreCase = true) ||
                             id.contains("desktop", ignoreCase = true)
                val isScreen = fromSdp || byName || remoteVideoTrack != null

                println(
                    "DEBUG: [WebRTC] remote track id=$id " +
                        "fromSdp=$fromSdp byName=$byName hadCamera=${remoteVideoTrack != null} " +
                        "-> ${if (isScreen) "SCREENSHARE" else "camera"}"
                )

                if (isScreen) {
                    remoteScreenVideoTrack = track
                    _isRemoteScreenSharing.value = true
                    watchTrackEnd(track) { clearRemoteScreenShare(track) }
                    remoteScreenRenderer?.let {
                        it.isScreenShare = true
                        track.addSink(it)
                        println("DEBUG: [WebRTC] Sink successfully added to Remote ScreenShare VideoTrack. ID: ${track.getId()}")
                    }
                } else {
                    remoteVideoTrack = track
                    // Clearing the field when the track ends is what stops the
                    // `remoteVideoTrack != null` clause above from firing on the
                    // NEXT video track. The peer's camera can restart for reasons
                    // that have nothing to do with screen sharing -- a device
                    // change, bandwidth adaptation, toggling the camera -- and
                    // because the old field was never cleared, the replacement was
                    // filed as a screen share: the camera tile kept rendering a
                    // dead track while their face appeared letterboxed in the
                    // share spotlight.
                    watchTrackEnd(track) { clearRemoteCamera(track) }
                    remoteRenderer?.let {
                        it.isScreenShare = false
                        track.addSink(it)
                        println("DEBUG: [WebRTC] Sink successfully added to Remote Camera VideoTrack. ID: ${track.getId()}")
                    }
                }
            }
        }
    }

    /**
 * Run [onEnd] when [track] ends, swallowing listener-registration failures.
 *
 * Listener registration is a JNI call on a track owned by a signalling thread. If
 * it throws, the only safe response is to carry on without the notification --
 * which is exactly the state this code was already in before, since nothing
 * listened for track ends at all.
 */
private fun watchTrackEnd(track: MediaStreamTrack, onEnd: () -> Unit) {
        runCatching {
            track.addTrackEndedListener { ended: MediaStreamTrack ->
                if (ended.getId() == track.getId()) onEnd()
            }
        }.onFailure { e ->
            println("DEBUG: [WebRTC] could not observe track end for ${track.getId()}: ${e.message}")
        }
    }

    /**
     * The peer's screen share has ended.
     *
     * `isRemoteScreenSharing` used to be set true on attach and reset only at
     * hangup, so when the other person stopped sharing, Orbit kept their
     * "$name's Screen" tile in the grid for the rest of the call: a frozen last
     * frame, permanently holding the spotlight panel away from the camera. The
     * group path has always cleaned this up -- LiveKitRemoteVideoFrameStore's
     * trackStopped is pinned by RemoteVideoTrackClassificationTest -- which is
     * the diff nobody carried across to the 1:1 path.
     */
    private fun clearRemoteScreenShare(track: MediaStreamTrack) {
        if (remoteScreenVideoTrack?.getId() != track.getId()) return
        remoteScreenVideoTrack = null
        _isRemoteScreenSharing.value = false
        _remoteScreenTrackIds.value = emptySet()
        remoteScreenRenderer?.isScreenShare = false
        // No removeSink: the sink is a single reusable DesktopVideoRenderer, and
        // the next share re-adds it to a new track. Detaching is not needed
        // because the ended track stops delivering frames on its own.
        println("DEBUG: [WebRTC] remote screen share ended, clearing tile")
    }

    private fun clearRemoteCamera(track: MediaStreamTrack) {
        if (remoteVideoTrack?.getId() != track.getId()) return
        remoteVideoTrack = null
        remoteRenderer?.isScreenShare = false
        println("DEBUG: [WebRTC] remote camera track ended")
    }

    private fun attachRemoteAudioTrack(track: AudioTrack) {
        track.setEnabled(true)
        remoteAudioTrack = track
        if (playbackMuted) {
            runCatching { track.setVolume(0.0) }
        }
        _onStreamAdded.tryEmit(true)
    }

    private fun chooseVideoCapability(capabilities: List<VideoCaptureCapability>): VideoCaptureCapability? {
        if (capabilities.isEmpty()) return null
        return capabilities.minByOrNull { capability ->
            abs(capability.width - 640) + abs(capability.height - 480) + abs(capability.frameRate - 30) * 20
        }
    }

    private fun stopVideoCapture(disposeTrack: Boolean) {
        cameraVideoTrack?.setEnabled(false)

        if (isVideoCaptureRunning) {
            runCatching { localVideoSource?.stop() }
            isVideoCaptureRunning = false
        }

        if (disposeTrack) {
            // Only remove from sender if screen share is NOT active
            if (screenVideoTrack == null) {
                localVideoSender?.let { sender ->
                    runCatching { peerConnection?.removeTrack(sender) }
                }
                localVideoSender = null
            }
            
            (localRenderer as? VideoTrackSink)?.let { sink ->
                runCatching { cameraVideoTrack?.removeSink(sink) }
            }
            runCatching { cameraVideoTrack?.dispose() }
            cameraVideoTrack = null
            
            if (screenVideoTrack == null) {
                localVideoTrack = null
            }
            
            runCatching { localVideoSource?.dispose() }
            localVideoSource = null
        }
    }

    override fun stopLocalMedia() {
        if (isGroupCall) {
            runCatching { livekit.LiveKitNativeAudioSession.stopPublishing() }
            isLiveKitMediaStarted = false
        }

        stopVideoCapture(disposeTrack = true)
        desktopAudioStreaming = false
        stopScreenShare()

        localAudioSender?.let { sender ->
            runCatching { peerConnection?.removeTrack(sender) }
        }
        localAudioSender = null
        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null
        // AudioTrackSource has no dispose() in this fork; the native source is
        // released with the factory in endCall().
        localAudioSource = null
    }

    override fun startScreenShare(data: Any?) {
        val sourceId = data as? String
        println("DEBUG [WebRTC] startScreenShare invoked sourceId=$sourceId isGroupCall=$isGroupCall factory=${factory != null} peerConnection=${peerConnection != null}")

        // Take Orbit out of its own capture BEFORE the first frame is produced.
        //
        // Sharing Orbit's screen used to produce an infinite mirror: the capture
        // picked up this window, the window showed the capture as a live preview,
        // and the preview was captured again -- each generation smaller than the
        // last. What the other person saw was that tunnel, not their screen.
        //
        // Engaged first so the very first published frame is already clean. A frame
        // or two of mirror at the start would still be visible to them.
        //
        // Unconditional rather than only-when-sharing-our-own-screen: it cannot be
        // known from here whether the chosen source is this monitor, and excluding
        // a window that is not being shared costs nothing.
        util.SelfCaptureGuard.engage()

        try {
            if (!isGroupCall && peerConnection == null) {
                println("WARN [WebRTC] startScreenShare aborted: P2P mode with no peer connection")
                util.SelfCaptureGuard.release()
                return
            }
            // Warm the factory and the source list off the critical path when we can.
            // See prewarmScreenShare() for why this is not the whole story.
            prewarmScreenShare()
            val f = obtainShareFactory()

            // Resolve from the cache the share picker already populated. This used
            // to construct a ScreenCapturer AND a WindowCapturer and enumerate both
            // a second time, moments after the picker had enumerated them once --
            // two full COM walks of every monitor and window on a machine running a
            // 10 ms-deadline audio mixer, and the audible symptom was a ~1s dropout
            // the instant sharing started.
            var isWindow = false
            var finalSourceId: Long? = null
            val t0 = System.currentTimeMillis()
            resolveShareSource(sourceId)?.let { (id, window) ->
                finalSourceId = id
                isWindow = window
            }
            if (finalSourceId == null) {
                // Cache miss. Enumerate, as before, and repopulate. Kept so this is
                // a speed-up and never a new way for sharing to fail.
                val screenCapturer = ScreenCapturer()
                val windowCapturer = dev.onvoid.webrtc.media.video.desktop.WindowCapturer()
                val screenSources = screenCapturer.desktopSources
                val windowSources = windowCapturer.desktopSources
                val sSource = screenSources.find { it.id.toString() == sourceId }
                val wSource = windowSources.find { it.id.toString() == sourceId }
                isWindow = wSource != null
                finalSourceId = wSource?.id ?: sSource?.id ?: screenSources.firstOrNull()?.id
                cachedDesktopSources = screenSources.map {
                    DesktopSourceModel(it.id.toString(), it.title ?: "Screen ${it.id}", false)
                } + windowSources.map {
                    DesktopSourceModel(it.id.toString(), it.title ?: "Window ${it.id}", true)
                }
                cachedDesktopSourcesAt = System.currentTimeMillis()
                screenCapturer.dispose()
                windowCapturer.dispose()
            }
            println(
                "DEBUG [WebRTC] share source resolved id=$finalSourceId isWindow=$isWindow " +
                    "in ${System.currentTimeMillis() - t0}ms (cached=${cachedDesktopSources != null})"
            )

            if (finalSourceId != null) {
                // If already screen sharing, stop old one
                if (screenVideoTrack != null) {
                    stopScreenShare()
                }

                val shareSettings = repository.SettingsManager()
                val quality = runCatching { shareSettings.screenShareQualitySync() }.getOrDefault("1080p")
                // Ceiling is 30, not 60.
                //
                // Inbound video is already capped at 20 fps / 1280 px because a
                // 1080p share at the sender's rate was pushing 237 MB/s through
                // JNI and ~1 GB/s once the UI copied each frame. Publishing was
                // left uncapped and had the same effect on the OTHER direction: at
                // 60 fps and 1440p this machine was converting ~186 MB/s of I420
                // while the audio mixer ran, and the mixer lost -- it went from
                // `starved=10 qDepth=3360` to `starved=283 qDepth=480`, the
                // playout buffer hit its 10 ms floor and the playback line started
                // dropping chunks. Everyone else's voice stuttered the moment you
                // shared your screen.
                //
                // A shared window gains nothing from 60 fps anyway: it is text and
                // static UI, and 30 is what Discord and Element cap at.
                val fps = runCatching { shareSettings.screenShareFpsSync() }.getOrDefault(30)
                    .coerceIn(5, 30)
                val (maxShareWidth, maxShareHeight) = when (quality.lowercase()) {
                    "720p" -> 1280 to 720
                    "1440p" -> 2560 to 1440
                    "source" -> Int.MAX_VALUE to Int.MAX_VALUE
                    else -> 1920 to 1080
                }
                println("DEBUG [WebRTC] screen share params quality=$quality fps=$fps max=${maxShareWidth}x$maxShareHeight")

                screenSource = VideoDesktopSource()
                screenSource?.setSourceId(finalSourceId, isWindow)
                if (maxShareHeight != Int.MAX_VALUE) {
                    screenSource?.setMaxFrameSize(maxShareWidth, maxShareHeight)
                }
                screenSource?.setFrameRate(fps)
                screenSource?.start()

                val screenTrack = f.createVideoTrack("screenTrack_${System.currentTimeMillis()}", screenSource)
                screenTrack.setEnabled(true)
                screenVideoTrack = screenTrack

                if (isGroupCall) {
                    println("DEBUG [WebRTC] Starting screen share in Group Call (LiveKit)")
                    screenTrack.addSink(livekit.LiveKitLocalScreenShareTrack.sink)
                    // Native C++ session publishes SOURCE_SCREENSHARE; dims are taken
                    // lazily from the first frame, so no fixed declaration here.
                    livekit.LiveKitNativeMediaSession.armScreenSharePublish(fps, 0)
                } else {
                    val pConn = peerConnection ?: return
                    localVideoSender = videoSenderFor(pConn)?.also { sender ->
                        sender.replaceTrack(screenTrack)
                    }
                    localVideoTrack = screenTrack
                    localRenderer?.isScreenShare = true
                    (localRenderer as? VideoTrackSink)?.let { sink ->
                        screenTrack.addSink(sink)
                    }
                }
                
                _isScreenSharing.tryEmit(true)
                if (isWindow) {
                    trackSharedWindow(finalSourceId)
                }
            } else {
                println("WARN [WebRTC] screen share aborted: no desktop/window source matched sourceId=$sourceId")
                // Nothing will ever call stopScreenShare for a share that never
                // started, so the guard has to be undone here or Orbit's window
                // stays excluded from every screenshot, screen recorder and share
                // on the machine until the call ends.
                util.SelfCaptureGuard.release()
                screenSource?.stop()
                screenSource = null
            }

        } catch (e: Exception) {
            e.printStackTrace()
            // Same reasoning as the branch above: engage() ran before the try, so
            // every exit out of here that is not a successful share must undo it.
            util.SelfCaptureGuard.release()
            runCatching { screenSource?.stop() }
            screenSource = null
        }
    }

    /**
     * Builds the share `PeerConnectionFactory` and enumerates desktop sources
     * ahead of time, so pressing Share does not pay for either.
     *
     * ## Why this exists
     *
     * Both are one-time costs that used to land on the click. `PeerConnectionFactory`
     * initialises the whole WebRTC native stack, and the desktop enumeration walks
     * every monitor and window through COM. On a machine running a 10 ms-deadline
     * audio mixer the pair cost a clear ~1 second of dropout at the start of every
     * share.
     *
     * ## Why call it rather than rely on the cache alone
     *
     * A call join already takes seconds and has spare CPU -- 8s once the microphone
     * probe was cached. Moving the cost there costs nothing the user is waiting on,
     * and by the time anyone clicks Share the factory is already warm. Without
     * this the very first share of a call still pays the construction, because the
     * cache is only ever populated by the picker.
     *
     * Safe to call repeatedly and from any thread: it is a no-op once both exist.
     * Failures are logged and swallowed, because the real path still builds them on
     * demand -- this is strictly an optimisation and must never be able to break a
     * share that would otherwise have worked.
     */
    override fun prewarmScreenShare() {
        val t0 = System.currentTimeMillis()
        runCatching { obtainShareFactory() }
            .onFailure { println("DEBUG [WebRTC] share factory prewarm failed: ${it.message}") }
        runCatching { getDesktopSources() }
            .onFailure { println("DEBUG [WebRTC] desktop source prewarm failed: ${it.message}") }
        println("DEBUG [WebRTC] screen share prewarmed in ${System.currentTimeMillis() - t0}ms")
    }

    private fun trackSharedWindow(sourceId: Long) {
        shareWindowTrackingJob?.cancel()
        shareWindowTrackingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                kotlinx.coroutines.delay(10_000)
                if (!_isScreenSharing.value) break
                // One capturer for the life of the tracker, not one per tick.
                //
                // This used to construct a WindowCapturer, enumerate every window
                // on the system, and dispose it -- every two seconds, for as long
                // as the share was running, on the same machine as the audio
                // mixer. Constructing the capturer registers OS callbacks, which
                // is the expensive half; the enumeration on an existing capturer
                // is comparatively cheap. Ten seconds instead of two is the other
                // half: a window that closed can stay shared for a few seconds
                // longer, which is harmless, in exchange for a fifth of the
                // periodic spike.
                val stillExists = runCatching {
                    val capturer = shareWindowCapturer ?: run {
                        dev.onvoid.webrtc.media.video.desktop.WindowCapturer()
                            .also { shareWindowCapturer = it }
                    }
                    capturer.desktopSources.any { it.id == sourceId }
                }.getOrDefault(true)
                if (!stillExists) {
                    println("DEBUG [WebRTC] shared window closed or gone; stopping screen share")
                    stopScreenShare()
                    break
                }
            }
            // One capturer for the tracker, so it is disposed exactly once --
            // including on the `break` above and on cancellation.
            runCatching { shareWindowCapturer?.dispose() }
            shareWindowCapturer = null
        }
    }

    override fun stopScreenShare() {
        shareWindowTrackingJob?.cancel()
        shareWindowTrackingJob = null
        // Put the window back into captures. This is not optional tidiness: the
        // affinity flag persists on the window until it is changed, so a share that
        // stopped without reaching here -- a crash, a hangup, a failed unpublish --
        // would leave Orbit invisible to every screenshot and screen recorder for
        // the rest of the session.
        util.SelfCaptureGuard.release()
        try {
            if (isGroupCall && _isScreenSharing.value) {
                println("DEBUG [WebRTC] Stopping screen share in Group Call (LiveKit)")
                livekit.LiveKitNativeMediaSession.disarmScreenSharePublish()
                livekit.LiveKitLocalScreenShareTrack.clearPreview()
            }
            if (!isGroupCall && peerConnection == null) return
            
            (localRenderer as? VideoTrackSink)?.let { sink ->
                runCatching { screenVideoTrack?.removeSink(sink) }
            }
            
            screenSource?.stop()
            screenSource?.dispose()
            screenSource = null
            
            runCatching { screenVideoTrack?.dispose() }
            screenVideoTrack = null
            
            val pConn = peerConnection
            if (pConn != null) {
                // Resume camera if it was enabled
                if (cameraVideoTrack != null) {
                    localVideoSender?.replaceTrack(cameraVideoTrack)
                    localVideoTrack = cameraVideoTrack
                    localRenderer?.isScreenShare = false
                    (localRenderer as? VideoTrackSink)?.let { sink ->
                        cameraVideoTrack?.addSink(sink)
                    }
                } else {
                    localVideoSender?.let { sender ->
                        runCatching { pConn.removeTrack(sender) }
                    }
                    localVideoSender = null
                    localVideoTrack = null
                }
            }
            
            // Stop sharing system audio
            runCatching { audioDeviceModule?.setSystemAudioShareEnabled(false) }
            if (desktopAudioStreaming) {
                desktopAudioStreaming = false
                rebuildMicAudioTrack()
            }
            if (isGroupCall && livekit.LiveKitFeatureFlags.cppMedia) {
                // Group calls ride the native share-audio track instead of the
                // ADM mixing path above; ending the share must tear it down no
                // matter which surface stopped the share (native side is a
                // safe no-op when nothing was published).
                runCatching { livekit.LiveKitNativeMediaSession.unpublishScreenShareAudio() }
                    .onFailure { it.printStackTrace() }
            }

            WindowsAudioDucker.unduckVolumes()
        
            _isScreenSharing.tryEmit(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Whether the local mic should be transmitting.
     *
     * Stored rather than read back off the track, because the track gets rebuilt.
     * `rebuildMicAudioTrack` is reached from three places -- setAudioOptions (the
     * in-call More Options menu), setStreamAudioEnabled, and endCall -- and it used
     * to hardcode `setEnabled(true)` on the new track. Muting, then touching noise
     * suppression or echo cancellation, therefore re-opened the mic with no
     * indication, while CallManager.isLocalAudioEnabled still reported "muted" so
     * the UI kept showing a muted microphone over a live one.
     *
     * That is the most serious defect this audit found: not a cosmetic state
     * mismatch, but a muted microphone that is not muted.
     */
    @Volatile
    private var localMicMuted = false

    override fun toggleMute(isMuted: Boolean) {
        if (isGroupCall && livekit.LiveKitNativeAudioSession.isConnected) {
            livekit.LiveKitNativeAudioSession.setMuted(isMuted)
            return
        }
        localMicMuted = isMuted
        localAudioTrack?.setEnabled(!isMuted)
        println("DEBUG: [WebRTC] local mic muted=$isMuted")
    }

    override fun toggleVideo(isVideoEnabled: Boolean) {
        if (isGroupCall && livekit.LiveKitNativeMediaSession.isConnected) {
            // Group calls publish video through the native LiveKit session
            // (CallManager routes the toggle there); never touch P2P tracks.
            return
        }
        if (cameraVideoTrack == null && isVideoEnabled) {
            startVideoCapture()
            // If screen share is active, startVideoCapture won't swap it in. 
            // We should only swap if screen share is NOT active.
            if (screenVideoTrack == null && cameraVideoTrack != null) {
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
            }
            return
        }
        if (isVideoEnabled) {
            startVideoCapture()
            if (screenVideoTrack == null) {
                localVideoSender?.replaceTrack(cameraVideoTrack)
                localVideoTrack = cameraVideoTrack
            }
        } else {
            stopVideoCapture(disposeTrack = false)
        }
    }

    override fun switchCamera() {}

    override fun createOffer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createOffer(RTCOfferOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) { onError(error) }
                    })
                }
                override fun onFailure(error: String) { onError(error) }
            })
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override fun createAnswer(onSuccess: (SessionDescriptionModel) -> Unit, onError: (String) -> Unit) {
        try {
            peerConnection?.createAnswer(RTCAnswerOptions(), object : dev.onvoid.webrtc.CreateSessionDescriptionObserver {
                override fun onSuccess(description: RTCSessionDescription) {
                    peerConnection?.setLocalDescription(description, object : SetSessionDescriptionObserver {
                        override fun onSuccess() {
                            onSuccess(SessionDescriptionModel(description.sdpType.toString().lowercase(), description.sdp))
                        }
                        override fun onFailure(error: String) { onError(error) }
                    })
                }
                override fun onFailure(error: String) { onError(error) }
            })
        } catch (e: Exception) {
            onError(e.message ?: "Unknown error")
        }
    }

    override fun setRemoteDescription(sessionDescription: SessionDescriptionModel, onSuccess: () -> Unit, onError: (String) -> Unit) {
        try {
            val type = RTCSdpType.valueOf(sessionDescription.type.uppercase())
            // Every remote description passes through here, so this is the one
            // place the negotiated SDP can be read for what it says the tracks
            // are. It is also the only trustworthy answer to "is this video track
            // a camera or a screen share" -- track ids are bare UUIDs from
            // Element, so there is nothing in the id to read.
            noteRemoteScreenShareTracks(sessionDescription.sdp)
            val sdp = RTCSessionDescription(type, sessionDescription.sdp)
            val observer = object : SetSessionDescriptionObserver {
                override fun onSuccess() { onSuccess() }
                override fun onFailure(error: String) { onError(error) }
            }
            if (peerConnection != null) {
                peerConnection?.setRemoteDescription(sdp, observer)
            } else {
                onError("PeerConnection is null")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError(e.message ?: "Unknown error")
        }
    }

    override fun addIceCandidate(candidate: IceCandidateModel) {
        if (candidate.sdp.isBlank()) return
        try {
            val rtcCandidate = RTCIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            peerConnection?.addIceCandidate(rtcCandidate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun endCall() {
        // Belt and braces with stopScreenShare: hanging up mid-share must also put
        // the window back into captures, because the affinity flag survives
        // everything else that can go wrong on the way out. A window left excluded
        // is invisible to every screenshot and screen recorder for the session.
        util.SelfCaptureGuard.release()

        // Every step is independently guarded, and ordered by how expensive it is
        // to skip.
        //
        // This used to be ONE try/catch around the whole body, so a throw from
        // `stopLocalMedia()` skipped `peerConnection?.close()` and all three
        // `dispose()` calls -- leaking a native factory and an audio device module
        // for the rest of the process, so the SECOND call in the session allocated
        // on top of the first and neither was ever released. The same catch also
        // printed a stack trace to stderr and swallowed the reason.
        //
        // `stopLocalMedia()` goes first because it closes the audio device line,
        // which is the most likely thing to throw and the most expensive to leak.
        runCatching { stopLocalMedia() }
            .onFailure { println("WARN [WebRtcClientImpl] endCall: stopLocalMedia failed: ${it.message}") }
        runCatching { livekit.LiveKitNativeAudioSession.disconnect() }
            .onFailure { println("WARN [WebRtcClientImpl] endCall: native audio disconnect failed: ${it.message}") }
        isLiveKitMediaStarted = false // Reset LiveKit media guard

        // Explicitly remove sinks to prevent native threads from calling back during shutdown
        localVideoTrack?.removeSink(localRenderer)
        cameraVideoTrack?.removeSink(localRenderer)
        screenVideoTrack?.removeSink(localRenderer)
        remoteVideoTrack?.removeSink(remoteRenderer)
        remoteScreenVideoTrack?.removeSink(remoteScreenRenderer)

        runCatching { peerConnection?.close() }
            .onFailure { println("WARN [WebRtcClientImpl] endCall: peerConnection.close failed: ${it.message}") }
        peerConnection = null
        shareWindowTrackingJob?.cancel()
        shareWindowTrackingJob = null

        // Hang up mid-share must DISPOSE, not just stop. This nulled both fields
        // without disposing, unlike stopScreenShare() which does dispose -- so
        // ending a call while sharing leaked a VideoDesktopSource plus its
        // VideoTrack, both native handles, for the rest of the session.
        runCatching { screenSource?.stop() }
        runCatching { screenSource?.dispose() }
        screenSource = null
        runCatching { screenVideoTrack?.dispose() }
        screenVideoTrack = null

        localVideoTrack = null
        cameraVideoTrack = null
        localAudioTrack = null
        localAudioSource = null
        remoteVideoTrack = null
        remoteScreenVideoTrack = null
        _isRemoteScreenSharing.value = false
        remoteAudioTrack = null
        localVideoSender = null
        // A track id seen in call #1 would otherwise make attachRemoteTrack
        // ignore that track for the rest of the app's lifetime.
        activeRemoteVideoTracks.clear()

        // Release the native capture/speaker devices: without this a second
        // call allocates another factory while the first is still open.
        runCatching { shareCaptureFactory?.dispose() }
        shareCaptureFactory = null
        runCatching { audioDeviceModule?.dispose() }
        audioDeviceModule = null
        runCatching { factory?.dispose() }
        factory = null

        runCatching { WindowsAudioDucker.unduckVolumes() }
    }

    override fun setOrbitZeroEnabled(enabled: Boolean) {
        audioDeviceModule?.setOrbitZeroEnabled(enabled)
    }

    override fun getLocalVideoRenderer(): Any? = localRenderer
    override fun getRemoteVideoRenderer(): Any? = remoteRenderer
    override fun getRemoteScreenRenderer(): Any? = if (remoteScreenVideoTrack != null) remoteScreenRenderer else null

    override fun setStreamAudioEnabled(enabled: Boolean) {
        // Group + cppMedia: system audio rides a dedicated SOURCE_SCREENSHARE_AUDIO
        // track fed by WASAPI loopback inside the native session — no mic mixing.
        if (isGroupCall && livekit.LiveKitFeatureFlags.cppMedia) {
            runCatching {
                if (enabled) livekit.LiveKitNativeMediaSession.publishScreenShareAudio()
                else livekit.LiveKitNativeMediaSession.unpublishScreenShareAudio()
            }.onFailure { it.printStackTrace() }
            return
        }
        audioDeviceModule?.setSystemAudioShareEnabled(enabled)
        if (desktopAudioStreaming == enabled) return
        desktopAudioStreaming = enabled
        rebuildMicAudioTrack()
    }

    private var currentNs = true
    private var currentEc = true
    private var currentAgc = true
    private var desktopAudioStreaming = false

    override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        currentNs = noiseSuppression
        currentEc = echoCancellation
        currentAgc = autoGainControl
        rebuildMicAudioTrack()
    }

    override fun setAttenuationStrength(strength: Float) {
        attenuationStrength = strength
        // No `if (peerConnection != null)` gate.
        //
        // That gate meant ducking only ever ran on a 1:1, because a group call has
        // no WebRTC peer connection -- it goes through LiveKit. So the setting was
        // live in the one call type where ducking matters least and dead in the one
        // where it matters most, and nobody noticed because the slider moved in
        // both.
        //
        // [duckVolumes] enumerates audio sessions itself and holds no reference to
        // the call, so it is safe with or without a peer connection.
        runCatching { WindowsAudioDucker.duckVolumes(strength) }
            .onFailure { println("WARN [WebRTC] duckVolumes failed: ${it.message}") }
    }

    override fun setRemoteVolume(volume: Float) {
        remoteAudioTrack?.setVolume(volume.toDouble())
    }

    private var playbackMuted = false

    override fun setPlaybackMuted(muted: Boolean) {
        playbackMuted = muted
        runCatching { remoteAudioTrack?.setVolume(if (muted) 0.0 else 1.0) }
    }

    override fun setAudioInputDevice(deviceName: String) {
        selectedAudioInput = deviceName
        applyAudioDevices()
    }

    override fun setAudioOutputDevice(deviceName: String) {
        selectedAudioOutput = deviceName
        applyAudioDevices()
    }

    override fun setVideoCaptureDevice(deviceName: String) {
        selectedCamera = deviceName
        // If camera is already running, we need to restart it
        if (cameraVideoTrack != null) {
            stopVideoCapture(disposeTrack = true)
            startVideoCapture()
        }
    }

    override fun setMirrorVideo(enabled: Boolean) {
        localRenderer?.mirror = enabled
    }

    override fun getDesktopSources(): List<DesktopSourceModel> {
        val now = System.currentTimeMillis()
        cachedSources(now, DESKTOP_SOURCE_CACHE_TTL_MS)?.let {
            println("DEBUG [WebRTC] desktop sources from cache n=${it.size}")
            return it
        }
        val screenCapturer = ScreenCapturer()
        // ScreenCapturer only enumerates monitors; windows come from WindowCapturer
        // below, so no title-based kind guessing is needed.
        val sources = screenCapturer.desktopSources.map {
            val title = it.title ?: "Screen ${it.id}"
            DesktopSourceModel(it.id.toString(), title, false)
        }
        screenCapturer.dispose()

        val windowCapturer = dev.onvoid.webrtc.media.video.desktop.WindowCapturer()
        val windowSources = windowCapturer.desktopSources.map {
            val title = it.title ?: "Window ${it.id}"
            DesktopSourceModel(it.id.toString(), title, true)
        }
        windowCapturer.dispose()

        val all = sources + windowSources
        val elapsed = System.currentTimeMillis() - now
        println("DEBUG [WebRTC] desktop sources enumerated n=${all.size} in ${elapsed}ms")
        cachedDesktopSources = all
        cachedDesktopSourcesAt = now
        return all
    }

    /**
     * Resolves a chosen source id to the (id, isWindow) pair the capture source
     * needs, using the cache rather than enumerating again.
     *
     * Returns null when the id is unknown or the enumeration has never run, and
     * the caller then falls back to enumerating -- which keeps this a
     * performance change and not a correctness one. A cache miss must never mean
     * "cannot share".
     */
    private fun resolveShareSource(sourceId: String?): Pair<Long, Boolean>? =
        resolveDesktopSource(cachedDesktopSources, sourceId)

    private fun rebuildMicAudioTrack() {
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return

            // Defined stream-mode profile instead of stripping every processor:
            // NS and AGC pump/gate music, so they are suspended while desktop
            // audio is mixed into this track; HPF (<100 Hz rumble/DC cut) and
            // AEC stay on. Otherwise the user's Settings choices apply verbatim.
            val options = AudioOptions().apply {
                noiseSuppression = !desktopAudioStreaming && currentNs
                echoCancellation = currentEc
                autoGainControl = !desktopAudioStreaming && currentAgc
                highpassFilter = true
            }

            val newAudioSource = f.createAudioSource(options)
            val newAudioTrack = f.createAudioTrack("audioTrack_${System.currentTimeMillis()}", newAudioSource)
            // Honour the current mute state rather than assuming open. This used to
            // be an unconditional setEnabled(true), which meant any rebuild after a
            // mute silently re-opened the microphone.
            newAudioTrack.setEnabled(!localMicMuted)

            if (localAudioSender != null) {
                localAudioSender?.replaceTrack(newAudioTrack)
            } else {
                localAudioSender = pConn.addTrack(newAudioTrack, listOf("mediaStream"))
            }

            // Clean up the old track. AudioTrackSource has no dispose() in this
            // JNI fork (JNI_PeerConnectionFactory::createAudioSource hands over a
            // raw released pointer), so the source cannot be freed individually;
            // it is reclaimed with the PeerConnectionFactory, which endCall()
            // now disposes.
            kotlin.runCatching { localAudioTrack?.dispose() }

            localAudioSource = newAudioSource
            localAudioTrack = newAudioTrack

            println("DEBUG: [WebRTC] Mic profile rebuilt: ns=${options.noiseSuppression} ec=${options.echoCancellation} agc=${options.autoGainControl} hpf=true stream=$desktopAudioStreaming")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    /**
     * Audio device names for the settings pickers.
     *
     * These come from **JavaSound**, not from WebRTC's `MediaDevices`.
     *
     * They used to come from `MediaDevices.getAudioCaptureDevices()` /
     * `getAudioRenderDevices()`, which is the wrong list. Orbit's group-call audio
     * path does not use WebRTC's device layer at all: capture opens a
     * `TargetDataLine` on a JavaSound mixer and playback opens a `SourceDataLine`
     * on one. So the picker was offering names from one enumeration and the
     * audio stack was substring-matching them against a completely different
     * one.
     *
     * The two name spaces overlap but are not the same, and where they differ the
     * selection silently did nothing. A real run picked
     * `Speakers (NVIDIA Broadcast)` and got
     *
     *     WARN output device "Speakers (NVIDIA Broadcast)" matched no mixer,
     *     using default
     *
     * -- a warning in a log, and no change to what the user heard. That is the
     * whole of "changing output device doesn't really change it".
     *
     * Enumerating from the layer that actually opens the line makes every offered
     * name resolve by construction. The device is genuinely different -- the user
     * is choosing which `SourceDataLine` the mixer opens, not a WebRTC sink.
     */
    override fun getAudioInputDevices(): List<String> = livekit.LiveKitAudioDevices.inputNames()

    override fun getAudioOutputDevices(): List<String> = livekit.LiveKitAudioDevices.outputNames()

    override fun getSelectedAudioInputDevice(): String? = selectedAudioInput

    override fun getSelectedAudioOutputDevice(): String? = selectedAudioOutput

    override fun getVideoCaptureDevices(): List<String> {
        return runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureDevices().map { it.getName() }
        }.getOrDefault(listOf("Default"))
    }
}

actual fun createWebRtcClient(): WebRtcClient = JvmWebRtcClient()
