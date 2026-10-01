package ui.screen.call

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallShortcuts
import webrtc.CallState
import webrtc.DesktopSourceModel
import kotlin.math.roundToInt

import ui.screen.auth.*
import ui.components.OrbitIconButton
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
    /**
     * The other person's avatar, already converted to an http(s) URL by the
     * repository that resolved it.
     *
     * This screen is the P2P surface and previously had no way to receive it: the
     * two tiles were built from [callerName] plus a hard-coded red, so every 1:1
     * call showed a red circle with a letter in it no matter what picture the
     * other person had set. The group surface has had avatar resolution for a
     * while; this is the same idea brought across, not a new mechanism.
     */
    callerAvatarUrl: String? = null,
    /** Our own avatar, same story and same source. */
    myAvatarUrl: String? = null,
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
    var pinnedNodeId by remember { mutableStateOf<String?>(null) }
    val isDeafened by callManager.isDeafened.collectAsState()

    // `.voice-room-maximized` / `isMaximized`: the share takes the whole stage on
    // pure black and the faces step aside. Escape leaves it, which is the
    // affordance the concept states in the button's own tooltip
    // ("Exit Full Screen (ESC)").
    //
    // The handler is only published while the stage is actually maximised, so
    // Escape keeps meaning "close whatever is open" everywhere else in the app.
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
    // A call that ends must not leave a stage "maximised" for the next one.
    LaunchedEffect(callState) {
        if (callState == CallState.IDLE || callState == CallState.ENDED) isStageMaximized = false
    }

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
    //
    // Gated on `isMinimized` ALONE, and this is a fix.
    //
    // It used to read `if (isMinimized && !isEmbedded)`, while the 1:1 surface in
    // OrbitApp is always mounted with `isEmbedded = true`. So the minimize button
    // set `isMinimized = true`, the PiP branch never ran, and the full-bleed
    // surface kept drawing over the whole app. The button was a dead control: you
    // could press it and nothing happened, and a 1:1 call was a full-screen modal
    // with no way back to the app.
    //
    // `isEmbedded` was doing two unrelated jobs here. It means "don't draw your own
    // corner card, because the host supplies one" -- which is why OrbitApp passes
    // it -- and it was ALSO being read as "the host handles minimising", which is
    // not true of any host. Only this branch cares, and it is the branch that was
    // broken.
    //
    // Returning here rather than falling through matters as much as the condition:
    // rendering both would draw the PiP on top of the full view, which is the bug
    // in the other direction.
    if (isMinimized) {
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
                            .background(Color(0xFF23A55A).copy(alpha = 0.9f), RoundedCornerShape(4.dp))
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

                // Hang up, from the card.
                //
                // Unreachable until the gate above was fixed, so this is not a
                // pre-existing gap -- it is one that became reachable the moment
                // minimizing worked at all. A minimized call you cannot end from
                // its own card is a dead end: the only route out is to expand it
                // again, which undoes the thing you just did.
                //
                // It also has to beat the card's own click-to-restore, so it is a
                // sibling rather than a child -- a nested clickable consumes the
                // gesture and the card underneath would never see it.
                OrbitIconButton(
                    icon = OrbitIcons.CallEnd,
                    contentDescription = "End call",
                    onClick = { callManager.endCall() },
                    danger = true,
                    size = 32.dp,
                    iconSize = 16.dp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
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
                nodes.add(
                    RenderNode(
                        "remote",
                        callerName,
                        remoteRenderer,
                        isScreenShare = false,
                        avatarUrl = callerAvatarUrl,
                        avatarColor = identityColor(callerName)
                    )
                )
            } else {
                nodes.add(
                    RenderNode(
                        "remote",
                        callerName,
                        null,
                        isScreenShare = false,
                        avatarUrl = callerAvatarUrl,
                        avatarColor = identityColor(callerName)
                    )
                )
            }
            if (isVideoEnabled && !isScreenSharing) {
                nodes.add(RenderNode("local", "You", localRenderer, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened, isHandRaised = isMyHandRaised, avatarUrl = myAvatarUrl))
            } else if (!isScreenSharing) {
                nodes.add(RenderNode("local", "You", null, isScreenShare = false, isMuted = isMuted, isDeafened = isDeafened, isHandRaised = isMyHandRaised, avatarUrl = myAvatarUrl))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (isStageMaximized) 0.dp else 56.dp)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = if (isStageMaximized) 12.dp else 96.dp)
            ) {
                CallGrid(
                    nodes = nodes,
                    tokens = tokens,
                    layoutMode = layoutMode,
                    callManager = callManager,
                    pinnedNodeId = pinnedNodeId,
                    onPinToggle = { id -> pinnedNodeId = if (pinnedNodeId == id) null else id },
                    maximized = isStageMaximized,
                    onToggleMaximized = { isStageMaximized = !isStageMaximized },
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
                            if (callerAvatarUrl != null) {
                                coil3.compose.AsyncImage(
                                    model = callerAvatarUrl,
                                    contentDescription = callerName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                OrbitDefaultAvatar(
                                    label = callerName,
                                    seed = callerName,
                                    color = Color(identityColor(callerName)),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
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
            visible = !isStageMaximized,
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
                    // Always rendered, including embedded.
                    //
                    // This was `if (!isEmbedded)`, and OrbitApp mounts the 1:1
                    // surface with `isEmbedded = true` -- so the minimize button did
                    // not exist in a 1:1 at all. That was the other half of the same
                    // mistake fixed in the PiP branch above: I widened the gate on
                    // the destination and left the one on the button that leads to
                    // it. Both had to move together; neither alone does anything.
                    //
                    // `isEmbedded` means "the host supplies its own corner card". It
                    // does not mean "this surface has no way out".
                    IconButton(
                        onClick = { callManager.isMinimized.value = true },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.08f), CircleShape)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Minimize", tint = Color.White, modifier = Modifier.size(18.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(identityColor(callerName))),
                        contentAlignment = Alignment.Center
                    ) {
                        if (callerAvatarUrl != null) {
                            coil3.compose.AsyncImage(
                                model = callerAvatarUrl,
                                contentDescription = callerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape)
                            )
                        } else {
                            OrbitDefaultAvatar(
                                label = callerName,
                                seed = callerName,
                                    color = Color(identityColor(callerName)),
                                modifier = Modifier.fillMaxSize()
                            )
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
                                        .background(Color(0xFF23A55A))
                                )
                                Text(
                                    timerText,
                                    color = Color(0xFF23A55A),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Right: stream indicators, then the layout and maximise toggles.
                //
                // `.stream-quality-badge` (rgba(255,255,255,0.08), 3px/8px, 4px
                // radius, 11px weight 700), `.stream-live-pill` (`#f23f43`, 2px/6px,
                // 4px radius, 11px weight 800, 0.05em tracking), a 1px
                // `--border-subtle` rule between them, then `.header-action-btn` for
                // the layout and maximise toggles.
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isVideoCall && callState == CallState.CONNECTED) {
                        Box(
                            modifier = Modifier
                                .background(OrbitConceptBorder.subtle(), CallIconPillShape)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                if (isScreenSharing) "720p 30FPS" else "HD 1080p 60fps",
                                color = tokens.Text_Secondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.22.sp
                            )
                        }
                    }
                    if (isScreenSharing || isRemoteScreenSharing) {
                        Row(
                            modifier = Modifier
                                .background(CallPillDanger, CallIconPillShape)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                            Text(
                                "LIVE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.55.sp
                            )
                        }
                    }
                    if (isVideoCall && callState == CallState.CONNECTED) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(16.dp)
                                .background(OrbitConceptBorder.subtle())
                        )
                        // `.header-action-btn` — transparent, 4px padding, 4px
                        // radius, `--interactive-normal`; hover
                        // `--interactive-hover`; `.active` `--interactive-active`.
                        CallHeaderActionButton(
                            icon = if (layoutMode == CallLayoutMode.Grid) Icons.Default.GridView else Icons.Default.CropFree,
                            contentDescription = if (layoutMode == CallLayoutMode.Grid) {
                                "Switch to focus view"
                            } else {
                                "Switch to grid view"
                            },
                            active = layoutMode == CallLayoutMode.Focus,
                            onClick = {
                                layoutMode = if (layoutMode == CallLayoutMode.Grid) {
                                    CallLayoutMode.Focus
                                } else {
                                    CallLayoutMode.Grid
                                }
                            }
                        )
                        CallHeaderActionButton(
                            icon = if (isStageMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = if (isStageMaximized) {
                                "Exit full screen (Escape)"
                            } else {
                                "Maximize the stream"
                            },
                            active = isStageMaximized,
                            onClick = { isStageMaximized = !isStageMaximized }
                        )
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
                            if (callerAvatarUrl != null) {
                                coil3.compose.AsyncImage(
                                    model = callerAvatarUrl,
                                    contentDescription = callerName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                OrbitDefaultAvatar(
                                    label = callerName,
                                    seed = callerName,
                                    color = Color(identityColor(callerName)),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
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
                                    .background(Color(0xFF23A55A), CircleShape)
                                    .shadow(8.dp, CircleShape)
                            ) {
                                Icon(OrbitIcons.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Accept", color = Color(0xFF23A55A), fontWeight = FontWeight.Medium, fontSize = 13.sp)
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
                // `.call-dock-pill-bar { bottom: 24px }`. It was 16dp here, which
                // put the bar's own 6px of padding within 10px of the stage edge.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
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

/**
 * The soundboard.
 *
 * ## Why the grid is replaced by an explanation on a 1:1
 *
 * Soundboard effects are rendered locally and queued on a bus that
 * `LiveKitNativeAudioCapture` folds into each outgoing mic frame, which is how
 * everybody in a call hears them. That mixer is the **LiveKit** capture path.
 *
 * A 1:1 that arrives through legacy WebRTC signalling takes a completely
 * different capture path, which runs inside the prebuilt `webrtc-java-orbit`
 * native audio device module. There is no JVM-side seam to add samples to it --
 * `AudioSink.onRecordedData` is an observer rather than an injector, and
 * `HeadlessAudioDeviceModule` is a stub with no push method in this binding.
 *
 * So on a 1:1 the panel used to play the effect straight into the speakers. The
 * person you were calling heard nothing, which reads as the feature being broken
 * rather than as it not applying here.
 *
 * The button is kept and the panel now SAYS so. Hiding it outright was the other
 * option and is worse: a control that vanishes is a question nobody can answer,
 * while one that explains itself is an answer. The panel is the right place
 * because that is where somebody has already decided they want this.
 */
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

    // A 1:1 ringing in through legacy signalling cannot carry the bus. Every call
    // Orbit STARTS is a LiveKit group call regardless of how many people are in it,
    // so this is true only for incoming 1:1s.
    val isGroupCall by callManager.isGroupCall.collectAsState(false)

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

            if (!isGroupCall) {
                // Say what is true instead of playing a sound only you can hear.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.VolumeOff,
                        null,
                        tint = Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Not available in a 1:1",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sounds need to travel to the other person, and this call's audio goes " +
                            "through a path that cannot carry them. Start a call from the room " +
                            "instead, or join one that is already running.",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
                return@Surface
            }

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
                                        // Capped to 30: publishing at 60 starves the
                                        // audio mixer on this machine. See the
                                        // Frame Rate card in Settings for the numbers.
                                        shareSettings.setScreenShareFps(shareFps.coerceIn(5, 30))
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
    val isStuckShare: Boolean = false,
    /**
     * Whether this participant's video is still expected, and the renderer should
     * be drawn rather than the avatar.
     *
     * A camera-off participant still arrives as a tile with a renderer, because
     * LiveKit announces the publication regardless of whether it will ever carry a
     * frame. The tile then drew that renderer indefinitely, which is a spinner (or,
     * on the 1:1 path, the words "Starting video") for someone whose camera is off
     * and never going to produce anything.
     *
     * True by default, so a node nobody has thought about draws its video. That
     * default matters more than it looks: this field was briefly named
     * `hasVideoFrameYet` and read as `!node.hasVideoFrameYet`, which inverted it.
     * A working frame set it true, the negation became false, and every video in
     * every call silently fell through to the avatar branch. Defaulting to "draw
     * the video" means an unset field cannot hide anybody.
     */
    val isVideoExpected: Boolean = true
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
    // participant is talking gets an animated green ring + halo.
    val speakers = if (callManager != null) callManager.activeSpeakers.collectAsState().value else emptySet()
    val isSpeaking = identityIsSpeaking(node.participantIdentity, speakers) && !node.isMuted && !node.isMicMuted
    val speakGlow by animateFloatAsState(
        targetValue = if (isSpeaking) 1f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "speakGlow"
    )
    // Hover is a real state of this tile in the concept, not a nicety: a share
    // lights up when pointed at, and the per-participant audio controls only
    // exist on hover at all.
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    val shareHoverGlow by animateFloatAsState(
        targetValue = if (node.isScreenShare && isHovered) 1f else 0f,
        animationSpec = tween(durationMillis = OrbitMotion.Fast),
        label = "shareHoverGlow"
    )

    // `.participant-card { border: 2px solid transparent }` — a tile at rest has
    // a 2px ring that is deliberately invisible, so that speaking and share
    // states have somewhere to appear. `.participant-card.speaking` swaps it for
    // `--status-online`, and `.stream-preview-card` for rgba(242, 63, 67, 0.6).
    //
    // Speaking green, not the accent. Discord's active-speaker border is green,
    // and Orbit's own sidebar roster already uses the same green for the same
    // signal -- two colours for one meaning is worse than either.
    val ringColor = when {
        node.isScreenShare && shareHoverGlow > 0f -> CallShareRingHover.copy(alpha = 1f)
        node.isScreenShare -> CallShareRing
        else -> OrbitConceptStatus.online.copy(alpha = speakGlow)
    }
    // 2dp, not 1dp + 1dp*glow. A 1px accent hairline around a 650px tile is
    // invisible at a glance, which is why the speaking indicator read as
    // "not implemented" rather than "quiet".
    val borderWidth = 2.dp
    // The speaking ring is drawn as an OVERLAY (see below) as well, so it
    // composites on top of a full-bleed video child rather than being painted
    // over by it.
    // Discord convention: the speaker's name goes green while they talk.
    val nameColor by animateColorAsState(
        if (isSpeaking) OrbitConceptStatus.online else Color.White,
        animationSpec = tween(durationMillis = 240),
        label = "speakerNameColor"
    )

    // `.participant-card { box-shadow: var(--elevation-high) }` is
    // `0 8px 16px rgba(0,0,0,0.24)`, and the two states that change it in the CSS
    // REPLACE it rather than add to it: speaking is
    // `0 0 20px rgba(35,165,90,0.35)`, and a hovered share is
    // `0 0 24px rgba(242,63,67,0.4)`. Both are coloured glows, so the shadow is
    // tinted as well as raised.
    val cardGlow = if (node.isScreenShare) shareHoverGlow else speakGlow
    val cardElevation by animateDpAsState(
        targetValue = 8.dp + 12.dp * cardGlow,
        animationSpec = OrbitMotion.SnappyDp,
        label = "cardElevation"
    )
    val cardShadowTint = if (node.isScreenShare) CallShareRingHover else CallSpeakingGlow

    var participantMenuOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .shadow(
                elevation = cardElevation,
                shape = CallTileShape,
                ambientColor = cardShadowTint,
                spotColor = cardShadowTint
            )
            .clip(CallTileShape)
            // `--bg-call-card`, not the literal ui.theme.Ink900 this used to
            // hardcode.
            //
            // That literal was the OLD Ink800. When the palette moved to Discord's
            // neutrals the surrounding surface became #313338 while every tile
            // stayed #14171D, so each participant rendered as a near-black hole in
            // a lighter stage with a border that was almost the same value as the
            // fill. Hardcoding a colour that also exists in the ramp means the tile
            // silently stops matching the app the moment the ramp moves -- which
            // is exactly what happened.
            .background(callCardSurface(tokens))
            .border(borderWidth, ringColor, CallTileShape)
            .hoverable(hoverInteraction)
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
        //
        // The concept pins `.participant-avatar-wrap` at a fixed 90x90. That is
        // kept as the value this scale passes THROUGH at a 346dp tile -- which is
        // the size the concept's own 2-column grid produces -- rather than as a
        // constant, because a 90dp avatar in a 1080dp spotlight is a stamp.
        val avatarSize = (minOf(maxWidth, maxHeight) * 0.26f).coerceIn(56.dp, 180.dp)

        // A stuck share skips the renderer entirely rather than drawing it. The
        // renderer now shows a spinner while it waits for a first frame, and
        // spinning forever behind "can't be received" says the opposite of what
        // is true -- Orbit has stopped trying and is offering the one thing that
        // still works.
        if (node.renderer != null && !node.isStuckShare && node.isVideoExpected) {
            VideoRenderer(renderer = node.renderer, modifier = Modifier.fillMaxSize().clip(CallTileShape))
        } else if (node.isStuckShare) {
            Box(modifier = Modifier.fillMaxSize().background(tokens.Sunken))
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
                // `.participant-avatar-wrap` -- a 90x90 relative box that the
                // speaking ring is positioned against.
                Box(
                    modifier = Modifier.size(avatarSize),
                    contentAlignment = Alignment.Center
                ) {
                    // `.speaking-wave-ring`: inset by -8px on every side, a 3px
                    // `--status-online` ring, breathing 0.95 -> 1.15 and back over
                    // 1.4s. It is the only part of a call tile that MOVES, which
                    // is what makes it findable in a grid of still faces without
                    // needing to be labelled.
                    if (isSpeaking) {
                        val waveTransition = rememberInfiniteTransition(label = "speakingWave")
                        val waveScale by waveTransition.animateFloat(
                            initialValue = CALL_WAVE_SCALE_MIN,
                            targetValue = CALL_WAVE_SCALE_MAX,
                            animationSpec = infiniteRepeatable(
                                animation = tween(
                                    durationMillis = CALL_WAVE_PULSE_MS,
                                    // @keyframes pulseSpeaking's
                                    // cubic-bezier(0.2, 0.8, 0.2, 1).
                                    easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
                                ),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "waveScale"
                        )
                        val waveAlpha by waveTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.5f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(CALL_WAVE_PULSE_MS),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "waveAlpha"
                        )
                        Box(
                            modifier = Modifier
                                // Inset by -8px on every side of the avatar, so
                                // the 3px ring clears the picture by 8px.
                                .size(avatarSize + CallWaveRingInset * 2)
                                .scale(waveScale)
                                .alpha(waveAlpha)
                                .clip(CircleShape)
                                .border(
                                    CallWaveRingWidth,
                                    OrbitConceptStatus.online,
                                    CircleShape
                                )
                                // Drawn first, so it sits UNDER the avatar:
                                // `.speaking-wave-ring` is z-index 1 and the img
                                // is z-index 2.
                        )
                    }
                    // Avatar
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
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

        // Raised-hand badge. The concept has no equivalent — it has no
        // raise-hand at all — so it stays where it was, top-left, and keeps its
        // warning fill; it is the one badge on the tile that Orbit adds and the
        // concept does not, and it is on the opposite corner from the status
        // pills so the two never collide.
        if (node.isHandRaised) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(tokens.Warning.copy(alpha = 0.92f), CallNamePillShape)
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

        // `.participant-badge-name` — absolute, 12px from the bottom-left, on a
        // rgba(0,0,0,0.7) plate, 4px/10px of padding, 6px radius, 13px weight
        // 600 white. When the speaker is talking the concept puts an 8px pulsing
        // `--status-online` dot in front of the name, which is why the name
        // itself does not have to change colour to be findable.
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 12.dp)
                .background(CallOverlayScrim, CallNamePillShape)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isSpeaking) {
                    // The dot's own 1s pulse, scoped INSIDE the `if` on purpose:
                    // an infinite transition left running on every tile of an
                    // eight-tile grid is eight animations a frame for a pixel
                    // nobody is looking at.
                    val nameDotTransition = rememberInfiniteTransition(label = "nameDot")
                    val nameDotScale by nameDotTransition.animateFloat(
                        initialValue = 0.9f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "nameDotScale"
                    )
                    val nameDotAlpha by nameDotTransition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "nameDotAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(nameDotScale)
                            .alpha(nameDotAlpha)
                            .clip(CircleShape)
                            .background(OrbitConceptStatus.online)
                    )
                }
                Text(
                    node.name,
                    color = nameColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // `.participant-status-icons` — absolute, 12px from the top-right, 6px
        // apart, each item an `.icon-pill` (4px/6px padding, 4px radius).
        //
        // The mic-off and deafen badges live HERE in the concept, not in the name
        // plate: a name plate that changes width as people mute is a name plate
        // that makes the names in a grid line up badly, and the two states that
        // matter most get the position with the most contrast behind it.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isMuted || node.isMicMuted) {
                Box(
                    modifier = Modifier
                        .background(CallMutedBadgeScrim, CallIconPillShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        OrbitIcons.MicOff,
                        contentDescription = "Muted",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            if (node.isDeafened) {
                Box(
                    modifier = Modifier
                        .background(CallMutedBadgeScrim, CallIconPillShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Icon(
                        Icons.Default.HeadsetOff,
                        contentDescription = "Deafened",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            // `.icon-pill` for a share, filled `#f23f43` at weight 800. The dot
            // inside it is `.live-dot-pulse`: an 8px red disc with an 8px glow,
            // breathing on a 1.5s cycle.
            if (node.isScreenShare) {
                val liveTransition = rememberInfiniteTransition(label = "liveDot")
                val liveScale by liveTransition.animateFloat(
                    initialValue = 0.9f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "liveScale"
                )
                val liveAlpha by liveTransition.animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "liveAlpha"
                )
                Row(
                    modifier = Modifier
                        .background(CallPillDanger, CallIconPillShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .scale(liveScale)
                            .alpha(liveAlpha)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Text(
                        "LIVE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            // Connection-quality signal bars: 3 lit green / 2 yellow / 1 red from
            // the native jitter/RTT/loss feed. Sits in the same `.icon-pill` row.
            val sampleBars = node.qualitySample?.bars ?: 0
            if (!node.isScreenShare && node.participantIdentity != null && sampleBars > 0) {
                Box(
                    modifier = Modifier
                        .background(CallOverlayScrim, CallIconPillShape)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    ConnectionQualityBars(bars = sampleBars)
                }
            }
        }

        // Per-participant volume / local mute — remote group-call tiles only
        // (cam/voice tiles; a screen-share tile mirrors the same identity's mic,
        // so controlling audio from there would be confusing).
        // Local-only: changes the gain this client mixes that person at; the
        // remote side's mic and everyone else are untouched.
        //
        // `.participant-card:hover .participant-controls-overlay { opacity: 1 }`:
        // this is that overlay. It is faded rather than removed so it does not
        // take the space it needs when nobody wants it, and forced visible while
        // the right-click menu is open -- a control that vanishes at the exact
        // moment somebody opens the menu that also controls it is a control that
        // looks broken.
        val participantIdentity = node.participantIdentity
        if (participantIdentity != null && !node.isScreenShare && callManager != null) {
            val gains by callManager.participantGains.collectAsState()
            val volume = gains[participantIdentity] ?: 1f
            val controlsVisible = isHovered || participantMenuOpen
            val controlsAlpha by animateFloatAsState(
                targetValue = if (controlsVisible) 1f else 0f,
                animationSpec = tween(OrbitMotion.Fast),
                label = "tileControlsAlpha"
            )
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .alpha(controlsAlpha)
                    .background(CallOverlayScrim, CallNamePillShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { callManager.toggleParticipantMute(participantIdentity) },
                    // Faded out is NOT the same as gone: at `alpha = 0f` these
                    // two controls would still swallow clicks, so a person could
                    // mute somebody by aiming at something they cannot see. They
                    // are disabled for exactly as long as they are invisible.
                    enabled = controlsVisible,
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
                    enabled = controlsVisible,
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
                        color = OrbitConceptStatus.online.copy(alpha = speakGlow),
                        shape = CallTileShape
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
                        Icon(Icons.Default.Check, contentDescription = "Active device", tint = Color(0xFF23A55A), modifier = Modifier.size(16.dp))
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
                        Icon(Icons.Default.Check, contentDescription = "Active device", tint = Color(0xFF23A55A), modifier = Modifier.size(16.dp))
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
                        Icon(Icons.Default.Check, contentDescription = "Active camera", tint = Color(0xFF23A55A), modifier = Modifier.size(16.dp))
                    }
                }
            )
        }
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
