package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitMenu
import ui.components.OrbitMenuDivider
import ui.components.OrbitMenuHeader
import ui.components.OrbitMenuItem
import ui.components.OrbitMenuSwitch
import ui.components.OrbitTooltip
import ui.components.onColorFor
import ui.icons.OrbitIcons
import ui.theme.LocalReduceMotion
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitMotion
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import webrtc.CallManager

/**
 * In-call controls.
 *
 * This bar used to be the loudest object in the app: a 28dp pill built from a
 * three-stop near-black gradient, containing Discord's exact greys (`#313338`,
 * `#2B2D31`, `#111214`), a hard `#F23F42` hang-up, a neon `#5865F2` layout
 * toggle and a `#FAA81A` raise-hand — none of which changed with the theme, so
 * the call screen stayed a screenshot of another product inside Orbit.
 *
 * It is now `.call-dock-pill-bar` from the concept, measured off that
 * stylesheet: a 30px-radius pill on `--bg-call-card` at 92%, ringed with a
 * `--border-subtle` hairline, 6px/14px of padding and a 12px gap, holding 44px
 * circular buttons. Its states are the four the CSS defines and no others —
 * resting, hover, `.active` (blurple fill), `.active-green` (a share that is
 * running) and `.danger` (mute, camera-off, hang-up) — so colour still carries
 * *state* only, which is the one rule worth keeping from the previous version.
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
        shape = CallDockShape,
        // The concept's dock carries a `0 12px 32px rgba(0,0,0,0.6)` drop. CSS
        // shadows are not expressible in Compose, but this bar is the one control
        // surface that floats over video, and the drop is most of what separates
        // "a bar" from "a bar in front of a call".
        shadowElevation = 12.dp,
        color = Color.Transparent,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .background(callDockSurface(t))
                .border(1.dp, OrbitConceptBorder.subtle(), CallDockShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Microphone + Audio device picker
            Row(verticalAlignment = Alignment.CenterVertically) {
                CallControlButton(
                    icon = if (!isMuted) OrbitIcons.Mic else OrbitIcons.MicOff,
                    tooltip = if (isMuted) "Unmute" else "Mute",
                    state = if (isMuted) CallControlState.Danger else CallControlState.Off,
                    onClick = { callManager.toggleMute(!isMuted) }
                )
                Box {
                    CallControlButton(
                        icon = Icons.Default.KeyboardArrowDown,
                        tooltip = "Choose audio input",
                        size = 28.dp,
                        iconSize = 14.dp,
                        onClick = { showAudioDeviceMenu = true },
                        modifier = Modifier.padding(start = 2.dp)
                    )
                    AudioDeviceMenu(
                        callManager = callManager,
                        expanded = showAudioDeviceMenu,
                        onDismiss = { showAudioDeviceMenu = false }
                    )
                }
            }

            // 2. Deafen / Headphones
            if (showDeafen) {
                CallControlButton(
                    icon = if (isDeafened) Icons.Default.HeadsetOff else Icons.Default.Headphones,
                    tooltip = if (isDeafened) "Undeafen" else "Deafen",
                    state = if (isDeafened) CallControlState.Danger else CallControlState.Off,
                    onClick = onToggleDeafen
                )
            }

            // 3. Screen Share
            CallControlButton(
                icon = if (isScreenSharing) OrbitIcons.ScreenShareOff else OrbitIcons.ScreenShare,
                tooltip = if (isScreenSharing) "Stop sharing" else "Share screen",
                state = if (isScreenSharing) CallControlState.ActiveGreen else CallControlState.Off,
                onClick = onScreenShareClick
            )

            // 4. Camera
            CallControlButton(
                icon = if (isVideoEnabled) OrbitIcons.Video else OrbitIcons.VideoOff,
                tooltip = if (isVideoEnabled) "Turn off camera" else "Turn on camera",
                state = if (isVideoEnabled) CallControlState.Active else CallControlState.Off,
                onClick = { callManager.toggleVideo(!isVideoEnabled) }
            )

            // 5. Raise Hand
            val myHandRaised by callManager.isMyHandRaised.collectAsState()
            CallControlButton(
                icon = Icons.Default.PanTool,
                tooltip = if (myHandRaised) "Lower hand" else "Raise hand",
                state = if (myHandRaised) CallControlState.Active else CallControlState.Off,
                onClick = { callManager.toggleRaiseHand() }
            )

            // 6. Layout Mode Toggle
            CallControlButton(
                icon = if (layoutMode == CallLayoutMode.Grid) Icons.Default.GridView else Icons.Default.CropFree,
                tooltip = if (layoutMode == CallLayoutMode.Grid) "Focus mode" else "Grid mode",
                state = if (layoutMode == CallLayoutMode.Focus) CallControlState.Active else CallControlState.Off,
                onClick = onLayoutModeToggle
            )

            // 7. Soundboard
            if (onSoundboardToggle != null) {
                CallControlButton(
                    icon = Icons.Default.MusicNote,
                    tooltip = "Soundboard",
                    state = if (showSoundboard) CallControlState.Active else CallControlState.Off,
                    onClick = { onSoundboardToggle() }
                )
            }

            // 8. More options
            if (showMoreOptions) {
                Box {
                    CallControlButton(
                        icon = Icons.Default.MoreHoriz,
                        tooltip = "Audio and video options",
                        state = if (moreOptionsExpanded) CallControlState.Active else CallControlState.Off,
                        onClick = { moreOptionsExpanded = !moreOptionsExpanded }
                    )
                    MoreOptionsMenu(
                        callManager = callManager,
                        expanded = moreOptionsExpanded,
                        onDismiss = { moreOptionsExpanded = false }
                    )
                }
            }

            // 9. Hang up (Discord Red)
            val reduce = LocalReduceMotion.current
            val endInteraction = remember { MutableInteractionSource() }
            val endHovered by endInteraction.collectIsHoveredAsState()
            val endLift by animateDpAsState(
                targetValue = if (endHovered && !reduce) -CallPillHoverLift else 0.dp,
                animationSpec = OrbitMotion.SnappyDp,
                label = "hangupLift"
            )
            val endBg by animateColorAsState(
                targetValue = if (endHovered) CallPillDangerHover else CallPillDanger,
                animationSpec = OrbitMotion.SnappyColor,
                label = "hangupBg"
            )
            Box(
                modifier = Modifier
                    .size(CallPillSize)
                    .offset(y = endLift)
                    .clip(CircleShape)
                    .background(endBg)
                    .hoverable(endInteraction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Leave the call",
                        onClick = { callManager.endCall() }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    OrbitIcons.CallEnd,
                    contentDescription = "Leave the call",
                    tint = onColorFor(endBg),
                    modifier = Modifier.size(20.dp)
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
 * Hairline between the halves of a JOINED control pair (mic, then its chevron).
 *
 * Not the same job as [ControlSeparator], which spaces whole groups of controls.
 * The distinction is load-bearing: a rule INSIDE a pill splits one control into
 * two, which is exactly Discord's affordance for "the main half does one thing,
 * the chevron opens a menu". A rule BETWEEN groups claims the two groups belong
 * together. Using the wrong one of the two is how a control bar ends up looking
 * like a spreadsheet.
 */
