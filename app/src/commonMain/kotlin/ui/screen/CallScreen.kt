package ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState
import kotlin.math.roundToInt

@Composable
fun CallScreen(
    callManager: CallManager,
    onStartScreenShare: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val callState by callManager.callState.collectAsState()
    val isMinimized by callManager.isMinimized.collectAsState()
    val isIncomingCall by callManager.isIncomingCall.collectAsState()
    val isVideoCall by callManager.isVideoCall.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    val tokens = rememberCosmicTokens()

    if (callState == CallState.IDLE || callState == CallState.ENDED) {
        return
    }

    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isMuted = !isAudioEnabled
    var showControls by remember { mutableStateOf(true) }
    var includeStreamAudio by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls && callState == CallState.CONNECTED) {
            delay(3000)
            showControls = false
        }
    }

    // Pulsing animation for ringing
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    if (isMinimized) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(240.dp, 160.dp)
                .shadow(16.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF111214))
                .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                .clickable { callManager.isMinimized.value = false }
        ) {
            if (isVideoCall && (isVideoEnabled || callState == CallState.CONNECTED)) {
                val renderer = if (callState == CallState.CONNECTED) {
                    callManager.webRtcClient.getRemoteVideoRenderer()
                } else {
                    callManager.webRtcClient.getLocalVideoRenderer()
                }
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    VideoRenderer(renderer = renderer, modifier = Modifier.fillMaxSize())
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = tokens.Accent.copy(alpha = 0.2f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(OrbitIcons.Call, contentDescription = null, tint = tokens.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (callState == CallState.RINGING) "Ringing..." else "In Call",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            
            // Re-expand overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.0f)) // Invisible trigger
            )
            Icon(
                Icons.Default.OpenInFull,
                contentDescription = "Expand",
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(20.dp)
            )
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000)) // Pure black for video background
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showControls = !showControls }
            )
    ) {
        val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()
        val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()

        // ── Video Grid (Main) ──
        if (callState == CallState.CONNECTED) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .padding(bottom = 100.dp), // Space for controls
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Remote Participant
                Box(
                    modifier = Modifier
                        .weight(if (isScreenSharing) 0.4f else 1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1F22))
                        .border(2.dp, Color.Transparent, RoundedCornerShape(16.dp))
                ) {
                    if (remoteRenderer != null) {
                        VideoRenderer(
                            renderer = remoteRenderer,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Fallback if remote has no camera
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = tokens.Accent, modifier = Modifier.size(100.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                                    }
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha=0.6f), RoundedCornerShape(8.dp)).padding(horizontal=12.dp, vertical=6.dp)) {
                        Text("Friend", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Local Participant
                Box(
                    modifier = Modifier
                        .weight(if (isScreenSharing) 1f else 1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1E1F22))
                        .border(2.dp, if (!isMuted) Color(0xFF23A559) else Color.Transparent, RoundedCornerShape(16.dp))
                ) {
                    if ((isVideoEnabled || isScreenSharing) && localRenderer != null) {
                        VideoRenderer(renderer = localRenderer, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = CircleShape, color = tokens.Accent.copy(alpha = 0.5f), modifier = Modifier.size(100.dp)) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                                    }
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color.Black.copy(alpha=0.6f), RoundedCornerShape(8.dp)).padding(horizontal=12.dp, vertical=6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isMuted) {
                                Icon(OrbitIcons.MicOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("You", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            // Ringing / Dialing screen (Audio only or waiting)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF111214)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val isPulsing = callState == CallState.RINGING || callState == CallState.CONNECTING
                    Box(contentAlignment = Alignment.Center) {
                        if (isPulsing) {
                            Box(modifier = Modifier.size(180.dp).scale(pulseScale).clip(CircleShape).background(tokens.Accent.copy(alpha = pulseAlpha)))
                        }
                        Surface(shape = CircleShape, color = tokens.Accent, modifier = Modifier.size(120.dp), shadowElevation = 8.dp) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text("Friend", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (callState) {
                            CallState.RINGING -> "Calling..."
                            CallState.CONNECTING -> "Connecting..."
                            else -> ""
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 16.sp
                    )
                }
            }
        }

        // ── Top Controls ──
        AnimatedVisibility(
            visible = showControls || callState != CallState.CONNECTED,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
        ) {
            IconButton(
                onClick = { callManager.isMinimized.value = true },
                modifier = Modifier.size(48.dp).background(Color(0xFF1E1F22).copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Minimize", tint = Color.White)
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
                modifier = Modifier.fillMaxSize().background(Color(0xFF000000).copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, color = tokens.Accent, modifier = Modifier.size(100.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(50.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Friend is calling you...", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(48.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.endCall() },
                                modifier = Modifier.size(64.dp).background(Color(0xFFF23F42), CircleShape)
                            ) {
                                Icon(OrbitIcons.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Decline", color = Color.White, fontWeight = FontWeight.Medium)
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.answerCall(isVideo = false) },
                                modifier = Modifier.size(64.dp).background(Color(0xFF23A559), CircleShape)
                            ) {
                                Icon(OrbitIcons.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Accept", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // ── Bottom Controls Bar (Discord style) ──
        if (callState == CallState.CONNECTED || callState == CallState.CONNECTING || (callState == CallState.RINGING && !isIncomingCall)) {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    
                    // Stream audio toggle pill
                    if (callState == CallState.CONNECTED) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .background(Color(0xFF1E1F22).copy(alpha = 0.9f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, null, tint = Color.White.copy(alpha=0.8f), modifier=Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Stream Desktop Audio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = includeStreamAudio,
                                onCheckedChange = { 
                                    includeStreamAudio = it
                                    callManager.webRtcClient.setStreamAudioEnabled(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White, 
                                    checkedTrackColor = Color(0xFF23A559),
                                    uncheckedThumbColor = Color(0xFFB5BAC1),
                                    uncheckedTrackColor = Color(0xFF4E5058)
                                ),
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    }
                    
                    // Main Control Bar
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF1E1F22).copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ControlButton(
                            icon = if (isVideoEnabled) OrbitIcons.Video else OrbitIcons.VideoOff,
                            isActive = isVideoEnabled,
                            activeColor = Color(0xFF313338), // active means unmuted (gray background)
                            activeIconColor = Color.White,
                            inactiveColor = Color.White, // inactive means muted (white background, dark icon)
                            inactiveIconColor = Color(0xFF111214),
                            onClick = { callManager.toggleVideo(!isVideoEnabled) }
                        )

                        ControlButton(
                            icon = if (!isMuted) OrbitIcons.Mic else OrbitIcons.MicOff,
                            isActive = !isMuted,
                            activeColor = Color(0xFF313338),
                            activeIconColor = Color.White,
                            inactiveColor = Color.White,
                            inactiveIconColor = Color(0xFF111214),
                            onClick = { callManager.toggleMute(!isMuted) }
                        )

                        ControlButton(
                            icon = OrbitIcons.ScreenShare,
                            isActive = isScreenSharing,
                            activeColor = Color(0xFF23A559), // Green when sharing
                            activeIconColor = Color.White,
                            inactiveColor = Color(0xFF313338),
                            inactiveIconColor = Color.White,
                            onClick = {
                                if (isScreenSharing) {
                                    callManager.stopScreenShare()
                                } else {
                                    callManager.startScreenShare(includeStreamAudio)
                                }
                            }
                        )

                        // Red Hangup Button
                        IconButton(
                            onClick = { callManager.endCall() },
                            modifier = Modifier.size(56.dp).background(Color(0xFFF23F42), RoundedCornerShape(16.dp))
                        ) {
                            Icon(OrbitIcons.CallEnd, contentDescription = "Hang Up", tint = Color.White, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    activeColor: Color,
    activeIconColor: Color,
    inactiveColor: Color,
    inactiveIconColor: Color,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(if (isActive) activeColor else inactiveColor, label = "bgColor")
    val iconTint by animateColorAsState(if (isActive) activeIconColor else inactiveIconColor, label = "iconTint")

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(bgColor, RoundedCornerShape(16.dp))
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
