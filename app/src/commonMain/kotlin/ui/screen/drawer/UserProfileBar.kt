package ui.screen.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitIconButton
import ui.components.OrbitMenu
import ui.components.OrbitMenuDivider
import ui.components.OrbitMenuItem
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Drawer footer: who you are, and your audio state.
 *
 * The old bar put a mic button, a 16dp chevron badge *on top of* the mic
 * button, a deafen button and a settings button in 248dp — and the chevron's
 * hit box overlapped the mic's. Voice controls now live in one popover behind
 * a single device button, the two always-available actions mute and deafen keep
 * their places, and the presence dot is drawn from the token set instead of
 * four hard-coded greens.
 */
@Composable
internal fun UserProfileBar(
    user: OrbitUser,
    onMicToggle: () -> Unit,
    onSettingsClick: () -> Unit,
    onStatusSelected: ((UserStatus) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    var showProfilePopover by remember { mutableStateOf(false) }
    var showVoicePopover by remember { mutableStateOf(false) }

    val callManager = webrtc.LocalCallManager.current
    val isAudioEnabled by (callManager?.isLocalAudioEnabled?.collectAsState(initial = true)
        ?: remember { mutableStateOf(true) })
    val isDeafened by (callManager?.isDeafened?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })

    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val profileBg by animateColorAsState(
        targetValue = if (showProfilePopover) t.AccentGhost else if (hovered) t.Hover else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "profileBg"
    )
    val profileBorder by animateColorAsState(
        targetValue = if (showProfilePopover) t.AccentBorder else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "profileBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(t.UserBar_Bg)
            .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.sm)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Identity + presence picker ───────────────────────────
        Row(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(OrbitRadius.smShape)
                .background(profileBg)
                .border(1.dp, profileBorder, OrbitRadius.smShape)
                .hoverable(interaction.hoverSource)
                .orbitClickable(interaction, onClick = { showProfilePopover = true })
                .padding(horizontal = OrbitSpace.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl, size = 26.dp)
            Spacer(Modifier.width(OrbitSpace.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = t.Text_White,
                            fontSize = 12.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOrbitVerifiedUser(user.username, user.displayName)) {
                        Spacer(Modifier.width(3.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(11.dp))
                    }
                }
                Text(
                    text = statusLabel(user.status),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = presenceColor(t, user.status),
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    ),
                    maxLines = 1
                )
            }
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = t.Text_Subtle,
                modifier = Modifier.size(14.dp)
            )
        }

        // ── Audio state ─────────────────────────────────────────
        OrbitIconButton(
            icon = Icons.Default.SettingsInputComponent,
            contentDescription = "Audio devices",
            onClick = { showVoicePopover = !showVoicePopover },
            size = 30.dp,
            iconSize = 15.dp,
            shape = CircleShape,
            active = showVoicePopover
        )
        OrbitIconButton(
            icon = if (isAudioEnabled) OrbitIcons.Mic else OrbitIcons.MicOff,
            contentDescription = if (isAudioEnabled) "Mute microphone" else "Unmute microphone",
            onClick = {
                callManager?.toggleMute(isAudioEnabled)
                onMicToggle()
            },
            size = 30.dp,
            iconSize = 16.dp,
            shape = CircleShape,
            active = !isAudioEnabled,
            danger = !isAudioEnabled
        )
        OrbitIconButton(
            icon = if (isDeafened) Icons.Default.HeadsetOff else Icons.Default.Headset,
            contentDescription = if (isDeafened) "Undeafen" else "Deafen",
            onClick = { callManager?.toggleDeafen() },
            size = 30.dp,
            iconSize = 16.dp,
            shape = CircleShape,
            active = isDeafened,
            danger = isDeafened
        )

        // ── Voice devices ───────────────────────────────────────
        OrbitMenu(
            expanded = showVoicePopover,
            onDismissRequest = { showVoicePopover = false },
            width = 268.dp,
            modifier = Modifier
        ) {
            VoiceControlPopover(onSettingsClick = {
                showVoicePopover = false
                onSettingsClick()
            })
        }
    }

    // Presence popover is anchored by the identity row above.
    OrbitMenu(
        expanded = showProfilePopover,
        onDismissRequest = { showProfilePopover = false },
        width = 240.dp
    ) {
        ProfilePopoverContent(
            user = user,
            onSettingsClick = {
                showProfilePopover = false
                onSettingsClick()
            },
            onStatusSelected = onStatusSelected
        )
    }
}

/**
 * The voice popover opens from the identity row's chevron, so the trigger is
 * the row itself rather than a floating badge that overlapped the mic button.
 */
