package ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState

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
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    if (isMinimized) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(120.dp, 180.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF0F172A))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                .clickable { callManager.isMinimized.value = false }
        ) {
            if (isVideoCall && isVideoEnabled) {
                val renderer = if (callState == CallState.CONNECTED) {
                    callManager.webRtcClient.getRemoteVideoRenderer()
                } else {
                    callManager.webRtcClient.getLocalVideoRenderer()
                }
                VideoRenderer(renderer = renderer, modifier = Modifier.fillMaxSize())
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, tint = tokens.Accent, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (callState == CallState.RINGING) "Ringing..." else "In Call",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF09090B)) // Deep dark slate
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showControls = !showControls }
            )
    ) {
        // Video renderers
        if (isVideoCall && (isVideoEnabled || callState == CallState.CONNECTED)) {
            val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()
            val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()

            // Render remote video when connected, otherwise show local preview while dialing.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoRenderer(
                    renderer = if (callState == CallState.CONNECTED) remoteRenderer else localRenderer,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (callState == CallState.CONNECTED && isVideoEnabled) {
                // Render local video (PiP)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 32.dp)
                        .size(140.dp, 200.dp)
                        .shadow(24.dp, RoundedCornerShape(20.dp))
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF1E293B))
                        .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                ) {
                    VideoRenderer(renderer = localRenderer, modifier = Modifier.fillMaxSize())
                }
            }

            if (!isVideoEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Camera Off", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                }
            }
        } else {
            // Audio only or ringing background
            Box(modifier = Modifier.fillMaxSize()) {
                // Background ambient glow
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(tokens.Accent.copy(alpha = 0.15f), Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset.Infinite
                            )
                        )
                )

                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Animated Avatar Placeholder with Rings
                    val isPulsingState = callState == CallState.RINGING || callState == CallState.CONNECTING || callState == CallState.CONNECTED
                    Box(contentAlignment = Alignment.Center) {
                        if (isPulsingState) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .scale(pulseScale * 1.1f)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(tokens.Accent.copy(alpha = pulseAlpha * 0.5f), Color.Transparent)
                                        )
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .scale(pulseScale * 0.9f)
                                    .clip(CircleShape)
                                    .background(tokens.Accent.copy(alpha = pulseAlpha))
                            )
                        }

                        Surface(
                            modifier = Modifier.size(110.dp),
                            shape = CircleShape,
                            color = tokens.Accent,
                            shadowElevation = 16.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(64.dp))
                    
                    // Glassmorphic User Info Card
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .background(Color(0xFF1E293B).copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                            .padding(horizontal = 48.dp, vertical = 24.dp)
                    ) {
                        Text(
                            text = "User", // Can be dynamic if we pass target user info
                            color = Color.White,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (callState) {
                                CallState.CONNECTING -> "Connecting..."
                                CallState.RINGING -> "Calling..."
                                CallState.CONNECTED -> "Active Call" // Placeholder for actual timer
                                else -> ""
                            },
                            color = tokens.Accent.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.titleMedium,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Top Controls (Minimize)
        AnimatedVisibility(
            visible = showControls || callState != CallState.CONNECTED,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            IconButton(
                onClick = { callManager.isMinimized.value = true },
                modifier = Modifier
                    .padding(24.dp)
                    .size(48.dp)
                    .background(Color(0xFF1E293B).copy(alpha = 0.7f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
            ) {
                Icon(Icons.Default.CloseFullscreen, contentDescription = "Minimize", tint = Color.White)
            }
        }

        // Incoming Call UI Overlay
        AnimatedVisibility(
            visible = callState == CallState.RINGING && callManager.callId != null && isIncomingCall,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF09090B).copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isVideoCall) "Incoming Video Call" else "Incoming Voice Call",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(64.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                        // Decline
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.endCall() },
                                modifier = Modifier
                                    .size(72.dp)
                                    .shadow(12.dp, CircleShape)
                                    .background(Color(0xFFEF4444), CircleShape)
                            ) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Decline", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                        }

                        // Accept
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { callManager.answerCall(isVideo = isVideoCall) },
                                modifier = Modifier
                                    .size(72.dp)
                                    .shadow(12.dp, CircleShape)
                                    .background(Color(0xFF10B981), CircleShape)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Accept", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        // Bottom Controls Container (Glassmorphic)
        if (callState == CallState.CONNECTED || callState == CallState.CONNECTING || (callState == CallState.RINGING && !isIncomingCall)) {
            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (callState == CallState.CONNECTED) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .background(Color(0xFF1E293B).copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Include Stream Audio", color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = includeStreamAudio,
                                onCheckedChange = { 
                                    includeStreamAudio = it
                                    callManager.webRtcClient.setStreamAudioEnabled(it)
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = tokens.Accent, checkedTrackColor = tokens.Accent.copy(alpha=0.5f))
                            )
                        }
                    }
                    Box(
                    modifier = Modifier
                        .shadow(24.dp, RoundedCornerShape(32.dp))
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color(0xFF1E293B).copy(alpha = 0.85f)) // Translucent slate
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 32.dp, vertical = 20.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Mute Button
                        ControlButton(
                            icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            isActive = isMuted,
                            activeColor = Color.White,
                            activeIconColor = Color.Black,
                            inactiveColor = Color.White.copy(alpha = 0.1f),
                            inactiveIconColor = Color.White,
                            onClick = {
                                callManager.toggleMute(!isMuted)
                            }
                        )

                        // Video Button
                        ControlButton(
                            icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            isActive = !isVideoEnabled,
                            activeColor = Color.White,
                            activeIconColor = Color.Black,
                            inactiveColor = Color.White.copy(alpha = 0.1f),
                            inactiveIconColor = Color.White,
                            onClick = {
                                callManager.toggleVideo(!isVideoEnabled)
                            }
                        )

                        // Screen Share Button
                        ControlButton(
                            icon = Icons.Default.ScreenShare,
                            isActive = isScreenSharing,
                            activeColor = tokens.Accent,
                            activeIconColor = Color.White,
                            inactiveColor = Color.White.copy(alpha = 0.1f),
                            inactiveIconColor = Color.White,
                            onClick = {
                                if (isScreenSharing) {
                                    callManager.stopScreenShare()
                                } else {
                                    callManager.startScreenShare(includeStreamAudio)
                                }
                            }
                        )

                        // Hangup Button
                        ControlButton(
                            icon = Icons.Default.CallEnd,
                            isActive = true,
                            activeColor = Color(0xFFEF4444),
                            activeIconColor = Color.White,
                            inactiveColor = Color(0xFFEF4444),
                            inactiveIconColor = Color.White,
                            size = 64.dp,
                            iconSize = 32.dp,
                            onClick = { callManager.endCall() }
                        )
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
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor by animateColorAsState(if (isActive) activeColor else inactiveColor, label = "bgColor")
    val iconTint by animateColorAsState(if (isActive) activeIconColor else inactiveIconColor, label = "iconTint")

    IconButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
