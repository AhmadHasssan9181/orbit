package ui.screen.call

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    if (renderer is org.webrtc.SurfaceViewRenderer) {
        AndroidView(
            factory = { renderer },
            modifier = modifier
        )
    }
}
