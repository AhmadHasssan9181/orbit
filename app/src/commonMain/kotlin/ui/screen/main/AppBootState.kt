package ui.screen.main

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitBrandMark
import ui.components.OrbitLoadingState
import ui.theme.LocalReduceMotion
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * First-paint state.
 *
 * A centred spinner on a flat rectangle used to greet the user while the sync
 * bootstrapped. This puts the brand mark at rest, breathes it gently, and gets
 * out of the way — and it disappears completely when the user has asked for
 * reduced motion.
 */
@Composable
internal fun AppBootState(modifier: Modifier = Modifier) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current

    val transition = rememberInfiniteTransition(label = "boot")
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bootBreathe"
    )
    val shimmer by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bootShimmer"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        OrbitBrandMark(
            modifier = Modifier
                .size(56.dp)
                .scale(if (reduce) 1f else breathe),
            showGlow = !reduce
        )
        Spacer(Modifier.height(OrbitSpace.lg))
        Text(
            text = "Orbit",
            style = MaterialTheme.typography.titleSmall.copy(
                color = t.Text_White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp
            )
        )
        Spacer(Modifier.height(OrbitSpace.xs))
        Text(
            text = "Restoring your session…",
            style = MaterialTheme.typography.bodySmall.copy(
                color = t.Text_Subtle.copy(alpha = if (reduce) 1f else shimmer),
                fontSize = 12.sp
            ),
            textAlign = TextAlign.Center
        )
    }
}
