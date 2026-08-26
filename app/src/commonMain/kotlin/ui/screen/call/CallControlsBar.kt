package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens
import webrtc.CallManager

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
fun CallControlsBar(
    callManager: CallManager,
    isVideoEnabled: Boolean,
    isMuted: Boolean,
    isDeafened: Boolean,
    isScreenSharing: Boolean,
    layoutMode: CallLayoutMode,
    onToggleDeafen: () -> Unit,
    onLayoutModeToggle: () -> Unit,
    onScreenShareClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Group path: NS/EC/AGC/ducking/virtual-bg live in the P2P engine only — hide the menu there.
    showMoreOptions: Boolean = true,
    showDeafen: Boolean = true,
    showSoundboard: Boolean = false,
    onSoundboardToggle: (() -> Unit)? = null
) {
    var showAudioDeviceMenu by remember { mutableStateOf(false) }
    var moreOptionsExpanded by remember { mutableStateOf(false) }
    val tokens = rememberCosmicTokens()

    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 16.dp,
        color = Color.Transparent,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1A1B1E).copy(alpha = 0.96f),
                            Color(0xFF1E1F22).copy(alpha = 0.96f),
                            Color(0xFF1A1B1E).copy(alpha = 0.96f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(28.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── AUDIO GROUP ──

            // Video toggle
            ControlButtonWithTooltip(
                icon = if (isVideoEnabled) OrbitIcons.Video else OrbitIcons.VideoOff,
                tooltip = if (isVideoEnabled) "Turn Off Camera" else "Turn On Camera",
                isActive = isVideoEnabled,
                activeColor = Color(0xFF313338),
                activeIconColor = Color.White,
                inactiveColor = Color.White.copy(alpha = 0.9f),
                inactiveIconColor = Color(0xFF111214),
                onClick = { callManager.toggleVideo(!isVideoEnabled) }
            )

            // Mute toggle with audio device dropdown arrow
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    ControlButtonWithTooltip(
                        icon = if (!isMuted) OrbitIcons.Mic else OrbitIcons.MicOff,
                        tooltip = if (isMuted) "Unmute" else "Mute",
                        isActive = !isMuted,
                        activeColor = Color(0xFF313338),
                        activeIconColor = Color.White,
                        inactiveColor = Color(0xFFF23F42),
                        inactiveIconColor = Color.White,
                        onClick = { callManager.toggleMute(!isMuted) }
                    )

                    // Small dropdown arrow attached to mic button
                    Box(
                        modifier = Modifier
                            .offset(x = (-6).dp)
                            .size(width = 20.dp, height = 36.dp)
                            .clip(RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp))
                            .background(Color(0xFF2B2D31))
                            .clickable { showAudioDeviceMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                AudioDeviceMenu(
                    callManager = callManager,
                    expanded = showAudioDeviceMenu,
                    onDismiss = { showAudioDeviceMenu = false }
                )
            }

            // Deafen toggle (real: mutes mixed playback, mic stays up — group path)
            if (showDeafen) {
                ControlButtonWithTooltip(
                    icon = if (isDeafened) Icons.Default.HeadsetOff else Icons.Default.Headphones,
                    tooltip = if (isDeafened) "Undeafen" else "Deafen",
                    isActive = !isDeafened,
                    activeColor = Color(0xFF313338),
                    activeIconColor = Color.White,
                    inactiveColor = Color(0xFFF23F42),
                    inactiveIconColor = Color.White,
                    onClick = onToggleDeafen
                )
            }

            // ── SEPARATOR ──
            ControlSeparator()

            // ── SHARE GROUP ──

            // Screen share
            ControlButtonWithTooltip(
                icon = if (isScreenSharing) OrbitIcons.ScreenShareOff else OrbitIcons.ScreenShare,
                tooltip = if (isScreenSharing) "Stop Sharing" else "Share Screen",
                isActive = isScreenSharing,
                activeColor = Color(0xFF23A559),
                activeIconColor = Color.White,
                inactiveColor = Color(0xFF313338),
                inactiveIconColor = Color.White,
                onClick = onScreenShareClick
            )

            // ── SEPARATOR ──
            ControlSeparator()

            // ── UTILITY GROUP ──

            // Raise hand (io.orbit.raise_hand room event; everyone's tiles show the badge)
            val myHandRaised by callManager.isMyHandRaised.collectAsState()
            ControlButtonWithTooltip(
                icon = Icons.Default.PanTool,
                tooltip = if (myHandRaised) "Lower Hand" else "Raise Hand",
                isActive = myHandRaised,
                activeColor = Color(0xFFFAA81A),
                activeIconColor = Color(0xFF111214),
                inactiveColor = Color(0xFF313338),
                inactiveIconColor = Color.White,
                onClick = { callManager.toggleRaiseHand() }
            )

            // Grid/Focus layout toggle
            ControlButtonWithTooltip(
                icon = if (layoutMode == CallLayoutMode.Grid) Icons.Default.GridView else Icons.Default.CropFree,
                tooltip = if (layoutMode == CallLayoutMode.Grid) "Focus Mode" else "Grid Mode",
                isActive = layoutMode == CallLayoutMode.Focus,
                activeColor = Color(0xFF5865F2),
                activeIconColor = Color.White,
                inactiveColor = Color(0xFF313338),
                inactiveIconColor = Color.White,
                onClick = onLayoutModeToggle
            )

            // Soundboard (only where the panel exists — P2P call screen)
            if (onSoundboardToggle != null) {
                ControlButtonWithTooltip(
                    icon = Icons.Default.MusicNote,
                    tooltip = "Soundboard",
                    isActive = showSoundboard,
                    activeColor = Color(0xFF5865F2),
                    activeIconColor = Color.White,
                    inactiveColor = Color(0xFF313338),
                    inactiveIconColor = Color.White,
                    onClick = { onSoundboardToggle() }
                )
            }

            // More Options (P2P-only audio processing toggles)
            if (showMoreOptions) {
                Box {
                    ControlButtonWithTooltip(
                        icon = Icons.Default.MoreHoriz,
                        tooltip = "More Options",
                        isActive = moreOptionsExpanded,
                        activeColor = Color(0xFF5865F2),
                        activeIconColor = Color.White,
                        inactiveColor = Color(0xFF313338),
                        inactiveIconColor = Color.White,
                        onClick = { moreOptionsExpanded = !moreOptionsExpanded }
                    )

                    MoreOptionsMenu(
                        callManager = callManager,
                        expanded = moreOptionsExpanded,
                        onDismiss = { moreOptionsExpanded = false }
                    )
                }
            }

            // ── SEPARATOR ──
            ControlSeparator()

            // ── END CALL ──
            val endHoverInteraction = remember { MutableInteractionSource() }
            val isEndHovered by endHoverInteraction.collectIsHoveredAsState()

            IconButton(
                onClick = { callManager.endCall() },
                modifier = Modifier
                    .size(48.dp)
                    .hoverable(endHoverInteraction)
                    .background(
                        if (isEndHovered) Color(0xFFD83C3E) else Color(0xFFF23F42),
                        CircleShape
                    )
                    .shadow(if (isEndHovered) 8.dp else 4.dp, CircleShape)
            ) {
                Icon(OrbitIcons.CallEnd, contentDescription = "Hang Up", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  MORE OPTIONS DROPDOWN
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MoreOptionsMenu(
    callManager: CallManager,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    val nsEnabled by callManager.noiseSuppression.collectAsState()
    val ecEnabled by callManager.echoCancellation.collectAsState()
    val agcEnabled by callManager.autoGainControl.collectAsState()
    val vbEnabled by callManager.isVirtualBackgroundEnabled.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val duckingStrength by callManager.duckingStrength.collectAsState()

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .background(Color(0xFF1E1F22))
            .width(240.dp)
    ) {
        // ── AUDIO PROCESSING ──
        Text(
            "AUDIO PROCESSING",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )

        MoreOptionsToggleRow("Noise Suppression", Icons.Default.NoiseAware, nsEnabled) {
            callManager.setAudioOptions(it, ecEnabled, agcEnabled)
        }
        MoreOptionsToggleRow("Echo Cancellation", Icons.Default.SurroundSound, ecEnabled) {
            callManager.setAudioOptions(nsEnabled, it, agcEnabled)
        }
        MoreOptionsToggleRow("Auto Gain Control", Icons.Default.VolumeUp, agcEnabled) {
            callManager.setAudioOptions(nsEnabled, ecEnabled, it)
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 2.dp))

        // ── ATTENUATION ──
        Text(
            "ATTENUATION",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${(duckingStrength * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier.width(36.dp)
            )
            Slider(
                value = duckingStrength,
                onValueChange = { callManager.setDuckingStrength(it) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = tokens.Accent,
                    activeTrackColor = tokens.Accent
                )
            )
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.05f), modifier = Modifier.padding(vertical = 2.dp))

        // ── VIDEO ──
        Text(
            "VIDEO",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White.copy(alpha = 0.4f),
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        )

        MoreOptionsToggleRow("Virtual Background", Icons.Default.Wallpaper, vbEnabled) {
            callManager.toggleVirtualBackground()
        }

        // Switch Camera (only when video is on)
        if (isVideoEnabled) {
            DropdownMenuItem(
                text = { Text("Switch Camera", color = Color.White, fontSize = 13.sp) },
                onClick = {
                    callManager.switchCamera()
                    onDismiss()
                },
                leadingIcon = {
                    Icon(Icons.Default.FlipCameraAndroid, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                }
            )
        }
    }
}

@Composable
private fun MoreOptionsToggleRow(
    label: String,
    icon: ImageVector,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = { onToggle(it) },
            modifier = Modifier.scale(0.6f),
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF23A559),
                checkedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF4E5058),
                uncheckedThumbColor = Color.White.copy(alpha = 0.6f)
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  REUSABLE COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun ControlSeparator() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlButtonWithTooltip(
    icon: ImageVector,
    tooltip: String,
    isActive: Boolean,
    activeColor: Color,
    activeIconColor: Color,
    inactiveColor: Color,
    inactiveIconColor: Color,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        if (isActive) activeColor else inactiveColor,
        animationSpec = tween(200),
        label = "bgColor"
    )
    val iconTint by animateColorAsState(
        if (isActive) activeIconColor else inactiveIconColor,
        animationSpec = tween(200),
        label = "iconTint"
    )

    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = Color(0xFF111214),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    tooltip,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        state = rememberTooltipState()
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .hoverable(hoverInteraction)
                .background(
                    if (isHovered) bgColor.copy(alpha = 0.8f) else bgColor,
                    CircleShape
                )
                .then(
                    if (isHovered) Modifier.scale(1.08f) else Modifier
                )
        ) {
            Icon(icon, contentDescription = tooltip, tint = iconTint, modifier = Modifier.size(iconSize))
        }
    }
}

// Keep the old ControlButton for backward compat if needed elsewhere
@Composable
fun ControlButton(
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color,
    activeIconColor: Color,
    inactiveColor: Color,
    inactiveIconColor: Color,
    size: Dp = 56.dp,
    iconSize: Dp = 24.dp,
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
