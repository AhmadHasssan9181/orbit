package ui.screen.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import ui.icons.TablerIcons
import ui.icons.ChartBar
import ui.icons.HeadphonesOff
import ui.icons.MicrophoneOff
import ui.icons.PhoneOff
import ui.icons.X
import livekit.LiveKitRemoteVideoFrameStore
import livekit.liveKitActiveSpeakers
import ui.theme.rememberCosmicTokens
import webrtc.ActiveCallBridge
import webrtc.CallManager
import webrtc.CallState
private val PIP_ACTIVE_STATES = setOf(CallState.CONNECTING, CallState.CONNECTED)

/**
 * Floating always-on-top call window shown while the main Orbit window is
 * minimized or hidden to tray during an active call: screen share first,
 * otherwise the current active speaker's camera, else any flowing remote
 * frame; audio-only calls get a compact card with mic/deafen state.
 * Clicking anywhere restores the main window; ✕ dismisses until the next call.
 */
@Composable
fun ActiveCallPipWindow(
    mainWindowPresentable: Boolean,
    onRestoreMainWindow: () -> Unit
) {
    val callManager = ActiveCallBridge.callManager ?: return
    val callState by callManager.callState.collectAsState()
    val callActive = callState in PIP_ACTIVE_STATES

    var dismissedThisCall by remember { mutableStateOf(false) }
    LaunchedEffect(callActive) {
        if (!callActive) dismissedThisCall = false
    }

    if (!callActive) return
    val pipVisible = !mainWindowPresentable && !dismissedThisCall

    val pipState = rememberWindowState(
        width = 320.dp,
        height = 190.dp,
        position = WindowPosition(Alignment.BottomEnd)
    )

    Window(
        onCloseRequest = { dismissedThisCall = true },
        state = pipState,
        visible = pipVisible,
        title = "Orbit call",
        icon = painterResource("orbit_logo.png"),
        undecorated = true,
        resizable = false,
        alwaysOnTop = true
    ) {
        if (pipVisible) {
            PipWindowContent(
                callManager = callManager,
                onRestoreMainWindow = onRestoreMainWindow,
                onDismiss = { dismissedThisCall = true },
                onEndCall = {
                    dismissedThisCall = true
                    callManager.endCall()
                }
            )
        }
    }
}

@Composable
private fun PipWindowContent(
    callManager: CallManager,
    onRestoreMainWindow: () -> Unit,
    onDismiss: () -> Unit,
    onEndCall: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isDeafened by callManager.isDeafened.collectAsState()

    val screenShareFrame by LiveKitRemoteVideoFrameStore.latestScreenShareFrame.collectAsState()
    val cameraFallback by LiveKitRemoteVideoFrameStore.latestCameraFrame.collectAsState()
    val lastResort by LiveKitRemoteVideoFrameStore.latestFrame.collectAsState()
    val activeTracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
    val framesByTrack by LiveKitRemoteVideoFrameStore.framesByTrack.collectAsState()
    val speakers by liveKitActiveSpeakers().collectAsState()
    val remoteParticipants by LiveKitRemoteVideoFrameStore.remoteParticipants.collectAsState()

    // Share > active speaker camera > any flowing frame. Speaker identities and
    // track participantIdentities share the LiveKit identity space; when no
    // speaker is flagged yet the latest camera frame is the sane stand-in.
    val targetFrame = when {
        screenShareFrame != null -> screenShareFrame
        speakers.isNotEmpty() -> {
            val speaker = speakers.first()
            val camSid = activeTracks.values.firstOrNull {
                it.participantIdentity == speaker && !it.isScreenShare
            }?.trackSid
            camSid?.let { framesByTrack[it] } ?: cameraFallback ?: lastResort
        }
        else -> cameraFallback ?: lastResort
    }
    val image = rememberRemoteFrameImage(targetFrame)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0C0E))
            .border(1.dp, tokens.Accent.copy(alpha = 0.25f))
            .clickable(onClick = onRestoreMainWindow)
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = tokens.Accent.copy(alpha = 0.2f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            with(TablerIcons) { ChartBar },
                            contentDescription = null,
                            tint = tokens.Accent,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Orbit call", color = tokens.OnDark, fontSize = 13.sp)
                Text(
                    "${remoteParticipants.size + 1} in call",
                    color = tokens.OnDark.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
        }

        // Mic / deafen badges (bottom-start)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!isAudioEnabled) PipBadge(container = Color(0xFFD65A54)) {
                Icon(with(TablerIcons) { MicrophoneOff }, "Muted", tint = Color.White, modifier = Modifier.size(13.dp))
            }
            if (isDeafened) PipBadge(container = Color(0xFFD65A54)) {
                Icon(with(TablerIcons) { HeadphonesOff }, "Deafened", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }

        // Dismiss (top-end); child clickable wins over the restore tap.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                with(TablerIcons) { X },
                "Close mini window",
                tint = tokens.OnDark.copy(alpha = 0.85f),
                modifier = Modifier.size(14.dp)
            )
        }

        // Hang up (bottom-end)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFD65A54))
                .clickable(onClick = onEndCall),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                with(TablerIcons) { PhoneOff },
                "Hang up",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun PipBadge(
    container: Color,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(5.dp)
    ) {
        content()
    }
}
