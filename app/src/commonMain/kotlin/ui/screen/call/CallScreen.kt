package ui.screen.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import livekit.soundboardAddCustomSound
import livekit.soundboardCustomSounds
import livekit.soundboardRemoveCustomSound
import util.rememberFilePickerLauncher
import ui.assets.OrbitBrandMark
import ui.assets.OrbitDefaultAvatar
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState
import webrtc.DesktopSourceModel
import kotlin.math.roundToInt

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// ─── Layout mode enum ───────────────────────────────────────────────────────
enum class CallLayoutMode { Grid, Focus }

@Composable
fun CallScreen(
    callManager: CallManager,
    callerName: String = "Friend",
    onStartScreenShare: () -> Unit = {},
    isEmbedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val callState by callManager.callState.collectAsState()
    val isMinimized by callManager.isMinimized.collectAsState()
    val isIncomingCall by callManager.isIncomingCall.collectAsState()
    val isVideoCall by callManager.isVideoCall.collectAsState()
    val isMyHandRaised by callManager.isMyHandRaised.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    val isRemoteScreenSharing by callManager.isRemoteScreenSharing.collectAsState()
    val hasRemoteStream by callManager.hasRemoteStream.collectAsState()
    val tokens = rememberCosmicTokens()

    var showScreenShareDialog by remember { mutableStateOf(false) }
    var showSoundboard by remember { mutableStateOf(false) }
    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
    val isDeafened by callManager.isDeafened.collectAsState()

    if (callState == CallState.IDLE || callState == CallState.ENDED) {
        return
    }

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

    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isMuted = !isAudioEnabled

    // ── Call Timer ──
    var elapsedSeconds by remember { mutableStateOf(0L) }
    // Key on the connected flag, not the raw state: transient state flips while
    // connected (e.g. renegotiation) must not zero the running timer.
    LaunchedEffect(callState == CallState.CONNECTED) {
        if (callState == CallState.CONNECTED) {
            elapsedSeconds = 0L
            while (true) {
                delay(1000)
                elapsedSeconds++
            }
        } else {
            elapsedSeconds = 0L
        }
    }
    val timerText = remember(elapsedSeconds) {
        val h = elapsedSeconds / 3600
        val m = (elapsedSeconds % 3600) / 60
        val s = elapsedSeconds % 60
        if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }


    // Pulsing animation for ringing
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val ringGlow by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    // ── Minimized PiP View ──
    if (isMinimized && !isEmbedded) {
        var offsetX by remember { mutableStateOf(0f) }
        var offsetY by remember { mutableStateOf(0f) }

        Box(
            modifier = modifier.wrapContentSize(Alignment.BottomEnd)
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp)
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .size(320.dp, 200.dp)
                    .shadow(24.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(ui.theme.Ink950)
                    .border(1.dp, tokens.Accent.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .clickable { callManager.isMinimized.value = false }
            ) {
                // Only show a video tile once media actually arrived — grabbing
                // the remote renderer earlier renders a black frame; with no tile
                // available at all, fall through to the audio-only card.
                val pipRenderer = when {
                    isVideoCall && callState == CallState.CONNECTED && hasRemoteStream ->
                        callManager.webRtcClient.getRemoteVideoRenderer()
                    isVideoCall && isVideoEnabled ->
                        callManager.webRtcClient.getLocalVideoRenderer()
                    else -> null
                }
                if (pipRenderer != null) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        VideoRenderer(renderer = pipRenderer, modifier = Modifier.fillMaxSize())
                    }
                } else {
                    // Audio-only PiP
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    listOf(tokens.Accent.copy(alpha = 0.12f), Color.Transparent),
                                    radius = 300f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = tokens.Accent.copy(alpha = 0.2f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(OrbitIcons.Call, contentDescription = null, tint = tokens.Accent, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = callerName,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Timer overlay on PiP (top-left)
                if (callState == CallState.CONNECTED) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFF23A559).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(timerText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xFFFAA61A).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (callState == CallState.RINGING) "Ringing..." else "Connecting...",
                            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Expand icon
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = "Expand",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp)
                )
            }
        }
        return
    }

    // ══════════════════════════════════════════════════════════════════════
    //  FULL-SIZE CALL VIEW
    // ══════════════════════════════════════════════════════════════════════

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ui.theme.Ink950)
    ) {
        val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()
        val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()

        // Subtle dark pattern background
        ui.assets.OrbitChatDoodleBackground(selectedId = "auto", modifier = Modifier.fillMaxSize().alpha(0.04f))

        // ── Video Grid (Main) ──
        if (callState == CallState.CONNECTED) {
            val nodes = mutableListOf<RenderNode>()

            if (isScreenSharing) {
                nodes.add(RenderNode("screen", "Screen Share", localRenderer, isScreenShare = true))
            }
            
            val remoteScreenRenderer = callManager.webRtcClient.getRemoteScreenRenderer()
            if (isRemoteScreenSharing && remoteScreenRenderer != null) {
                nodes.add(RenderNode("remote_screen", "$callerName's Screen", remoteScreenRenderer, isScreenShare = true))
            }

            if (remoteRenderer != null && hasRemoteStream) {
                nodes.add(RenderNode("remote", callerName, remoteRenderer, isScreenShare = false, avatarColor = 0xFFD65A54.toInt()))
            }
            if (isVideoEnabled && !isScreenSharing) {
                nodes.add(RenderNode("local", "You", localRenderer, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened, isHandRaised = isMyHandRaised))
            } else if (!isScreenSharing) {
                nodes.add(RenderNode("local", "You", null, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened, isHandRaised = isMyHandRaised))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp)  // Space for header
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 80.dp) // Space for controls
            ) {
                CallGrid(
                    nodes = nodes,
                    tokens = tokens,
                    layoutMode = layoutMode,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // ── Ringing / Connecting Screen ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                tokens.Accent.copy(alpha = 0.08f),
                                ui.theme.Ink950
                            ),
                            radius = 600f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val isPulsing = callState == CallState.RINGING || callState == CallState.CONNECTING

                    Box(contentAlignment = Alignment.Center) {
                        // Outer pulse rings
                        if (isPulsing) {
                            Box(
                                modifier = Modifier
                                    .size(200.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(tokens.Accent.copy(alpha = pulseAlpha * 0.3f))
                            )
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .scale(pulseScale * 0.85f)
                                    .clip(CircleShape)
                                    .background(tokens.Accent.copy(alpha = pulseAlpha * 0.5f))
                            )
                        }

                        // Avatar circle
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            tokens.Accent.copy(alpha = 0.8f),
                                            ui.theme.Rust500.copy(alpha = 0.6f)
                                        )
                                    )
                                )
                                .border(3.dp, Color.White.copy(alpha = 0.1f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            OrbitBrandMark(modifier = Modifier.size(70.dp), showGlow = false)
                        }
                    }

                    Spacer(Modifier.height(32.dp))

                    Text(
                        callerName,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(Modifier.height(8.dp))

                    // Status text with animated dots
                    val dotCount by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 4f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        )
                    )
                    val statusDots = ".".repeat(dotCount.toInt().coerceIn(0, 3))

                    Text(
                        when (callState) {
                            CallState.RINGING -> "Calling$statusDots"
                            CallState.CONNECTING -> "Connecting$statusDots"
                            else -> ""
                        },
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Show call type indicator
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (isVideoCall) OrbitIcons.Video else OrbitIcons.Call,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            if (isVideoCall) "Video Call" else "Voice Call",
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        //  HEADER BAR (Top)
        // ══════════════════════════════════════════════════════════════════
        AnimatedVisibility(
            visible = true,
            enter = fadeIn(tween(200)) + slideInVertically { -it },
            exit = fadeOut(tween(200)) + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(ui.theme.Ink950, ui.theme.Ink950.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Left: Back button + channel name
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!isEmbedded) {
                        IconButton(
                            onClick = { callManager.isMinimized.value = true },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    Column {
                        Text(
                            callerName,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (callState == CallState.CONNECTED) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF23A559))
                                )
                                Text(
                                    timerText,
                                    color = Color(0xFF23A559),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Right: Layout info
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isScreenSharing) {
                        Box(
                            modifier = Modifier
                                .background(tokens.Danger, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White)
                                )
                                Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }

        // ── Incoming Call UI Overlay ──
        AnimatedVisibility(
            visible = callState == CallState.RINGING && callManager.callId != null && isIncomingCall,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                tokens.Accent.copy(alpha = 0.1f),
                                Color(0xFF000000).copy(alpha = 0.92f)
                            ),
                            radius = 500f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Pulsing avatar
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(160.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(tokens.Accent.copy(alpha = pulseAlpha * 0.3f))
                        )
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(tokens.Accent, ui.theme.Rust500)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            OrbitBrandMark(modifier = Modifier.size(60.dp), showGlow = false)
                        }
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Incoming Call",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        callerName,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.height(48.dp))

                    // Accept / Decline buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.endCall() },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(ui.theme.Rust500, CircleShape)
                                    .shadow(8.dp, CircleShape)
                            ) {
                                Icon(OrbitIcons.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Decline", color = ui.theme.Rust500, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.answerCall(isVideo = false) },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color(0xFF23A559), CircleShape)
                                    .shadow(8.dp, CircleShape)
                            ) {
                                Icon(OrbitIcons.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Accept", color = Color(0xFF23A559), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                        }

                        if (isVideoCall) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = { callManager.answerCall(isVideo = true) },
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color(0xFF7B74E8), CircleShape)
                                        .shadow(8.dp, CircleShape)
                                ) {
                                    Icon(OrbitIcons.Video, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("Video", color = Color(0xFF7B74E8), fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Soundboard Panel Overlay ──
        AnimatedVisibility(
            visible = showSoundboard,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)
        ) {
            SoundboardPanel(
                callManager = callManager,
                onDismiss = { showSoundboard = false }
            )
        }

        // ── Bottom Controls Bar (always visible) ──
        if (callState == CallState.CONNECTED || callState == CallState.CONNECTING || (callState == CallState.RINGING && !isIncomingCall)) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
            ) {
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
                    onScreenShareClick = {
                        if (isScreenSharing) {
                            callManager.stopScreenShare()
                        } else {
                            showScreenShareDialog = true
                        }
                    },
                    // Real P2P playback-mute hook (remote AudioTrack volume).
                    showDeafen = true,
                    showSoundboard = showSoundboard,
                    onSoundboardToggle = { showSoundboard = !showSoundboard }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SOUNDBOARD PANEL
// ═══════════════════════════════════════════════════════════════════════════

data class SoundEffect(
    val emoji: String,
    val name: String
)

private val soundEffects = listOf(
    SoundEffect("👏", "Applause"),
    SoundEffect("😂", "Laugh"),
    SoundEffect("📯", "Airhorn"),
    SoundEffect("🥁", "Drumroll"),
    SoundEffect("🎉", "Party"),
    SoundEffect("😮", "Wow"),
    SoundEffect("👍", "Nice"),
    SoundEffect("🔔", "Ding"),
    SoundEffect("💀", "Bruh"),
    SoundEffect("🎵", "Music"),
    SoundEffect("🐱", "Meow"),
    SoundEffect("🦆", "Quack"),
)

@Composable
fun SoundboardPanel(
    callManager: CallManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    var effectVolume by remember { mutableStateOf(1f) }
    val customSounds by soundboardCustomSounds().collectAsState()
    var uploadError by remember { mutableStateOf<String?>(null) }

    val launchSoundPicker = rememberFilePickerLauncher { bytes, filename, _ ->
        val error = soundboardAddCustomSound(filename, bytes)
        uploadError = error
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ui.theme.Ink650.copy(alpha = 0.95f),
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = modifier.width(360.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Soundboard",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 240.dp)
            ) {
                val tileCount = soundEffects.size + customSounds.size + 1
                // Keyed: the trailing "Add sound" tile sits at a different index
                // as soon as a custom sound is added, so an unkeyed grid reused a
                // sound tile's slot for the Add tile and vice versa.
                items(
                    count = tileCount,
                    key = { index ->
                        if (index < soundEffects.size) {
                            "effect_${soundEffects[index].name}"
                        } else if (index < soundEffects.size + customSounds.size) {
                            "custom_${customSounds[index - soundEffects.size].id}"
                        } else {
                            "add_sound"
                        }
                    },
                    contentType = { index ->
                        when {
                            index < soundEffects.size -> "effect"
                            index < soundEffects.size + customSounds.size -> "custom"
                            else -> "add"
                        }
                    }
                ) { index ->
                    when {
                        index < soundEffects.size -> {
                            val effect = soundEffects[index]
                            var isHovered by remember { mutableStateOf(false) }
                            val hoverInteraction = remember { MutableInteractionSource() }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isHovered) ui.theme.Ink700 else ui.theme.Ink650,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .hoverable(hoverInteraction)
                                    .clickable { callManager.playSoundEffect(effect.name.lowercase()) }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(effect.emoji, fontSize = 24.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        effect.name,
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        index < soundEffects.size + customSounds.size -> {
                            val custom = customSounds[index - soundEffects.size]

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF1A3A2C),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clickable { callManager.playSoundEffect(custom.id) }
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("📁", fontSize = 24.sp)
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            custom.displayName,
                                            color = Color(0xFF7FE0AE),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { soundboardRemoveCustomSound(custom.id) },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove ${custom.displayName}",
                                            tint = Color.White.copy(alpha = 0.45f),
                                            modifier = Modifier.size(10.dp)
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = ui.theme.Ink650,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clickable {
                                        uploadError = null
                                        launchSoundPicker()
                                    }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text("＋", fontSize = 22.sp, color = tokens.Accent)
                                    Text(
                                        "Add",
                                        color = Color.White.copy(alpha = 0.6f),
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (uploadError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    uploadError ?: "",
                    color = ui.theme.Rust500,
                    fontSize = 11.sp
                )
            } else {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Upload your own (WAV/AIFF/AU, max 10 s)",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Volume",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Slider(
                    value = effectVolume,
                    onValueChange = {
                        effectVolume = it
                        callManager.setSoundEffectVolume(it)
                    },
                    valueRange = 0f..2f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                TextButton(onClick = { callManager.stopSoundEffects() }) {
                    Text("Stop All", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SCREEN SHARE DIALOG
// ═══════════════════════════════════════════════════════════════════════════

// Quality / FPS pill row used by the share dialog; both write the same
// SettingsManager keys the capture paths already read at share start.
@Composable
private fun ShareOptionRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val tokens = rememberCosmicTokens()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(72.dp)
        )
        options.forEach { option ->
            val isSelected = option.equals(selected, ignoreCase = true)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) tokens.Accent.copy(alpha = 0.25f) else ui.theme.Ink700,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) tokens.Accent else Color.White.copy(alpha = 0.08f)
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(option) }
            ) {
                Text(
                    option,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
fun ScreenShareDialog(
    callManager: CallManager,
    onDismiss: () -> Unit,
    onSourceSelected: (webrtc.DesktopSourceModel) -> Unit
) {
    var sources by remember { mutableStateOf<List<webrtc.DesktopSourceModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var shareSystemAudio by remember { mutableStateOf(false) }
    // Pre-share pickers for the SAME settings the capture paths read at
    // startScreenShare; persisted on source selection below.
    val shareSettings = remember { repository.SettingsManager() }
    var shareQuality by remember {
        mutableStateOf(runCatching { shareSettings.screenShareQualitySync() }.getOrDefault("1080p"))
    }
    var shareFps by remember {
        mutableStateOf(runCatching { shareSettings.screenShareFpsSync() }.getOrDefault(30))
    }
    val tokens = rememberCosmicTokens()

    LaunchedEffect(Unit) {
        runCatching { callManager.getDesktopSources() }
            .onSuccess {
                sources = it
                if (it.isEmpty()) loadError = "No screens or windows were found to share."
            }
            .onFailure { loadError = "Could not enumerate screens: ${it.message ?: it::class.simpleName}" }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ui.theme.Ink650,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(OrbitIcons.ScreenShare, null, tint = tokens.Accent, modifier = Modifier.size(22.dp))
                Text("Share Your Screen", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = tokens.Accent, strokeWidth = 3.dp)
                }
            } else if (loadError != null) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text(
                        loadError ?: "",
                        color = Color.White.copy(alpha = 0.7f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                Column {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 400.dp).weight(1f, fill = false),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            count = sources.size,
                            key = { index -> sources[index].id },
                            contentType = { "share_source" }
                        ) { index ->
                            val source = sources[index]
                            var isHovered by remember { mutableStateOf(false) }
                            val hoverInteraction = remember { MutableInteractionSource() }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .hoverable(hoverInteraction)
                                    .clickable {
                                        callManager.setStreamAudioEnabled(shareSystemAudio)
                                        shareSettings.setScreenShareQuality(shareQuality.lowercase())
                                        shareSettings.setScreenShareFps(shareFps.coerceIn(5, 60))
                                        onSourceSelected(source)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isHovered) ui.theme.Ink600 else ui.theme.Ink700
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if (isHovered) tokens.Accent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.06f)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        if (source.isWindow) Icons.Default.Window else Icons.Default.Monitor,
                                        contentDescription = null,
                                        tint = tokens.Accent,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        source.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    ShareOptionRow(
                        label = "Quality",
                        options = listOf("720p", "1080p", "1440p", "Source"),
                        selected = shareQuality,
                        onSelect = { shareQuality = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ShareOptionRow(
                        label = "Frame rate",
                        options = listOf("15", "30", "60"),
                        selected = shareFps.toString(),
                        onSelect = { shareFps = it.toInt() }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ui.theme.Ink700,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { shareSystemAudio = !shareSystemAudio }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = shareSystemAudio,
                                onCheckedChange = { shareSystemAudio = it },
                                colors = CheckboxDefaults.colors(checkedColor = tokens.Accent, checkmarkColor = Color.White)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Share System Audio", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text("Others will hear audio from your computer", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(alpha = 0.6f))
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
//  DATA MODELS & VIDEO COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════

data class RenderNode(
    val id: String,
    val name: String,
    val renderer: Any?,
    val isScreenShare: Boolean,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    // Remote participant whose mic track is muted (group calls). Local nodes
    // use isMuted instead.
    val isMicMuted: Boolean = false,
    // LiveKit identity for remote participants (group calls). Non-null enables
    // per-participant volume/mute controls on the tile; never set on local nodes.
    val participantIdentity: String? = null,
    // Latest jitter/RTT/loss sample from the native stats watcher (remote
    // audio tiles); renders Discord-style signal bars when present.
    val qualitySample: livekit.ConnectionQualitySample? = null,
    // Participant's hand is currently raised (io.orbit.raise_hand events).
    val isHandRaised: Boolean = false,
    val avatarUrl: String? = null,
    val avatarColor: Int = 0xFF7B74E8.toInt(),
    // Remote screen share whose track the SDK never produced. The tile has nothing
    // to draw and retrying has stopped helping, so it carries the offer to rebuild
    // the SFU session instead of sitting there blank.
    val isStuckShare: Boolean = false
)

/**
 * Mic-mute match across identity shapes: the muted set holds LiveKit
 * identities (`${userId}:${deviceId}`) while callers may hold either that
 * full form (tiles) or a bare Matrix userId (member list).
 */
internal fun identityIsMicMuted(identity: String?, mutedIdentities: Set<String>): Boolean {
    if (identity.isNullOrBlank() || mutedIdentities.isEmpty()) return false
    if (identity in mutedIdentities) return true
    return mutedIdentities.any { it.substringBeforeLast(':') == identity }
}

/**
 * Raise-hand match: the set holds Matrix userIds (event senders) while tile
 * identities may be full LiveKit form (`${userId}:${deviceId}`) or bare mxids.
 */
internal fun identityHasRaisedHand(identity: String?, raisedUserIds: Set<String>): Boolean {
    if (identity.isNullOrBlank() || raisedUserIds.isEmpty()) return false
    if (identity in raisedUserIds) return true
    return identity.substringBeforeLast(':') in raisedUserIds
}

/**
 * Active-speaker match, tolerant of the shapes the metering feed can carry.
 *
 * The native mixer publishes the LiveKit identity of whoever is talking
 * (`${userId}:${deviceId}`), so the exact-set test is the correct fast path.
 * The userId-prefix test mirrors [identityIsMicMuted] on purpose: a peer on two
 * devices is two identities but ONE person, and the ring belongs to the person.
 */
internal fun identityIsSpeaking(identity: String?, speakers: Set<String>): Boolean {
    if (identity.isNullOrBlank() || speakers.isEmpty()) return false
    if (identity in speakers) return true
    return speakers.any { it.substringBeforeLast(':') == identity }
}

/**
 * Discord-style ascending signal bars: 3 lit = good (green), 2 = degraded
 * (yellow), 1 = bad (red). Unlit bars stay dim.
 */
@Composable
internal fun ConnectionQualityBars(bars: Int, modifier: Modifier = Modifier) {
    val litColor = when {
        bars >= 3 -> ui.theme.Sage500
        bars == 2 -> ui.theme.Amber500
        else -> ui.theme.Rust500
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        listOf(4.dp, 7.dp, 10.dp).forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = height)
                    .background(
                        if (index < bars) litColor else Color.White.copy(alpha = 0.25f),
                        RoundedCornerShape(1.dp)
                    )
            )
        }
    }
}

@Composable
fun VideoNodeCard(node: RenderNode, tokens: ui.theme.CosmicTokens, modifier: Modifier = Modifier, callManager: webrtc.CallManager? = null, isPinned: Boolean = false, onPinToggle: ((String) -> Unit)? = null) {
    // Active-speaker glow: native mixer meters remote voices; a tile whose
    // participant is talking gets an animated accent ring + soft halo.
    val speakers = if (callManager != null) callManager.activeSpeakers.collectAsState().value else emptySet()
    val isSpeaking = identityIsSpeaking(node.participantIdentity, speakers)
    val speakGlow by animateFloatAsState(
        targetValue = if (isSpeaking) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "speakGlow"
    )
    val borderColor = animateColorAsState(
        if (node.isScreenShare) {
            tokens.Danger
        } else {
            // Speaking green, not the accent. Discord's active-speaker border is
            // green, and Orbit's own sidebar roster already uses Success for the
            // same signal -- two colours for one meaning is worse than either.
            lerp(tokens.Separator, tokens.Success, speakGlow)
        },
        label = "borderColor"
    )
    // 2dp, not 1dp + 1dp*glow. A 1px accent hairline around a 650px tile is
    // invisible at a glance, which is why the speaking indicator read as
    // "not implemented" rather than "quiet".
    val borderWidth = if (node.isScreenShare) 2.dp else 2.dp
    // The speaking ring is drawn as an OVERLAY (see below), not as this Box's
    // own border, so its width stays 2dp. It used to live here at
    // 1.dp + 1.dp * speakGlow, i.e. 1dp at rest — a hairline at the exact edge
    // of a fillMaxSize() child, which is invisible in practice.
    // Discord convention: the speaker's name goes green while they talk.
    val nameColor by animateColorAsState(
        if (isSpeaking) tokens.Success else Color.White,
        animationSpec = tween(durationMillis = 240),
        label = "speakerNameColor"
    )

    var participantMenuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = 18.dp * speakGlow,
                shape = RoundedCornerShape(8.dp),
                ambientColor = tokens.Accent,
                spotColor = tokens.Accent
            )
            .clip(RoundedCornerShape(8.dp))
            // Token, not the literal ui.theme.Ink900 this used to hardcode.
            //
            // That literal was the OLD Ink800. When the palette moved to Discord's
            // neutrals the surrounding surface became #313338 while every tile
            // stayed #14171D, so each participant rendered as a near-black hole in
            // a lighter stage with a border that was almost the same value as the
            // fill. Hardcoding a colour that also exists in the ramp means the tile
            // silently stops matching the app the moment the ramp moves -- which
            // is exactly what happened.
            .background(tokens.Sunken)
            .border(borderWidth, borderColor.value, RoundedCornerShape(8.dp))
            .pointerInput(node.participantIdentity) {
                if (node.participantIdentity == null) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            participantMenuOpen = true
                        }
                    }
                }
            }
    ) {
        // Avatar size scales with the tile.
        //
        // Fixed at 64dp it read as a coin lost in a 650x900 tile: the one thing a
        // camera-off participant has to identify them by was the smallest element
        // on screen. Discord scales it to roughly a quarter of the tile's short
        // edge, so a small tile gets a small avatar and a full-screen one does not
        // look abandoned.
        val avatarSize = (minOf(maxWidth, maxHeight) * 0.26f).coerceIn(56.dp, 180.dp)

        if (node.renderer != null) {
            VideoRenderer(renderer = node.renderer, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
        } else {
            // Audio-only fallback with radial gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(Color(node.avatarColor).copy(alpha = 0.1f), Color.Transparent),
                            radius = 400f
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(Color(node.avatarColor).copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (node.avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = node.avatarUrl,
                            contentDescription = "Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        Text(
                            node.name.take(1).uppercase(),
                            color = Color.White,
                            // Scales with the circle, so a large tile does not
                            // render a single 26sp letter floating in the middle.
                            fontSize = (avatarSize.value * 0.4f).sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // A stuck remote share gets a real explanation and a real action instead of
        // a black rectangle. Retrying its subscription does not work -- the audio
        // track of the same share recovers and the video track never does -- so the
        // one thing that works is offered: rebuild the SFU session. Offered rather
        // than forced, because it briefly restarts everyone's audio from us.
        if (node.isStuckShare) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Screen share can't be received",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${node.name}'s share is being sent but its video never attaches.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Button(
                    onClick = { callManager?.reconnectForStuckShare("user-tapped") },
                    enabled = callManager != null
                ) {
                    Text("Reconnect call media")
                }
            }
        }

        // Raised-hand badge (Discord-style top-left)
        if (node.isHandRaised) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .background(tokens.Warning.copy(alpha = 0.92f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 5.dp)
            ) {
                Icon(
                    Icons.Default.PanTool,
                    contentDescription = "Hand raised",
                    tint = ui.theme.Ink950,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // Name Overlay (Discord-style bottom left pill)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(tokens.Sunken.copy(alpha = 0.86f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (node.isMuted || node.isMicMuted) {
                    Icon(OrbitIcons.MicOff, null, tint = tokens.Danger, modifier = Modifier.size(14.dp))
                }
                if (node.isDeafened) {
                    Icon(Icons.Default.HeadsetOff, null, tint = tokens.Danger, modifier = Modifier.size(14.dp))
                }
                Text(
                    node.name,
                    color = nameColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Screen Share Badge (top-right)
        if (node.isScreenShare) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(tokens.Danger, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                    Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }

        // Connection-quality signal bars (top-right, remote audio tiles only):
        // 3 bars green / 2 yellow / 1 red from the native jitter/RTT/loss feed.
        val sampleBars = node.qualitySample?.bars ?: 0
        if (!node.isScreenShare && node.participantIdentity != null && sampleBars > 0) {
            ConnectionQualityBars(
                bars = sampleBars,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(tokens.Sunken.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 4.dp)
            )
        }

        // Per-participant volume / local mute — remote group-call tiles only
        // (cam/voice tiles; a screen-share tile mirrors the same identity's mic,
        // so controlling audio from there would be confusing).
        // Local-only: changes the gain this client mixes that person at; the
        // remote side's mic and everyone else are untouched.
        val participantIdentity = node.participantIdentity
        if (participantIdentity != null && !node.isScreenShare && callManager != null) {
            val gains by callManager.participantGains.collectAsState()
            val volume = gains[participantIdentity] ?: 1f
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(tokens.Sunken.copy(alpha = 0.86f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { callManager.toggleParticipantMute(participantIdentity) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        if (volume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = if (volume == 0f) "Unmute participant locally" else "Mute participant locally",
                        tint = if (volume == 0f) ui.theme.Rust500 else Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Slider(
                    value = volume,
                    onValueChange = { callManager.setParticipantVolume(participantIdentity, it) },
                    valueRange = 0f..2f,
                    modifier = Modifier
                        .width(72.dp)
                        .height(24.dp)
                )
            }
        }

        // Right-click context menu — per-participant audio controls (Element/
        // Discord style). Everything here is LOCAL: it changes how THIS client
        // renders that person, never their actual mic or stream.
        val menuIdentity = node.participantIdentity
        if (menuIdentity != null && callManager != null) {
            DropdownMenu(
                expanded = participantMenuOpen,
                onDismissRequest = { participantMenuOpen = false }
            ) {
                val menuGains by callManager.participantGains.collectAsState()
                val menuVolume = menuGains[menuIdentity] ?: 1f
                val clipboard = LocalClipboardManager.current

                Text(
                    node.name,
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (menuVolume == 0f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Slider(
                        value = menuVolume,
                        onValueChange = { callManager.setParticipantVolume(menuIdentity, it) },
                        valueRange = 0f..2f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(menuVolume * 100).toInt()}%",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }

                DropdownMenuItem(
                    text = { Text("Reset volume") },
                    onClick = {
                        callManager.setParticipantVolume(menuIdentity, 1f)
                    },
                    enabled = menuVolume != 1f
                )
                // Share tiles carry the same local-gain semantics; "stream"
                // just names the thing being muted more clearly.
                val streamWording = node.isScreenShare
                DropdownMenuItem(
                    text = {
                        Text(
                            when {
                                streamWording && menuVolume == 0f -> "Unmute stream"
                                streamWording -> "Mute stream"
                                menuVolume == 0f -> "Unmute for me"
                                else -> "Mute for me"
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (menuVolume == 0f) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        callManager.toggleParticipantMute(menuIdentity)
                        participantMenuOpen = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("Copy user ID") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    onClick = {
                        clipboard.setText(AnnotatedString(menuIdentity))
                        participantMenuOpen = false
                    }
                )
                if (onPinToggle != null && node.id != "local" && node.id != "local_share") {
                    DropdownMenuItem(
                        text = { Text(if (isPinned) "Unpin" else "Pin") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        onClick = {
                            onPinToggle(node.id)
                            participantMenuOpen = false
                        }
                    )
                }
            }
        }

        // Active-speaker ring, drawn LAST so nothing the tile paints can hide it.
        //
        // It used to be `.border()` on the outer BoxWithConstraints. A Box draws
        // its own border during ITS draw pass, which happens BEFORE its children
        // — and the first child is `Modifier.fillMaxSize()` (the video frame, or
        // the radial-gradient avatar background). A 1-2dp ring at the exact edge
        // of a full-bleed child is therefore completely painted over, so the
        // speaking indicator could never be seen no matter what the meter said.
        // As a matchParentSize overlay it composites on top.
        //
        // Rendered unconditionally with alpha 0 when quiet rather than behind an
        // `if`, so a tile does not gain/lose a layout node every time someone
        // starts and stops talking.
        if (!node.isScreenShare) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(
                        width = 2.dp + 1.dp * speakGlow,
                        color = tokens.Success.copy(alpha = speakGlow),
                        shape = RoundedCornerShape(8.dp)
                    )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  AUDIO DEVICE MENU
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AudioDeviceMenu(
    callManager: CallManager,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    var inputs by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedInput by remember { mutableStateOf<String?>(null) }
    var selectedOutput by remember { mutableStateOf<String?>(null) }
    val settingsManager = remember { repository.SettingsManager() }
    // Camera selection is setting-driven: WebRtcEffects observes cameraDevice
    // and hot-switches the capture device, so persisting here is enough mid-call.
    val savedCamera by settingsManager.cameraDevice.collectAsState("Default")

    LaunchedEffect(expanded) {
        if (expanded) {
            inputs = callManager.webRtcClient.getAudioInputDevices()
            outputs = callManager.webRtcClient.getAudioOutputDevices()
            cameras = listOf("Default") +
                callManager.webRtcClient.getVideoCaptureDevices().filter { it != "Default" }
            selectedInput = callManager.webRtcClient.getSelectedAudioInputDevice()
            selectedOutput = callManager.webRtcClient.getSelectedAudioOutputDevice()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(ui.theme.Ink650).width(260.dp)
    ) {
        // ── INPUT DEVICE ──
        Text(
            "INPUT DEVICE",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
        inputs.forEach { device ->
            val isSelected = device == selectedInput
            DropdownMenuItem(
                text = {
                    Text(
                        device,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                onClick = {
                    callManager.setAudioInputDevice(device)
                    settingsManager.setInputDevice(device)
                    selectedInput = device
                    onDismiss()
                },
                leadingIcon = {
                    Icon(OrbitIcons.Mic, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "Active device", tint = Color(0xFF23A559), modifier = Modifier.size(16.dp))
                    }
                }
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))

        // ── OUTPUT DEVICE ──
        Text(
            "OUTPUT DEVICE",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
        outputs.forEach { device ->
            val isSelected = device == selectedOutput
            DropdownMenuItem(
                text = {
                    Text(
                        device,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                onClick = {
                    callManager.setAudioOutputDevice(device)
                    settingsManager.setOutputDevice(device)
                    selectedOutput = device
                    onDismiss()
                },
                leadingIcon = {
                    Icon(Icons.Default.Headphones, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "Active device", tint = Color(0xFF23A559), modifier = Modifier.size(16.dp))
                    }
                }
            )
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 4.dp))

        // ── CAMERA ──
        Text(
            "CAMERA",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
        cameras.forEach { device ->
            val isSelected = device == savedCamera
            DropdownMenuItem(
                text = {
                    Text(
                        device,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.75f),
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                onClick = {
                    settingsManager.setCameraDevice(device)
                    onDismiss()
                },
                leadingIcon = {
                    Icon(OrbitIcons.Video, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                },
                trailingIcon = {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = "Active camera", tint = Color(0xFF23A559), modifier = Modifier.size(16.dp))
                    }
                }
            )
        }
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
