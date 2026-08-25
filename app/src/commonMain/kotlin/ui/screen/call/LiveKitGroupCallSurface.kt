package ui.screen.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import livekit.NativeMediaState
import repository.CredentialManager
import ui.model.OrbitUser
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState
import webrtc.GroupCallConnection
import webrtc.GroupCallStage

private val tileAvatarPalette = listOf(
    0xFF5865F2, 0xFF23A559, 0xFFEB459E, 0xFFFAA61A, 0xFF00A8FC, 0xFFF23F42
).map { it.toInt() }

private fun identityDisplayName(identity: String?): String = when {
    identity.isNullOrBlank() -> "Participant"
    else -> identity.substringAfter("@", identity).substringBefore(":").ifBlank { identity }
}

private fun identityColor(identity: String?): Int =
    tileAvatarPalette[((identity ?: "").hashCode() % tileAvatarPalette.size + tileAvatarPalette.size) % tileAvatarPalette.size]

private fun memberInfoFor(members: List<OrbitUser>, identity: String?): OrbitUser? {
    if (identity.isNullOrBlank()) return null
    return members.firstOrNull { it.id == identity }
        ?: members.firstOrNull { it.id.endsWith(identity) || identity.endsWith(it.id) }
}

@Composable
fun LiveKitGroupCallSurface(
    callManager: CallManager,
    modifier: Modifier = Modifier,
    remoteFallbackName: String = "Participant",
    // Overlay hosts (floating popup) always render the compact pill so an active
    // call can never blanket the whole window and block navigation; the expanded
    // controls live only in the in-channel surface.
    forceMinimized: Boolean = false,
    onRestore: (() -> Unit)? = null
) {
    val callState by callManager.callState.collectAsState()
    val nativeReadiness by callManager.nativeMediaReadiness.collectAsState()
    val groupConnection by callManager.groupConnection.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isMuted = !isAudioEnabled
    val isDeafened by callManager.isDeafened.collectAsState()
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    val isMinimized by callManager.isMinimized.collectAsState()
    val localFrame by LiveKitLocalVideoFrameStore.latestFrame.collectAsState()
    val activeTracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
    val remoteParticipants by LiveKitRemoteVideoFrameStore.remoteParticipants.collectAsState()
    val isVideoIntent by callManager.isVideoCall.collectAsState()
    val members by callManager.groupCallMembers.collectAsState()
    val selfNudgeRecovery by callManager.selfNudgeRecovery.collectAsState()
    val remoteMicMutedIds by callManager.remoteMicMutedIds.collectAsState()
    val tokens = rememberCosmicTokens()

    if (isMinimized || forceMinimized) {
        val totalParticipants = (members.map { it.id } + remoteParticipants).filter { it.isNotBlank() }.distinct().size
        GroupCallMiniView(
            connection = groupConnection,
            callName = remoteFallbackName,
            stageLabel = joinStageLabel(
                groupConnection.stage,
                isVideoIntent,
                groupConnection.attempt
            ),
            participantCount = if (totalParticipants > 0) totalParticipants else 1,
            isMuted = isMuted,
            onToggleMute = { callManager.toggleMute(!isMuted) },
            onRestore = onRestore ?: { callManager.isMinimized.value = false },
            onLeave = { callManager.endCall() },
            modifier = modifier
        )
        return
    }

    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
    var showScreenShareDialog by remember { mutableStateOf(false) }
    var showSoundboard by remember { mutableStateOf(false) }

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
            .background(Color(0xFF111214))
    ) {
        if (renderingGrid) {
            val localRenderer = if (isVideoEnabled && localFrame != null) {
                LiveKitVideoTileRenderer.LocalPreview
            } else {
                null
            }
            val cameraTracks = activeTracks.values.filter { !it.isScreenShare }
            val screenShareTracks = activeTracks.values.filter { it.isScreenShare }
            val identitiesWithVideo = cameraTracks.map { it.participantIdentity }.toSet()
            val selfId = CredentialManager.userId

            fun tileName(identity: String?): String =
                memberInfoFor(members, identity)?.displayName?.takeIf { it.isNotBlank() }
                    ?: memberInfoFor(members, identity)?.username?.takeIf { it.isNotBlank() }
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
                        avatarColor = 0xFF5865F2.toInt()
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
                            avatarColor = memberInfoFor(members, track.participantIdentity)?.avatarColor
                                ?: identityColor(track.participantIdentity),
                            avatarUrl = memberInfoFor(members, track.participantIdentity)?.avatarUrl
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
                            avatarColor = memberInfoFor(members, track.participantIdentity)?.avatarColor
                                ?: identityColor(track.participantIdentity)
                        )
                    )
                }
                val voiceIdentities = (members.map { it.id } + remoteParticipants)
                    .filter { id -> id.isNotBlank() && id != selfId && id !in identitiesWithVideo }
                    .distinct()

                voiceIdentities.forEach { identity ->
                    val member = memberInfoFor(members, identity)
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
                callManager = callManager
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
                color = Color(0xFFF23F42)
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

@Composable
private fun GroupCallMiniView(
    connection: GroupCallConnection,
    callName: String,
    stageLabel: String,
    participantCount: Int,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onRestore: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val subtitle = if (connection.audioOnlyFallback && connection.stage != GroupCallStage.Failed) {
        "$stageLabel · Audio-only"
    } else {
        stageLabel
    }

    Box(modifier = modifier.wrapContentSize(Alignment.BottomEnd)) {
        val entranceState = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = entranceState,
            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.Transparent,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .padding(24.dp)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
            ) {
                Row(
                    modifier = Modifier
                        .clickable(onClick = onRestore)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF1A1B1E).copy(alpha = 0.97f),
                                    Color(0xFF1E1F22).copy(alpha = 0.97f),
                                    Color(0xFF1A1B1E).copy(alpha = 0.97f)
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp))
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveDot(reconnecting = connection.stage == GroupCallStage.Reconnecting)

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = callName,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                        Text(
                            text = subtitle,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            OrbitIcons.Members,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "$participantCount",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    ControlSeparator()

                    MiniPillButton(
                        icon = if (isMuted) OrbitIcons.MicOff else OrbitIcons.Mic,
                        contentDescription = if (isMuted) "Unmute" else "Mute",
                        container = if (isMuted) Color(0xFFF23F42) else Color(0xFF313338),
                        onClick = onToggleMute
                    )
                    MiniPillButton(
                        icon = OrbitIcons.CallEnd,
                        contentDescription = "Leave call",
                        container = Color(0xFFF23F42),
                        onClick = onLeave
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveDot(reconnecting: Boolean) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val haloAlpha by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "haloAlpha"
    )
    val baseColor = if (reconnecting) Color(0xFFFAA61A) else Color(0xFF23A559)
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(16.dp).background(baseColor.copy(alpha = haloAlpha * 0.35f), CircleShape))
        Box(Modifier.size(7.dp).background(baseColor, CircleShape))
    }
}

@Composable
private fun MiniPillButton(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    onClick: () -> Unit
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .hoverable(hoverInteraction)
            .background(if (isHovered) container.copy(alpha = 0.85f) else container, CircleShape)
            .then(if (isHovered) Modifier.scale(1.12f) else Modifier)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}
