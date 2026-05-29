package ui.screen.drawer

import androidx.compose.animation.core.animateDpAsState

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import ui.model.UserStatus

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun UserProfileBar(
    user: OrbitUser,
    onMicToggle: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var showProfilePopover by remember { mutableStateOf(false) }
    var showVoicePopover by remember { mutableStateOf(false) }
    val callManager = webrtc.LocalCallManager.current
    val isAudioEnabledState = callManager?.isLocalAudioEnabled?.collectAsState(initial = true)
        ?: remember { mutableStateOf(true) }
    val isAudioEnabled by isAudioEnabledState
    val profileInteractionSource = remember { MutableInteractionSource() }
    val isProfileHovered by profileInteractionSource.collectIsHoveredAsState()
    val profileShape = RoundedCornerShape(10.dp)
    val profileBg by animateColorAsState(
        targetValue = if (showProfilePopover || isProfileHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileBg"
    )
    val profileBorder by animateColorAsState(
        targetValue = if (showProfilePopover) Accent.copy(alpha = 0.38f) else Text_White.copy(alpha = if (isProfileHovered) 0.08f else 0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UserBar_Bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(profileShape)
                    .background(profileBg)
                    .border(1.dp, profileBorder, profileShape)
                    .hoverable(profileInteractionSource)
                    .clickable(
                        interactionSource = profileInteractionSource,
                        indication = null,
                        onClick = { showProfilePopover = true }
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl)
                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Text_White
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
                        text = statusLabel(user.status),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = statusColor(user.status),
                            fontSize = 10.sp
                        )
                    )
                }
            }

            DropdownMenu(
                expanded = showProfilePopover,
                onDismissRequest = { showProfilePopover = false },
                modifier = Modifier
                    .width(252.dp)
                    .background(UserBar_Bg)
            ) {
                ProfilePopoverContent(
                    user = user,
                    onSettingsClick = {
                        showProfilePopover = false
                        onSettingsClick()
                    }
                )
            }
        }

        Box {
            BarIconBtn(
                icon = if (isAudioEnabled) OrbitIcons.Mic else OrbitIcons.MicOff,
                desc = if (isAudioEnabled) "Mute" else "Unmute",
                activeTint = if (isAudioEnabled) null else Color(0xFFF23F42),
                onClick = {
                    callManager?.toggleMute(isAudioEnabled)
                    onMicToggle()
                }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(UserBar_Bg)
                    .border(1.dp, Separator.copy(alpha = 0.55f), CircleShape)
                    .clickable { showVoicePopover = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Voice controls", tint = Text_Muted, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(
                expanded = showVoicePopover,
                onDismissRequest = { showVoicePopover = false },
                modifier = Modifier
                    .width(278.dp)
                    .background(UserBar_Bg)
            ) {
                VoiceControlPopover(onSettingsClick = {
                    showVoicePopover = false
                    onSettingsClick()
                })
            }
        }
        BarIconBtn(icon = Icons.Default.Headset, desc = "Deafen", onClick = {})
        BarIconBtn(icon = Icons.Default.Settings, desc = "Settings", onClick = onSettingsClick)
    }
}

@Composable
internal fun VoiceControlPopover(onSettingsClick: () -> Unit) {
    val callManager = webrtc.LocalCallManager.current
    var inputVolume by remember { mutableStateOf(1f) }
    var showInputDevices by remember { mutableStateOf(false) }
    var inputDevices by remember(callManager) { mutableStateOf(emptyList<String>()) }
    var inputDeviceName by remember(callManager) { mutableStateOf("Windows Default (Internal Microphone)") }

    fun refreshInputDevices() {
        inputDevices =
            runCatching { callManager?.webRtcClient?.getAudioInputDevices().orEmpty() }.getOrDefault(emptyList())
        inputDeviceName = inputDevices.firstOrNull() ?: "Windows Default (Internal Microphone)"
    }

    LaunchedEffect(callManager) {
        refreshInputDevices()
    }

    LaunchedEffect(showInputDevices) {
        if (showInputDevices) refreshInputDevices()
    }

    Column(
        modifier = Modifier
            .background(UserBar_Bg)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box {
            VoiceSettingRow(
                title = "Input Device",
                value = inputDeviceName,
                onClick = { showInputDevices = true }
            )
            DropdownMenu(
                expanded = showInputDevices,
                onDismissRequest = { showInputDevices = false },
                modifier = Modifier
                    .width(250.dp)
                    .background(UserBar_Bg)
            ) {
                if (inputDevices.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No input devices found", color = Text_Muted, fontSize = 13.sp) },
                        onClick = { showInputDevices = false }
                    )
                } else {
                    inputDevices.forEach { device ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = device,
                                    color = Text_White,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                inputDeviceName = device
                                callManager?.setAudioInputDevice(device)
                                showInputDevices = false
                            },
                            trailingIcon = {
                                if (device == inputDeviceName) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
        VoiceSettingRow(
            title = "Input Profile",
            value = "Custom",
            onClick = {}
        )

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Text(
            "Input Volume",
            style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        )
        Slider(
            value = inputVolume,
            onValueChange = { inputVolume = it },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = Separator.copy(alpha = 0.7f)
            )
        )

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSettingsClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voice Settings",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.Settings, contentDescription = null, tint = Text_Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun VoiceSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceSettingBg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceSettingTitle"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceSettingIcon"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "voiceSettingOffset"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = rowOffset)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = titleColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Text_Muted,
                    fontSize = 13.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
    }
}

@Composable
internal fun ProfilePopoverContent(
    user: OrbitUser,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(UserBar_Bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Text_White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOrbitVerifiedUser(user.username, user.displayName)) {
                        Spacer(Modifier.width(5.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Accent.copy(alpha = 0.10f))
                .border(1.dp, Accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(user.status))
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = statusLabel(user.status),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Text_Secondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            )
        }

        ProfilePopoverAction(
            icon = Icons.Default.Settings,
            label = "Profile settings",
            onClick = onSettingsClick
        )
    }
}

@Composable
internal fun ProfilePopoverAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileActionBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isHovered) Text_White else AccentLight,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileActionIcon"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileActionOffset"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = rowOffset)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Text_Secondary,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
internal fun BarIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    activeTint: Color? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = activeTint ?: if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barIconTint"
    )
    val bg by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barIconBg"
    )

    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-31).dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(UserBar_Bg)
                    .border(1.dp, Separator.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1
                )
            }
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg),
            interactionSource = interactionSource
        ) {
            Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(17.dp))
        }
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
    Box(modifier = Modifier.size(size)) {
        if (avatarUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Text_White.copy(alpha = 0.08f), CircleShape)
                    .padding(1.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                coil3.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            OrbitDefaultAvatar(
                label = displayName,
                seed = displayName,
                color = Color(avatarColor),
                modifier = Modifier.fillMaxSize()
            )
        }
        val dotColor = statusColor(status)
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 13.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, UserBar_Bg, CircleShape)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

internal fun statusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE        -> Color(0xFF34D399)
    UserStatus.IDLE          -> Color(0xFFFBBF24)
    UserStatus.DO_NOT_DISTURB -> Color(0xFFF87171)
    UserStatus.OFFLINE       -> Color(0xFF3D4160)
}

internal fun statusLabel(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}

