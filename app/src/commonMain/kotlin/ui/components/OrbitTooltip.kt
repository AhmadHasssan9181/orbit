package ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import ui.theme.LocalReduceMotion
import ui.theme.OrbitRadius
import ui.theme.rememberCosmicTokens

/**
 * Desktop tooltip.
 *
 * The app had tooltips in two places (Material's TooltipBox in the call bar, a
 * hand-rolled AnimatedVisibility badge in the user bar) with different
 * timings, colours and offsets. One implementation now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitTooltip(
    text: String,
    content: @Composable () -> Unit
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val state = rememberTooltipState()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = t.ElevatedAlt,
                contentColor = t.Text_White,
                shape = OrbitRadius.xsShape
            ) {
                Text(
                    text = text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = t.Text_White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        state = state
    ) {
        content()
    }
}

/**
 * Tooltip that appears on hover rather than on long-press. Used for the server
 * rail, where the tooltip is the only way to name a space.
 */
@Composable
fun HoverTooltip(
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    Box(modifier = modifier) {
        content()
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(if (reduce) 0 else 110)),
            exit = fadeOut(tween(if (reduce) 0 else 80))
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 54.dp)
                    .clip(OrbitRadius.xsShape)
                    .background(t.ElevatedAlt)
                    .border(1.dp, t.Separator, OrbitRadius.xsShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Small arrow-tip tooltip anchored below a point — the quick-switcher hint and
 * the empty-state call to action use this.
 */
@Composable
fun OrbitPopoverCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    val t = rememberCosmicTokens()
    Box(
        modifier = modifier
            .then(
                if (elevated) {
                    Modifier.shadow(18.dp, OrbitRadius.lgShape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                } else {
                    Modifier
                }
            )
            .clip(OrbitRadius.lgShape)
            .background(t.Elevated)
            .border(1.dp, t.SeparatorStrong, OrbitRadius.lgShape)
            .padding(padding)
    ) {
        content()
    }
}
