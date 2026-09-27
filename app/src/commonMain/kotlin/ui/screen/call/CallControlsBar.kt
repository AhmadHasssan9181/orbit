package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NoiseAware
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import ui.components.OrbitMenu
import ui.components.OrbitMenuDivider
import ui.components.OrbitMenuHeader
import ui.components.OrbitMenuItem
import ui.components.OrbitMenuSwitch
import ui.components.OrbitSwitch
import ui.components.OrbitTooltip
import ui.components.onColorFor
import ui.icons.OrbitIcons
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import webrtc.CallManager

/**
 * In-call controls.
 *
 * This bar was the loudest object in the app: a 28dp pill built from a
 * three-stop near-black gradient, containing Discord's exact greys (`#313338`,
 * `#2B2D31`, `#111214`), a hard `#F23F42` hang-up, a neon `#5865F2` layout
 * toggle and a `#FAA81A` raise-hand — none of which changed with the theme, so
 * the call screen stayed a screenshot of another product inside Orbit.
 *
 * Now: one elevation surface from the token set, one shape language, and a
 * strict rule that colour carries *state* only — accent for engaged, danger for
 * destructive, everything else neutral.
 */
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
    // Group path: NS/EC/AGC/ducking/virtual-bg live in the P2P engine only.
    showMoreOptions: Boolean = true,
    showDeafen: Boolean = true,
    showSoundboard: Boolean = false,
    onSoundboardToggle: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    var showAudioDeviceMenu by remember { mutableStateOf(false) }
    var moreOptionsExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(26.dp),
        shadowElevation = 18.dp,
        color = Color.Transparent,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(t.Elevated)
                .border(1.dp, t.SeparatorStrong, RoundedCornerShape(26.dp))
                .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Media ──────────────────────────────────────────────
            CallControlButton(
                icon = if (isVideoEnabled) OrbitIcons.Video else OrbitIcons.VideoOff,
                tooltip = if (isVideoEnabled) "Turn off camera" else "Turn on camera",
                onClick = { callManager.toggleVideo(!isVideoEnabled) }
            )

            // ── Mic, with its own device picker ────────────────────
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CallControlButton(
                        icon = if (!isMuted) OrbitIcons.Mic else OrbitIcons.MicOff,
                        tooltip = if (isMuted) "Unmute" else "Mute",
                        active = !isMuted,
                        onClick = { callManager.toggleMute(!isMuted) }
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 18.dp, height = 34.dp)
                            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
                            .background(t.Sunken)
                            .hoverable(remember { MutableInteractionSource() })
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showAudioDeviceMenu = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Choose audio input",
                            tint = t.Text_Subtle,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
                AudioDeviceMenu(
                    callManager = callManager,
                    expanded = showAudioDeviceMenu,
                    onDismiss = { showAudioDeviceMenu = false }
                )
            }

            if (showDeafen) {
                CallControlButton(
                    icon = if (isDeafened) Icons.Default.HeadsetOff else Icons.Default.Headphones,
                    tooltip = if (isDeafened) "Undeafen" else "Deafen",
                    active = !isDeafened,
                    onClick = onToggleDeafen
                )
            }

            ControlSeparator()

            // ── Share ──────────────────────────────────────────────
            CallControlButton(
                icon = if (isScreenSharing) OrbitIcons.ScreenShareOff else OrbitIcons.ScreenShare,
                tooltip = if (isScreenSharing) "Stop sharing" else "Share screen",
                active = isScreenSharing,
                activeTint = t.Success,
                onClick = onScreenShareClick
            )

            ControlSeparator()

            // ── Utility ────────────────────────────────────────────
            val myHandRaised by callManager.isMyHandRaised.collectAsState()
            CallControlButton(
                icon = Icons.Default.PanTool,
                tooltip = if (myHandRaised) "Lower hand" else "Raise hand",
                active = myHandRaised,
                activeTint = t.Warning,
                onClick = { callManager.toggleRaiseHand() }
            )

            CallControlButton(
                icon = if (layoutMode == CallLayoutMode.Grid) Icons.Default.GridView else Icons.Default.CropFree,
                tooltip = if (layoutMode == CallLayoutMode.Grid) "Focus mode" else "Grid mode",
                active = layoutMode == CallLayoutMode.Focus,
                onClick = onLayoutModeToggle
            )

            if (onSoundboardToggle != null) {
                CallControlButton(
                    icon = Icons.Default.MusicNote,
                    tooltip = "Soundboard",
                    active = showSoundboard,
                    onClick = { onSoundboardToggle() }
                )
            }

            if (showMoreOptions) {
                Box {
                    CallControlButton(
                        icon = Icons.Default.MoreHoriz,
                        tooltip = "Audio and video options",
                        active = moreOptionsExpanded,
                        onClick = { moreOptionsExpanded = !moreOptionsExpanded }
                    )
                    MoreOptionsMenu(
                        callManager = callManager,
                        expanded = moreOptionsExpanded,
                        onDismiss = { moreOptionsExpanded = false }
                    )
                }
            }

            ControlSeparator()

            // ── Hang up ────────────────────────────────────────────
            val endInteraction = remember { MutableInteractionSource() }
            val endHovered by endInteraction.collectIsHoveredAsState()
            val endScale by animateFloatAsState(
                targetValue = if (endHovered) 1.08f else 1f,
                animationSpec = OrbitMotion.Snappy,
                label = "hangupScale"
            )
            val endBg by animateColorAsState(
                targetValue = if (endHovered) t.Danger.copy(alpha = 0.88f) else t.Danger,
                animationSpec = OrbitMotion.SnappyColor,
                label = "hangupBg"
            )
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .scale(endScale)
                    .shadow(8.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(endBg)
                    .hoverable(endInteraction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { callManager.endCall() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    OrbitIcons.CallEnd,
                    contentDescription = "Leave the call",
                    tint = onColorFor(endBg),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun MoreOptionsMenu(
    callManager: CallManager,
    expanded: Boolean,
    onDismiss: () -> Unit
) {
    val t = rememberCosmicTokens()
    val nsEnabled by callManager.noiseSuppression.collectAsState()
    val ecEnabled by callManager.echoCancellation.collectAsState()
    val agcEnabled by callManager.autoGainControl.collectAsState()
    val vbEnabled by callManager.isVirtualBackgroundEnabled.collectAsState()
    val isVideoEnabled by callManager.isLocalVideoEnabled.collectAsState()
    val duckingStrength by callManager.duckingStrength.collectAsState()

    OrbitMenu(expanded = expanded, onDismissRequest = onDismiss, width = 252.dp) {
        OrbitMenuHeader("Audio processing")
        OrbitMenuSwitch(
            label = "Noise suppression",
            icon = Icons.Default.NoiseAware,
            checked = nsEnabled,
            onCheckedChange = { callManager.setAudioOptions(it, ecEnabled, agcEnabled) }
        )
        OrbitMenuSwitch(
            label = "Echo cancellation",
            icon = Icons.Default.SurroundSound,
            checked = ecEnabled,
            onCheckedChange = { callManager.setAudioOptions(nsEnabled, it, agcEnabled) }
        )
        OrbitMenuSwitch(
            label = "Auto gain control",
            icon = Icons.Default.VolumeUp,
            checked = agcEnabled,
            onCheckedChange = { callManager.setAudioOptions(nsEnabled, ecEnabled, it) }
        )

        OrbitMenuDivider()
        OrbitMenuHeader("Attenuation")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${(duckingStrength * 100).toInt()}%",
                color = t.Text_Secondary,
                fontSize = 12.sp,
                modifier = Modifier.width(42.dp)
            )
            Slider(
                value = duckingStrength,
                onValueChange = { callManager.setDuckingStrength(it) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(
                    thumbColor = t.Accent,
                    activeTrackColor = t.Accent,
                    inactiveTrackColor = t.Sunken
                )
            )
        }

        OrbitMenuDivider()
        OrbitMenuHeader("Video")
        OrbitMenuSwitch(
            label = "Virtual background",
            icon = Icons.Default.Wallpaper,
            checked = vbEnabled,
            onCheckedChange = { callManager.toggleVirtualBackground() }
        )
        if (isVideoEnabled) {
            OrbitMenuItem(
                label = "Switch camera",
                icon = Icons.Default.FlipCameraAndroid,
                onClick = {
                    callManager.switchCamera()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
fun ControlSeparator() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(24.dp)
            .background(rememberCosmicTokens().Separator)
    )
}

/**
 * One call control. Neutral at rest, accent-tinted when engaged, and it grows
 * a little on hover — the entire vocabulary of the call bar in one composable.
 */
@Composable
fun CallControlButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    activeTint: Color? = null,
    size: Dp = 42.dp,
    iconSize: Dp = 20.dp
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current
    val accent = activeTint ?: t.Accent

    val bg by animateColorAsState(
        targetValue = when {
            active -> accent.copy(alpha = 0.18f)
            hovered -> t.CardBgHover
            else -> t.Sunken
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "callBtnBg"
    )
    val tint by animateColorAsState(
        targetValue = when {
            active -> accent
            hovered -> t.Text_White
            else -> t.Text_Secondary
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "callBtnTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (hovered && !reduce) 1.06f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "callBtnScale"
    )

    OrbitTooltip(tooltip) {
        Box(
            modifier = modifier
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .background(bg)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}
