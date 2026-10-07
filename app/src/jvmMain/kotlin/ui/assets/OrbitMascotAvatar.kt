package ui.assets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import ui.theme.pastelAvatarSeedColors

@Composable
actual fun OrbitMascotAvatar(
    seed: String,
    modifier: Modifier,
    color: Color?
) {
    val (lightColor, shadeColor) = if (color != null) {
        color to color.copy(alpha = 0.85f)
    } else {
        pastelAvatarSeedColors(seed)
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(lightColor, shadeColor),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource("orbit_mascot.svg"),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.82f),
            contentScale = ContentScale.Fit
        )
    }
}
