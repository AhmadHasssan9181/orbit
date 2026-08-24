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
            val activeRoomId by callManager.activeRoomIdFlow.collectAsState()
            // A connected call in a DIFFERENT channel must not render here — the
            // global floating pill owns that case; duplicating it per-channel
            // produced multiple floating bars at once.
            val isActiveCallInThisRoom = activeRoomId == channel.matrixRoomId || activeRoomId == channel.id
            val activeGroupCallUrl by matrixRepository.observeActiveGroupCall(channel.matrixRoomId).collectAsState("LOADING")
            var pendingVoiceJoin by remember(channel.id) { mutableStateOf(false) }

            LaunchedEffect(callState) {
                if (callState != CallState.CONNECTING) {
                    pendingVoiceJoin = false
                }
            }

            if (callState == CallState.IDLE || callState == CallState.ENDED || !isActiveCallInThisRoom) {
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
                                    if (pendingVoiceJoin || callState == CallState.CONNECTING) {
                                        println("DEBUG [VoiceChannelScreen] Ignoring duplicate voice join click")
                                    } else {
                                        pendingVoiceJoin = true
                                        scope.launch {
                                            try {
                                                println("DEBUG [VoiceChannelScreen] User clicked Connect to Voice")
                                                var resolved = matrixRepository.resolveGroupCallJoinTarget(channel.matrixRoomId)
                                                if (!resolved.exists) {
                                                    // Fresh creator decision (no stale LOADING race): publish our definition
                                                    matrixRepository.startGroupCall(channel.matrixRoomId, "m.call")
                                                    resolved = matrixRepository.resolveGroupCallJoinTarget(channel.matrixRoomId)
                                                }
                                                val livekitUrl = resolved.serviceUrl
                                                    ?: matrixRepository.context.effectiveLiveKitServiceUrl()
                                                val callId = resolved.callId ?: "m.call"
                                                println(
                                                    "DEBUG [VoiceChannelScreen] JoinFocus callId=$callId sfu=$livekitUrl source=${resolved.source ?: "creator"}"
                                                )
                                                callManager.joinGroupCall(channel.matrixRoomId, callId, false, livekitUrl) {
                                                    matrixRepository.fetchLiveKitToken(channel.matrixRoomId, callId, livekitUrl)
                                                }
                                            } catch (e: Exception) {
                                                pendingVoiceJoin = false
                                                println("ERROR [VoiceChannelScreen] Uncaught exception during join: ${e.message}")
                                            }
                                        }
                                    }
                                },
                                enabled = !pendingVoiceJoin && callState != CallState.CONNECTING,
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
                LiveKitGroupCallSurface(
                    callManager = callManager,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
