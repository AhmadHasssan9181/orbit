package ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    // For Desktop, rendering video frames via Compose ImageBitmap is complex.
    // As a placeholder, we just draw a background or handle SwingPanel if necessary.
    Box(
        modifier = modifier.background(Color.DarkGray),
        contentAlignment = Alignment.Center
    ) {
        Text("Video Stream (Desktop)", color = Color.White)
    }
}