@Composable
internal fun VoiceControlPopover(onSettingsClick: () -> Unit) {
    val t = rememberCosmicTokens()
    val callManager = webrtc.LocalCallManager.current
    var inputVolume by remember { mutableStateOf(1f) }
    var showInputDevices by remember { mutableStateOf(false) }
    var inputDevices by remember(callManager) { mutableStateOf(emptyList<String>()) }
    var inputDeviceName by remember(callManager) { mutableStateOf("System default") }

    fun refreshInputDevices() {
        inputDevices = runCatching { callManager?.webRtcClient?.getAudioInputDevices().orEmpty() }
            .getOrDefault(emptyList())
        if (inputDeviceName == "System default" || inputDeviceName !in inputDevices) {
            inputDeviceName = inputDevices.firstOrNull() ?: "System default"
        }
    }

    remember(callManager) { refreshInputDevices() }

    Column(modifier = Modifier.padding(OrbitSpace.sm)) {
        OrbitMenuItem(
            label = inputDeviceName,
            icon = Icons.Default.Mic,
            onClick = { showInputDevices = !showInputDevices }
        )
        if (showInputDevices) {
            if (inputDevices.isEmpty()) {
                Text(
                    text = "No input devices found",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.Text_Subtle,
                    modifier = Modifier.padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.xs)
                )
            } else {
                inputDevices.forEach { device ->
                    OrbitMenuItem(
                        label = device,
                        icon = Icons.Default.Mic,
                        selected = device == inputDeviceName,
                        onClick = {
                            inputDeviceName = device
                            callManager?.setAudioInputDevice(device)
                            showInputDevices = false
                        }
                    )
                }
            }
        }

        OrbitMenuDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            Text(
                text = "Input volume",
                style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Secondary, fontSize = 12.5.sp),
                modifier = Modifier.weight(1f)
            )
            Slider(
                value = inputVolume,
                onValueChange = { inputVolume = it },
                modifier = Modifier.width(110.dp),
                colors = SliderDefaults.colors(
                    thumbColor = t.Accent,
                    activeTrackColor = t.Accent,
                    inactiveTrackColor = t.Sunken
                )
            )
        }

        OrbitMenuDivider()

        OrbitMenuItem(
            label = "Voice & video settings",
            icon = Icons.Default.Settings,
            onClick = onSettingsClick
        )
    }
}

@Composable
internal fun ProfilePopoverContent(
    user: OrbitUser,
    onSettingsClick: () -> Unit,
    onStatusSelected: ((UserStatus) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    var showStatusMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(OrbitSpace.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl, size = 36.dp)
            Spacer(Modifier.width(OrbitSpace.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = t.Text_White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOrbitVerifiedUser(user.username, user.displayName)) {
                        Spacer(Modifier.width(4.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        OrbitMenuDivider()

        if (onStatusSelected != null) {
            OrbitMenuItem(
                label = statusLabel(user.status),
                icon = null,
                leading = {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(presenceColor(t, user.status))
                    )
                },
                onClick = { showStatusMenu = !showStatusMenu }
            )
            if (showStatusMenu) {
                listOf(
                    UserStatus.ONLINE,
                    UserStatus.IDLE,
                    UserStatus.DO_NOT_DISTURB,
                    UserStatus.OFFLINE
                ).forEach { status ->
                    OrbitMenuItem(
                        label = statusLabel(status),
                        selected = status == user.status,
                        leading = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(presenceColor(t, status))
                            )
                        },
                        onClick = {
                            showStatusMenu = false
                            onStatusSelected(status)
                        }
                    )
                }
            }
        }

        OrbitMenuItem(
            label = "Profile settings",
            icon = Icons.Default.Settings,
            onClick = onSettingsClick
        )
    }
}

@Composable
internal fun UserAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    avatarUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 32.dp
) {
    val t = rememberCosmicTokens()
    Box(modifier = Modifier.size(size)) {
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = displayName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            OrbitDefaultAvatar(
                label = displayName,
                seed = displayName,
                color = Color(avatarColor),
                modifier = Modifier.size(size)
            )
        }
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 12.dp else 9.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = 1.dp)
                .border(2.dp, t.UserBar_Bg, CircleShape)
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(presenceColor(t, status))
        )
    }
}


/** Presence colour, sourced from the token set so themes stay coherent. */
@Composable
fun presenceColor(t: ui.theme.CosmicTokens, status: UserStatus): Color = when (status) {
    UserStatus.ONLINE -> t.PresenceOnline
    UserStatus.IDLE -> t.PresenceIdle
    UserStatus.DO_NOT_DISTURB -> t.PresenceDnd
    UserStatus.OFFLINE -> t.PresenceOffline
}

internal fun statusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> ui.theme.PresenceOnline
    UserStatus.IDLE -> ui.theme.PresenceIdle
    UserStatus.DO_NOT_DISTURB -> ui.theme.PresenceDnd
    UserStatus.OFFLINE -> ui.theme.PresenceOffline
}

internal fun statusLabel(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}
