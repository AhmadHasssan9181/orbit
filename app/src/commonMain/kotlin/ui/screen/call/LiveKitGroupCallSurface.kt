package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import livekit.NativeMediaState
import repository.CredentialManager
import ui.model.OrbitUser
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallShortcuts
import webrtc.CallState
import webrtc.GroupCallConnection
import webrtc.GroupCallStage

private fun identityDisplayName(identity: String?): String = when {
    identity.isNullOrBlank() -> "Participant"
    else -> identity.substringAfter("@", identity).substringBefore(":").ifBlank { identity }
}

private fun memberInfoFor(members: List<OrbitUser>, identity: String?): OrbitUser? {
    if (identity.isNullOrBlank()) return null
    val base = identity.substringBeforeLast(':')
    return members.firstOrNull { it.id == identity || it.id == base }
        ?: members.firstOrNull { it.id.endsWith(identity) || identity.endsWith(it.id) || it.id.endsWith(base) || base.endsWith(it.id) }
}

@Composable
fun LiveKitGroupCallSurface(
    callManager: CallManager,
    modifier: Modifier = Modifier,
    remoteFallbackName: String = "Participant"
) {
    val callState by callManager.callState.collectAsState()
    val nativeReadiness by callManager.nativeMediaReadiness.collectAsState()
    val groupConnection by callManager.groupConnection.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isMuted = !isAudioEnabled
    val isDeafened by callManager.isDeafened.collectAsState()
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    // Only "is there a local frame at all" is needed below. Collecting the frame
    // object here made the whole group-call surface recompose at camera
    // framerate (30/s) on top of the O(n) node list build.
    val hasLocalFrame by remember { derivedStateOf { LiveKitLocalVideoFrameStore.latestFrame.value != null } }
    val activeTracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
    val stuckTracks by LiveKitRemoteVideoFrameStore.stuckTracks.collectAsState()
    val remoteParticipants by LiveKitRemoteVideoFrameStore.remoteParticipants.collectAsState()
    val isVideoIntent by callManager.isVideoCall.collectAsState()
    // Raw Matrix membership roster: kept as the PROFILE DIRECTORY for tiles.
    // A tile is keyed by a LiveKit identity, and the roster is the cheapest
    // place to find the matching OrbitUser — but it is a membership record, so
    // it is NOT a participant list and must never be rendered as one.
    val members by callManager.groupCallMembers.collectAsState()
    // Reconciled against the LiveKit room (see CallRosterReconciliation); this is
    // the roster a human may actually be shown.
    val displayMembers by callManager.displayCallMembers.collectAsState()
    val participantProfiles by callManager.participantProfiles.collectAsState()
    val selfNudgeRecovery by callManager.selfNudgeRecovery.collectAsState()
    val remoteMicMutedIds by callManager.remoteMicMutedIds.collectAsState()
    val connectionQuality by callManager.remoteConnectionQuality.collectAsState()
    val raisedHands by callManager.raisedHands.collectAsState()
    val myHandRaised by callManager.isMyHandRaised.collectAsState()
    val tokens = rememberCosmicTokens()

    var showScreenShareDialog by remember { mutableStateOf(false) }

    // Ctrl+Shift+S (Start/Stop Stream) lands here via the root Window handler.
    // There is no minimized state any more, so there is nothing to gate the
    // picker on — the shortcut works whenever this surface is on screen.
    DisposableEffect(callManager) {
        CallShortcuts.onToggleStream = {
            if (isScreenSharing) {
                callManager.stopScreenShare()
            } else {
                showScreenShareDialog = true
            }
        }
        onDispose { CallShortcuts.onToggleStream = null }
    }

    // `forceMinimized` and the `isMinimized` stand-down are both gone.
    //
    // The floating pill they existed to serve has been removed: a call renders
    // in the channel, full size, and there is nothing to minimise *to*. Keeping
    // the `isMinimized` branch would have been worse than dead code — it
    // returned an empty Box, so clicking the "Voice Connected" card would have
    // blanked the call you were standing in, with no widget left to click back
    // into.
    //
    // `displayMembers` and `remoteParticipants` are still collected above and
    // used by the full grid, so nothing is orphaned by this.

    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
    var showSoundboard by remember { mutableStateOf(false) }
    // Right-click Pin: spotlight one participant until unpinned or call ends.
    var pinnedNodeId by remember { mutableStateOf<String?>(null) }
    // `.voice-room-maximized`. Escape leaves it, and the handler is published
    // only while it is on, so Escape keeps meaning "close whatever is open"
    // everywhere else in the app.
    var isStageMaximized by remember { mutableStateOf(false) }
    DisposableEffect(isStageMaximized) {
        CallShortcuts.isStageMaximized = isStageMaximized
        val exit: (() -> Unit)? = if (isStageMaximized) {
            { isStageMaximized = false }
        } else {
            null
        }
        CallShortcuts.onExitMaximizedStage = exit
        onDispose {
            CallShortcuts.onExitMaximizedStage = null
            CallShortcuts.isStageMaximized = false
        }
    }
    LaunchedEffect(callState) {
        if (callState == CallState.IDLE || callState == CallState.ENDED) {
            pinnedNodeId = null
            isStageMaximized = false
        }
    }

    val nativeMediaReady = nativeReadiness?.state == NativeMediaState.MediaReady &&
        nativeReadiness?.audioPublished == true
    val renderingGrid = callState == CallState.CONNECTED || nativeMediaReady

    if (showScreenShareDialog) {
        ScreenShareDialog(
            callManager = callManager,
            onDismiss = { showScreenShareDialog = false },
            onSourceSelected = { source ->
                callManager.startScreenShare(source.id)
                showScreenShareDialog = false
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ui.theme.Ink950)
    ) {
        if (renderingGrid) {
            val localRenderer = if (isVideoEnabled && hasLocalFrame) {
                LiveKitVideoTileRenderer.LocalPreview
            } else {
                null
            }
            val cameraTracks = activeTracks.values.filter { !it.isScreenShare }
            val screenShareTracks = activeTracks.values.filter { it.isScreenShare }

            // Which camera tracks have actually delivered a frame.
            //
            // This is the fix for a camera-off participant rendering as a
            // placeholder forever. LiveKit announces the publication either way,
            // so the tile always has a renderer, and the renderer draws a spinner
            // because no frame ever arrives. Reading the frame store lets the tile
            // tell "not yet" from "not coming" and fall back to the avatar.
            val framesByTrack by LiveKitRemoteVideoFrameStore.framesByTrack.collectAsState()
            val frameWaitedMs = rememberFrameWaitMs(cameraTracks.any { framesByTrack[it.trackSid] != null })
            val identitiesWithVideo = cameraTracks.flatMap { 
                listOf(it.participantIdentity, it.participantIdentity.substringBeforeLast(':')) 
            }.toSet()
            val selfId = CredentialManager.userId

            fun isSelf(id: String): Boolean {
                val self = selfId ?: return false
                val base = id.substringBeforeLast(':')
                return id == self || base == self || id.startsWith("$self:")
            }

            // Every identity that will get a tile, so each one's Matrix profile is
            // fetched once. The membership roster only knows about peers that
            // published a member event we happened to see, so a tile whose
            // identity is missing from it rendered as a bare letter forever.
            // Idempotent: CallManager skips identities it already resolved.
            //
            // selfId is in this set, which it was not. The "You" tile asks
            // profileForIdentity(selfId) for an avatar, but nothing ever requested
            // a profile for our own identity, so the lookup could only ever return
            // null and that tile was permanently a seeded circle with "Y" in it --
            // in every group call, for as long as group calls have existed. The
            // roster's usual reason for a missing avatar does not apply to us at
            // all: our own profile is the one profile a client can always fetch.
            LaunchedEffect(remoteParticipants, activeTracks, selfId) {
                val identities = buildSet {
                    selfId?.let { add(it) }
                    remoteParticipants.forEach { add(it) }
                    activeTracks.keys.forEach { trackSid ->
                        LiveKitRemoteVideoFrameStore.activeTracks.value[trackSid]?.participantIdentity?.let { add(it) }
                    }
                }
                callManager.requestProfilesFor(identities)
            }

            // A stuck share gets five seconds, then the session is rebuilt whether
            // or not anyone pressed the button.
            //
            // The button is there so the reconnect is never a surprise, not so it
            // can be declined -- a share that cannot be received is a dead call
            // for everyone in it, and sitting on a black tile waiting for someone
            // to notice is the worse outcome. Five seconds is long enough to read
            // the explanation and short enough that nobody is still looking at it
            // when the audio comes back.
            LaunchedEffect(stuckTracks) {
                if (stuckTracks.isEmpty()) return@LaunchedEffect
                val who = stuckTracks.values.firstOrNull() ?: return@LaunchedEffect
                println("DEBUG [CallGridTiles] stuck share detected tracks=${stuckTracks.keys} participant=$who; auto-reconnect in 5s")
                delay(5_000)
                // Re-read rather than trusting the captured map: the share may have
                // attached, or been withdrawn, while the countdown ran.
                val stillStuck = LiveKitRemoteVideoFrameStore.stuckTracks.value
                if (stillStuck.isEmpty()) {
                    println("DEBUG [CallGridTiles] stuck share resolved during countdown; not reconnecting")
                    return@LaunchedEffect
                }
                callManager.reconnectForStuckShare("auto-after-5s")
            }

            // What each tile is ACTUALLY holding, on a timer.
            //
            // "The share does not appear" has two completely different causes, and
            // every existing log line lands on the same side of the divide: the
            // store and the reader both log frames ARRIVING, so a run can be full
            // of 1920x1080 `frame updated` lines while the window shows nothing.
            // Those lines cannot distinguish "the pixels never got this far" from
            // "the pixels are here and the UI is not keeping up", and they are the
            // only evidence available when someone reports the second one.
            //
            // This reads the per-tile render input — the same `framesByTrack` map
            // `VideoRenderer` indexes — and prints the size each tile would draw.
            // A tile reading `<none>` never received a frame; a tile reading
            // 1920x1080 has its pixels and the problem is downstream of here.
            LaunchedEffect(callManager) {
                while (true) {
                    delay(2_000)
                    val tracks = LiveKitRemoteVideoFrameStore.activeTracks.value
                    val frames = LiveKitRemoteVideoFrameStore.framesByTrack.value
                    if (tracks.isEmpty() && frames.isEmpty()) continue
                    val perTrack = tracks.values
                        .sortedBy { it.trackSid }
                        .joinToString(" ") { track ->
                            val source = track.trackSource.ifBlank { "?" }
                            val size = frames[track.trackSid]?.let { "${it.width}x${it.height}" } ?: "<none>"
                            "$source/${track.trackSid.takeLast(6)}=$size"
                        }
                    val orphanFrames = frames.keys.filter { it !in tracks.keys }
                    println(
                        "DEBUG [CallGridTiles] tiles=${tracks.size} withFrame=${frames.size} " +
                            "perTrack=[$perTrack]" +
                            (if (orphanFrames.isEmpty()) "" else " orphanFrames=$orphanFrames")
                    )
                }
            }

            // Roster first (it is a complete profile directory for the people we
            // know are here), then the identity→profile cache as the fallback
            // that rescues anyone the roster does not mention.
            fun memberFor(identity: String?): OrbitUser? =
                memberInfoFor(members, identity)
                    ?: memberInfoFor(displayMembers, identity)
                    ?: participantProfiles[identity]
                    ?: callManager.profileForIdentity(identity)

            fun tileName(identity: String?): String =
                memberFor(identity)?.displayName?.takeIf { it.isNotBlank() }
                    ?: memberFor(identity)?.username?.takeIf { it.isNotBlank() }
                    ?: identityDisplayName(identity).takeIf { it != "Participant" }
                    ?: remoteFallbackName

            val nodes = buildList {
                add(
                    RenderNode(
                        id = "local",
                        name = "You",
                        renderer = localRenderer,
                        isScreenShare = false,
                        isMuted = isMuted,
                        isDeafened = isDeafened,
                        isHandRaised = myHandRaised,
                        // Our OWN avatar, not a coloured circle with "Y" in it.
                        avatarUrl = callManager.profileForIdentity(selfId)?.avatarUrl,
                        avatarColor = callManager.profileForIdentity(selfId)?.avatarColor
                            ?: 0xFF7B74E8.toInt()
                    )
                )
                if (isScreenSharing) {
                    add(
                        RenderNode(
                            id = "local_share",
                            name = "Your Screen",
                            renderer = livekit.LiveKitVideoTileRenderer.LocalScreenShare,
                            isScreenShare = true
                        )
                    )
                }
                cameraTracks.forEach { track ->
                    add(
                        RenderNode(
                            id = "remote_cam_${track.trackSid}",
                            name = tileName(track.participantIdentity),
                            renderer = LiveKitVideoTileRenderer.RemoteTrack(track.trackSid, isScreenShare = false),
                            isScreenShare = false,
                            hasVideoFrameYet = videoPlaceholder(
                                hasFrame = framesByTrack[track.trackSid] != null,
                                waitedMs = frameWaitedMs
                            ) != VideoPlaceholder.Avatar,
                            isMicMuted = identityIsMicMuted(track.participantIdentity, remoteMicMutedIds),
                            participantIdentity = track.participantIdentity,
                            qualitySample = connectionQuality[track.participantIdentity],
                            isHandRaised = identityHasRaisedHand(track.participantIdentity, raisedHands),
                            avatarColor = memberFor(track.participantIdentity)?.avatarColor
                                ?: identityColor(track.participantIdentity),
                            avatarUrl = memberFor(track.participantIdentity)?.avatarUrl
                        )
                    )
                }
                screenShareTracks.forEach { track ->
                    add(
                        RenderNode(
                            id = "remote_share_${track.trackSid}",
                            name = "${tileName(track.participantIdentity)}'s Screen",
                            renderer = LiveKitVideoTileRenderer.RemoteTrack(track.trackSid, isScreenShare = true),
                            isScreenShare = true,
                            participantIdentity = track.participantIdentity,
                            isStuckShare = stuckTracks.containsKey(track.trackSid),
                            avatarColor = memberFor(track.participantIdentity)?.avatarColor
                                ?: identityColor(track.participantIdentity)
                        )
                    )
                }
                val voiceIdentities = remoteParticipants
                    .filter { id -> id.isNotBlank() && !isSelf(id) && id !in identitiesWithVideo && id.substringBeforeLast(':') !in identitiesWithVideo }
                    .distinctBy { it.substringBeforeLast(':') }

                voiceIdentities.forEach { identity ->
                    val member = memberFor(identity)
                    val displayName = member?.displayName?.ifBlank { member.username }
                        ?: identityDisplayName(identity)
                    add(
                        RenderNode(
                            id = "remote_voice_$identity",
                            name = displayName,
                            renderer = null,
                            isScreenShare = false,
                            isMicMuted = identityIsMicMuted(identity, remoteMicMutedIds),
                            participantIdentity = identity,
                            qualitySample = connectionQuality[identity],
                            isHandRaised = identityHasRaisedHand(identity, raisedHands),
                            avatarUrl = member?.avatarUrl,
                            avatarColor = member?.avatarColor ?: identityColor(identity)
                        )
                    )
                }
            }

            CallGrid(
                nodes = nodes,
                tokens = tokens,
                layoutMode = layoutMode,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = if (isStageMaximized) 12.dp else 96.dp),
                callManager = callManager,
                pinnedNodeId = pinnedNodeId,
                onPinToggle = { id -> pinnedNodeId = if (pinnedNodeId == id) null else id },
                maximized = isStageMaximized,
                onToggleMaximized = { isStageMaximized = !isStageMaximized }
            )
        } else {
            GroupCallJoinStatus(
                connection = groupConnection,
                videoRequested = isVideoIntent,
                accentColor = tokens.Accent,
                onLeave = { callManager.endCall() },
                modifier = Modifier.align(Alignment.Center)
            )
        }

        val showReconnecting = renderingGrid && groupConnection.stage == webrtc.GroupCallStage.Reconnecting
        if (showReconnecting || (renderingGrid && groupConnection.audioOnlyFallback) ||
            (renderingGrid && groupConnection.waitingForRemoteMedia)
        ) {
            GroupCallStatusBanner(
                reconnecting = showReconnecting,
                audioOnlyFallback = groupConnection.audioOnlyFallback,
                waitingForRemoteMedia = groupConnection.waitingForRemoteMedia,
                repairingLink = selfNudgeRecovery,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            )
        }

        if (showSoundboard) {
            SoundboardPanel(
                callManager = callManager,
                onDismiss = { showSoundboard = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp)
            )
        }

        CallControlsBar(
            callManager = callManager,
            isVideoEnabled = isVideoEnabled,
            isMuted = isMuted,
            isDeafened = isDeafened,
            isScreenSharing = isScreenSharing,
            layoutMode = layoutMode,
            onToggleDeafen = { callManager.toggleDeafen() },
            onLayoutModeToggle = {
                layoutMode = if (layoutMode == CallLayoutMode.Grid) CallLayoutMode.Focus else CallLayoutMode.Grid
            },
            // Tooltip reads "Stop Sharing" while active, so the click must stop the
            // share rather than reopen the source picker.
            onScreenShareClick = {
                if (isScreenSharing) callManager.stopScreenShare() else showScreenShareDialog = true
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            showMoreOptions = false,
            showSoundboard = showSoundboard,
            onSoundboardToggle = { showSoundboard = !showSoundboard }
        )

        // "Voice Connected" panel, bottom-left.
        //
        // The concept draws this at the bottom of the channel sidebar, above the
        // user bar, where it stays visible for the whole call. Orbit's group
        // surface draws it bottom-left over the stage, which is the same job done
        // in the one place the stage has spare room, and the same CSS does the
        // work: `--bg-secondary` between two `--border-subtle` hairlines, a
        // `--status-online` heading, an 11px muted channel line, a red
        // quick-disconnect and a row of 34x32 tools.
        //
        // Built from displayMembers, never the raw Matrix roster: that roster is a
        // membership record, so counting it is how a two-person call came to
        // report eight participants.
    }
}

private fun joinStageLabel(stage: GroupCallStage, videoRequested: Boolean, attempt: Int): String {
    val base = when (stage) {
        GroupCallStage.Joining, GroupCallStage.Idle -> "Joining…"
        GroupCallStage.Joined -> "Connecting…"
        GroupCallStage.Connected -> "Publishing audio…"
        GroupCallStage.AudioPublished -> if (videoRequested) "Publishing video…" else "Almost there…"
        GroupCallStage.VideoPublished -> "Waiting for media…"
        GroupCallStage.InCall -> "Connected"
        GroupCallStage.Reconnecting -> "Reconnecting…"
        GroupCallStage.Failed -> "Couldn't join the call"
    }
    return if (attempt > 1 && stage != GroupCallStage.Failed) "$base (retry $attempt)" else base
}

@Composable
private fun GroupCallJoinStatus(
    connection: GroupCallConnection,
    videoRequested: Boolean,
    accentColor: Color,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (connection.stage == GroupCallStage.Failed) {
            Text(
                text = connection.message ?: joinStageLabel(GroupCallStage.Failed, videoRequested, connection.attempt),
                color = ui.theme.Rust500
            )
            Button(onClick = onLeave) {
                Text("Back")
            }
        } else {
            CircularProgressIndicator(color = accentColor)
            Text(
                text = joinStageLabel(connection.stage, videoRequested, connection.attempt),
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun GroupCallStatusBanner(
    reconnecting: Boolean,
    audioOnlyFallback: Boolean,
    waitingForRemoteMedia: Boolean,
    repairingLink: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // During a key-exchange self-nudge the media session is untouched —
        // show calm recovery copy instead of implying the call dropped.
        if (reconnecting) {
            StatusChip(
                if (repairingLink) "Repairing audio link…" else "Reconnecting…",
                Color(0xFFFAA61A)
            )
        }
        if (audioOnlyFallback) StatusChip("No camera — joined audio-only", Color(0xFFFAA61A))
        if (waitingForRemoteMedia) {
            StatusChip(
                if (repairingLink) "Restoring participants' audio…" else "Waiting for participants' media…",
                Color(0xFF00A8FC)
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

