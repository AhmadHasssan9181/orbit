package livekit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object LiveKitNativeMediaSession : NativeMediaEventSink {
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val PUBLISH_TIMEOUT_MS = 10_000L
    private const val RECONNECT_TIMEOUT_MS = 15_000L

    private val bridge = OrbitLiveKitNativeBridge()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sessionId: Long = 0L
    private var epoch: Long = 0L
    private var connectedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    private var audioPublishedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    private var videoPublishedDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    private var mediaReadyDeferred: CompletableDeferred<NativeResult<Unit>>? = null
    private var reconnectAllowed: Boolean = true
    private var refreshJob: Job? = null
    private var lastAuthResponse: LiveKitAuthResponse? = null
    private var lastVideoPolicy: JoinVideoPolicy = JoinVideoPolicy.AudioOnly
    private var lastRefreshTokenFetcher: (suspend () -> LiveKitAuthResponse)? = null
    @Volatile private var desiredAudioMuted: Boolean = false

    @Volatile
    private var readinessCache = emptyReadiness()

    val isConnected: Boolean
        get() = readinessCache.connected

    val isAudioPublished: Boolean
        get() = readinessCache.audioPublished

    val isAcceptingAudioFrames: Boolean
        get() = readinessCache.acceptingAudioFrames && LiveKitNativeAudioBridge.acceptingFrames

    suspend fun connectAndPublish(
        authResponse: LiveKitAuthResponse,
        videoPolicy: JoinVideoPolicy,
        refreshTokenFetcher: (suspend () -> LiveKitAuthResponse)?
    ): NativeResult<NativeMediaReadiness> {
        reconnectAllowed = true
        lastAuthResponse = authResponse
        lastVideoPolicy = videoPolicy
        lastRefreshTokenFetcher = refreshTokenFetcher
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
        }

        epoch += 1
        bridge.setEventSink(this)
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
        println("DEBUG [LiveKitNativeMediaSession] audio publish format sampleRate=${LiveKitNativeAudioCapture.SAMPLE_RATE} channels=$publishChannels samplesPerChannel=${LiveKitNativeAudioCapture.SAMPLES_PER_CHANNEL} frameMs=${LiveKitNativeAudioCapture.FRAME_MS} queueSizeMs=$queueSizeMs expectedTestTone=$expectedTestTone")
        val audioStarted = bridge.publishAudio(sessionId, epoch, LiveKitNativeAudioCapture.SAMPLE_RATE, publishChannels, queueSizeMs, expectedTestTone)
        if (!audioStarted.ok) {
            val error = audioStarted.error ?: unknownError("Native audio publish failed", "nativePublishAudio")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        val audioPublished = awaitDeferred(audioPublishedDeferred, PUBLISH_TIMEOUT_MS, "publishAudio")
        if (!audioPublished.ok) {
            val error = audioPublished.error ?: unknownError("Native audio publish timed out", "publishAudio")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }

        LiveKitNativeAudioCapture.setMuted(desiredAudioMuted)
        val initialMute = bridge.setAudioMuted(sessionId, epoch, desiredAudioMuted)
        if (!initialMute.ok) {
            val error = initialMute.error ?: unknownError("Native mic mute state failed after publish", "nativeSetAudioMuted")
            cancelPendingDeferreds(error)
            return NativeResult.failure(error)
        }
        if (desiredAudioMuted) {
            println("DEBUG [LiveKitNativeMediaSession] native mic muted after publish")
        } else {
            println("DEBUG [LiveKitNativeMediaSession] native mic unmuted after publish")
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
            ?: System.getenv("ORBIT_LIVEKIT_AUDIO_SOURCE_QUEUE_MS")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val value = raw?.toIntOrNull() ?: 10
        if (value != 10) {
            println("WARN [LiveKitNativeMediaSession] orbit.livekit.audioSourceQueueMs=$value is disabled for the decode test; using 10")
            return 10
        }
        return value
    }

    fun disconnect(userInitiated: Boolean) {
        if (userInitiated) {
            reconnectAllowed = false
            desiredAudioMuted = false
        }
        refreshJob?.cancel()
        refreshJob = null
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioBridge.nativeSetAcceptingFrames(false) }
        runCatching { LiveKitNativeAudioCapture.stopAndJoin() }
        runCatching { LiveKitNativeAudioPlayer.stop() }
        if (sessionId != 0L) {
            bridge.disconnect(sessionId, epoch, userInitiated)
        }
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
                updateReadiness(
                    state = NativeMediaState.LiveKitConnected,
                    connected = true,
                    audioPublished = false,
                    videoPublished = false,
                    acceptingAudioFrames = false,
                    acceptingVideoFrames = false,
                    cameraRequested = readinessCache.cameraRequested,
                    cameraRequired = readinessCache.cameraRequired,
                    cameraState = readinessCache.cameraState,
                    joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
                    tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
                    lastError = null
                )
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
                updateReadiness(
                    state = NativeMediaState.AudioPublished,
                    connected = true,
                    audioPublished = true,
                    videoPublished = false,
                    acceptingAudioFrames = LiveKitNativeAudioBridge.acceptingFrames,
                    acceptingVideoFrames = false,
                    cameraRequested = readinessCache.cameraRequested,
                    cameraRequired = readinessCache.cameraRequired,
                    cameraState = readinessCache.cameraState,
                    joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
                    tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
                    lastError = null
                )
                audioPublishedDeferred?.complete(NativeResult.success(Unit))
            }

            is NativeMediaEvent.VideoPublished -> {
                println("DEBUG [LiveKitNativeMediaSession] video published")
                updateReadiness(
                    state = NativeMediaState.VideoPublished,
                    connected = true,
                    audioPublished = readinessCache.audioPublished,
                    videoPublished = true,
                    acceptingAudioFrames = readinessCache.acceptingAudioFrames,
                    acceptingVideoFrames = true,
                    cameraRequested = true,
                    cameraRequired = readinessCache.cameraRequired,
                    cameraState = CameraPublishState.Published,
                    joinedAsAudioOnlyFallback = false,
                    tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
                    lastError = null
                )
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
                handleNativeDisconnected(event.reason, event.recoverable)
            }

            is NativeMediaEvent.Error -> {
                handleNativeError(event.error)
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

        println(
            "DEBUG [LiveKitNativeMediaSession] video publish started " +
                "width=${config.width} height=${config.height} fps=${config.fps} format=${config.format}"
        )
        val publish = bridge.publishVideo(sessionId, epoch, config.width, config.height, config.fps, config.format)
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

        val framePush = LiveKitNativeVideoCapture.enableNativePush(sessionId, epoch, bridge, ::handleVideoCaptureFailure)
        if (!framePush.ok) {
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
        LiveKitFeatureFlags.videoPublish

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

    private suspend fun refreshAndReconnect(fetcher: suspend () -> LiveKitAuthResponse) {
        if (!reconnectAllowed) return

        println("DEBUG [LiveKitNativeMediaSession] token refresh started")
        println("DEBUG [LiveKitNativeMediaSession] mute state before reconnect: $desiredAudioMuted")
        updateNativeState(NativeMediaState.RefreshingToken)
        runCatching { LiveKitNativeVideoCapture.stopAndJoin() }
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

        epoch += 1
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
        println("DEBUG [LiveKitNativeMediaSession] audio republish format sampleRate=${LiveKitNativeAudioCapture.SAMPLE_RATE} channels=$publishChannels samplesPerChannel=${LiveKitNativeAudioCapture.SAMPLES_PER_CHANNEL} frameMs=${LiveKitNativeAudioCapture.FRAME_MS} queueSizeMs=$queueSizeMs expectedTestTone=$expectedTestTone")
        val audioStarted = bridge.publishAudio(sessionId, epoch, LiveKitNativeAudioCapture.SAMPLE_RATE, publishChannels, queueSizeMs, expectedTestTone)
        if (!audioStarted.ok) {
            handleNativeError(audioStarted.error ?: unknownError("Audio republish failed", "nativePublishAudio"))
            return
        }
        val audioPublished = awaitDeferred(audioPublishedDeferred, PUBLISH_TIMEOUT_MS, "republishAudio")
        if (!audioPublished.ok) {
            handleNativeError(audioPublished.error ?: unknownError("Audio republish timed out", "republishAudio"))
            return
        }
        println("DEBUG [LiveKitNativeMediaSession] audio republished")
        val muteRestored = bridge.setAudioMuted(sessionId, epoch, desiredAudioMuted)
        if (!muteRestored.ok) {
            handleNativeError(muteRestored.error ?: unknownError("Native mute restore failed", "nativeSetAudioMuted"))
            return
        }
        if (desiredAudioMuted) {
            println("DEBUG [LiveKitNativeMediaSession] native mute reapplied")
        } else {
            println("DEBUG [LiveKitNativeMediaSession] native mute remains false after reconnect")
        }
        val videoOutcome = handleOptionalVideoPublish(lastVideoPolicy, grantCheck.value.videoAllowed)
        if (!videoOutcome.ok) {
            handleNativeError(videoOutcome.error ?: unknownError("Video republish failed", "republishVideo"))
            return
        }
        LiveKitNativeAudioCapture.start(sessionId, epoch, bridge, ::handleCaptureFailure)
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
        clearCompletedDeferreds()
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
        val error = NativeError(
            code = if (recoverable) NativeErrorCode.DISCONNECTED else NativeErrorCode.USER_LEFT,
            message = reason,
            operation = "nativeDisconnected",
            recoverable = recoverable
        )
        updateReadinessWithError(NativeMediaState.Disconnected, error)
        if (recoverable && reconnectAllowed) {
            scheduleTokenRefreshNow()
        } else {
            cancelPendingDeferreds(error)
        }
    }

    private fun handleNativeError(error: NativeError) {
        updateReadinessWithError(NativeMediaState.Failed, error)
        cancelPendingDeferreds(error)
    }

    private fun handleCaptureFailure(error: NativeError) {
        handleNativeError(error)
    }

    private fun handleVideoCaptureFailure(error: NativeError) {
        println("WARN [LiveKitNativeMediaSession] video capture failed after publish: ${error.code} ${error.message}")
        updateReadiness(
            state = readinessCache.state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = false,
            acceptingAudioFrames = readinessCache.acceptingAudioFrames,
            acceptingVideoFrames = false,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = cameraStateFromVideoError(error),
            joinedAsAudioOnlyFallback = !readinessCache.cameraRequired,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = error
        )
    }

    private fun handlePlaybackFailure(error: NativeError) {
        handleNativeError(error)
    }

    private fun updateReadinessWithError(state: NativeMediaState, error: NativeError) {
        updateReadiness(
            state = state,
            connected = readinessCache.connected,
            audioPublished = readinessCache.audioPublished,
            videoPublished = readinessCache.videoPublished,
            acceptingAudioFrames = false,
            acceptingVideoFrames = false,
            cameraRequested = readinessCache.cameraRequested,
            cameraRequired = readinessCache.cameraRequired,
            cameraState = readinessCache.cameraState,
            joinedAsAudioOnlyFallback = readinessCache.joinedAsAudioOnlyFallback,
            tokenRefreshScheduled = readinessCache.tokenRefreshScheduled,
            lastError = error
        )
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
