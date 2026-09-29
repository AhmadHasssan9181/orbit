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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 *
 * The tooltip is a child of the rail item's own Box, so it is MEASURED with that
 * box's constraints -- which for a rail item is about 68dp wide. With 54dp of
 * start padding, the text had roughly 14dp to draw in, and with `maxLines = 1` and
 * no overflow it was sliced mid-glyph rather than ellipsised. Every space name
 * came out as a stub.
 *
 * [unboundedWidth] measures the label against an infinite max width so it can size
 * to its content and overflow the rail, which is what a tooltip is supposed to do.
 * The cap keeps a pathologically long name from running off the window, and the
 * ellipsis is the backstop if it ever hits that cap.
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
                    // No ring: a tooltip is a transient label, not a container,
                    // and a 1dp outline on a 20dp-tall badge is pure noise. The
                    // raised fill alone separates it from the rail.
                    .background(t.ElevatedAlt)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .unboundedWidth()
                    .widthIn(max = 320.dp)
            ) {
                Text(
                    text = text,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false
                )
            }
        }
    }
}

/**
 * Measures this composable's child with an unbounded width while still reporting
 * the child's size to the parent.
 *
 * Compose has no built-in "ignore my parent's max width", and a tooltip anchored
 * inside a narrow rail is exactly the case that needs one: the parent is a 68dp
 * column, and the thing inside it is a label whose whole job is to be as wide as
 * the text in it.
 */
private fun Modifier.unboundedWidth(): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(
        Constraints(minWidth = 0, maxWidth = Constraints.Infinity)
    )
    layout(placeable.width, placeable.height) {
        placeable.place(0, 0)
    }
}

// `OrbitPopoverCard` used to live here -- a small elevated surface with a shadow
// and a hairline, documented as being for the quick-switcher hint and the empty
// state. Zero call sites in app/src. Both of those screens ended up drawing their
// own surface inline, so it was a third spelling of "a floating panel" alongside
// `OrbitMenu`'s container and `OrbitDialog`'s.
//
// Also removed from this file during the de-boxing pass: the hairline on
// [HoverTooltip]'s badge, which is a transient label rather than a surface.
