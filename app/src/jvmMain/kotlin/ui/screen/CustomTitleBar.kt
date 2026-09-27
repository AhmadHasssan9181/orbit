package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Window chrome.
 *
 * The previous bar was a 32dp strip of hard-coded `#0D0D12` with the glyphs
 * "—" and "□" standing in for minimise and maximise, and a close button that
 * behaved exactly like the other two. Windows users expect the real
 * convention: 46×32 hit areas, a red close on hover, and a distinct icon for
 * "restore". The bar also earns its pixels by naming the app and carrying the
 * window menu, which the undecorated window otherwise throws away.
 */
@Composable
fun FrameWindowScope.CustomTitleBar(
    state: WindowState,
    onClose: () -> Unit,
    title: String = "Orbit",
    trailingContent: (@Composable () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val maximized = state.placement == WindowPlacement.Maximized

    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(t.Rail_Bg)
                .padding(start = OrbitSpace.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource("logo.jpeg"),
                contentDescription = null,
                modifier = Modifier
                    .size(15.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(OrbitSpace.sm))
            Text(
                text = title,
                color = t.Text_Secondary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp
            )

            if (trailingContent != null) {
                Spacer(Modifier.width(OrbitSpace.md))
                trailingContent()
            }

            Spacer(Modifier.weight(1f))

            TitleBarButton(
                description = "Minimise",
                onClick = { state.isMinimized = true }
            ) { glyph -> MinimiseGlyph(glyph) }

            TitleBarButton(
                description = if (maximized) "Restore" else "Maximise",
                onClick = {
                    state.placement =
                        if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
                }
            ) { glyph -> MaximiseGlyph(maximized, glyph) }

            TitleBarButton(
                description = "Close",
                onClick = onClose,
                hoverColor = Color(0xFFE81123)
            ) { glyph ->
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = glyph,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun TitleBarButton(
    description: String,
    onClick: () -> Unit,
    hoverColor: Color? = null,
    content: @Composable (Color) -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            !hovered -> Color.Transparent
            hoverColor != null -> hoverColor
            else -> t.Pressed
        },
        animationSpec = tween(110),
        label = "titleBtnBg"
    )
    val glyph = if (hovered && hoverColor != null) Color.White else t.Text_Secondary

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(46.dp)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content(glyph)
    }
}

/** Tiny window glyphs drawn inline so they never depend on an icon font. */
@Composable
private fun MinimiseGlyph(color: Color) {
    Box(
        modifier = Modifier
            .width(10.dp)
            .height(1.dp)
            .background(color)
    )
}

@Composable
private fun MaximiseGlyph(maximized: Boolean, color: Color) {
    Box(modifier = Modifier.size(10.dp), contentAlignment = Alignment.Center) {
        if (maximized) {
            // Two offset outlines read as "restore".
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .border(1.dp, color, RoundedCornerShape(1.dp))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(7.dp)
                    .border(1.dp, color, RoundedCornerShape(1.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .border(1.dp, color, RoundedCornerShape(1.dp))
            )
        }
    }
}
