package ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun FrameWindowScope.CustomTitleBar(
    state: WindowState,
    onClose: () -> Unit
) {
    WindowDraggableArea {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Color(0xFF0D0D12)), // Dark cosmic background matching the app
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.width(12.dp))
            Image(
                painter = painterResource("logo.jpeg"),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Orbit",
                color = Color(0xFFE2E2E2),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.weight(1f))

            // Minimize button
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(46.dp)
                    .clickable { state.isMinimized = true },
                contentAlignment = Alignment.Center
            ) {
                Text("—", color = Color(0xFFE2E2E2), fontSize = 10.sp)
            }
            // Maximize button
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(46.dp)
                    .clickable {
                        state.placement = if (state.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("□", color = Color(0xFFE2E2E2), fontSize = 14.sp)
            }
            // Close button
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(46.dp)
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = Color(0xFFE2E2E2),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
