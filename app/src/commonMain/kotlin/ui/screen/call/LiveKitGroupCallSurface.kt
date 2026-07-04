package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import livekit.NativeMediaState
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState

@Composable
fun LiveKitGroupCallSurface(
    callManager: CallManager,
    modifier: Modifier = Modifier,
    remoteFallbackName: String = "Element"
) {
    val callState by callManager.callState.collectAsState()
    val nativeReadiness by callManager.nativeMediaReadiness.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val isAudioEnabled by callManager.isLocalAudioEnabled.collectAsState()
    val isMuted = !isAudioEnabled
    val isScreenSharing by callManager.isScreenSharing.collectAsState()
    val localFrame by LiveKitLocalVideoFrameStore.latestFrame.collectAsState()
    val remoteFrame by LiveKitRemoteVideoFrameStore.latestFrame.collectAsState()
    val remoteCameraFrame by LiveKitRemoteVideoFrameStore.latestCameraFrame.collectAsState()
    val remoteScreenShareFrame by LiveKitRemoteVideoFrameStore.latestScreenShareFrame.collectAsState()
    val remoteTracks by LiveKitRemoteVideoFrameStore.activeTracks.collectAsState()
    val tokens = rememberCosmicTokens()

    var isDeafened by remember { mutableStateOf(false) }
    var layoutMode by remember { mutableStateOf(CallLayoutMode.Grid) }
    var showSoundboard by remember { mutableStateOf(false) }
    var showScreenShareDialog by remember { mutableStateOf(false) }

    val nativeMediaReady = nativeReadiness?.state == NativeMediaState.MediaReady &&
        nativeReadiness?.audioPublished == true
    val renderingGrid = callState == CallState.CONNECTED || nativeMediaReady
    val remoteIdentities = remoteTracks.values
        .map { it.participantIdentity }
        .filter { it.isNotBlank() }
        .distinct()
    val remoteParticipantsCount = if (remoteIdentities.isNotEmpty()) remoteIdentities.size else if (renderingGrid) 1 else 0
    val remoteTileName = remoteFrame?.participantIdentity
        ?.takeIf { it.isNotBlank() }
        ?: remoteIdentities.firstOrNull()
        ?: remoteFallbackName

    val localFrameLogKey = localFrame?.sequence?.takeIf { it <= 3L || it % 60L == 0L }
    val remoteFrameLogKey = remoteFrame?.sequence?.takeIf { it <= 3L || it % 60L == 0L }

    LaunchedEffect(
        callState,
        nativeReadiness,
        isVideoEnabled,
        localFrameLogKey,
        remoteFrameLogKey,
        remoteTracks,
        renderingGrid
    ) {
        println("OrbitCallUI state=$callState")
        println(
            "OrbitCallUI nativeMediaReady=$nativeMediaReady nativeState=${nativeReadiness?.state} " +
                "audioPublished=${nativeReadiness?.audioPublished == true} " +
                "videoPublished=${nativeReadiness?.videoPublished == true}"
        )
        println("OrbitCallUI localVideoEnabled=$isVideoEnabled")
        println(
            "OrbitCallUI localPreviewFrame=${localFrame?.sequence ?: 0} " +
                "width=${localFrame?.width ?: 0} height=${localFrame?.height ?: 0}"
        )
        println("OrbitCallUI remoteParticipants=$remoteParticipantsCount identities=${remoteIdentities.joinToString(",").ifBlank { "<fallback>" }}")
        println("OrbitCallUI remoteVideoTracks=${remoteTracks.size}")
        println(
            "OrbitCallUI remoteFrameReceived=${remoteFrame != null} frame=${remoteFrame?.sequence ?: 0} " +
                "width=${remoteFrame?.width ?: 0} height=${remoteFrame?.height ?: 0}"
        )
        println("OrbitCallUI renderingGrid=$renderingGrid")
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF111214))
    ) {
        if (renderingGrid) {
            val localRenderer = if (isVideoEnabled && localFrame != null) {
                LiveKitVideoTileRenderer.LocalPreview
            } else {
                null
            }
            val hasScreenShare = remoteTracks.values.any { it.isScreenShare } || remoteScreenShareFrame != null
            val remoteRenderer = if (remoteCameraFrame != null || (!hasScreenShare && remoteFrame != null)) {
                LiveKitVideoTileRenderer.Remote(remoteTileName)
            } else {
                null
            }
            val nodes = mutableListOf(
                RenderNode(
                    id = "local",
                    name = "You",
                    renderer = localRenderer,
                    isScreenShare = false,
                    isMuted = isMuted,
                    isDeafened = isDeafened,
                    avatarColor = 0xFF5865F2.toInt()
                ),
                RenderNode(
                    id = "remote",
                    name = remoteTileName,
                    renderer = remoteRenderer,
                    isScreenShare = false,
                    avatarColor = 0xFFF23F42.toInt()
                )
            )
            if (hasScreenShare) {
                nodes.add(
                    RenderNode(
                        id = "remote_screen",
                        name = "${remoteTileName}'s Screen",
                        renderer = if (remoteScreenShareFrame != null) LiveKitVideoTileRenderer.RemoteScreenShare(remoteTileName) else null,
                        isScreenShare = true,
                        avatarColor = 0xFFF23F42.toInt()
                    )
                )
            }

            CallGrid(
                nodes = nodes,
                tokens = tokens,
                layoutMode = layoutMode,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp, bottom = 96.dp)
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = tokens.Accent)
            }
        }

        CallControlsBar(
            callManager = callManager,
            isVideoEnabled = isVideoEnabled,
            isMuted = isMuted,
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
