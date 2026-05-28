package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ui.icons.OrbitIcons
import webrtc.CallManager

@Composable
fun CallControlsBar(
    callManager: CallManager,
    isVideoEnabled: Boolean,
    isMuted: Boolean,
    isScreenSharing: Boolean,
    onScreenShareClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAudioDeviceMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .background(Color(0xFF1E1F22).copy(alpha = 0.95f), CircleShape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(
            icon = if (isVideoEnabled) OrbitIcons.Video else OrbitIcons.VideoOff,
            isActive = isVideoEnabled,
            activeColor = Color(0xFF313338),
            activeIconColor = Color.White,
            inactiveColor = Color.White,
            inactiveIconColor = Color(0xFF111214),
            onClick = { callManager.toggleVideo(!isVideoEnabled) }
        )

        Box {
            ControlButton(
                icon = if (!isMuted) OrbitIcons.Mic else OrbitIcons.MicOff,
                isActive = !isMuted,
                activeColor = Color(0xFF313338),
                activeIconColor = Color.White,
                inactiveColor = Color.White,
                inactiveIconColor = Color(0xFF111214),
                onClick = { callManager.toggleMute(!isMuted) }
            )

            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(width = 24.dp, height = 48.dp)
                    .background(Color(0xFF2B2D31), RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp))
                    .clickable { showAudioDeviceMenu = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, tint = Color.White.copy(alpha=0.7f), modifier = Modifier.size(16.dp))
            }

            ui.screen.AudioDeviceMenu(
                callManager = callManager,
                expanded = showAudioDeviceMenu,
                onDismiss = { showAudioDeviceMenu = false }
            )
        }

        ControlButton(
            icon = OrbitIcons.ScreenShare,
            isActive = isScreenSharing,
            activeColor = Color(0xFF23A559),
            activeIconColor = Color.White,
            inactiveColor = Color(0xFF313338),
            inactiveIconColor = Color.White,
            onClick = onScreenShareClick
        )

        ControlButton(
            icon = Icons.Default.GridView,
            isActive = false,
            activeColor = Color.White,
            activeIconColor = Color(0xFF111214),
            inactiveColor = Color(0xFF313338),
            inactiveIconColor = Color.White,
            onClick = { /* Grid Layout toggle */ }
        )

        ControlButton(
            icon = Icons.Default.MusicNote,
            isActive = false,
            activeColor = Color.White,
            activeIconColor = Color(0xFF111214),
            inactiveColor = Color(0xFF313338),
            inactiveIconColor = Color.White,
            onClick = { /* Soundboard */ }
        )

        ControlButton(
            icon = Icons.Default.MoreHoriz,
            isActive = false,
            activeColor = Color.White,
            activeIconColor = Color(0xFF111214),
            inactiveColor = Color(0xFF313338),
            inactiveIconColor = Color.White,
            onClick = { /* More Options */ }
        )

        IconButton(
            onClick = { callManager.endCall() },
            modifier = Modifier.size(56.dp).background(Color(0xFFF23F42), CircleShape)
        ) {
            Icon(OrbitIcons.CallEnd, contentDescription = "Hang Up", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    activeColor: Color,
    activeIconColor: Color,
    inactiveColor: Color,
    inactiveIconColor: Color,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(if (isActive) activeColor else inactiveColor, label = "bgColor")
    val iconTint by animateColorAsState(if (isActive) activeIconColor else inactiveIconColor, label = "iconTint")

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .background(bgColor, CircleShape)
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}
