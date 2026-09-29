package ui.screen.call

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
fun EmptyVoicePanel(modifier: Modifier = Modifier) {
    val tokens = rememberCosmicTokens()

    // Subtle breathing animation
    val infiniteTransition = rememberInfiniteTransition()
    val breatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tokens.Accent.copy(alpha = 0.06f),
                        tokens.Bg
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Orbit icon with glow
            Box(
                modifier = Modifier.size(72.dp),
                contentAlignment = Alignment.Center
            ) {
                // Glow ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(breatheAlpha)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(tokens.Accent.copy(alpha = 0.2f), Color.Transparent)
                            )
                        )
                )
                // Inner circle
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(tokens.Sunken),
                    contentAlignment = Alignment.Center
                ) {
                    OrbitDefaultAvatar(
                        label = "O",
                        seed = "Orbit",
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                "No one else is here",
                // Was Color.White at 50% over a hard-coded dark panel; now it
                // comes from the token set so it reads in the Light theme too.
                color = tokens.Text_Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