@Composable
fun ControlDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(22.dp)
            .background(rememberCosmicTokens().Separator)
    )
}

/**
 * The five states a `.call-pill-btn` can be in.
 *
 * The concept writes these as CSS classes — `active`, `active-green`,
 * `danger` — and each one is a *filled* pill, not a wash. That is the whole
 * reason they are an enum here: a `Boolean active` plus a separate
 * `Boolean danger` is how this control came to be unable to say "a share is
 * running" and "the mic is off" at the same time, which are the two states
 * that matter most on a call.
 */
enum class CallControlState {
    /** At rest. */
    Off,

    /**
     * `.call-pill-btn.active` — `--brand-blurple` fill, white glyph. Camera on,
     * focus layout, soundboard open, hand raised.
     */
    Active,

    /**
     * `.call-pill-btn.active-green` — `#23a55a` fill, white glyph, hover
     * `#1f9450`. Only ever a screen share that is actually running.
     */
    ActiveGreen,

    /**
     * `.call-pill-btn.danger` — `#f23f43` fill, white glyph, hover `#d83a3e`.
     *
     * Discord colours a muted mic RED rather than dimming the glyph, because
     * dimming is how a control that is merely idle looks — and muted-vs-idle is
     * exactly the distinction someone on a call most needs to make at a glance.
     * This was previously carried as `active = !isMuted`, which inverted the
     * meaning: unmuted rendered as the "highlighted" state and muted as idle.
     */
    Danger
}

/**
 * One call control: a 44px circle that is neutral at rest, filled when it is
 * carrying state, and lifts 2px on hover. The entire vocabulary of the call bar
 * in one composable.
 */
@Composable
fun CallControlButton(
    icon: ImageVector,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: CallControlState = CallControlState.Off,
    size: Dp = CallPillSize,
    iconSize: Dp = 20.dp
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current

    val bg by animateColorAsState(
        targetValue = when (state) {
            // The concept has a hover step for every filled state it defines
            // except `.active`; blurple's own `--brand-blurple-hover` fills
            // that gap rather than leaving the one filled control inert.
            CallControlState.Active -> if (hovered) OrbitConceptDark.brandHover else t.Accent
            CallControlState.ActiveGreen ->
                if (hovered) CallPillActiveGreenHover else CallPillActiveGreen
            CallControlState.Danger -> if (hovered) CallPillDangerHover else CallPillDanger
            // `.call-pill-btn:hover { background-color: #383a40; color: #fff }`.
            // Discord's resting button is a mid grey, not the sunken well colour:
            // on a dark stage a well-coloured button is invisible, which is why the
            // bar used to read as a row of floating icons.
            CallControlState.Off -> if (hovered) callPillHover(t) else callPillRest(t)
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "callBtnBg"
    )
    val tint by animateColorAsState(
        targetValue = when (state) {
            // White on a fill: a red glyph on a red fill disappears.
            CallControlState.Active,
            CallControlState.ActiveGreen,
            CallControlState.Danger -> Color.White
            CallControlState.Off -> if (hovered) t.Text_White else t.Text_Secondary
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "callBtnTint"
    )
    // `.call-pill-btn:hover { transform: translateY(-2px) }`.
    val lift by animateDpAsState(
        targetValue = if (hovered && !reduce) -CallPillHoverLift else 0.dp,
        animationSpec = OrbitMotion.SnappyDp,
        label = "callBtnLift"
    )

    OrbitTooltip(tooltip) {
        Box(
            modifier = modifier
                .size(size)
                .offset(y = lift)
                .clip(CircleShape)
                .background(bg)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = tooltip,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}
