package ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent

private var vlcDiscovered = false

@Composable
actual fun NativeVideoPlayer(
    url: String,
    modifier: Modifier,
    onClose: () -> Unit
) {
    if (!vlcDiscovered) {
        NativeDiscovery().discover()
        vlcDiscovered = true
    }

    val mediaPlayerComponent = remember { EmbeddedMediaPlayerComponent() }

    DisposableEffect(url) {
        mediaPlayerComponent.mediaPlayer().media().play(url)
        onDispose {
            mediaPlayerComponent.mediaPlayer().controls().stop()
            mediaPlayerComponent.mediaPlayer().release()
            mediaPlayerComponent.release()
        }
    }

    SwingPanel(
        background = androidx.compose.ui.graphics.Color.Black,
        modifier = modifier.fillMaxSize(),
        factory = {
            mediaPlayerComponent
        }
    )
}
