package ui.screen.main

import androidx.compose.runtime.*
import webrtc.*
import repository.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import ui.screen.chat.*
import ui.icons.*
import ui.theme.*
import ui.components.OrbitNotices
import ui.components.OrbitNotices.onFailureOrNotify
import ui.screen.main.redactedIceUrl

@Composable
fun WebRtcEffects(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    scope: kotlinx.coroutines.CoroutineScope
): CallManager {
    val webRtcClient = remember { createWebRtcClient() }
    val callManager = remember { CallManager(webRtcClient, scope) }

    // Register app-wide call keybinds (dispatched from the root Window key handler).
    DisposableEffect(callManager) {
        ActiveCallBridge.register(callManager)
        CallShortcuts.onToggleMute = {
            if (callManager.callState.value != CallState.IDLE) {
                callManager.toggleMute(callManager.isLocalAudioEnabled.value)
            }
        }
        CallShortcuts.onToggleDeafen = {
            if (callManager.callState.value != CallState.IDLE) {
                callManager.toggleDeafen()
            }
        }
        CallShortcuts.onToggleCamera = {
            if (callManager.callState.value != CallState.IDLE) {
                callManager.toggleVideo(!callManager.isLocalVideoEnabled.value)
            }
        }
        CallShortcuts.onReturnToCall = {
            if (callManager.callState.value != CallState.IDLE &&
                callManager.isMinimized.value
            ) {
                callManager.isMinimized.value = false
            }
        }
        CallShortcuts.onPushToTalk = { active ->
            callManager.setPushToTalkActive(active)
        }
        CallShortcuts.onAppQuitRequested = {
            if (callManager.callState.value != CallState.IDLE) {
                callManager.endCall()
            }
        }
        onDispose {
            ActiveCallBridge.register(null)
            CallShortcuts.onToggleMute = null
            CallShortcuts.onToggleDeafen = null
            CallShortcuts.onToggleCamera = null
            CallShortcuts.onReturnToCall = null
            CallShortcuts.onPushToTalk = null
            CallShortcuts.onAppQuitRequested = null
            // Release the native media stack on unmount. This effect runs when
            // WebRtcEffects leaves composition, which is exactly what happens on
            // logout -- and the client has no other release path, so without
            // this the OS recording indicator stayed on and audio kept flowing
            // to the peer after signing out. The next person at the machine
            // inherited a live call with no UI.
            if (callManager.callState.value != CallState.IDLE) {
                callManager.endCall(notifyRemote = false)
            }
            callManager.release()
        }
    }
    
    val orbitZeroEnabled by settingsManager.orbitZeroAiNoiseSuppression.collectAsState(true)
    val attenuation by settingsManager.attenuation.collectAsState(0f)
    val inputDevice by settingsManager.inputDevice.collectAsState("Default")
    val outputDevice by settingsManager.outputDevice.collectAsState("Default")
    val cameraDevice by settingsManager.cameraDevice.collectAsState("Default")
    val audioBitrateKbps by settingsManager.audioBitrate.collectAsState(64f)
    val nsEnabled by settingsManager.noiseSuppression.collectAsState(true)
    val ecEnabled by settingsManager.echoCancellation.collectAsState(true)
    val agcEnabled by settingsManager.autoGainControl.collectAsState(true)
    val inputVolume by settingsManager.inputVolume.collectAsState(100f)
    val outputVolume by settingsManager.outputVolume.collectAsState(100f)
    val mirrorVideo by settingsManager.mirrorVideo.collectAsState(true)
    val videoBackground by settingsManager.videoBackground.collectAsState("None")
    val sfuOverrideUrl by settingsManager.liveKitServiceUrl.collectAsState("")
    val inputMode by settingsManager.inputMode.collectAsState("Voice Activity")

    LaunchedEffect(inputMode) {
        callManager.pushToTalkMode = inputMode == "Push to Talk"
    }

    // Incoming-call ringtone: loop while a DM invite is ringing, honoring the
    // Voice & Video toggle. Stops on accept (CONNECTING), decline/expiry (IDLE).
    LaunchedEffect(callManager, settingsManager) {
        combine(
            callManager.callState,
            callManager.isIncomingCall,
            settingsManager.callRingtone
        ) { state, incoming, enabled ->
            state == CallState.RINGING && incoming && enabled
        }.collect { ringing ->
            livekit.setCallRinging(ringing)
        }
    }

    // System tray notification for incoming calls and missed ones (window
    // unfocused only — the in-app overlay covers the focused case), honoring
    // the Desktop notifications toggle. Answering (CONNECTING) suppresses the
    // missed variant; invite expiry or a remote hangup during the ring fires it.
    val desktopNotifsEnabled by settingsManager.desktopNotifs.collectAsState(true)
    LaunchedEffect(callManager, desktopNotifsEnabled) {
        var ringingIncoming = false
        var answeredThisCall = false
        callManager.callState.collect { state ->
            when {
                state == CallState.RINGING && callManager.isIncomingCall.value && !ringingIncoming -> {
                    ringingIncoming = true
                    answeredThisCall = false
                    if (desktopNotifsEnabled) {
                        val caller = webrtc.IncomingCallRegistry.callerName ?: "Someone"
                        livekit.showCallNotification("Incoming call", "$caller is calling\u2026")
                    }
                }
                state == CallState.CONNECTING || state == CallState.CONNECTED -> answeredThisCall = true
                (state == CallState.IDLE || state == CallState.ENDED) && ringingIncoming -> {
                    if (!answeredThisCall && desktopNotifsEnabled) {
                        val caller = webrtc.IncomingCallRegistry.callerName ?: "Someone"
                        livekit.showCallNotification("Missed call", "Missed call from $caller")
                    }
                    ringingIncoming = false
                    webrtc.IncomingCallRegistry.clear()
                }
            }
        }
    }

    // Join/leave blips: self connect/disconnect plus remote peers arriving or
    // leaving during a connected native call, honoring the join/leave toggle.
    val joinLeaveSoundEnabled by settingsManager.joinLeaveSound.collectAsState(true)
    LaunchedEffect(callManager, joinLeaveSoundEnabled) {
        var selfConnected = false
        var knownPeers: Set<String>? = null
        combine(
            callManager.callState,
            livekit.LiveKitRemoteVideoFrameStore.remoteParticipants
        ) { state, peers -> state to peers }.collect { (state, peers) ->
            when {
                state == CallState.CONNECTED -> {
                    if (joinLeaveSoundEnabled && !selfConnected) livekit.playSoundEffect("join")
                    val known = knownPeers
                    if (selfConnected && known != null && peers.size != known.size) {
                        if (joinLeaveSoundEnabled) {
                            livekit.playSoundEffect(if (peers.size > known.size) "join" else "leave")
                        }
                    }
                    knownPeers = peers
                    selfConnected = true
                }
                else -> {
                    if (selfConnected && joinLeaveSoundEnabled) livekit.playSoundEffect("leave")
                    selfConnected = false
                    knownPeers = null
                }
            }
        }
    }

    // Deafen/undeafen blips on local deafen-state changes, honoring the toggle.
    val deafenSoundEnabled by settingsManager.deafenSound.collectAsState(true)
    LaunchedEffect(callManager, deafenSoundEnabled) {
        var previous: Boolean? = null
        callManager.isDeafened.collect { deafened ->
            val prev = previous
            previous = deafened
            if (!deafenSoundEnabled || prev == null || prev == deafened) return@collect
            livekit.playSoundEffect(if (deafened) "deafen" else "undeafen")
        }
    }


    LaunchedEffect(orbitZeroEnabled) {
        webRtcClient.setOrbitZeroEnabled(orbitZeroEnabled)
    }
    // Part I: SFU endpoint chain (settings override → .well-known rtc_foci → dev fallback).
    LaunchedEffect(sfuOverrideUrl, repository) {
        repository.configureCallEndpoints(sfuOverrideUrl.takeIf { it.isNotBlank() })
    }
    LaunchedEffect(attenuation) {
        // Through CallManager, not straight to the client.
        //
        // It used to call webRtcClient.setAttenuationStrength directly, which left
        // CallManager.duckingStrength untouched. That flow is what the in-call
        // "Attenuation" slider reads, and it was seeded with a hardcoded 0.8f, so
        // the two controls were independent values for one feature: set Ducking
        // Strength to 0 in Settings and the in-call slider still read 80%, or set
        // it to 20% and the slider read 80% and quietly overwrote the setting.
        //
        // Routing through CallManager means one write updates both, which is the
        // only thing that makes two controls for one setting defensible.
        callManager.setDuckingStrength(attenuation / 100f)
    }
    LaunchedEffect(inputDevice) {
        callManager.setAudioInputDevice(inputDevice)
    }
    LaunchedEffect(outputDevice) {
        callManager.setAudioOutputDevice(outputDevice)
    }
    LaunchedEffect(cameraDevice) {
        callManager.setVideoCaptureDevice(cameraDevice)
    }
    LaunchedEffect(audioBitrateKbps) {
        callManager.audioBitrateKbps = audioBitrateKbps.toInt().coerceIn(8, 384)
    }

    // Settings are the source of truth for voice processing at launch; the in-call
    // More Options menu can override them for the session.
    LaunchedEffect(nsEnabled, ecEnabled, agcEnabled) {
        callManager.setAudioOptions(nsEnabled, ecEnabled, agcEnabled)
    }
    LaunchedEffect(inputVolume) {
        callManager.setMicVolume(inputVolume / 100f)
    }
    LaunchedEffect(outputVolume) {
        callManager.setPlaybackVolume(outputVolume / 100f)
    }
    LaunchedEffect(mirrorVideo) {
        callManager.setMirrorVideo(mirrorVideo)
    }
    // Only "Blur" maps to the engine's virtual background; "Custom Image" is not implemented yet.
    LaunchedEffect(videoBackground) {
        val wantBlur = videoBackground == "Blur"
        if (callManager.isVirtualBackgroundEnabled.value != wantBlur) {
            callManager.toggleVirtualBackground()
        }
    }

    LaunchedEffect(callManager, repository) {
        callManager.refreshIceServers = {
            repository.getTurnServers()
                .onSuccess { servers ->
                    val urls = servers.flatMap { it.uris }
                    util.OrbitLog.d("WebRtcEffects", "Matrix ICE discovery returned ${urls.size} urls: ${urls.joinToString { it.redactedIceUrl() }}")
                }
                .onFailure { error ->
                    util.OrbitLog.d("WebRtcEffects", "Matrix ICE discovery failed: ${error.message}")
                }
                .getOrElse { emptyList() }
        }

        // Group-call roster: MatrixRTC membership flow feeds the call surface tiles
        callManager.groupMembersProvider = { roomId -> repository.observeCallMembers(roomId) }

        // Call tiles are keyed by LiveKit identity, not by OrbitUser, so they need
        // their own identity -> profile path. getUserProfile runs through
        // MatrixContext.fetchUserProfile, which applies convertMxcUrl — without
        // that, Coil receives an `mxc://` string and silently fails, leaving the
        // tile with a letter avatar.
        callManager.groupProfileResolver = { userId -> repository.getUserProfile(userId) }

        // matrixRTC E2EE media keys: room encryption probe + olm key exchange control
        callManager.roomIsEncryptedChecker = { roomId -> repository.isRoomEncrypted(roomId) }
        callManager.mediaKeysStart = { roomId, livekitIdentity ->
            // Starting the key exchange is what makes the call encrypted. If it
            // fails, the Result was discarded and the call proceeded with media
            // keys that were never agreed — the worst possible outcome, because
            // it looks like a working encrypted call.
            OrbitNotices.notifying("Call encryption setup", "CallEncryption") {
                repository.startCallMediaKeyExchange(roomId, livekitIdentity)
            }
        }
        callManager.mediaKeysStop = { repository.stopCallMediaKeyExchange() }
        // Without this the 3 s room-state poller keeps running after a hangup on
        // the paths that bypass endGroupCall/leaveGroupCall.
        callManager.onGroupCallObserversStop = { roomId -> repository.stopCallRosterPolling(roomId) }

        // Warm the TURN/STUN config early, and CallManager refreshes it again before each call.
        launch {
            callManager.iceServers = callManager.refreshIceServers?.invoke().orEmpty()
        }

        // Listen for signaling events. `scope` is an INJECTED outer scope, so a
        // child launched into it survived this LaunchedEffect being cancelled:
        // on any repository change the old collector kept running, ringing twice
        // for every invite and writing ICE candidates to a stale repository.
        launch {
            repository.observeCallEvents().collect { event ->
                util.OrbitLog.d("WebRtcEffects", "observeCallEvents received: ${event::class.simpleName} (callId=${event.callId})")
                // H4: drop own-device echoes + other-devices-of-ours (multi-device noise guard).
                if (webrtc.CallEventGate.shouldIgnore(
                        senderId = event.senderId,
                        partyId = event.partyId,
                        ownDeviceId = CredentialManager.deviceId,
                        ownUserId = repository.getCurrentUser()?.id
                    )
                ) {
                    return@collect
                }

                when (event) {
                    is repository.CallEvent.Invite -> {
                        // Resolve the caller's display name for system notifications;
                        // the ring-state effect reads it once RINGING actually starts.
                        launch {
                            val displayName = runCatching {
                                repository.getRoomMembers(event.roomId)
                                    .firstOrNull { it.id == event.senderId || event.senderId.startsWith(it.id) }
                                    ?.displayName
                            }.getOrNull()
                            webrtc.IncomingCallRegistry.register(event.senderId, displayName)
                        }
                        callManager.handleCallInvite(event.roomId, event.callId, event.sdp, event.lifetimeMs)
                    }
                    is repository.CallEvent.Answer -> {
                        callManager.handleCallAnswer(event.sdp)
                        event.partyId?.let { selectedPartyId ->
                            repository.sendCallEvent(event.roomId, "m.call.select_answer", buildJsonObject {
                                put("call_id", event.callId)
                                put("selected_party_id", selectedPartyId)
                                put("version", "1")
                            })
                        }
                    }
                    is repository.CallEvent.Negotiate -> {
                        callManager.handleCallNegotiate(event.roomId, event.callId, event.type, event.sdp)
                    }
                    is repository.CallEvent.Candidate -> callManager.handleIceCandidate(
                        webrtc.IceCandidateModel(event.sdp, event.sdpMid, event.sdpMLineIndex)
                    )
                    is repository.CallEvent.Hangup -> {
                        if (callManager.callId == event.callId) {
                            util.OrbitLog.w(
                                "WebRtcEffects",
                                "m.call.hangup matching active call callId=${event.callId} sender=${event.senderId} — ending call"
                            )
                            callManager.endCall(notifyRemote = false)
                        }
                    }
                    is repository.CallEvent.HandRaised -> {
                        // H4 echo guard already dropped our own devices' events; the
                        // local button owns our state, so only remote senders land here.
                        if (event.roomId == callManager.activeRoomIdFlow.value) {
                            callManager.setHandRaised(event.senderId, event.raised)
                        }
                    }
                }
            }
        }

        callManager.sendInvite = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.invite", buildJsonObject {
                put("call_id", callId)
                put("offer", buildJsonObject {
                    put("type", "offer")
                    put("sdp", sdp)
                })
                put("version", "1")

                put("lifetime", 30000)
            }).onFailure { error ->
                util.OrbitLog.d("WebRtcEffects", "Failed to send call invite: ${error.message}")
                // An invite that never reaches the homeserver means the callee
                // never rings, and Orbit sits there looking connected. Debug-level
                // logging is off by default, so this failure was invisible.
                OrbitNotices.error(
                    "Call invite not delivered: ${error.message ?: "unknown error"}",
                    "CallSignalling",
                    "Retry",
                    { scope.launch { callManager.sendInvite?.invoke(roomId, callId, sdp) } }
                )
            }
        }

        callManager.sendAnswer = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.answer", buildJsonObject {
                put("call_id", callId)
                put("answer", buildJsonObject {
                    put("type", "answer")
                    put("sdp", sdp)
                })
                put("version", "1")

            }).onFailure { error ->
                util.OrbitLog.d("WebRtcEffects", "Failed to send call answer: ${error.message}")
                // No answer means the caller waits forever on a call that is
                // locally "connecting".
                OrbitNotices.error(
                    "Could not answer the call: ${error.message ?: "unknown error"}",
                    "CallSignalling",
                    "Retry",
                    { scope.launch { callManager.sendAnswer?.invoke(roomId, callId, sdp) } }
                )
            }
        }

        callManager.sendNegotiate = { roomId, callId, type, sdp ->
            repository.sendCallEvent(roomId, "m.call.negotiate", buildJsonObject {
                put("call_id", callId)
                put("description", buildJsonObject {
                    put("type", type)
                    put("sdp", sdp)
                })
                put("version", "1")

                put("lifetime", 30000)
            }).onFailure { error ->
                util.OrbitLog.d("WebRtcEffects", "Failed to send call negotiate: ${error.message}")
            }
        }

        callManager.sendHangup = { roomId, callId ->
            repository.sendCallEvent(roomId, "m.call.hangup", buildJsonObject {
                put("call_id", callId)
                put("version", "1")

            }).onFailure { error ->
                util.OrbitLog.d("WebRtcEffects", "Failed to send call hangup: ${error.message}")
                // A hangup that does not land leaves the OTHER side ringing at a
                // call nobody is in, and our membership in the roster until the
                // window expires.
                OrbitNotices.error(
                    "Hangup not delivered — others may still see you in the call",
                    "CallSignalling"
                )
            }
        }

        callManager.sendRaiseHand = { roomId, callId, raised ->
            repository.sendCallEvent(roomId, "io.orbit.raise_hand", buildJsonObject {
                put("call_id", callId)
                put("raised", raised)
                put("version", "1")
            }).onFailure { error ->
                util.OrbitLog.d("WebRtcEffects", "Failed to send raise-hand event: ${error.message}")
            }
        }

        callManager.onLeaveGroupCall = { roomId, callId ->
            // Leaving a call publishes TWO state events. The second is what
            // actually removes this device from the roster; if only the first
            // lands, every other participant keeps showing this device as
            // present until the membership window expires — hours.
            //
            // Both failures used to vanish, so a failed leave looked exactly
            // like a successful one and produced a ghost participant with no
            // way to diagnose it. Neither gets a Retry button: the user has
            // already left locally, and re-running the write is safe but not
            // something they asked for.
            //
            // These return Unit and throw, so `notifying` wraps them directly.
            //
            // They MUST NOT be wrapped in plain `runCatching`: CallManager
            // deliberately cancels the leave job after 1500 ms, and
            // runCatching would catch that CancellationException and tell the
            // user their hangup failed — which it had not, since the same write
            // had already logged status=200.
            OrbitNotices.notifying("Call end", "CallMembership") {
                repository.endGroupCall(roomId, callId)
            }
            OrbitNotices.notifying("Call leave", "CallMembership") {
                repository.leaveGroupCall(roomId, callId)
            }
        }

        callManager.onUpdateGroupCallMembershipCapabilities = { roomId, callId, audio, video, identity ->
            // Publishes on every mute/unmute, so a failure means the roster
            // advertises the wrong device state for hours.
            //
            // This one returns a bare Boolean, NOT a Result: `true` means the
            // state event published, `false` means it did not. Wrapping it and
            // only inspecting the throwing path would let every `false` — the
            // ordinary offline case — pass as success. Both routes report.
            //
            // The callback must return the Boolean (the manager's contract), so
            // the failure is reported here and the value passed through.
            val published = runCatching {
                repository.updateGroupCallMembershipCapabilities(roomId, callId, audio, video, identity)
            }.getOrElse { error ->
                OrbitNotices.error(
                    "Call state update failed: ${error.message ?: "unknown error"}",
                    "CallMembership"
                )
                false
            }
            if (!published) {
                OrbitNotices.error(
                    "Call state did not reach the server — others may see you incorrectly",
                    "CallMembership"
                )
            }
            published
        }

        // --- ICE Candidate Batching Logic ---
        val candidateBatch = mutableListOf<webrtc.IceCandidateModel>()
        var batchJob: kotlinx.coroutines.Job? = null

        callManager.sendCandidate = { roomId, callId, candidate ->
            candidateBatch.add(candidate)

            if (batchJob?.isActive != true) {
                // Effect scope, not the injected one: the debounce job must die
                // with the effect, or a repository swap leaves batches in flight
                // that write to the old repository.
                batchJob = launch {
                    kotlinx.coroutines.delay(500) // Debounce window to catch burst

                    val toSend = candidateBatch.toList()
                    candidateBatch.clear()

                    if (toSend.isNotEmpty()) {
                        util.OrbitLog.d("WebRtcEffects", "Sending ${toSend.size} local ICE candidates")
                        repository.sendCallEvent(roomId, "m.call.candidates", buildJsonObject {
                            put("call_id", callId)
                            put("version", "1")
            
                            put("candidates", buildJsonArray {
                                toSend.forEach { cand ->
                                    add(buildJsonObject {
                                        put("candidate", cand.sdp)
                                        put("sdpMid", cand.sdpMid)
                                        put("sdpMLineIndex", cand.sdpMLineIndex)
                                    })
                                }
                            })
                        }).onFailure { error ->
                            util.OrbitLog.d("WebRtcEffects", "Failed to send ICE candidates: ${error.message}")
                // ICE candidates are the addressing information. Without them the
                // media path may never connect, so this is a "call connected but
                // no audio" failure, not a cosmetic one.
                OrbitNotices.error(
                    "Network candidates not sent — audio may not connect",
                    "CallSignalling"
                )
                        }
                    }
                }
            }
        }
    }


    return callManager
}
