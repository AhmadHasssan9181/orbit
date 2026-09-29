package ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.LocalReduceMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Shimmering placeholder block.
 *
 * The app used a bare `CircularProgressIndicator` centred on a flat
 * background for every loading state, which meant switching rooms flashed a
 * spinner. Skeletons at the right shape are calmer and faster to read.
 */
@Composable
fun OrbitSkeleton(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = OrbitRadius.smShape
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "skeletonShift"
    )
    val base = t.CardBgHover
    val highlight = t.SeparatorStrong
    Box(
        modifier = modifier
            .clip(shape)
            .background(base)
            .then(
                if (reduce) {
                    Modifier
                } else {
                    Modifier.background(
                        Brush.linearGradient(
                            colors = listOf(Color.Transparent, highlight.copy(alpha = 0.55f), Color.Transparent),
                            start = androidx.compose.ui.geometry.Offset(shift * 900f - 300f, 0f),
                            end = androidx.compose.ui.geometry.Offset(shift * 900f + 100f, 260f)
                        )
                    )
                }
            )
    )
}

/** A row of a message, used while a channel's history loads. */
@Composable
fun OrbitMessageSkeleton(
    modifier: Modifier = Modifier,
    avatar: Boolean = true
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.sm),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
    ) {
        if (avatar) {
            OrbitSkeleton(modifier = Modifier.size(34.dp), shape = CircleShape)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            OrbitSkeleton(modifier = Modifier.fillMaxWidth(0.28f).height(11.dp))
            OrbitSkeleton(modifier = Modifier.fillMaxWidth(if (avatar) 0.62f else 0.7f).height(12.dp))
        }
    }
}


/**
 * Empty / zero state.
 *
 * The old one drew a Canvas illustration in `#23252E` regardless of theme and
 * centred it with a hard-coded headline. This is typographic, themed, and
 * accepts an optional action so every empty state can offer a way forward.
 */
@Composable
fun OrbitEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    accent: Color? = null
) {
    val t = rememberCosmicTokens()
    val highlight = accent ?: t.Accent
    Column(
        modifier = modifier.padding(OrbitSpace.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(OrbitRadius.lgShape)
                    .background(t.AccentGhost)
                    .border(1.dp, t.AccentBorder, OrbitRadius.lgShape),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = highlight,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(OrbitSpace.lg))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = t.Text_White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(OrbitSpace.xs))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = t.Text_Muted,
                fontSize = 13.sp,
                lineHeight = 18.sp
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(OrbitSpace.lg))
            OrbitButton(
                text = actionLabel,
                onClick = onAction,
                tone = OrbitButtonTone.Neutral
            )
        }
    }
}
