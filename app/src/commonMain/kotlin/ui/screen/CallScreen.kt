package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import webrtc.CallManager
import webrtc.CallState

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.CloseFullscreen

@Composable
fun CallScreen(
    callManager: CallManager,
    modifier: Modifier = Modifier
) {
    val callState by callManager.callState.collectAsState()
    val isMinimized by callManager.isMinimized.collectAsState()
    
    if (callState == CallState.IDLE || callState == CallState.ENDED) {
        return
    }
    
    var isMuted by remember { mutableStateOf(false) }
    var isVideoEnabled by remember { mutableStateOf(true) }

    if (isMinimized) {
        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(100.dp, 150.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .clickable { callManager.isMinimized.value = false }
        ) {
            if (callState == CallState.CONNECTED && isVideoEnabled) {
                VideoRenderer(renderer = callManager.webRtcClient.getRemoteVideoRenderer(), modifier = Modifier.fillMaxSize())
            } else {
                Text("In Call", color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Minimize Button
        IconButton(
            onClick = { callManager.isMinimized.value = true },
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp).size(48.dp)
        ) {
            Icon(Icons.Default.CloseFullscreen, contentDescription = "Minimize", tint = Color.White)
        }

        // Video renderers (platform specific)
        if (callState == CallState.CONNECTED && isVideoEnabled) {
            val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()
            val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()
            
            // Render remote video (fullscreen)
            VideoRenderer(renderer = remoteRenderer, modifier = Modifier.fillMaxSize())
            
            // Render local video (PiP)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 100.dp, end = 24.dp)
                    .size(120.dp, 160.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.DarkGray)
            ) {
                VideoRenderer(renderer = localRenderer, modifier = Modifier.fillMaxSize())
            }
        } else {
            // Audio only or ringing
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (callState) {
                        CallState.CONNECTING -> "Connecting..."
                        CallState.RINGING -> "Ringing..."
                        CallState.CONNECTED -> "In Call"
                        else -> ""
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        
        // Incoming Call UI
        if (callState == CallState.RINGING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = Color.DarkGray
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("Incoming Call", color = Color.White, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(48.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        IconButton(
                            onClick = { callManager.endCall() },
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Red)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline", tint = Color.White)
                        }
                        IconButton(
                            onClick = { callManager.answerCall(isVideo = true) },
                            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Green)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White)
                        }
                    }
                }
            }
        }
        
        // Controls (Only show if not ringing)
        if (callState != CallState.RINGING) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute
                IconButton(
                    onClick = { isMuted = !isMuted },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(if (isMuted) Color.Red else Color.DarkGray)
                ) {
                    Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = "Mute", tint = Color.White)
                }
                
                Spacer(Modifier.width(24.dp))
                
                // Hangup
                IconButton(
                    onClick = { callManager.endCall() },
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(Color.Red)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                
                Spacer(Modifier.width(24.dp))
                
                // Video
                IconButton(
                    onClick = { isVideoEnabled = !isVideoEnabled },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(if (!isVideoEnabled) Color.Red else Color.DarkGray)
                ) {
                    Icon(if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = "Video", tint = Color.White)
                }
            }
        }
    }
}

@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
