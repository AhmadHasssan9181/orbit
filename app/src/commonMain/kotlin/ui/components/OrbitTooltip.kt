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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
 * ## Why this is a Popup and not an overlay
 *
 * Two attempts in this file were wrong, and both failed the same way, which is
 * worth recording so a third does not get written:
 *
 * 1. The badge was a child of the rail item's own Box. It was therefore MEASURED
 *    with that box's constraints -- about 68dp -- leaving roughly 14dp to draw in
 *    after its start padding, so names were sliced mid-glyph.
 * 2. An `unboundedWidth()` modifier fixed the measurement: the badge sized itself
 *    to its text. It still came out as a stub, because the rail item lives inside
 *    a `LazyColumn`, and a lazy list clips its items to its own bounds. Escaping
 *    one box's constraints does not escape its ancestor's clip.
 *
 * A `Popup` is a separate composition root in its own window, so nothing in the
 * rail can clip it. That is also why Material's own tooltips work and this one did
 * not. The cost is that the badge no longer inherits the rail's layout position,
 * so it is positioned explicitly against the anchor's bounds instead -- which is
 * also why the `start = 54.dp` padding hack and `unboundedWidth` are both gone.
 *
 * ## Positioning
 *
 * Beside the anchor, vertically centred on it, because the rail is on the left
 * edge and there is nothing to the left of it. Clamped inside the window, so a
 * long name near the right edge slides back rather than off-screen. The width cap
 * keeps a pathologically long name from wrapping to a tall block.
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
        if (visible && text.isNotBlank()) {
            Popup(
                popupPositionProvider = rememberTooltipBesidePositionProvider(),
                // No dismiss request: this is a passive label that tracks the
                // pointer. Making it dismissable would mean dismissing it by
                // clicking the very thing it is describing.
                onDismissRequest = {},
                // Not focusable, so a tooltip never enters the tab order. And not
                // clipping-enabled, so a name longer than the badge cap is
                // ellipsised by the Text rather than sliced by the window edge.
                properties = PopupProperties(focusable = false, clippingEnabled = false)
            ) {
                Box(
                    modifier = Modifier
                        .clip(OrbitRadius.xsShape)
                        // No ring: a tooltip is a transient label, not a
                        // container, and a 1dp outline on a 20dp-tall badge is pure
                        // noise. The raised fill alone separates it from the rail.
                        .background(t.ElevatedAlt)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
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
}

/** Gap between the anchor's right edge and the badge. */
private val TooltipBesideGap = 8.dp

/** Keep the badge this far inside the window on every edge. */
private val TooltipEdgeMargin = 8.dp

/**
 * Places the badge beside the anchor, vertically centred on it, clamped on screen.
 *
 * The dp values are converted to pixels HERE, in the composable, because
 * `calculatePosition` runs during layout with no density in scope -- converting
 * there was the first thing that failed to compile, and reaching for a global
 * density from a non-composable callback is exactly how that goes wrong.
 */
@Composable
private fun rememberTooltipBesidePositionProvider(): PopupPositionProvider {
    val density = LocalDensity.current
    val gapPx = with(density) { TooltipBesideGap.roundToPx() }
    val marginPx = with(density) { TooltipEdgeMargin.roundToPx() }
    return remember(gapPx, marginPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val preferredX = anchorBounds.right + gapPx
                val maxX = (windowSize.width - popupContentSize.width - marginPx)
                    .coerceAtLeast(marginPx)
                // Coerce, not wrap: a name that would overflow the right edge slides
                // back inside, rather than appearing on the far left where it would
                // look like it belonged to something else.
                val x = preferredX.coerceAtMost(maxX)
                val centeredY = anchorBounds.center.y - popupContentSize.height / 2
                val minY = marginPx
                val maxY = (windowSize.height - popupContentSize.height - marginPx)
                    .coerceAtLeast(minY)
                return IntOffset(x, centeredY.coerceIn(minY, maxY))
            }
        }
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
