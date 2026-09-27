package ui.screen.server

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitIconButton
import ui.icons.OrbitIcons
import ui.model.OrbitServer
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Space header.
 *
 * The banner case used to scale a photo to 100dp with a hard black gradient
 * and squeeze a 15sp title into 24dp of vertical space, which clipped any
 * server with a long name. The banner now gets real room, the title always
 * gets two lines' worth of fallback height, and the actions appear on hover
 * instead of permanently competing with the name.
 */
@Composable
internal fun ServerHeader(
    server: OrbitServer,
    showArrow: Boolean,
    onHeaderClick: () -> Unit = {},
    onCreateRoomClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val hasBanner = server.bannerUrl != null
    val headerHeight by animateDpAsState(
        targetValue = if (hasBanner) 116.dp else 48.dp,
        animationSpec = OrbitMotion.GentleDp,
        label = "headerHeight"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .hoverable(interaction)
    ) {
        if (hasBanner) {
            coil3.compose.AsyncImage(
                model = server.bannerUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to t.Rail_Bg.copy(alpha = 0.35f),
                            1f to t.Drawer_Bg
                        )
                    )
            )
        } else if (hovered) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(t.Hover)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .clip(OrbitRadius.xsShape)
                .hoverable(interaction)
                .clickableNoRipple(onHeaderClick)
                .padding(start = OrbitSpace.md, end = OrbitSpace.xs, top = OrbitSpace.sm, bottom = OrbitSpace.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    fontSize = 14.sp,
                    letterSpacing = 0.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (showArrow) {
                Spacer(Modifier.width(OrbitSpace.xxs))
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = if (hovered) t.Text_Secondary else t.Text_Subtle,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (onCreateRoomClick != null) {
                OrbitIconButton(
                    icon = OrbitIcons.Add,
                    contentDescription = "Create a room in ${server.name}",
                    onClick = onCreateRoomClick,
                    size = 26.dp,
                    iconSize = 15.dp,
                    shape = OrbitRadius.xsShape
                )
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
