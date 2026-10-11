package ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.X
import kotlinx.coroutines.delay
import ui.icons.OrbitIcons
import ui.theme.LocalReduceMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
/**
 * Renders [OrbitNotices] as a stack anchored to the bottom-end of its parent.
 *
 * Place it as the **last child of the app's root `Box`** so it draws over
 * everything, including the fullscreen call surfaces. It has no intrinsic size —
 * it measures only its notices.
 *
 * Three layout rules this file has to respect, each one a bug that shipped:
 *
 * 1. **No `fillMaxWidth()` on the stack.** It is a child of a `Box`, so
 *    `fillMaxWidth` would stretch it edge-to-edge and cover the composer and
 *    the message list. The notices themselves are `widthIn(max = …)`.
 * 2. **No `fillMaxSize` sibling inside the notice.** A parent `.border()` draws
 *    before its children, so a full-size child paints over the border and the
 *    notice loses its edge. Here the notice draws its own background and the
 *    border is a sibling drawn on a wrapper, so ordering cannot hide it.
 * 3. **Notice text must not wrap to one glyph per line.** See `docs/planning/WORKING_NOTES.md`
 *    §2 — a starved `Text` wraps per character. The `Row` gives the text a
 *    weighted slot and the actions a fixed one, in that order, and the text slot
 *    carries a `widthIn(min = …)`.
 */
@Composable
fun OrbitNoticeHost(modifier: Modifier = Modifier) {
    val notices by OrbitNotices.notices.collectAsState()

    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Above the user bar / composer rather than flush to the window edge.
            .padding(end = OrbitSpace.lg, bottom = insets.calculateBottomPadding() + 76.dp)
            .padding(horizontal = OrbitSpace.md),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.xs)
        ) {
            notices.forEach { notice ->
                OrbitNoticeCard(notice)
            }
        }
    }
}

@Composable
private fun OrbitNoticeCard(notice: OrbitNotice) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current

    // Dismissal is owned here, not by the store: the store has no clock and no
    // coroutine scope, which keeps it a pure, testable data structure.
    LaunchedEffect(notice.id) {
        val total = notice.autoDismissMs ?: return@LaunchedEffect
        delay(total)
        OrbitNotices.dismiss(notice.id)
    }

    // Held visible once `mustActMs` elapses, so an actionable failure cannot
    // vanish while the user is reading it.
    var pinned by remember(notice.id) { mutableStateOf(false) }
    LaunchedEffect(notice.id, notice.mustActMs) {
        val mark = notice.mustActMs ?: return@LaunchedEffect
        delay(mark)
        pinned = true
    }

    val accent = when (notice.kind) {
        OrbitNotice.Kind.Error -> t.Danger
        OrbitNotice.Kind.Info -> t.Info
    }
    val anim = if (reduce) 0 else 220

    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(anim)) + slideInVertically(tween(anim)) { it / 2 },
        exit = fadeOut(tween(anim)) + slideOutVertically(tween(anim)) { it / 2 }
    ) {
        val shape = OrbitRadius.smShape
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(shape)
                // Background and border on separate wrappers: see rule 2 above.
                .background(t.Elevated)
                .border(1.dp, accent.copy(alpha = 0.45f), shape)
                .padding(start = OrbitSpace.md, top = OrbitSpace.sm, bottom = OrbitSpace.sm, end = OrbitSpace.sm),
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.xs)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width(OrbitSpace.sm))
                // The weighted slot comes FIRST so it is measured before the
                // fixed-width actions. See rule 3.
                Text(
                    text = notice.text,
                    modifier = Modifier.weight(1f).widthIn(min = 120.dp),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Text_White,
                        fontSize = 12.5.sp
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(OrbitSpace.xs))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { OrbitNotices.dismiss(notice.id) }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = with(TablerIcons) { X },
                        contentDescription = "Dismiss",
                        tint = t.Text_Subtle,
                        modifier = Modifier.width(14.dp)
                    )
                }
            }

            if (notice.retry != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OrbitButton(
                        text = notice.retryLabel ?: "Retry",
                        onClick = {
                            // Dismiss BEFORE retrying, so a retry that fails again
                            // produces one fresh notice rather than a retry button
                            // on a notice the user is already looking at.
                            val action = notice.retry
                            OrbitNotices.dismiss(notice.id)
                            action?.invoke()
                        },
                        tone = OrbitButtonTone.Primary,
                        size = 28.dp
                    )
                }
            }
        }
    }
}
