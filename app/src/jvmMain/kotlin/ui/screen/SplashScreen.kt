package ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SplashScreenWindow(
    onLoadingComplete: () -> Unit
) {
    val state = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        size = DpSize(300.dp, 350.dp)
    )

    Window(
        onCloseRequest = { /* Prevent closing */ },
        state = state,
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        title = "Orbit Loading",
        resizable = false,
        icon = painterResource("logo.jpeg")
    ) {
        val infiniteTransition = rememberInfiniteTransition()

        // Orbital progress (0 to 2*PI)
        val progress by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        // Pulsing center
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        LaunchedEffect(Unit) {
            delay(10000) // 10 seconds for debugging/viewing
            onLoadingComplete()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0D0D12)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                    // Center Planet
                    Image(
                        painter = painterResource("splash_center.png"),
                        contentDescription = "Orbit Center",
                        modifier = Modifier
                            .size(200.dp)
                            .scale(scale)
                    )
                    
                    // Static Rings
                    Image(
                        painter = painterResource("splash_rings_bare.png"),
                        contentDescription = "Orbit Rings",
                        modifier = Modifier.size(200.dp)
                    )

                    // Rotating Balls Container
                    Box(modifier = Modifier.fillMaxSize().rotate(-35f)) {
                        val a = 110.0 // Major radius (width)
                        val b = 50.0 // Minor radius (height)
                        val centerX = 2.2 // Shift ellipse center horizontally
                        val centerY = -2.2 // Shift ellipse center vertically

                        val ballColors = listOf(Color(0xFFE91E63), Color(0xFF2196F3), Color(0xFF00BCD4))

                        // Draw 3 balls spaced evenly around the ellipse
                        for (i in 0 until 3) {
                            val offsetProgress = progress + (i * (2 * Math.PI) / 3.0)
                            val tDeg = Math.toDegrees(offsetProgress % (2 * Math.PI))
                            
                            // 3D Depth Masking Logic:
                            val isVisible = tDeg in 0.0..180.0
                            
                            // Smooth fade in/out to simulate passing behind the edge
                            val alpha by animateFloatAsState(
                                targetValue = if (isVisible) 1f else 0f,
                                animationSpec = tween(150)
                            )

                            val xPos = centerX + a * cos(offsetProgress)
                            val yPos = centerY + b * sin(offsetProgress)

                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .offset(x = xPos.dp, y = yPos.dp)
                                    .size(14.dp)
                                    .alpha(alpha)
                                    .background(ballColors[i], CircleShape)
                                    .border(2.dp, Color(0xFF0D0D12), CircleShape)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(40.dp))

                Text(
                    text = "Orbit",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "STARTING...",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
