package ui.screen.call

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import livekit.NativeMediaState
import repository.CredentialManager
import ui.model.OrbitUser
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
    val isMinimized by callManager.isMinimized.collectAsState()
    val localFrame by LiveKitLocalVideoFrameStore.latestFrame.collectAsState()
    val activeTracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
    val isVideoIntent by callManager.isVideoCall.collectAsState()
    val members by callManager.groupCallMembers.collectAsState()
    val tokens = rememberCosmicTokens()

    if (isMinimized) {
        GroupCallMiniView(
            connection = groupConnection,
            callName = remoteFallbackName,
            stageLabel = joinStageLabel(
                groupConnection.stage,
                isVideoIntent,
                groupConnection.attempt
            ),
            participantCount = members.size,
            accentColor = tokens.Accent,
            onRestore = { callManager.isMinimized.value = false },
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
                            avatarColor = memberInfoFor(members, track.participantIdentity)?.avatarColor
                                ?: identityColor(track.participantIdentity)
                        )
                    )
                }
                members
                    .filter { member ->
                        member.id != selfId && member.id !in identitiesWithVideo
                    }
                    .forEach { member ->
                        add(
                            RenderNode(
                                id = "remote_voice_${member.id}",
                                name = member.displayName.ifBlank { member.username },
                                renderer = null,
                                isScreenShare = false,
                                avatarUrl = member.avatarUrl,
                                avatarColor = member.avatarColor
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
                    .padding(top = 12.dp, bottom = 96.dp)
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (reconnecting) StatusChip("Reconnecting…", Color(0xFFFAA61A))
        if (audioOnlyFallback) StatusChip("No camera — joined audio-only", Color(0xFFFAA61A))
        if (waitingForRemoteMedia) StatusChip("Waiting for participants' media…", Color(0xFF00A8FC))
    }
}

@Composable
private fun StatusChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .background(Color(0xFF1E1F22).copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = tint)
    }
}

@Composable
private fun GroupCallMiniView(
    connection: GroupCallConnection,
    callName: String,
    stageLabel: String,
    participantCount: Int,
    accentColor: Color,
    onRestore: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    Box(modifier = modifier.wrapContentSize(Alignment.BottomEnd)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E1F22),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f)),
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
            Column(
                modifier = Modifier
                    .clickable(onClick = onRestore)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = callName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StatusChip("$participantCount in call", accentColor)
                }
                Text(
                    text = stageLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (connection.stage == GroupCallStage.Reconnecting) {
                        StatusChip("Reconnecting…", Color(0xFFFAA61A))
                    }
                    if (connection.audioOnlyFallback) {
                        StatusChip("Audio-only", Color(0xFFFAA61A))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onLeave) {
                        Text("Leave", color = Color(0xFFF23F42))
                    }
                }
            }
        }
    }
}
