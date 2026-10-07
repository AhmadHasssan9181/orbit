package ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent
import repository.CredentialManager

/**
 * `NativeDiscovery().discover()` is a blocking filesystem scan for libvlc. It ran
 * in the composable body on the AWT/Compose thread and mutated a global flag
 * during composition, freezing the window the first time a video played. One
 * lazy, outside composition, means it happens at most once per process.
 */
private val vlcDiscovery: Boolean by lazy {
    runCatching { NativeDiscovery().discover() }.isSuccess
}

@Composable
actual fun NativeVideoPlayer(
    url: String,
    modifier: Modifier,
    onClose: () -> Unit
) {
    // Touch the lazy here so the one-time scan is paid before the SwingPanel is
    // created rather than from inside its factory.
    remember { vlcDiscovery }

    // Keyed on url: an unkeyed remember handed the same (already released)
    // component to a new DisposableEffect whenever the source changed, so
    // playback was attempted on a dead native object.
    val mediaPlayerComponent = remember(url) { EmbeddedMediaPlayerComponent() }

    DisposableEffect(url) {
        val token = CredentialManager.accessToken
        // SECURITY: origin check, not `url.contains("/_matrix/")`. The substring form
        // is satisfied by any host containing that text, so a hostile media URL
        // would get the account token attached by libVLC. See util/OriginGuard.
        val isMatrixMedia = util.OriginGuard.mayAttachCredential(
            url,
            CredentialManager.homeserverUrl
        )
        val media = mediaPlayerComponent.mediaPlayer().media()
        if (token != null && isMatrixMedia) {
            // Authorization HEADER, never a query parameter. The access token is
            // the long-lived account credential; in a URL it lands in the
            // homeserver's, the reverse proxy's and any CDN's access logs, in a
            // Referer on redirect, and in libVLC's internal URL string, which
            // surfaces verbatim in player error text and any crash report.
            //
            // The option goes in the PER-PLAY argument list. This used to do
            // `media.options().add(...)` and then `media.play(url)`, and `play`
            // builds a fresh media item internally which does not inherit defaults
            // set on the old one -- so the header was silently dropped, the
            // homeserver answered M_MISSING_TOKEN, and the video refused to play
            // with nothing on screen to say why: a media item that 401s simply
            // ends.
            //
            // vlcj 4.8 removed the `add(arrayOf(...))` overload, which is what the
            // old call used.
            runCatching {
                media.play(url, ":http-header=Authorization=Bearer $token")
            }.onFailure { e ->
                println("WARN [NativeVideoPlayer] could not open $url: ${e.message}")
            }
        } else {
            runCatching { media.play(url) }
                .onFailure { e -> println("WARN [NativeVideoPlayer] could not open $url: ${e.message}") }
        }

        onDispose {
            // Stop, never release: this composable lives inside a LazyColumn
            // item, so releasing on dispose killed playback as soon as the row
            // scrolled out of view, and SwingPanel re-used the dead component.
            runCatching { mediaPlayerComponent.mediaPlayer().controls().stop() }
        }
    }

    SwingPanel(
        background = Color.Black,
        modifier = modifier.fillMaxSize(),
        factory = {
            mediaPlayerComponent
        }
    )
}
