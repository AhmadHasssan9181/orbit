package ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import webrtc.CallManager
import webrtc.CallState
import ui.theme.rememberCosmicTokens

@Composable
fun CallScreen(
    callManager: CallManager,
    modifier: Modifier = Modifier
) {
    val callState by callManager.callState.collectAsState()
    val isMinimized by callManager.isMinimized.collectAsState()
    val tokens = rememberCosmicTokens()

    if (callState == CallState.IDLE || callState == CallState.ENDED) {
        return
    }
    
    var isMuted by remember { mutableStateOf(false) }
    var isVideoEnabled by remember { mutableStateOf(true) }

    // Pulsing animation for ringing
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    if (isMinimized) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(100.dp, 150.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E1E1E), Color(0xFF121212))))
                .clickable { callManager.isMinimized.value = false }
        ) {
            if (callState == CallState.CONNECTED && isVideoEnabled) {
                VideoRenderer(renderer = callManager.webRtcClient.getRemoteVideoRenderer(), modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    text = if (callState == CallState.RINGING) "Ringing..." else "In Call", 
                    color = Color.White, 
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
        return
    }

    Box(modifier = modifier
        .fillMaxSize()
        .background(Brush.verticalGradient(listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E))))
    ) {
        // Video renderers (platform specific)
        if (callState == CallState.CONNECTED && isVideoEnabled) {
            val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()
            val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()
            
            // Render remote video (fullscreen)
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                VideoRenderer(renderer = remoteRenderer, modifier = Modifier.fillMaxSize())
            }
            
            // Render local video (PiP)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 120.dp, end = 24.dp)
                    .size(110.dp, 160.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                VideoRenderer(renderer = localRenderer, modifier = Modifier.fillMaxSize())
            }
        } else {
            // Audio only or ringing background
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated Avatar Placeholder
                Surface(
                    modifier = Modifier
                        .size(120.dp)
                        .scale(if (callState == CallState.RINGING || callState == CallState.CONNECTING) pulseScale else 1f),
                    shape = CircleShape,
                    color = tokens.Accent.copy(alpha = 0.2f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp).clip(CircleShape).background(tokens.Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Call",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = when (callState) {
                        CallState.CONNECTING -> "Connecting..."
                        CallState.RINGING -> "Calling..."
                        CallState.CONNECTED -> "Active Call"
                        else -> ""
                    },
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Minimize Button
        IconButton(
            onClick = { callManager.isMinimized.value = true },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .size(48.dp)
                .background(Color.Black.copy(alpha = 0.3f), CircleShape)
        ) {
            Icon(Icons.Default.CloseFullscreen, contentDescription = "Minimize", tint = Color.White)
        }
        
        // Incoming Call UI Overlay
        AnimatedVisibility(
            visible = callState == CallState.RINGING && callManager.callId != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(130.dp).scale(pulseScale),
                        shape = CircleShape,
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(16.dp).clip(CircleShape).background(Color(0xFF4CAF50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Incoming Call...",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(64.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        IconButton(
                            onClick = { callManager.endCall() },
                            modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFFF44336))
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            onClick = { callManager.answerCall(isVideo = true) },
                            modifier = Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF4CAF50))
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
        
        // Controls (Only show if not ringing)
        if (callState == CallState.CONNECTED || callState == CallState.CONNECTING) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .wrapContentSize(),
                shape = RoundedCornerShape(32.dp),
                color = Color.Black.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mute
                    IconButton(
                        onClick = { 
                            isMuted = !isMuted 
                            callManager.toggleMute(isMuted)
                        },
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(if (isMuted) Color.White else Color.DarkGray.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute",
                            tint = if (isMuted) Color.Black else Color.White
                        )
                    }
                    
                    // Hangup
                    IconButton(
                        onClick = { callManager.endCall() },
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFFF44336))
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    
                    // Video
                    IconButton(
                        onClick = { 
                            isVideoEnabled = !isVideoEnabled 
                            callManager.toggleVideo(isVideoEnabled)
                        },
                        modifier = Modifier.size(56.dp).clip(CircleShape).background(if (!isVideoEnabled) Color.White else Color.DarkGray.copy(alpha = 0.7f))
                    ) {
                        Icon(
                            imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                            contentDescription = "Video",
                            tint = if (!isVideoEnabled) Color.Black else Color.White
                        )
                    }

                    // Flip Camera
                    if (isVideoEnabled) {
                        IconButton(
                            onClick = { callManager.switchCamera() },
                            modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.DarkGray.copy(alpha = 0.7f))
                        ) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
