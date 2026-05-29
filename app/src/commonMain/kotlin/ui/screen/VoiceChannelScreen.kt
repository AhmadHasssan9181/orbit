package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import webrtc.CallManager
import ui.components.WebViewContainer
import repository.MatrixOrbitRepository

import kotlinx.datetime.Clock
import androidx.compose.ui.Alignment
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import kotlinx.coroutines.launch
import webrtc.CallState
import ui.model.OrbitChannel

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
            val activeGroupCallUrl by matrixRepository.observeActiveGroupCall(channel.matrixRoomId).collectAsState(null)
            
            if (callState == CallState.IDLE || callState == CallState.ENDED) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ready to join ${channel.name}?", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.padding(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    // 1. Check if Element call already exists
                                    val activeUrl = activeGroupCallUrl
                                    val callId = if (activeUrl != null && activeUrl.startsWith("matrix-call://")) {
                                        activeUrl.substringAfter("://")
                                    } else {
                                        val newId = "orbit_call_${kotlin.random.Random.nextLong()}"
                                        matrixRepository.startGroupCall(channel.matrixRoomId, newId)
                                        newId
                                    }
                                    // 1. Join call on backend (registration)
                                    matrixRepository.joinGroupCall(channel.matrixRoomId, callId)
                                    // 2. Start local media
                                    callManager.joinGroupCall(channel.matrixRoomId, callId, true)
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
            } else {
                CallScreen(
                    callManager = callManager,
                    callerName = channel.name,
                    onStartScreenShare = { callManager.startScreenShare() },
                    isEmbedded = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

