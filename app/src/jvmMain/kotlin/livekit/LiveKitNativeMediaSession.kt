package livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object LiveKitNativeMediaSession : NativeMediaEventSink {
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val PUBLISH_TIMEOUT_MS = 10_000L
    private const val RECONNECT_TIMEOUT_MS = 15_000L
    private const val MAX_CONSECUTIVE_RECONNECTS = 3
    private const val REMOTE_VIDEO_RESCAN_INTERVAL_MS = 2_000L

    private val bridge = OrbitLiveKitNativeBridge()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Volatile for the same reason as the deferreds below: onNativeEvent runs on
    // a native dispatch thread and gates on these two, so without a
    // happens-before edge it could read a stale epoch and drop a legitimate
    // Connected / AudioPublished / VideoPublished event -- which the awaiting
    // connect coroutine then reports as a 15 s CONNECT_TIMEOUT on a healthy join.
    // A JNI call does not establish a JMM edge.
    @Volatile private var sessionId: Long = 0L
    @Volatile private var epoch: Long = 0L

    /**
     * Bumps [epoch] and returns the new value.
     *
     * `epoch += 1` is a non-atomic read-modify-write on a Long, and it is
     * reached from two coroutines (the connect path and the reconnect path), so
     * a lost update gave two connects the same epoch -- which breaks both the
     * stale-event filter in onNativeEvent and the store's epoch guard.
     *
     * The fix is [MonotonicEpoch], which keeps the store inside the monitor. The
     * previous version here did not: `.also { epoch = it }` ran after the lock was
     * released, so two threads both read 5, both computed 6, and both stored 6.
     * That made the read atomic and left the write unprotected.
     */
    private val epochAllocator = MonotonicEpoch(epoch)
    private val epochLock = Any()
    private fun nextEpoch(): Long {
        val value = epochAllocator.next()
        // Mirror to the volatile field the native event filter and the frame
        // stores read. Those read it without a lock, which is sound precisely
        // because the value only ever increases.
        epoch = value
        return value
    }

    // LiveKit identities currently speaking (remote side), pushed from the
    // native mixer's per-participant RMS metering. Change-only + throttled.
    private val _activeSpeakers = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val activeSpeakers: kotlinx.coroutines.flow.StateFlow<Set<String>> = _activeSpeakers

    // Screen-share publish slot (Part F): armed while the user is sharing;
    // actual native publish happens lazily on first frame with its real dimensions.
    //
    // All volatile: these are written from the EDT (arm/disarm) and from
    // disconnect, but read from the WebRTC capture thread inside
    // pushScreenShareFrame. Without a happens-before edge the capture thread
    // could never observe screenShareArmed = true (the share silently never
    // publishes) or could keep seeing true after a disarm.
    @Volatile private var screenShareArmed = false
    @Volatile private var screenShareWidth = 0
    @Volatile private var screenShareHeight = 0
    @Volatile private var screenShareFps = 30
    @Volatile private var screenShareMaxBitrateBps = 0
    @Volatile private var screenShareFirstFrameLogged = false
    // Guards the (screenShareWidth, screenShareHeight) read-modify-write in the
    // republish decision, which runs on the capture thread while the EDT can
    // zero both.
    private val screenShareSlotLock = Any()
    // onNativeEvent completes these from the native dispatch threads while the
    // connect coroutine awaits them on the EDT. Without a happens-before edge the
    // EDT can read a stale null ("Missing deferred for connectLiveKit") or miss a
    // completion and time out a healthy join.
    @Volatile private var connectedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    @Volatile private var audioPublishedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    @Volatile private var videoPublishedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    @Volatile private var mediaReadyDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    @Volatile private var reconnectAllowed: Boolean = true
    @Volatile private var refreshJob: Job? = null
    private var lastAuthResponse: LiveKitAuthResponse? = null
    private var lastVideoPolicy: JoinVideoPolicy = JoinVideoPolicy.AudioOnly
    @Volatile private var lastVideoAllowed: Boolean = false
    // BUG-A fix: true while the camera capture pipeline is open and pushing frames.
    @Volatile private var cameraPushEnabled: Boolean = false
    // Dimensions the camera track was PUBLISHED with. Some drivers ignore the
    // requested capability; the native side rejects frames that don't match the
    // declared source, so the first real frame triggers a republish if they differ.
    @Volatile private var publishedCameraWidth = 0
    @Volatile private var publishedCameraHeight = 0
    @Volatile private var publishedCameraFps = 30
    private val cameraToggleMutex = kotlinx.coroutines.sync.Mutex()
    private var lastRefreshTokenFetcher: (suspend () -> LiveKitAuthResponse)? = null
    @Volatile private var desiredAudioMuted: Boolean = false
    @Volatile private var lastAudioBitrateKbps: Int = 64
    // matrixRTC E2EE media: armed for encrypted-room calls BEFORE connectAsync so
    // RoomOptions::encryption is populated; the native module flag survives reconnects.
    @Volatile private var e2eeArmed: Boolean = false

    // H1: bounded mid-call resume. Fires after a successful refreshAndReconnect so
    // the owner (CallManager) can restore UI stage + re-affirm MatrixRTC membership.
    // Incremented from the reconnect coroutine and reset from the EDT's disconnect
    // path, so it needs to be atomic rather than a plain Int.
    private val reconnectAttempts = AtomicInteger(0)
    var onReconnected: (() -> Unit)? = null

    // BUG-B fix: periodic reader rescan so remote video/screen-share tracks whose
    // subscription completed outside the event-driven startup windows still get
    // a frame reader (idempotent on the native side).
    @Volatile private var remoteVideoRescanJob: Job? = null

    /**
     * Handle on the loopback-duck probe launched by [applyLoopbackDuckIfNeeded].
     *
     * It polls for up to 2 seconds, so it must be cancellable: leaving it running
     * let it re-enable ducking after `unpublishScreenShareAudio` had turned it off,
     * silently muting all playback for the rest of the call.
     */
    @Volatile private var loopbackDuckJob: Job? = null

    // Guards the readinessCache reference AND makes read-modify-write of it atomic.
    //
    // `readinessCache` is a 12-field immutable object, and nearly every call site
    // reads it FIELD BY FIELD to build the next value:
    //
    //     updateReadiness(
    //         state = readinessCache.state,
    //         connected = readinessCache.connected,
    //         ... // 10 more
    //     )
    //
    // `updateReadiness` then replaces the whole object. Four thread classes write
    // it — the native dispatch thread (onNativeEvent), the Dispatchers.IO
    // reconnect coroutine, the video capture thread (handleVideoCaptureFailure)
    // and the EDT. So between a call site's first `readinessCache.state` read and
    // its final store, another writer can have swapped the reference, and the
    // site then assembles a value out of fields from TWO DIFFERENT snapshots. A
    // `Connected` event, which forces audioPublished/acceptingAudioFrames false,
    // landing inside that window gets clobbered by the stale values.
    //
    // `@Volatile` does not help: it makes each individual reference read atomic,
    // which is exactly the wrong granularity — the unit that matters is the whole
    // object.
    //
    // Writers should therefore use [mutateReadiness], which performs the read
    // and the write in ONE critical section. See the callers of
    // [handleNativeEvent] and [handleVideoCaptureFailure].
    private val readinessLock = Any()

    @Volatile
    private var readinessCache = emptyReadiness()

    /** A single consistent view. Never read the field repeatedly across statements. */
    private fun readinessSnapshot(): NativeMediaReadiness =
        synchronized(readinessLock) { readinessCache }

    /**
     * Read-modify-write the readiness in one critical section.
     *
     * This is the ONLY correct way to derive a new readiness from the current one.
     * Passing `old.copy(...)` rather than spelling out every field matters for the
     * reason above: the transform runs against a snapshot that cannot move.
     */
    private inline fun mutateReadiness(transform: (NativeMediaReadiness) -> NativeMediaReadiness) {
        synchronized(readinessLock) {
            readinessCache = transform(readinessCache)
        }
    }

    val isConnected: Boolean
        get() = readinessSnapshot().connected

    val isAudioPublished: Boolean
        get() = readinessSnapshot().audioPublished

    val isAcceptingAudioFrames: Boolean
        get() = readinessSnapshot().acceptingAudioFrames && LiveKitNativeAudioBridge.acceptingFrames

    suspend fun connectAndPublish(
        authResponse: LiveKitAuthResponse,
        videoPolicy: JoinVideoPolicy,
        refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)?,
        audioBitrateKbps: Int = 64
    ): NativeResult<NativeMediaReadiness> {
        reconnectAllowed = true
        lastAuthResponse = authResponse
        lastVideoPolicy = videoPolicy
        lastRefreshTokenFetcher = refreshTokenFetcher
        lastAudioBitrateKbps = audioBitrateKbps
        updateNativeState(NativeMediaState.FetchingToken)

        val tokenResult = authResponse.toTokenResult()
        if (!tokenResult.ok || tokenResult.value == null) {
            val error = tokenResult.error ?: unknownError("LiveKit token decode failed", "decodeLiveKitJwt")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        val grantCheck = validateLiveKitGrants(tokenResult.value, videoPolicy)
        if (!grantCheck.ok || grantCheck.value == null) {
            val error = grantCheck.error ?: unknownError("LiveKit grant validation failed", "validateLiveKitGrants")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }
        val videoPublishRequested = shouldPublishVideo()
        lastVideoAllowed = grantCheck.value.videoAllowed
        val matrixRtcMemberId = authResponse.matrixRtcMemberId
            ?: authResponse.identity
            ?: tokenResult.value.identity.orEmpty()
        val claimedDeviceId = authResponse.claimedDeviceId
            ?: matrixRtcMemberId.substringAfterLast(":", missingDelimiterValue = "ORBIT_DESKTOP")
        println("DEBUG [LiveKitNativeMediaSession] LiveKit token details identity=${tokenResult.value.identity ?: "<none>"} room=${tokenResult.value.roomName ?: "<none>"} canPublish=${tokenResult.value.grants.canPublish} canSubscribe=${tokenResult.value.grants.canSubscribe} canPublishSources=${tokenResult.value.grants.canPublishSources ?: emptyList<String>()}")
        println(
            "DEBUG [MatrixRTCMapping] native media tokenRequestMemberId=$matrixRtcMemberId tokenRequestClaimedDeviceId=$claimedDeviceId jwtSub=${tokenResult.value.identity ?: "<none>"} livekitUrl=${tokenResult.value.livekitUrl} room=${tokenResult.value.roomName ?: "<none>"}"
        )

        if (LiveKitNativeAudioCapture.configuredTestTone()) {
            println("DEBUG [LiveKitNativeMediaSession] test tone enabled before call start; microphone self-test skipped")
        } else {
            val micSelfTestDurationMs = LiveKitNativeAudioCapture.configuredSelfTestDurationMs()
            println("DEBUG [LiveKitNativeMediaSession] microphone self-test before call start durationMs=$micSelfTestDurationMs")
            val micSelfTest = LiveKitNativeAudioCapture.prepareMicrophoneForCall(micSelfTestDurationMs)
            if (!micSelfTest.ok || micSelfTest.value == null) {
                val error = micSelfTest.error ?: unknownError("Microphone self-test failed", "JVM_MIC_CAPTURE")
                cancelPendingDeferreds(error)
                updateReadinessWithError(NativeMediaState.Failed, error)
                return NativeResult.failure(error)
            }
            println(
                "DEBUG [LiveKitNativeMediaSession] microphone self-test selected mixer=${micSelfTest.value.selectedMixerName} description=${micSelfTest.value.selectedMixerDescription} peak=${micSelfTest.value.selectedPeak} rms=${micSelfTest.value.selectedRms} allInputsTooLow=${micSelfTest.value.allInputsTooLow}"
            )
        }

        if (sessionId == 0L) {
            val created = bridge.createSession()
            if (!created.ok || created.value == null) {
                return NativeResult.failure(created.error ?: unknownError("Native session creation failed", "nativeCreateSession"))
            }
            sessionId = created.value
            println("DEBUG [LiveKitNativeMediaSession] native session created: $sessionId")
        } else {
            // Reaching here on a REJOIN meant the previous session was being
            // reused after teardown -- every `if (sessionId == 0L)` guard in this
            // file then passed against a dead session. disconnect() clears the id
            // now, so this branch is reachable only when a session is genuinely
            // still live. Said out loud rather than silently tolerated, because
            // that silence is what made the rejoin hang undiagnosable.
            println(
                "WARN [LiveKitNativeMediaSession] reusing live native session $sessionId for a new join; " +
                    "disconnect() is expected to have cleared it"
            )
        }

        nextEpoch()
        // Native side resets the share slot on room reset; force lazy re-publish at this epoch.
        screenShareWidth = 0
        screenShareHeight = 0
        bridge.setEventSink(this)
        configureRemoteVideoSink("connect")
        connectedDeferred = CompletableDeferred()
        audioPublishedDeferred = CompletableDeferred()
        videoPublishedDeferred = if (videoPublishRequested) CompletableDeferred() else null
        mediaReadyDeferred = CompletableDeferred()

        updateReadiness(
            state = NativeMediaState.ConnectingLiveKit,
            connected = false,
            audioPublished = false,
            videoPublished = false,
            acceptingAudioFrames = false,
            acceptingVideoFrames = false,
            cameraRequested = videoPublishRequested,
            cameraRequired = videoPolicy == JoinVideoPolicy.VideoRequired,
            cameraState = if (videoPublishRequested) CameraPublishState.Probing else CameraPublishState.NotRequested,
            joinedAsAudioOnlyFallback = false,
            tokenRefreshScheduled = false,
            lastError = null
        )

        // Apply E2EE arming HERE rather than only at request time: the session may
        // not have existed yet when requestE2EE ran, and the native side rejects
        // unknown session ids without storing the flag. Session id is guaranteed
        // valid at this point, and an explicit false clears any stale encrypted-call
        // arming from a previous join.
        run {
            val e2eeApply = bridge.configureE2EE(sessionId, e2eeArmed)
            if (e2eeApply.ok) {
                e2eeArmConfirmed = true
                println("DEBUG [LiveKitNativeMediaSession] e2ee pre-connect armed=$e2eeArmed")
            } else {
                e2eeArmConfirmed = false
                println(
                    "ERROR [LiveKitNativeMediaSession] e2ee pre-connect arm FAILED " +
                        "code=${e2eeApply.error?.code} message=${e2eeApply.error?.message ?: "<none>"}"
                )
            }
        }

        println("DEBUG [LiveKitNativeMediaSession] native connecting")
        val startConnect = bridge.connectAsync(
            sessionId = sessionId,
            epoch = epoch,
            url = tokenResult.value.livekitUrl,
            token = tokenResult.value.token,
            identity = matrixRtcMemberId
        )
        if (!startConnect.ok) {
            val error = startConnect.error ?: unknownError("Native connect failed", "nativeConnectAsync")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        val connected = awaitDeferred(connectedDeferred, CONNECT_TIMEOUT_MS, "connectLiveKit")
        if (!connected.ok) {
            val error = connected.error ?: unknownError("Native connect timed out", "connectLiveKit")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        LiveKitNativeAudioPlayer.start(sessionId, epoch, bridge, ::handlePlaybackFailure)
        println("DEBUG [LiveKitNativeMediaSession] audio publish started")
        updateNativeState(NativeMediaState.PublishingAudio)
        val publishChannels = LiveKitNativeAudioCapture.configuredPublishChannels()
        val queueSizeMs = configuredAudioSourceQueueMs()
        val expectedTestTone = LiveKitNativeAudioCapture.configuredTestTone()
        println("DEBUG [LiveKitNativeMediaSession] audio publish format sampleRate=${LiveKitNativeAudioCapture.SAMPLE_RATE} channels=$publishChannels samplesPerChannel=${LiveKitNativeAudioCapture.SAMPLES_PER_CHANNEL} frameMs=${LiveKitNativeAudioCapture.FRAME_MS} queueSizeMs=$queueSizeMs expectedTestTone=$expectedTestTone audioBitrateKbps=$lastAudioBitrateKbps")
        val audioStarted = bridge.publishAudio(sessionId, epoch, LiveKitNativeAudioCapture.SAMPLE_RATE, publishChannels, queueSizeMs, expectedTestTone, lastAudioBitrateKbps * 1000)
        if (!audioStarted.ok) {
            val error = audioStarted.error ?: unknownError("Native audio publish failed", "nativePublishAudio")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        // The track exists the moment nativePublishAudio returns (it auto-unmutes after
        // publish) — apply the desired mute BEFORE waiting on the ack so a muted join
        // never transmits mic audio during the await window.
        LiveKitNativeAudioCapture.setMuted(desiredAudioMuted)
        val initialMute = bridge.setAudioMuted(sessionId, epoch, desiredAudioMuted)
        if (!initialMute.ok) {
            val error = initialMute.error ?: unknownError("Native mic mute state failed after publish", "nativeSetAudioMuted")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }
        if (desiredAudioMuted) {
            println("DEBUG [LiveKitNativeMediaSession] native mic muted before publish ack")
        } else {
            println("DEBUG [LiveKitNativeMediaSession] native mic unmuted before publish ack")
        }

        val audioPublished = awaitDeferred(audioPublishedDeferred, PUBLISH_TIMEOUT_MS, "publishAudio")
        if (!audioPublished.ok) {
            val error = audioPublished.error ?: unknownError("Native audio publish timed out", "publishAudio")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        val videoOutcome = handleOptionalVideoPublish(videoPolicy, grantCheck.value.videoAllowed)
        if (!videoOutcome.ok) {
            val error = videoOutcome.error ?: unknownError("Native video publish failed", "publishVideo")
            cancelPendingDeferreds(error)
            disconnect(userInitiated = false)
            return NativeResult.failure(error)
        }

        if (!LiveKitNativeAudioCapture.start(sessionId, epoch, bridge, ::handleCaptureFailure)) {
            val error = NativeError(
                code = NativeErrorCode.AUDIO_CAPTURE_FAILED,
                message = "Microphone capture did not start after audio publish",
                operation = "startMicCapture",
                recoverable = false
            )
            cancelPendingDeferreds(error)
            disconnect(userInitiated = false)
            return NativeResult.failure(error)
        }

        scheduleTokenRefresh(tokenResult.value)
        startRemoteVideoRescan()
        updateReadiness(
            state = NativeMediaState.MediaReady,
            connected = true,
            audioPublished = true,
            videoPublished = readinessCache.videoPublished,
            acceptingAudioFrames = LiveKitNativeAudioBridge.acceptingFrames,
            acceptingVideoFrames = readinessCache.acceptingVideoFrames,
            cameraRequested = videoPublishRequested,
            cameraRequired = videoPolicy == JoinVideoPolicy.VideoRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = refreshJob != null,
            lastError = null
        )
        mediaReadyDeferred?.complete(NativeResult.success(Unit))

        val mediaReady = awaitDeferred(mediaReadyDeferred, PUBLISH_TIMEOUT_MS, "mediaReady")
        if (!mediaReady.ok) {
            val error = mediaReady.error ?: unknownError("Native media readiness timed out", "mediaReady")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        clearCompletedDeferreds()
        println("DEBUG [LiveKitNativeMediaSession] native media ready")
        return NativeResult.success(readinessCache)
    }

    /** Read-only peek at the current readiness for join-progress UI (E1). */
    fun currentReadiness(): NativeMediaReadiness? =
        readinessCache.takeIf { it.state != NativeMediaState.Idle }

    suspend fun ensureMediaReady(): NativeResult<NativeMediaReadiness> {
        if (readinessCache.state == NativeMediaState.MediaReady &&
            readinessCache.audioPublished &&
            readinessCache.acceptingAudioFrames
        ) {
            return NativeResult.success(readinessCache)
        }

        if (sessionId == 0L) {
            return NativeResult.failure(unknownError("No native media session exists", "ensureMediaReady"))
        }

        val nativeReadiness = bridge.getReadiness(sessionId)
        val value = nativeReadiness.value
        return if (nativeReadiness.ok && value != null && value.connected && value.audioPublished && value.acceptingAudioFrames) {
            readinessCache = value.copy(state = NativeMediaState.MediaReady)
            NativeResult.success(readinessCache)
        } else {
            NativeResult.failure(nativeReadiness.error ?: unknownError("Native media is not ready", "ensureMediaReady"))
        }
    }

    private fun configuredAudioSourceQueueMs(): Int {
        val raw = System.getProperty("orbit.livekit.audioSourceQueueMs")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        // Default 0 = SDK real-time mode: captureFrame consumes synchronously and
        // the worker's own 10 ms pacing is the only clock. Buffered mode (10) made
        // captureFrame block until the SDK drained in real time, stacking with our
        // pacing into a ~31 ms cycle and dropping ~68% of mic frames.
        val value = raw?.toIntOrNull() ?: 0
        if (value != 0 && value != 10) {
            println("WARN [LiveKitNativeMediaSession] orbit.livekit.audioSourceQueueMs=$value unsupported; using 0")
            return 0
        }
        return value
    }

    fun armScreenSharePublish(fps: Int, maxBitrateBps: Int) {
        // 30, not 60. This is the last of three places the ceiling is applied
        // (settings UI, WebRtcClientImpl, here) so a stale stored value or a
        // direct caller cannot publish faster than the machine can carry. At 60
        // and 1440p this box was converting ~186 MB/s of I420 while the audio
        // mixer ran a 10 ms tick, and the mixer lost: `starved=10 qDepth=3360`
        // became `starved=283 qDepth=480` with playback dropping chunks.
        screenShareFps = fps.coerceIn(1, 30)
        screenShareMaxBitrateBps = maxBitrateBps
        screenShareWidth = 0
        screenShareHeight = 0
        screenShareFirstFrameLogged = false
        screenShareArmed = true
        println("DEBUG [LiveKitNativeMediaSession] screenshare publish armed fps=$screenShareFps maxBitrateBps=$maxBitrateBps")
    }

    fun disarmScreenSharePublish() {
        if (!screenShareArmed && screenShareWidth == 0 && screenShareHeight == 0) return
        screenShareArmed = false
        screenShareWidth = 0
        screenShareHeight = 0
        val result = bridge.unpublishScreenShare(sessionId, epoch)
        if (result.ok) {
            println("DEBUG [LiveKitNativeMediaSession] screenshare unpublished")
        } else {
            println("WARN [LiveKitNativeMediaSession] screenshare unpublish failed: ${result.error?.message}")
        }
    }

    fun publishScreenShareAudio() {
        if (sessionId == 0L) return
        val result = bridge.publishScreenShareAudio(sessionId, epoch)
        if (result.ok) {
            println("DEBUG [LiveKitNativeMediaSession] screenshare audio published (system loopback)")
            applyLoopbackDuckIfNeeded()
        } else {
            println("WARN [LiveKitNativeMediaSession] screenshare audio publish failed: ${result.error?.message}")
        }
    }

    // On Windows builds without process-loopback exclusion the share capture is
    // full-device: our own playback would be re-transmitted in an endless loop.
    // Ducking playback keeps loop gain below unity so echoes decay instead.
    private fun applyLoopbackDuckIfNeeded() {
        // Hold a handle so the poller can be cancelled.
        //
        // This launched into a process-global scope and was never cancellable. If
        // the user stopped sharing inside the 2-second window, `unpublishScreen-
        // ShareAudio` set the duck flag FALSE and then this still-running poller
        // reached its `2 -> setLoopbackDuckEnabled(true)` arm -- leaving all Orbit
        // playback at 30% for the rest of the call, with no UI indication and
        // nothing in the log to explain it.
        loopbackDuckJob?.cancel()
        loopbackDuckJob = scope.launch {
            repeat(20) {
                delay(100)
                when (bridge.shareLoopbackMode()) {
                    2 -> {
                        // Stop sharing while we are still polling? Do not duck.
                        // The loopback mode is the source of truth and it has
                        // already been torn down, so re-enabling would be wrong.
                        if (!screenShareArmed) {
                            println("DEBUG [LiveKitNativeMediaSession] share audio stopped while probing loopback; not ducking")
                            return@launch
                        }
                        LiveKitNativeAudioPlayer.setLoopbackDuckEnabled(true)
                        println("WARN [LiveKitNativeMediaSession] share-audio loopback captured full device; ducking own playback to limit echo (Windows 11 24H2+ enables clean exclusion)")
                        return@launch
                    }
                    1 -> return@launch
                }
            }
        }
    }

    fun unpublishScreenShareAudio() {
        if (sessionId == 0L) return
        // Cancel the probe BEFORE clearing the flag, so it cannot re-assert ducking
        // after this line has turned it off.
        loopbackDuckJob?.cancel()
        loopbackDuckJob = null
        val result = bridge.unpublishScreenShareAudio(sessionId, epoch)
        LiveKitNativeAudioPlayer.setLoopbackDuckEnabled(false)
        ShareAudioAec.reset()
        if (result.ok) {
            println("DEBUG [LiveKitNativeMediaSession] screenshare audio unpublished")
        } else {
            println("WARN [LiveKitNativeMediaSession] screenshare audio unpublish failed: ${result.error?.message}")
        }
    }

    fun pushScreenShareFrame(buffer: java.nio.ByteBuffer, width: Int, height: Int, timestampNs: Long, rotation: Int): Boolean {
        if (!screenShareArmed || width <= 0 || height <= 0) return false
        if (!screenShareFirstFrameLogged) {
            screenShareFirstFrameLogged = true
            println("DEBUG [LiveKitNativeMediaSession] first share frame received ${width}x${height} (armed)")
        }
        // The dimension pair is a read-modify-write shared with the EDT, which
        // can zero both on connect/disconnect. Checked and updated under one lock
        // so a concurrent reset cannot leave the slot stuck "published" or cause
        // a republish loop.
        synchronized(screenShareSlotLock) {
            if (width != screenShareWidth || height != screenShareHeight) {
                if (screenShareWidth != 0) {
                    runCatching { bridge.unpublishScreenShare(sessionId, epoch) }
                }
                val publish = bridge.publishScreenShare(sessionId, epoch, width, height, screenShareFps, screenShareMaxBitrateBps)
                if (!publish.ok) {
                    println("ERROR [LiveKitNativeMediaSession] screenshare publish failed: ${publish.error?.message}")
                    return false
                }
                screenShareWidth = width
                screenShareHeight = height
                println("DEBUG [LiveKitNativeMediaSession] screenshare publishing ${width}x${height} fps=$screenShareFps")
            }
        }
        val chromaStride = (width + 1) / 2
        val push = bridge.pushScreenShareFrame(sessionId, epoch, buffer, width, height, 5, width, chromaStride, chromaStride, timestampNs, rotation)
        if (!push.ok) {
            // Force republish with fresh dimensions on the next frame (e.g. window resized).
            synchronized(screenShareSlotLock) {
                screenShareWidth = 0
                screenShareHeight = 0
            }
        }
        return push.ok
    }

    /**
     * Releases the native session for good, so a new account starts clean.
     *
     * [disconnect] ends the call and stops the local devices, but the native
     * session object (and its E2EE key map and per-participant gains) outlives
     * it. On logout nothing released that: the OS recording indicator could stay
     * on and the next login inherited the previous account's native state.
     */
    fun releaseNativeSession() {
        // Captured before disconnect(), which now clears the id: this is the
        // native session that needs destroying, and reading sessionId afterwards
        // would always yield 0 and leak the session on logout -- exactly the
        // "next login inherits the previous account's native state" problem this
        // function exists to prevent.
        val sessionToDestroy = sessionId
        disconnect(userInitiated = true)
        if (sessionToDestroy != 0L) {
            runCatching { bridge.destroySession(sessionToDestroy) }
                .onFailure { println("WARN [LiveKitNativeMediaSession] destroySession failed: ${it.message}") }
            sessionId = 0L
        }
    }

    fun disconnect(userInitiated: Boolean) {
        // Any explicit teardown — user leave OR owner give-up — kills auto-resume;
        // otherwise a give-up disconnect could race the owner with a token-refresh
        // reconnect (H1).
        reconnectAllowed = false
        reconnectAttempts.set(0)
        if (userInitiated) {
            desiredAudioMuted = false
        }
        refreshJob?.cancel()
        refreshJob = null
        remoteVideoRescanJob?.cancel()
        remoteVideoRescanJob = null
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
        cameraPushEnabled = false
        publishedCameraWidth = 0
        publishedCameraHeight = 0
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        runCatching { LiveKitNativeAudioCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioPlayer.stop() }
        disableRemoteVideoSink("disconnect")
        screenShareArmed = false
        screenShareWidth = 0
        screenShareHeight = 0
        if (sessionId != 0L) {
            bridge.disconnect(sessionId, epoch, userInitiated)
        }

        // The native session does not survive the call that used it.
        //
        // Reported as "disconnect from Orbit, connect again, and the app is
        // stuck". The rejoin walked the whole connect path and then sat there:
        //
        //     microphone self-test selected mixer=... allInputsTooLow=false
        //     ERROR [CallManager] Group-call join stalled at Joined
        //
        // with mic.read=0 for the rest of the run. The self-test had already
        // succeeded, so the stall was downstream of it, and the next statement is
        // this:
        //
        //     if (sessionId == 0L) { val created = bridge.createSession() ... }
        //
        // On a rejoin sessionId was still the previous call's id, so the session
        // was never recreated. `native session created:` never printed -- not once,
        // in any run -- which is the proof. Orbit then connected against a session
        // whose room and delegate had already been torn down, and every later
        // guard in this file (`if (sessionId == 0L) return`) passed against a dead
        // session instead of failing loudly.
        //
        // Only releaseNativeSession() used to clear this, and that runs on logout.
        // disconnect() ends the call but left the id in place, so every rejoin in
        // a session reused a dead session.
        //
        // Clearing it here means the next join takes the createSession() path and
        // gets a live session with a live room. The native side keeps its own
        // generation counter, so a fresh id cannot be confused with the old one.
        sessionId = 0L

        cancelPendingDeferreds(
            NativeError(
                code = if (userInitiated) NativeErrorCode.USER_LEFT else NativeErrorCode.DISCONNECTED,
                message = if (userInitiated) "User left the call" else "Native media disconnected",
                operation = "nativeDisconnect",
                recoverable = !userInitiated
            )
        )
        updateReadiness(
            state = NativeMediaState.Disconnected,
            connected = false,
            audioPublished = false,
            videoPublished = false,
            acceptingAudioFrames = false,
            acceptingVideoFrames = false,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = false,
            lastError = null
        )
    }

    fun setAudioMuted(isMuted: Boolean): NativeResult<Unit> {
        desiredAudioMuted = isMuted
        LiveKitNativeAudioCapture.setMuted(isMuted)
        if (sessionId == 0L) {
            return NativeResult.failure(unknownError("No native media session exists", "nativeSetAudioMuted"))
        }
        return bridge.setAudioMuted(sessionId, epoch, isMuted)
    }

    /**
     * Arms/disarms the native E2EE media path for the NEXT connect. Safe to call
     * with no live session: nativeConfigureE2EE only flips a module-level flag that
     * ConnectAsync consumes when building RoomOptions.
     */
    fun requestE2EE(enabled: Boolean) {
        if (!enabled && !e2eeArmed) return
        e2eeArmed = enabled
        val result = bridge.configureE2EE(sessionId, enabled)
        if (result.ok) {
            e2eeArmConfirmed = true
            println(
                "DEBUG [LiveKitNativeMediaSession] e2ee media armed=$enabled session=$sessionId " +
                    "(live=${sessionId != 0L})"
            )
        } else {
            // Previously this only logged a warning and the caller went on to
            // print "native E2EE media armed" anyway, so a failed arming looked
            // like a successful one in the log while the media went out
            // unencrypted in an encrypted room.
            e2eeArmConfirmed = false
            println(
                "ERROR [LiveKitNativeMediaSession] configureE2EE($enabled) FAILED " +
                    "code=${result.error?.code} message=${result.error?.message ?: "<no message>"}; " +
                    "media will NOT be encrypted for this call"
            )
        }
    }

    /**
     * Whether native E2EE arming is actually in effect.
     *
     * Read by [isNativeE2eeArmed] so the UI can refuse to present a call as
     * secure when the native side did not accept the configuration.
     */
    @Volatile private var e2eeArmConfirmed = false

    fun isNativeE2eeArmed(): Boolean = e2eeArmed && e2eeArmConfirmed

    /** Feeds a per-participant media key into the native KeyProvider slot [identity]. */
    fun deliverParticipantKey(identity: String, key: ByteArray, keyIndex: Int): Boolean {
        if (sessionId == 0L || identity.isBlank() || key.size != CallMediaKeys.KEY_BYTES) return false
        val result = bridge.setParticipantMediaKey(sessionId, identity, key, keyIndex)
        if (!result.ok) {
            println("WARN [LiveKitNativeMediaSession] setParticipantMediaKey($identity, idx=$keyIndex) failed: ${result.error?.message}")
        }
        return result.ok
    }

    override fun onNativeEvent(event: NativeMediaEvent) {
        if (event.sessionId != sessionId || event.epoch != epoch) {
            println("DEBUG [LiveKitNativeMediaSession] Dropping stale native event: $event")
            return
        }

        when (event) {
            is NativeMediaEvent.Connecting -> {
                updateNativeState(NativeMediaState.ConnectingLiveKit)
            }

            is NativeMediaEvent.Connected -> {
                println("DEBUG [LiveKitNativeMediaSession] native connected")
                CallMediaKeyExchangeManager.rearmOwnKey()
                // Atomic derive, not read-then-write: this runs on the native
                // dispatch thread and races the reconnect coroutine, which can
                // replace the readiness entirely between a field read and the
                // store.
                mutateReadiness { old ->
                    old.copy(
                        state = NativeMediaState.LiveKitConnected,
                        connected = true,
                        audioPublished = false,
                        videoPublished = false,
                        acceptingAudioFrames = false,
                        acceptingVideoFrames = false,
                        lastError = null
                    )
                }
                connectedDeferred?.complete(NativeResult.success(Unit))
            }

            is NativeMediaEvent.ConnectFailed -> {
                failConnect(event.error)
            }

            is NativeMediaEvent.AuthFailure -> {
                handleAuthFailure(event.error)
            }

            is NativeMediaEvent.AudioPublished -> {
                println("DEBUG [LiveKitNativeMediaSession] audio published")
                CallMediaKeyExchangeManager.rearmOwnKey()
                mutateReadiness { old ->
                    old.copy(
                        state = NativeMediaState.AudioPublished,
                        connected = true,
                        audioPublished = true,
                        videoPublished = false,
                        acceptingAudioFrames = LiveKitNativeAudioBridge.acceptingFrames,
                        acceptingVideoFrames = false,
                        lastError = null
                    )
                }
                audioPublishedDeferred?.complete(NativeResult.success(Unit))
            }

            is NativeMediaEvent.VideoPublished -> {
                println("DEBUG [LiveKitNativeMediaSession] video published")
                mutateReadiness { old ->
                    old.copy(
                        state = NativeMediaState.VideoPublished,
                        connected = true,
                        videoPublished = true,
                        acceptingVideoFrames = true,
                        cameraRequested = true,
                        cameraState = CameraPublishState.Published,
                        joinedAsAudioOnlyFallback = false,
                        lastError = null
                    )
                }
                videoPublishedDeferred?.complete(NativeResult.success(Unit))
            }

            is NativeMediaEvent.MediaReady -> {
                updateNativeState(NativeMediaState.MediaReady)
                mediaReadyDeferred?.complete(NativeResult.success(Unit))
            }

            is NativeMediaEvent.TokenRefreshRequired -> {
                scheduleTokenRefreshNow()
            }

            is NativeMediaEvent.Disconnected -> {
                _activeSpeakers.value = emptySet()
                handleNativeDisconnected(event.reason, event.recoverable)
            }

            is NativeMediaEvent.Error -> {
                handleNativeError(event.error)
            }

            is NativeMediaEvent.ActiveSpeakersChanged -> {
                // Transition-only logging. The meter is throttled to 150 ms and
                // change-only natively, so a per-event line is a per-frame-class
                // line; worse, with NO line at all here, a ring that never lit up
                // was indistinguishable from "the native event never fires".
                // Logging the transitions makes the feed observable and shows the
                // exact token shape the mixer is publishing.
                val previous = _activeSpeakers.value
                val speakers = event.speakers.toSet()
                _activeSpeakers.value = speakers
                if (previous != speakers) {
                    util.OrbitLog.d(
                        "LiveKitNativeMediaSession",
                        "active speakers ${if (speakers.isEmpty()) "cleared" else speakers.toString()}"
                    )
                }
            }
        }
    }

    private suspend fun handleOptionalVideoPublish(videoPolicy: JoinVideoPolicy, videoAllowed: Boolean): NativeResult<Unit> {
        val videoRequested = shouldPublishVideo()
        val videoRequired = videoPolicy == JoinVideoPolicy.VideoRequired
        if (!videoRequested) {
            updateCameraFallback(CameraPublishState.NotRequested, joinedAsAudioOnlyFallback = false)
            return NativeResult.success(Unit)
        }

        println("DEBUG [LiveKitNativeMediaSession] camera probe started")
        if (!videoAllowed) {
            updateCameraFallback(CameraPublishState.Unavailable, joinedAsAudioOnlyFallback = !videoRequired)
            return if (!videoRequired) {
                println("DEBUG [LiveKitNativeMediaSession] video publish skipped; joined audio-only fallback")
                NativeResult.success(Unit)
            } else {
                NativeResult.failure(
                    NativeError(
                        code = NativeErrorCode.PUBLISH_VIDEO_FAILED,
                        message = "Camera publishing is not allowed by LiveKit grants",
                        operation = "publishVideo",
                        recoverable = false
                    )
                )
            }
        }

        updateReadiness(
            state = NativeMediaState.PublishingVideo,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = false,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = false,
            cameraRequested = true,
            cameraRequired = videoRequired,
            cameraState = CameraPublishState.Publishing,
            joinedAsAudioOnlyFallback = false,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = null
        )

        val opened = LiveKitNativeVideoCapture.openForPublish()
        val config = opened.value
        if (!opened.ok || config == null) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(cameraStateFromVideoError(opened.error), joinedAsAudioOnlyFallback = !videoRequired)
            return if (!videoRequired) {
                println("DEBUG [LiveKitNativeMediaSession] camera open failed; joined audio-only fallback: ${opened.error?.message}")
                NativeResult.success(Unit)
            } else {
                NativeResult.failure(opened.error ?: unknownError("Camera capture failed to open", "openVideoCapture"))
            }
        }

        if (videoPublishedDeferred == null) {
            videoPublishedDeferred = CompletableDeferred()
        }

        val videoMaxBitrateBps = System.getProperty("orbit.video.maxBitrateKbps")
            ?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0
        println(
            "DEBUG [LiveKitNativeMediaSession] video publish started " +
                "width=${config.width} height=${config.height} fps=${config.fps} format=${config.format} " +
                "videoMaxBitrateBps=$videoMaxBitrateBps"
        )
        val publish = bridge.publishVideo(sessionId, epoch, config.width, config.height, config.fps, config.format, videoMaxBitrateBps)
        if (!publish.ok) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = !videoRequired)
            return if (!videoRequired) {
                println("DEBUG [LiveKitNativeMediaSession] native video publish failed; keeping audio-only: ${publish.error?.message}")
                NativeResult.success(Unit)
            } else {
                NativeResult.failure(publish.error ?: unknownError("Native video publish failed", "nativePublishVideo"))
            }
        }
        publishedCameraWidth = config.width
        publishedCameraHeight = config.height
        publishedCameraFps = config.fps

        val videoPublished = awaitDeferred(videoPublishedDeferred, PUBLISH_TIMEOUT_MS, "publishVideo")
        if (!videoPublished.ok) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = !videoRequired)
            return if (!videoRequired) {
                println("DEBUG [LiveKitNativeMediaSession] native video publish event timed out; keeping audio-only")
                NativeResult.success(Unit)
            } else {
                NativeResult.failure(videoPublished.error ?: unknownError("Native video publish timed out", "publishVideo"))
            }
        }

        // Arm before attaching the push target: capture frames are already flowing,
        // and the first-frame dimension callback must not be dropped in the gap.
        cameraPushEnabled = true
        val framePush = LiveKitNativeVideoCapture.enableNativePush(
            sessionId, epoch, bridge, ::handleVideoCaptureFailure, ::handleActualCameraDimensions
        )
        if (!framePush.ok) {
            cameraPushEnabled = false
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = !videoRequired)
            return if (!videoRequired) {
                println("DEBUG [LiveKitNativeMediaSession] video frame push failed to start; keeping audio-only: ${framePush.error?.message}")
                NativeResult.success(Unit)
            } else {
                NativeResult.failure(framePush.error ?: unknownError("Video frame push failed to start", "enableVideoFramePush"))
            }
        }

        return NativeResult.success(Unit)
    }

    // Camera drivers sometimes ignore the requested capture capability and deliver
    // their own mode. The native video source rejects mismatched frames outright,
    // so republish the track with whatever dimensions actually arrive (republish is
    // safe: the C++ side unpublishes the previous camera track first).
    private fun handleActualCameraDimensions(width: Int, height: Int) {
        if (!cameraPushEnabled) return
        if (width == publishedCameraWidth && height == publishedCameraHeight) return
        println(
            "WARN [LiveKitNativeMediaSession] camera delivers ${width}x${height} but track was published " +
                "${publishedCameraWidth}x${publishedCameraHeight}; republishing with actual dimensions"
        )
        val videoMaxBitrateBps = System.getProperty("orbit.video.maxBitrateKbps")
            ?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0
        val result = bridge.publishVideo(sessionId, epoch, width, height, publishedCameraFps, 5, videoMaxBitrateBps)
        if (!result.ok) {
            println("ERROR [LiveKitNativeMediaSession] camera dimension-mismatch republish failed: ${result.error?.code} ${result.error?.message}")
            return
        }
        publishedCameraWidth = width
        publishedCameraHeight = height
        println("DEBUG [LiveKitNativeMediaSession] camera republished at ${width}x${height} fps=$publishedCameraFps")
    }

    // BUG-A fix: mid-call camera publish for the native group path. Joins are
    // audio-only by default; the UI "Turn On Camera" toggle routes here instead
    // of the P2P engine (which has no sender in group mode). Republish-safe: the
    // C++ side unpublishes any previous camera track before creating a new one.
    //
    // withContext(Dispatchers.IO): the body enumerates capture devices, creates
    // a PeerConnectionFactory and starts the source -- hundreds of ms. The
    // caller is CallManager on rememberCoroutineScope(), i.e. the EDT, so the
    // enumeration the connect path was fixed to offload was still freezing the
    // UI the moment a user turned their camera on.
    suspend fun enableCamera(): NativeResult<Unit> = withContext(Dispatchers.IO) {
        cameraToggleMutex.withLock {
        if (sessionId == 0L || !readinessCache.connected) {
            return@withContext NativeResult.failure(unknownError("No connected native media session", "enableCamera"))
        }
        if (!LiveKitFeatureFlags.videoPublish) {
            return@withContext NativeResult.failure(unknownError("Video publishing is disabled", "enableCamera"))
        }
        if (!lastVideoAllowed) {
            return@withContext NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.PUBLISH_VIDEO_FAILED,
                    message = "Camera publishing is not allowed by LiveKit grants",
                    operation = "publishVideo",
                    recoverable = false
                )
            )
        }
        if (readinessCache.videoPublished && cameraPushEnabled) {
            return@withContext NativeResult.success(Unit)
        }

        println("DEBUG [LiveKitNativeMediaSession] mid-call camera enable started")
        updateReadiness(
            state = readinessCache.state,
            connected = true,
            audioPublished = readinessCache.audioPublished,
            videoPublished = false,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = false,
            cameraRequested = true,
            cameraRequired = false,
            cameraState = CameraPublishState.Publishing,
            joinedAsAudioOnlyFallback = false,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = null
        )

        val opened = LiveKitNativeVideoCapture.openForPublish()
        val config = opened.value
        if (!opened.ok || config == null) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(cameraStateFromVideoError(opened.error), joinedAsAudioOnlyFallback = false)
            return@withContext NativeResult.failure(opened.error ?: unknownError("Camera capture failed to open", "openVideoCapture"))
        }

        videoPublishedDeferred = CompletableDeferred()

        val videoMaxBitrateBps = System.getProperty("orbit.video.maxBitrateKbps")
            ?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0
        println(
            "DEBUG [LiveKitNativeMediaSession] mid-call camera publish " +
                "width=${config.width} height=${config.height} fps=${config.fps} format=${config.format} " +
                "videoMaxBitrateBps=$videoMaxBitrateBps"
        )
        val publish = bridge.publishVideo(sessionId, epoch, config.width, config.height, config.fps, config.format, videoMaxBitrateBps)
        if (!publish.ok) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = false)
            return@withContext NativeResult.failure(publish.error ?: unknownError("Native video publish failed", "nativePublishVideo"))
        }
        publishedCameraWidth = config.width
        publishedCameraHeight = config.height
        publishedCameraFps = config.fps

        val videoPublished = awaitDeferred(videoPublishedDeferred, PUBLISH_TIMEOUT_MS, "enableCamera")
        if (!videoPublished.ok) {
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = false)
            return@withContext NativeResult.failure(videoPublished.error ?: unknownError("Native video publish timed out", "enableCamera"))
        }

        // Arm before attaching the push target (see handleOptionalVideoPublish).
        cameraPushEnabled = true
        val framePush = LiveKitNativeVideoCapture.enableNativePush(
            sessionId, epoch, bridge, ::handleVideoCaptureFailure, ::handleActualCameraDimensions
        )
        if (!framePush.ok) {
            cameraPushEnabled = false
            runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
            updateCameraFallback(CameraPublishState.Failed, joinedAsAudioOnlyFallback = false)
            return@withContext NativeResult.failure(framePush.error ?: unknownError("Video frame push failed to start", "enableVideoFramePush"))
        }

        // disableCamera() mutes the published track; a fresh republish creates an
        // unmuted track but be explicit in case the old one survived.
        bridge.setVideoMuted(sessionId, epoch, false)
        println("DEBUG [LiveKitNativeMediaSession] mid-call camera enabled ${config.width}x${config.height} fps=${config.fps}")
        NativeResult.success(Unit)
        }
    }

    fun disableCamera() {
        if (sessionId == 0L || !cameraPushEnabled) return
        bridge.setVideoMuted(sessionId, epoch, true)
        cameraPushEnabled = false
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
        updateReadiness(
            state = readinessCache.state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = false,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = false,
            cameraRequested = true,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = CameraPublishState.NotRequested,
            joinedAsAudioOnlyFallback = false,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = null
        )
        println("DEBUG [LiveKitNativeMediaSession] camera disabled (track muted, capture stopped)")
    }

    private fun updateCameraFallback(cameraState: CameraPublishState, joinedAsAudioOnlyFallback: Boolean) {
        updateReadiness(
            state = readinessCache.state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = false,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = false,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = cameraState,
            joinedAsAudioOnlyFallback = joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = readinessCache.lastError
        )
    }

    private fun shouldPublishVideo(): Boolean =
        LiveKitFeatureFlags.videoPublish && lastVideoPolicy != JoinVideoPolicy.AudioOnly

    private fun startRemoteVideoRescan() {
        remoteVideoRescanJob?.cancel()
        remoteVideoRescanJob = scope.launch {
            while (isActive) {
                delay(REMOTE_VIDEO_RESCAN_INTERVAL_MS)
                if (sessionId == 0L || !readinessCache.connected) break
                // Result intentionally ignored: stale-epoch during a reconnect window
                // self-heals on the next tick; terminal teardown cancels this job.
                bridge.rescanRemoteVideoTracks(sessionId, epoch)
            }
        }
    }

    private fun configureRemoteVideoSink(reason: String) {
        if (!LiveKitFeatureFlags.videoRemoteRender) {
            disableRemoteVideoSink("$reason-disabled")
            return
        }

        LiveKitRemoteVideoFrameStore.clear("$reason-start")
        LiveKitRemoteVideoFrameStore.beginSession(sessionId, epoch)
        val result = bridge.setRemoteVideoSink(LiveKitNativeRemoteVideoRenderer)
        if (result.ok) {
            println("DEBUG [LiveKitNativeMediaSession] remote video render sink enabled reason=$reason")
        } else {
            println(
                "WARN [LiveKitNativeMediaSession] remote video render sink failed reason=$reason " +
                    "message=${result.error?.message}"
            )
            LiveKitRemoteVideoFrameStore.clear("$reason-sink-failed")
        }
    }

    private fun disableRemoteVideoSink(reason: String) {
        val result = bridge.setRemoteVideoSink(null)
        if (!result.ok) {
            println(
                "WARN [LiveKitNativeMediaSession] remote video render sink disable failed " +
                    "reason=$reason message=${result.error?.message}"
            )
        }
        LiveKitRemoteVideoFrameStore.clear(reason)
    }

    private fun cameraStateFromVideoError(error: NativeError?): CameraPublishState =
        when (error?.code) {
            NativeErrorCode.CAMERA_PERMISSION_DENIED -> CameraPublishState.PermissionDenied
            NativeErrorCode.CAMERA_DEVICE_BUSY -> CameraPublishState.DeviceBusy
            NativeErrorCode.CAMERA_DRIVER_FAILURE -> CameraPublishState.DriverFailure
            NativeErrorCode.CAMERA_CAPABILITY_UNAVAILABLE,
            NativeErrorCode.CAMERA_MODE_REJECTED -> CameraPublishState.FormatRejected
            NativeErrorCode.VIDEO_DEVICE_UNAVAILABLE -> CameraPublishState.Unavailable
            else -> CameraPublishState.Failed
        }

    private fun scheduleTokenRefresh(tokenResult: LiveKitTokenResult) {
        refreshJob?.cancel()
        val fetcher = lastRefreshTokenFetcher
        if (fetcher == null) {
            println("DEBUG [LiveKitNativeMediaSession] token refresh not scheduled; no refresh fetcher")
            return
        }

        val nowSeconds = kotlin.time.Clock.System.now().epochSeconds
        val delayMs = computeTokenRefreshDelayMillis(tokenResult.expiresAtEpochSeconds, nowSeconds)
        println("DEBUG [LiveKitNativeMediaSession] token refresh scheduled in ${delayMs}ms")
        refreshJob = scope.launch {
            delay(delayMs)
            refreshAndReconnect(fetcher)
        }
        updateReadiness(
            state = readinessCache.state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = readinessCache.videoPublished,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = readinessCache.acceptingVideoFrames,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = true,
            lastError = readinessCache.lastError
        )
    }

    private fun scheduleTokenRefreshNow() {
        val fetcher = lastRefreshTokenFetcher ?: return
        refreshJob?.cancel()
        refreshJob = scope.launch {
            refreshAndReconnect(fetcher)
        }
    }

    /**
     * Rebuild the SFU session without leaving the call.
     *
     * Used for a remote screen share whose track the SDK never produced. Retrying
     * the subscription does not help -- verified across three runs, the audio track
     * of the same share recovers and the video track never does -- but a fresh
     * connection picks the same still-running share up with its track attached,
     * because the track object is built during connection setup rather than
     * publication.
     *
     * Goes through the existing token-refresh reconnect rather than a new path, so
     * it inherits the same epoch handling, republish and readiness bookkeeping that
     * a genuine reconnect already uses. The Matrix membership is untouched, so the
     * user stays in the call; the cost is that everyone's audio from us restarts,
     * which is why the UI offers this rather than doing it silently at speed.
     */
    fun reconnectForStuckShare(reason: String) {
        if (sessionId == 0L || !readinessCache.connected) {
            println("DEBUG [LiveKitNativeMediaSession] stuck-share reconnect skipped reason=$reason notConnected")
            return
        }
        if (!reconnectAllowed) {
            println("WARN [LiveKitNativeMediaSession] stuck-share reconnect refused reason=$reason reconnectNotAllowed")
            return
        }
        println("DEBUG [LiveKitNativeMediaSession] stuck-share reconnect requested reason=$reason")
        LiveKitRemoteVideoFrameStore.clearStuckTracks()
        scheduleTokenRefreshNow()
    }

    private suspend fun refreshAndReconnect(fetcher: suspend () -> LiveKitAuthResponse) {
        if (!reconnectAllowed) return

        val attempts = reconnectAttempts.incrementAndGet()
        if (attempts > MAX_CONSECUTIVE_RECONNECTS) {
            println("ERROR [LiveKitNativeMediaSession] giving up after $attempts consecutive reconnect attempts")
            reconnectAllowed = false
            handleNativeError(
                NativeError(
                    code = NativeErrorCode.DISCONNECTED,
                    message = "Connection lost — reconnection attempts exhausted",
                    operation = "refreshAndReconnect",
                    recoverable = false
                )
            )
            return
        }

        println("DEBUG [LiveKitNativeMediaSession] token refresh started (attempt $attempts)")
        println("DEBUG [LiveKitNativeMediaSession] mute state before reconnect: $desiredAudioMuted")
        updateNativeState(NativeMediaState.Reconnecting)
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
        cameraPushEnabled = false
        publishedCameraWidth = 0
        publishedCameraHeight = 0
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        runCatching { LiveKitNativeAudioCapture.stopAndJoin() }

        val refreshedAuth = runCatching { fetcher() }.getOrElse { error ->
            handleNativeError(
                NativeError(
                    code = NativeErrorCode.TOKEN_REFRESH_FAILED,
                    message = error.message ?: "Token refresh failed",
                    operation = "refreshLiveKitToken",
                    recoverable = true
                )
            )
            return
        }

        val tokenResult = refreshedAuth.toTokenResult()
        if (!tokenResult.ok || tokenResult.value == null) {
            handleNativeError(tokenResult.error ?: unknownError("Token refresh decode failed", "refreshLiveKitToken"))
            return
        }

        val grantCheck = validateLiveKitGrants(tokenResult.value, lastVideoPolicy)
        if (!grantCheck.ok || grantCheck.value == null) {
            handleNativeError(grantCheck.error ?: unknownError("Token refresh grant validation failed", "validateLiveKitGrants"))
            return
        }
        lastVideoAllowed = grantCheck.value.videoAllowed

        nextEpoch()
        configureRemoteVideoSink("reconnect")
        connectedDeferred = CompletableDeferred()
        audioPublishedDeferred = CompletableDeferred()
        videoPublishedDeferred = if (shouldPublishVideo()) CompletableDeferred() else null
        mediaReadyDeferred = CompletableDeferred()

        println("DEBUG [LiveKitNativeMediaSession] native reconnect started")
        val reconnect = bridge.reconnectAsync(sessionId, epoch, tokenResult.value.livekitUrl, tokenResult.value.token)
        if (!reconnect.ok) {
            handleNativeError(reconnect.error ?: unknownError("Native reconnect failed", "nativeReconnectAsync"))
            return
        }
        val connected = awaitDeferred(connectedDeferred, RECONNECT_TIMEOUT_MS, "nativeReconnect")
        if (!connected.ok) {
            handleNativeError(connected.error ?: unknownError("Native reconnect timed out", "nativeReconnect"))
            return
        }
        println("DEBUG [LiveKitNativeMediaSession] native reconnect success")
        LiveKitNativeAudioPlayer.start(sessionId, epoch, bridge, ::handlePlaybackFailure)

        val publishChannels = LiveKitNativeAudioCapture.configuredPublishChannels()
        val queueSizeMs = configuredAudioSourceQueueMs()
        val expectedTestTone = LiveKitNativeAudioCapture.configuredTestTone()
        println("DEBUG [LiveKitNativeMediaSession] audio republish format sampleRate=${LiveKitNativeAudioCapture.SAMPLE_RATE} channels=$publishChannels samplesPerChannel=${LiveKitNativeAudioCapture.SAMPLES_PER_CHANNEL} frameMs=${LiveKitNativeAudioCapture.FRAME_MS} queueSizeMs=$queueSizeMs expectedTestTone=$expectedTestTone audioBitrateKbps=$lastAudioBitrateKbps")
        val audioStarted = bridge.publishAudio(sessionId, epoch, LiveKitNativeAudioCapture.SAMPLE_RATE, publishChannels, queueSizeMs, expectedTestTone, lastAudioBitrateKbps * 1000)
        if (!audioStarted.ok) {
            handleNativeError(audioStarted.error ?: unknownError("Audio republish failed", "nativePublishAudio"))
            return
        }
        // Track exists now (publish auto-unmutes) — restore the desired mute before the
        // ack wait so a muted user never leaks mic audio across a reconnect.
        val muteRestored = bridge.setAudioMuted(sessionId, epoch, desiredAudioMuted)
        if (!muteRestored.ok) {
            handleNativeError(muteRestored.error ?: unknownError("Native mute restore failed", "nativeSetAudioMuted"))
            return
        }
        if (desiredAudioMuted) {
            println("DEBUG [LiveKitNativeMediaSession] native mute reapplied before republish ack")
        } else {
            println("DEBUG [LiveKitNativeMediaSession] native mute remains false after reconnect")
        }
        val audioPublished = awaitDeferred(audioPublishedDeferred, PUBLISH_TIMEOUT_MS, "republishAudio")
        if (!audioPublished.ok) {
            handleNativeError(audioPublished.error ?: unknownError("Audio republish timed out", "republishAudio"))
            return
        }
        println("DEBUG [LiveKitNativeMediaSession] audio republished")
        val videoOutcome = handleOptionalVideoPublish(lastVideoPolicy, grantCheck.value.videoAllowed)
        if (!videoOutcome.ok) {
            handleNativeError(videoOutcome.error ?: unknownError("Video republish failed", "republishVideo"))
            return
        }
        if (!LiveKitNativeAudioCapture.start(sessionId, epoch, bridge, ::handleCaptureFailure)) {
            val error = NativeError(
                code = NativeErrorCode.AUDIO_CAPTURE_FAILED,
                message = "Microphone capture did not restart after audio republish",
                operation = "restartMicCapture",
                recoverable = false
            )
            // Tear down first, THEN surface Failed: disconnect() overwrites readiness
            // with Disconnected/lastError=null, and the CallManager watchdog only reacts
            // to Failed — the reverse order would mask this as a zombie InCall.
            disconnect(userInitiated = false)
            updateReadinessWithError(NativeMediaState.Failed, error)
            return
        }
        updateReadiness(
            state = NativeMediaState.MediaReady,
            connected = true,
            audioPublished = true,
            videoPublished = readinessCache.videoPublished,
            acceptingAudioFrames = LiveKitNativeAudioBridge.acceptingFrames,
            acceptingVideoFrames = readinessCache.acceptingVideoFrames,
            cameraRequested = shouldPublishVideo(),
            cameraRequired = lastVideoPolicy == JoinVideoPolicy.VideoRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = refreshJob != null,
            lastError = null
        )
        mediaReadyDeferred?.complete(NativeResult.success(Unit))
        scheduleTokenRefresh(tokenResult.value)
        startRemoteVideoRescan()
        clearCompletedDeferreds()
        reconnectAttempts.set(0)
        runCatching { onReconnected?.invoke() }
            .onFailure { println("WARN [LiveKitNativeMediaSession] onReconnected listener failed: ${it.message}") }
    }

    private suspend fun awaitDeferred(
        deferred: CompletableDeferred<NativeResult<Unit>>?,
        timeoutMs: Long,
        operation: String
    ): NativeResult<Unit> {
        val wrapped = awaitNativeResult(timeoutMs, operation) {
            deferred?.await() ?: NativeResult.failure(unknownError("Missing deferred for $operation", operation))
        }
        return if (!wrapped.ok) {
            NativeResult.failure(wrapped.error ?: unknownError("$operation failed", operation))
        } else {
            wrapped.value ?: NativeResult.failure(unknownError("$operation produced no result", operation))
        }
    }

    private fun updateNativeState(state: NativeMediaState) {
        updateReadiness(
            state = state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = readinessCache.videoPublished,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = readinessCache.acceptingVideoFrames,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = readinessCache.lastError
        )
    }

    private fun updateReadiness(
        state: NativeMediaState,
        connected: Boolean,
        audioPublished: Boolean,
        videoPublished: Boolean,
        acceptingAudioFrames: Boolean,
        acceptingVideoFrames: Boolean,
        cameraRequested: Boolean,
        cameraRequired: Boolean,
        cameraState: CameraPublishState,
        joinedAsAudioOnlyFallback: Boolean,
        tokenRefreshScheduled: Boolean,
        lastError: NativeError?
    ) {
        // Store under the lock. The sessionId/epoch arguments are read here, so
        // without it they could be torn against each other AND against the
        // readiness object they are being stamped onto.
        synchronized(readinessLock) {
            readinessCache = NativeMediaReadiness(
                sessionId = sessionId,
                epoch = epoch,
                state = state,
                connected = connected,
                audioPublished = audioPublished,
                videoPublished = videoPublished,
                acceptingAudioFrames = acceptingAudioFrames,
                acceptingVideoFrames = acceptingVideoFrames,
                cameraRequested = cameraRequested,
                cameraRequired = cameraRequired,
                cameraState = cameraState,
                joinedAsAudioOnlyFallback = joinedAsAudioOnlyFallback,
                tokenRefreshScheduled = tokenRefreshScheduled,
                lastError = lastError
            )
        }
    }

    private fun clearCompletedDeferreds() {
        connectedDeferred = null
        audioPublishedDeferred = null
        videoPublishedDeferred = null
        mediaReadyDeferred = null
    }

    private fun cancelPendingDeferreds(error: NativeError) {
        val reason = CancellationException(error.message)
        connectedDeferred?.cancel(reason)
        audioPublishedDeferred?.cancel(reason)
        videoPublishedDeferred?.cancel(reason)
        mediaReadyDeferred?.cancel(reason)
        clearCompletedDeferreds()
    }

    private fun failConnect(error: NativeError) {
        connectedDeferred?.complete(NativeResult.failure(error))
        cancelPendingDeferreds(error)
        updateReadinessWithError(NativeMediaState.Failed, error)
    }

    private fun handleAuthFailure(error: NativeError) {
        updateReadinessWithError(NativeMediaState.RefreshingToken, error)
        scheduleTokenRefreshNow()
    }

    private fun handleNativeDisconnected(reason: String, recoverable: Boolean) {
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        runCatching { LiveKitNativeAudioCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioPlayer.stop() }
        disableRemoteVideoSink("native-disconnected")
        val error = NativeError(
            code = if (recoverable) NativeErrorCode.DISCONNECTED else NativeErrorCode.USER_LEFT,
            message = reason,
            operation = "nativeDisconnected",
            recoverable = recoverable
        )
        if (recoverable && reconnectAllowed) {
            // Surface Reconnecting (not Disconnected) so UI shows the resume chip.
            updateReadinessWithError(NativeMediaState.Reconnecting, error)
            scheduleTokenRefreshNow()
        } else {
            updateReadinessWithError(NativeMediaState.Disconnected, error)
            cancelPendingDeferreds(error)
        }
    }

    private fun handleNativeError(error: NativeError) {
        updateReadinessWithError(NativeMediaState.Failed, error)
        cancelPendingDeferreds(error)
    }

    private fun handleCaptureFailure(error: NativeError) {
        if (error.code == NativeErrorCode.MIC_INPUT_TOO_LOW) {
            // Near-silence is not a capture failure: OS mic level/mute or the user
            // simply not talking yet. Failing the session ejected people from calls.
            println("WARN [LiveKitNativeMediaSession] mic input near-silent (check OS mic level/mute); keeping call alive")
            return
        }
        handleNativeError(error)
    }

    private fun handleVideoCaptureFailure(error: NativeError) {
        println("WARN [LiveKitNativeMediaSession] video capture failed after publish: ${error.code} ${error.message}")
        // This is the one the audit called out: the video capture thread derives
        // from the readiness while the native dispatch thread may be writing it.
        // Read and write in one critical section, or a `Connected` landing between
        // them gets reverted to `connected = false` by these stale values.
        mutateReadiness { old ->
            old.copy(
                videoPublished = false,
                acceptingVideoFrames = false,
                cameraState = cameraStateFromVideoError(error),
                joinedAsAudioOnlyFallback = !old.cameraRequired,
                lastError = error
            )
        }
    }

    private fun handlePlaybackFailure(error: NativeError) {
        handleNativeError(error)
    }

    private fun updateReadinessWithError(state: NativeMediaState, error: NativeError) {
        // Reached from the native dispatch thread AND the reconnect coroutine, both
        // of which can be writing the readiness concurrently — so this derives
        // under the lock rather than reading a dozen fields and storing.
        mutateReadiness { old ->
            old.copy(
                state = state,
                acceptingAudioFrames = false,
                acceptingVideoFrames = false,
                lastError = error
            )
        }
    }

    private fun unknownError(message: String, operation: String): NativeError =
        NativeError(
            code = NativeErrorCode.UNKNOWN,
            message = message,
            operation = operation,
            recoverable = false
        )

    private fun emptyReadiness(): NativeMediaReadiness =
        NativeMediaReadiness(
            sessionId = 0L,
            epoch = 0L,
            state = NativeMediaState.Idle,
            connected = false,
            audioPublished = false,
            videoPublished = false,
            acceptingAudioFrames = false,
            acceptingVideoFrames = false,
            cameraRequested = false,
            cameraRequired = false,
            cameraState = CameraPublishState.NotRequested,
            joinedAsAudioOnlyFallback = false,
            tokenRefreshScheduled = false,
            lastError = null
        )
}
