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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
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
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    val hasRemoteStream by callManager.hasRemoteStream.collectAsState()
    val tokens = rememberCosmicTokens()

    var showScreenShareDialog by remember { mutableStateOf(false) }
    var showSoundboard by remember { mutableStateOf(false) }
    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
    var isDeafened by remember { mutableStateOf(false) }

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
    var includeStreamAudio by remember { mutableStateOf(false) }

    // ── Call Timer ──
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(callState) {
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
                    .background(Color(0xFF111214))
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
                if (isVideoCall && (isVideoEnabled || callState == CallState.CONNECTED)) {
                    val renderer = if (callState == CallState.CONNECTED) {
                        callManager.webRtcClient.getRemoteVideoRenderer()
                    } else {
                        callManager.webRtcClient.getLocalVideoRenderer()
                    }
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        if (renderer != null) {
                            VideoRenderer(renderer = renderer, modifier = Modifier.fillMaxSize())
                        }
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
            .background(Color(0xFF111214))
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
            if (remoteRenderer != null && hasRemoteStream) {
                nodes.add(RenderNode("remote", callerName, remoteRenderer, isScreenShare = false, avatarColor = 0xFFF23F42.toInt()))
            }
            if (isVideoEnabled && !isScreenSharing) {
                nodes.add(RenderNode("local", "You", localRenderer, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened))
            } else if (!isScreenSharing) {
                nodes.add(RenderNode("local", "You", null, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened))
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
                                Color(0xFF111214)
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
                                            Color(0xFFF23F42).copy(alpha = 0.6f)
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
                            listOf(Color(0xFF111214), Color(0xFF111214).copy(alpha = 0.85f), Color.Transparent)
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
                                .background(Color(0xFFF23F42), RoundedCornerShape(4.dp))
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
                                        listOf(tokens.Accent, Color(0xFFF23F42))
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
                                    .background(Color(0xFFF23F42), CircleShape)
                                    .shadow(8.dp, CircleShape)
                            ) {
                                Icon(OrbitIcons.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Decline", color = Color(0xFFF23F42), fontWeight = FontWeight.Medium, fontSize = 13.sp)
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
                                        .background(Color(0xFF5865F2), CircleShape)
                                        .shadow(8.dp, CircleShape)
                                ) {
                                    Icon(OrbitIcons.Video, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(28.dp))
                                }
                                Spacer(Modifier.height(12.dp))
                                Text("Video", color = Color(0xFF5865F2), fontWeight = FontWeight.Medium, fontSize = 13.sp)
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
                    showSoundboard = showSoundboard,
                    onToggleDeafen = { isDeafened = !isDeafened },
                    onLayoutModeToggle = {
                        layoutMode = if (layoutMode == CallLayoutMode.Grid) CallLayoutMode.Focus else CallLayoutMode.Grid
                    },
                    onSoundboardToggle = { showSoundboard = !showSoundboard },
                    onScreenShareClick = {
                        if (isScreenSharing) {
                            callManager.stopScreenShare()
                        } else {
                            showScreenShareDialog = true
                        }
                    }
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
    onDismiss: () -> Unit
) {
    val tokens = rememberCosmicTokens()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1E1F22).copy(alpha = 0.95f),
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.width(360.dp)
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
                modifier = Modifier.heightIn(max = 200.dp)
            ) {
                items(soundEffects.size) { index ->
                    val effect = soundEffects[index]
                    var isHovered by remember { mutableStateOf(false) }
                    val hoverInteraction = remember { MutableInteractionSource() }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isHovered) Color(0xFF2B2D31) else Color(0xFF232428),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .hoverable(hoverInteraction)
                            .clickable {
                                // Sound effect would be played via CallManager in the future
                            }
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
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  SCREEN SHARE DIALOG
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ScreenShareDialog(
    callManager: CallManager,
    onDismiss: () -> Unit,
    onSourceSelected: (webrtc.DesktopSourceModel) -> Unit
) {
    var sources by remember { mutableStateOf<List<webrtc.DesktopSourceModel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var shareSystemAudio by remember { mutableStateOf(false) }
    val tokens = rememberCosmicTokens()

    LaunchedEffect(Unit) {
        sources = callManager.getDesktopSources()
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1F22),
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
            } else {
                Column {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 400.dp).weight(1f, fill = false),
                        contentPadding = PaddingValues(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sources.size) { index ->
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
                                        onSourceSelected(source)
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isHovered) Color(0xFF323438) else Color(0xFF2B2D31)
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

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF2B2D31),
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
    val avatarUrl: String? = null,
    val avatarColor: Int = 0xFF5865F2.toInt()
)

@Composable
fun VideoNodeCard(node: RenderNode, tokens: ui.theme.CosmicTokens, modifier: Modifier = Modifier) {
    val isSpeaking = node.id == "local" && !node.isMuted
    val borderColor = animateColorAsState(
        when {
            node.isScreenShare -> Color(0xFFF23F42)
            isSpeaking -> Color(0xFF23A559)
            else -> Color(0xFF1E1F22)
        },
        label = "borderColor"
    )
    val borderWidth = if (isSpeaking || node.isScreenShare) 2.dp else 1.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF18191C))
            .border(borderWidth, borderColor.value, RoundedCornerShape(8.dp))
    ) {
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
                        .size(64.dp)
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
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Name Overlay (Discord-style bottom left pill)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(Color(0xFF111214).copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (node.isMuted) {
                    Icon(OrbitIcons.MicOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(14.dp))
                }
                if (node.isDeafened) {
                    Icon(Icons.Default.HeadsetOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(14.dp))
                }
                Text(
                    node.name,
                    color = Color.White,
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
                    .background(Color(0xFFF23F42), RoundedCornerShape(4.dp))
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

    LaunchedEffect(expanded) {
        if (expanded) {
            inputs = callManager.webRtcClient.getAudioInputDevices()
            outputs = callManager.webRtcClient.getAudioOutputDevices()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF1E1F22)).width(260.dp)
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
            DropdownMenuItem(
                text = { Text(device, color = Color.White, fontSize = 13.sp) },
                onClick = {
                    callManager.setAudioInputDevice(device)
                    onDismiss()
                },
                leadingIcon = {
                    Icon(OrbitIcons.Mic, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
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
            DropdownMenuItem(
                text = { Text(device, color = Color.White, fontSize = 13.sp) },
                onClick = {
                    callManager.setAudioOutputDevice(device)
                    onDismiss()
                },
                leadingIcon = {
                    Icon(Icons.Default.Headphones, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
