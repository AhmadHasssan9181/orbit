package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import webrtc.CallManager
import repository.MatrixOrbitRepository

import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import kotlinx.coroutines.launch
import webrtc.CallState
import ui.model.OrbitChannel
import ui.screen.call.CallLayoutMode
import ui.screen.call.ScreenShareDialog

@Composable
fun VoiceChannelScreen(
    callManager: CallManager,
    matrixRepository: MatrixOrbitRepository,
    channel: OrbitChannel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize()) {
            val callState by callManager.callState.collectAsState()
            val scope = rememberCoroutineScope()
            val activeGroupCallUrl by matrixRepository.observeActiveGroupCall(channel.matrixRoomId).collectAsState("LOADING")
            
            if (callState == CallState.IDLE || callState == CallState.ENDED) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (activeGroupCallUrl == "LOADING") {
                            androidx.compose.material3.CircularProgressIndicator()
                            Spacer(modifier = Modifier.padding(16.dp))
                            Text("Finding active calls...", style = MaterialTheme.typography.titleMedium)
                        } else {
                            Text("Ready to join ${channel.name}?", style = MaterialTheme.typography.titleLarge)
                            Spacer(modifier = Modifier.padding(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        try {
                                            println("DEBUG [VoiceChannelScreen] User clicked Connect to Voice")
                                            val activeUrl = activeGroupCallUrl
                                            val isCreator = activeUrl == null || activeUrl == "LOADING"
                                            
                                            val callId = if (!isCreator) {
                                                if (activeUrl!!.startsWith("matrix-call://")) {
                                                    activeUrl.substringAfter("://")
                                                } else {
                                                    val extracted = activeUrl.substringAfter("?callId=", missingDelimiterValue = "m.call")
                                                    if (extracted.isBlank()) "m.call" else extracted
                                                }
                                            } else {
                                                // Creator flow uses seamless 'm.call' state key
                                                "m.call"
                                            }

                                            if (isCreator) {
                                                matrixRepository.startGroupCall(channel.matrixRoomId, callId)
                                            } else {
                                                matrixRepository.joinGroupCall(channel.matrixRoomId, callId)
                                            }

                                            val livekitUrl = if (!isCreator && (activeUrl!!.startsWith("wss://") || activeUrl.startsWith("ws://"))) {
                                                activeUrl.substringBefore("?callId=")
                                            } else {
                                                (matrixRepository as? MatrixOrbitRepository)?.context?.defaultLiveKitServiceUrl
                                            }

                                            callManager.joinGroupCall(channel.matrixRoomId, callId, true, livekitUrl) {
                                                matrixRepository.fetchLiveKitToken(channel.matrixRoomId, livekitUrl ?: "")
                                            }
                                        } catch (e: Exception) {
                                            println("ERROR [VoiceChannelScreen] Uncaught exception during join: ${e.message}")
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Filled.Call, contentDescription = null)
                                Spacer(Modifier.padding(horizontal = 4.dp))
                                Text("Connect to Voice")
                            }
                        }
                    }
                }
            } else {
                // ACTIVE CALL UI
                Box(modifier = Modifier.fillMaxSize()) {
                    LiveKitVideoRenderer(modifier = Modifier.fillMaxSize())

                    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
                    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
                    val isMutedVal = !isAudioEnabled
                    val isScreenSharing by callManager.isScreenSharing.collectAsState()
                    
                    var isDeafened by remember { mutableStateOf(false) }
                    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
                    var showSoundboard by remember { mutableStateOf(false) }
                    var showScreenShareDialog by remember { mutableStateOf(false) }

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

                    CallControlsBar(
                        callManager = callManager,
                        isVideoEnabled = isVideoEnabled,
                        isMuted = isMutedVal,
                        isDeafened = isDeafened,
                        isScreenSharing = isScreenSharing,
                        layoutMode = layoutMode,
                        showSoundboard = showSoundboard,
                        onToggleDeafen = { isDeafened = !isDeafened },
                        onLayoutModeToggle = {
                            layoutMode = if (layoutMode == CallLayoutMode.Grid) CallLayoutMode.Focus else CallLayoutMode.Grid
                        },
                        onSoundboardToggle = { showSoundboard = !showSoundboard },
                        onScreenShareClick = { showScreenShareDialog = true },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                    )
                }
            }
        }
    }
}
