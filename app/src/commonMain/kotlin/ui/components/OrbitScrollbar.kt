package ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ui.theme.LocalReduceMotion
import ui.theme.rememberCosmicTokens

/**
 * Desktop scrollbar.
 *
 * Compose's scrollbars are Android-shaped: a permanent grey track with a thumb
 * that never changes width. On a wide desktop window a permanent track cuts a
 * visible gouge through the layout, so this one is invisible until the list
 * moves, then fades back out.
 *
 * It is deliberately **not** interactive. An overlay strip that handles pointer
 * input wins hit-testing over the content beneath it, which would quietly make
 * a ~12dp band along the right edge of every list unclickable — including the
 * message hover toolbar, which deliberately overhangs the edge. A decorative
 * overlay has no such problem, and wheel + keyboard scrolling already work.
 */
@Composable
fun OrbitScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thickness: Dp = 6.dp
) {
    val t = rememberCosmicTokens()
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current

    var moving by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            moving = true
        } else {
            delay(900)
            moving = false
        }
    }

    val geometry = rememberScrollbarGeometry(listState)
    val geo = geometry ?: return

    val alpha by animateFloatAsState(
        targetValue = if (moving) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 220),
        label = "scrollAlpha"
    )
    val grow by animateFloatAsState(
        targetValue = if (moving) 1f else 0.78f,
        animationSpec = tween(if (reduceMotion) 0 else 220),
        label = "scrollGrow"
    )

    val thicknessDp = thickness * grow
    val topDp = geo.top
    val thumbDp = geo.thumbSize

    Box(modifier = modifier.fillMaxSize()) {
        if (alpha > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = topDp, end = 3.dp)
                    .width(thicknessDp)
                    .height(thumbDp)
                    .clip(RoundedCornerShape(50))
                    .background(t.ScrollbarThumb.copy(alpha = t.ScrollbarThumb.alpha * alpha))
            )
        }
    }
}

private class ScrollGeometry(
    val top: Dp,
    val thumbSize: Dp
)

/**
 * Thumb position and size derived from the lazy list's layout info. Item
 * heights are not uniform (attachments, grouped messages), so this uses the
 * average visible item height rather than assuming a fixed stride.
 */
@Composable
private fun rememberScrollbarGeometry(state: LazyListState): ScrollGeometry? {
    val density = LocalDensity.current
    val derived = remember(state, density) {
        derivedStateOf {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val total = info.totalItemsCount
            if (visible.isEmpty() || total <= 1) {
                null
            } else {
                val first = visible.first()
                val last = visible.last()
                val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
                val span = (last.offset + last.size - first.offset).toFloat()
                if (viewport <= 0f || span <= 0f) {
                    null
                } else {
                    val itemSize = span / visible.size
                    val content = itemSize * total
                    val ratio = (visible.size.toFloat() / total).coerceIn(0.05f, 1f)
                    val thumb = (viewport * ratio).coerceIn(30f, viewport)
                    val range = (content - viewport).coerceAtLeast(1f)
                    val scrolled = (-first.offset + info.viewportStartOffset).toFloat().coerceIn(0f, range)
                    val travel = (viewport - thumb).coerceAtLeast(0f)
                    val top = (scrolled / range) * travel
                    with(density) {
                        ScrollGeometry(top = top.toDp(), thumbSize = thumb.toDp())
                    }
                }
            }
        }
    }
    return derived.value
}

/**
 * A `LazyColumn` with the Orbit scrollbar already wired up.
 *
 * Every scrolling surface should go through this so the scrollbar behaviour can
 * never regress on one screen only.
 */
@Composable
fun OrbitLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    reverseLayout: Boolean = false,
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical =
        androidx.compose.foundation.layout.Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyListScope.() -> Unit
) {
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            contentPadding = contentPadding,
            content = content
        )
        OrbitScrollbar(listState = state)
    }
}
