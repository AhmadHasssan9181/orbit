package ui.assets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Renders the Orbit app brand logo emblem.
 */
@Composable
expect fun OrbitAppLogo(
    modifier: Modifier = Modifier,
    tint: Color? = null,
    contentDescription: String? = null
)
