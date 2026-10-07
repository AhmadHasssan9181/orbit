package ui.assets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Renders the Orbit mascot fallback avatar on a cheerful pastel background.
 * Used across the app for members and contacts without a custom avatar.
 */
@Composable
expect fun OrbitMascotAvatar(
    seed: String,
    modifier: Modifier = Modifier,
    color: Color? = null
)
