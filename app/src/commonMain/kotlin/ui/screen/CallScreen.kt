package ui.screen

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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
    var showAudioDeviceMenu by remember { mutableStateOf(false) }

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

    if (isMinimized && !isEmbedded) {
        Box(
            modifier = modifier.wrapContentSize(Alignment.BottomEnd) // Allow it to shrink and align to bottom end
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp)
                    .size(320.dp, 200.dp)
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
                        if (renderer != null) {
                            VideoRenderer(renderer = renderer, modifier = Modifier.fillMaxSize())
                        }
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
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0D0E12)) // Deep dark gray/blue base
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { showControls = !showControls }
            )
    ) {
        val remoteRenderer = callManager.webRtcClient.getRemoteVideoRenderer()
        val localRenderer = callManager.webRtcClient.getLocalVideoRenderer()

        // Repeating background pattern
        ui.assets.OrbitChatDoodleBackground(selectedId = "auto", modifier = Modifier.fillMaxSize().alpha(0.08f))

        // ── Video Grid (Main) ──
        if (callState == CallState.CONNECTED) {
            // Aggregate all active feeds
            val nodes = mutableListOf<RenderNode>()
            
            if (isScreenSharing) {
                // If local user is screen sharing, the localRenderer is used for the screen share
                nodes.add(RenderNode("screen", "Screen Share", localRenderer, isScreenShare = true))
            }
            if (remoteRenderer != null && hasRemoteStream) {
                // If there's a remote stream, show it
                nodes.add(RenderNode("remote", callerName, remoteRenderer, isScreenShare = false, avatarColor = 0xFFF23F42.toInt()))
            }
            if (isVideoEnabled && !isScreenSharing) {
                nodes.add(RenderNode("local", "You", localRenderer, isScreenShare = false, isMuted = isMuted))
            } else if (!isScreenSharing) {
                // Add an audio-only placeholder for local user so grid still shows them
                nodes.add(RenderNode("local", "You", null, isScreenShare = false, isMuted = isMuted))
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .padding(bottom = 100.dp) // Space for controls
            ) {
                ui.screen.call.CallGrid(nodes = nodes, tokens = tokens, modifier = Modifier.fillMaxSize())
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
                        Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(Color(0xFFF23F42)), contentAlignment = Alignment.Center) {
                            OrbitBrandMark(modifier = Modifier.size(70.dp), showGlow = false)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                    Text(callerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
        if (!isEmbedded) {
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
                    Box(modifier = Modifier.size(100.dp).clip(CircleShape).background(Color(0xFFF23F42)), contentAlignment = Alignment.Center) {
                        OrbitBrandMark(modifier = Modifier.size(60.dp), showGlow = false)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("$callerName is calling you...", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
                ui.screen.call.CallControlsBar(
                    callManager = callManager,
                    isVideoEnabled = isVideoEnabled,
                    isMuted = isMuted,
                    isScreenSharing = isScreenSharing,
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
        containerColor = Color(0xFF313338),
        title = { Text("Share your screen", color = Color.White) },
        text = {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = tokens.Accent)
                }
            } else {
                Column {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                        modifier = Modifier.heightIn(max = 400.dp).weight(1f, fill = false),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(sources.size) { index ->
                            val source = sources[index]
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1.5f)
                                    .clickable {
                                        callManager.setStreamAudioEnabled(shareSystemAudio)
                                        onSourceSelected(source)
                                    },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D31)),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        if (source.isWindow) Icons.Default.Window else Icons.Default.Monitor,
                                        contentDescription = null,
                                        tint = tokens.Accent,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        source.name,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { shareSystemAudio = !shareSystemAudio }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shareSystemAudio,
                            onCheckedChange = { shareSystemAudio = it },
                            colors = CheckboxDefaults.colors(checkedColor = tokens.Accent, checkmarkColor = Color.White)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share System Audio", color = Color.White, fontSize = 14.sp)
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

data class RenderNode(
    val id: String,
    val name: String,
    val renderer: Any?,
    val isScreenShare: Boolean,
    val isMuted: Boolean = false,
    val avatarUrl: String? = null,
    val avatarColor: Int = 0xFF5865F2.toInt()
)

@Composable
fun VideoNodeCard(node: RenderNode, tokens: ui.theme.CosmicTokens, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF111214)) // Base background
            .border(
                width = if (node.id == "local" && !node.isMuted) 2.dp else 1.dp,
                color = if (node.id == "local" && !node.isMuted) Color(0xFF23A559) else Color(0xFF1E1F22),
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        if (node.renderer != null) {
            VideoRenderer(renderer = node.renderer, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)))
        } else {
            // Audio-only fallback with dark gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(Color(node.avatarColor).copy(alpha = 0.15f), Color.Transparent), radius = 400f)),
                contentAlignment = Alignment.Center
            ) {
                if (node.avatarUrl != null) {
                    coil3.compose.AsyncImage(
                        model = node.avatarUrl,
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(80.dp).clip(CircleShape)
                    )
                } else {
                    OrbitBrandMark(modifier = Modifier.size(100.dp), showGlow = false)
                }
            }
        }
        
        // Name Overlay (Discord-style bottom left pill)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color(0xFF111214).copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(node.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        
        // Status Icons (Top Right corner)
        if (node.isMuted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(Color(0xFF111214).copy(alpha = 0.7f), CircleShape)
                    .padding(6.dp)
            ) {
                Icon(OrbitIcons.MicOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(16.dp))
            }
        }
        
        // Screen Share Badge
        if (node.isScreenShare) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color(0xFFF23F42), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun AudioDeviceMenu(
    callManager: CallManager,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    var inputs by remember { mutableStateOf<List<String>>(emptyList()) }
    var outputs by remember { mutableStateOf<List<String>>(emptyList()) }
    val tokens = rememberCosmicTokens()

    val duckingStrength by callManager.duckingStrength.collectAsState()
    val nsEnabled by callManager.noiseSuppression.collectAsState()
    val ecEnabled by callManager.echoCancellation.collectAsState()
    val agcEnabled by callManager.autoGainControl.collectAsState()
    val vbEnabled by callManager.isVirtualBackgroundEnabled.collectAsState()

    LaunchedEffect(expanded) {
        if (expanded) {
            inputs = callManager.webRtcClient.getAudioInputDevices()
            outputs = callManager.webRtcClient.getAudioOutputDevices()
        }
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF1E1F22)).width(240.dp)
    ) {
        Text(
            "INPUT DEVICE",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        )
        inputs.forEach { device ->
            DropdownMenuItem(
                text = { Text(device, color = Color.White, fontSize = 13.sp) },
                onClick = {
                    callManager.setAudioInputDevice(device)
                    onDismiss()
                },
                trailingIcon = {
                    // Logic to show checkmark if selected
                }
            )
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        
        Text(
            "OUTPUT DEVICE",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        )
        outputs.forEach { device ->
            DropdownMenuItem(
                text = { Text(device, color = Color.White, fontSize = 13.sp) },
                onClick = {
                    callManager.setAudioOutputDevice(device)
                    onDismiss()
                }
            )
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        
        Text(
            "AUDIO PROCESSING",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Noise Suppression", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = nsEnabled, onCheckedChange = { callManager.setAudioOptions(it, ecEnabled, agcEnabled) }, modifier = Modifier.scale(0.7f))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Echo Cancellation", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = ecEnabled, onCheckedChange = { callManager.setAudioOptions(nsEnabled, it, agcEnabled) }, modifier = Modifier.scale(0.7f))
        }
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        
        Text(
            "ATTENUATION (DUCKING)",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        )
        Slider(
            value = duckingStrength,
            onValueChange = { callManager.setDuckingStrength(it) },
            valueRange = 0f..1f,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
        )
        
        HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
        
        Text(
            "VIDEO PROCESSING",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall.copy(color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
        )
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Virtual Background", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = vbEnabled, onCheckedChange = { callManager.toggleVirtualBackground() }, modifier = Modifier.scale(0.7f))
        }
    }
}


@Composable
expect fun VideoRenderer(renderer: Any?, modifier: Modifier = Modifier)
