package ui.assets

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource

@Composable
actual fun OrbitAppLogo(
    modifier: Modifier,
    tint: Color?,
    contentDescription: String?
) {
    Image(
        painter = painterResource("orbit_transparent.png"),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = tint?.let { ColorFilter.tint(it) }
    )
}
