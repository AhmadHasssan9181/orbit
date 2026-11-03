package ui.screen.call

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import ui.icons.TablerIcons
import ui.icons.DeviceDesktop
import ui.icons.Window
import ui.theme.rememberCosmicTokens
import webrtc.DesktopSourceModel
import webrtc.DesktopThumbnailProvider

@Composable
actual fun DesktopSourceThumbnail(
    source: DesktopSourceModel,
    modifier: Modifier
) {
    val tokens = rememberCosmicTokens()
    var thumbnail by remember(source.id) {
        mutableStateOf<ImageBitmap?>(DesktopThumbnailProvider.getCachedThumbnail(source.id))
    }

    LaunchedEffect(source.id) {
        val initial = DesktopThumbnailProvider.captureThumbnail(source)
        if (initial != null) {
            thumbnail = initial
        }
        // Periodic 1.5s refresh for live preview without lag
        while (isActive) {
            delay(1500)
            val updated = DesktopThumbnailProvider.captureThumbnail(source)
            if (updated != null) {
                thumbnail = updated
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.Sunken)
    ) {
        Crossfade(
            targetState = thumbnail,
            animationSpec = tween(durationMillis = 180),
            modifier = Modifier.fillMaxSize()
        ) { bmp ->
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (source.isWindow) with(TablerIcons) { Window } else with(TablerIcons) { DeviceDesktop },
                        contentDescription = null,
                        tint = tokens.Accent.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }

        // Top tag badge: "WINDOW" or "SCREEN"
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .background(
                    Color.Black.copy(alpha = 0.65f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                if (source.isWindow) "WINDOW" else "SCREEN",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Bottom gradient scrim with title & icon
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    if (source.isWindow) with(TablerIcons) { Window } else with(TablerIcons) { DeviceDesktop },
                    contentDescription = null,
                    tint = tokens.Accent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    source.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
