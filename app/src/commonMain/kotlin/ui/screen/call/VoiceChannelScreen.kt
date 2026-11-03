package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.icons.TablerIcons
import ui.icons.Phone
import kotlinx.coroutines.launch
import repository.MatrixOrbitRepository
import ui.model.OrbitChannel
import ui.screen.call.CallLayoutMode
import ui.screen.call.ScreenShareDialog
import webrtc.CallManager
import webrtc.CallState
/**
 * Join (or start) the MatrixRTC call in [roomId] and connect [callManager] to it.
 *
 * Extracted from the "Connect to Voice" button so that **clicking the voice
 * channel in the drawer joins immediately**, Discord-style, without a second
 * click and without the two paths drifting apart.
 *
 * Returns null on success, or a message describing what went wrong. It does not
 * throw: the caller is a click handler, and an uncaught exception there just
 * logs.
 */
suspend fun joinVoiceChannel(
    callManager: CallManager,
    matrixRepository: MatrixOrbitRepository,
    roomId: String,
    trigger: String
): String? {
    return try {
        util.OrbitLog.d("VoiceChannelScreen", "join requested trigger=$trigger room=$roomId")
        // Re-decide here rather than trusting the "Loading" value the drawer may
        // be showing: a stale LOADING made this start a second call definition
        // in a room that already had one.
        var resolved = matrixRepository.resolveGroupCallJoinTarget(roomId)
        if (!resolved.exists) {
            matrixRepository.startGroupCall(roomId, "m.call")
            resolved = matrixRepository.resolveGroupCallJoinTarget(roomId)
        }
        val livekitUrl = resolved.serviceUrl
            ?: matrixRepository.context.effectiveLiveKitServiceUrl()
        val callId = resolved.callId ?: "m.call"
        util.OrbitLog.d(
            "VoiceChannelScreen",
            "JoinFocus callId=$callId sfu=$livekitUrl source=${resolved.source ?: "creator"}"
        )
        callManager.joinGroupCall(roomId, callId, false, livekitUrl) {
            matrixRepository.fetchLiveKitToken(roomId, callId, livekitUrl)
        }
        null
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        util.OrbitLog.e("VoiceChannelScreen", "join failed: ${e.message}")
        e.message ?: "Could not join the call"
    }
}

@Composable
fun VoiceChannelScreen(
    callManager: CallManager,
    matrixRepository: MatrixOrbitRepository,
    channel: OrbitChannel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            val callState by callManager.callState.collectAsState()
            val scope = rememberCoroutineScope()
            val activeRoomId by callManager.activeRoomIdFlow.collectAsState()
            // A connected call in a DIFFERENT channel must not render here. The
            // floating pill used to own that case; the pill is gone, so this
            // screen simply shows its own join affordance and the active call
            // lives wherever the user is actually looking.
            val isActiveCallInThisRoom = activeRoomId == channel.matrixRoomId || activeRoomId == channel.id
            val activeGroupCallUrl by matrixRepository.observeActiveGroupCall(channel.matrixRoomId).collectAsState("LOADING")
            var pendingVoiceJoin by remember(channel.id) { mutableStateOf(false) }

            LaunchedEffect(callState) {
                if (callState != CallState.CONNECTING) {
                    pendingVoiceJoin = false
                }
            }

            // Auto-join on arrival is GONE.
            //
            // It was added to match Discord's click-a-voice-channel-to-join, and
            // it was wrong here for the same reason the Text/Voice split was
            // wrong: it assumed opening a room and entering a call are the same
            // act. In Discord they are different channels, so clicking one does
            // not open the other's conversation.
            //
            // In Matrix they are the SAME room. So joining on click meant you
            // could never read a room that also had a call in it — clicking
            // "General" dropped you into a call and its chat was unreachable.
            // Reported with two screenshots, one per room, both landing in a
            // call grid.
            //
            // Joining is now always an explicit press of "Connect to Voice".

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
                                        util.OrbitLog.d("VoiceChannelScreen", "Ignoring duplicate voice join click")
                                    } else {
                                        pendingVoiceJoin = true
                                        scope.launch {
                                            val error = joinVoiceChannel(
                                                callManager, matrixRepository, channel.matrixRoomId, "connect-button"
                                            )
                                            if (error != null) {
                                                pendingVoiceJoin = false
                                                ui.components.OrbitNotices.error(
                                                    "Could not join ${channel.name}: $error", "VoiceJoin"
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = !pendingVoiceJoin && callState != CallState.CONNECTING,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(with(TablerIcons) { Phone }, contentDescription = null)
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
                    modifier = Modifier.fillMaxSize(),
                    remoteFallbackName = channel.name
                )
            }
        }
    }
}
