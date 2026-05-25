package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import webrtc.DesktopVideoRenderer

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    when (renderer) {
        is DesktopVideoRenderer -> {
            val frame by renderer.frameImage.collectAsState()
            Box(
                modifier = modifier
                    .clipToBounds()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val image = frame
                if (image != null) {
                    Image(
                        bitmap = image.toComposeImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (renderer.mirror) -1f else 1f
                            }
                    )
                } else {
                    Text("Starting video", color = Color.White)
                }
            }
        }
        else -> {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Starting video", color = Color.White)
            }
        }
    }
}
