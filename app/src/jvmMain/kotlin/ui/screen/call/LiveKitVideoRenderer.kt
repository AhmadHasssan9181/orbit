package ui.screen.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import livekit.LiveKitRemoteVideoFrameStore
import ui.theme.rememberCosmicTokens

@Composable
actual fun LiveKitVideoRenderer(modifier: Modifier) {
    val t = rememberCosmicTokens()
    val remoteFrame by LiveKitRemoteVideoFrameStore.latestFrame.collectAsState()

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val image = rememberRemoteFrameImage(remoteFrame)
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("Voice Call Connected (No Video)", color = t.OnDark)
        }
    }
}

