package ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    // For Desktop, rendering video frames via Compose ImageBitmap is complex.
    // As a placeholder, we just draw a background or handle SwingPanel if necessary.
    androidx.compose.foundation.layout.Box(
        modifier = modifier.androidx.compose.foundation.background(androidx.compose.ui.graphics.Color.DarkGray),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text("Video Stream (Desktop)", color = androidx.compose.ui.graphics.Color.White)
    }
}
