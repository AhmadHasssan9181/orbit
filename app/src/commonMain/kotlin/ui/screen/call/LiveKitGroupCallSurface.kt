package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import livekit.NativeMediaState
import repository.CredentialManager
import ui.model.OrbitUser
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallShortcuts
import webrtc.CallState
import webrtc.GroupCallConnection
import webrtc.GroupCallStage

private val tileAvatarPalette = listOf(
    0xFF7B74E8, 0xFF23A559, 0xFFEB459E, 0xFFFAA61A, 0xFF00A8FC, 0xFFD65A54
).map { it.toInt() }

private fun identityDisplayName(identity: String?): String = when {
    identity.isNullOrBlank() -> "Participant"
    else -> identity.substringAfter("@", identity).substringBefore(":").ifBlank { identity }
}

private fun identityColor(identity: String?): Int =
    tileAvatarPalette[((identity ?: "").hashCode() % tileAvatarPalette.size + tileAvatarPalette.size) % tileAvatarPalette.size]

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
    LaunchedEffect(callState) {
        if (callState == CallState.IDLE || callState == CallState.ENDED) pinnedNodeId = null
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
            LaunchedEffect(remoteParticipants, activeTracks) {
                val identities = buildSet {
                    remoteParticipants.forEach { add(it) }
                    activeTracks.keys.forEach { trackSid ->
                        LiveKitRemoteVideoFrameStore.activeTracks.value[trackSid]?.participantIdentity?.let { add(it) }
                    }
                }
                callManager.requestProfilesFor(identities)
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
                    .padding(top = 12.dp, bottom = 96.dp),
                callManager = callManager,
                pinnedNodeId = pinnedNodeId,
                onPinToggle = { id -> pinnedNodeId = if (pinnedNodeId == id) null else id }
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

        // "Voice Connected" card, bottom-left.
        //
        // Discord keeps a persistent card there for the whole call showing who you
        // are connected to, your mute state, and a hang-up button. Orbit had only
        // the bottom-centre bar, so once the call was live there was nothing on
        // screen naming the room you were in -- and when you minimised, the only
        // cue that a call was running at all was a small floating pill.
        //
        // Built from displayMembers, never the raw Matrix roster: that roster is a
        // membership record, so counting it is how a two-person call came to
        // report eight participants.
        val callLabel = remoteFallbackName.ifBlank { "Voice call" }
        val visibleParticipants = remember(displayMembers, remoteParticipants) {
            (displayMembers.map { it.id } + remoteParticipants)
                .filter { it.isNotBlank() }
                .distinct()
                .size
                .coerceAtLeast(1)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 24.dp, bottom = 24.dp)
                .widthIn(max = 280.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                .background(ui.theme.Ink850)
                .border(1.dp, ui.theme.Ink600, RoundedCornerShape(10.dp))
                // Not clickable. It used to set `isMinimized`, which minimised to
                // the floating pill. The pill is gone, so a click here would have
                // done nothing at all — and worse, an earlier version of this
                // change left the `isMinimized` branch in place, which made this
                // card blank the entire call surface.
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isMuted) OrbitIcons.MicOff else OrbitIcons.Mic,
                    contentDescription = null,
                    tint = if (isMuted) ui.theme.Rust500 else ui.theme.Sage500,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isMuted) "Muted" else "Voice Connected",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = ui.theme.Sage500,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = callLabel,
                    style = MaterialTheme.typography.bodySmall.copy(color = ui.theme.Ink550, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (visibleParticipants > 1) "$visibleParticipants" else "",
                    style = MaterialTheme.typography.bodySmall.copy(color = ui.theme.Ink550, fontSize = 11.sp)
                )
            }
        }
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

