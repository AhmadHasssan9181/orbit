package ui.screen.drawer

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.HeadphonesOff
import ui.icons.Headset
import ui.icons.Microphone
import ui.icons.Settings
import ui.icons.SettingsAutomation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitIconButton
import ui.components.OrbitMenu
import ui.components.OrbitMenuDivider
import ui.components.OrbitMenuItem
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import webrtc.LocalCallManager
/**
 * Drawer footer: who you are, and your audio state. The concept's `.user-panel`.
 *
 * ## Geometry
 *
 * `.user-panel { height: var(--user-bar-height) }` — 52dp, `--bg-secondary`,
 * `padding: 0 8px`, `justify-content: space-between`. Inside it:
 *
 *  - `.user-info-section` — 4px/6px padding, 4px corners, `--bg-modifier-hover`
 *    on hover, and `max-width: 130px`, which is what keeps a long display name
 *    from pushing the controls off the panel;
 *  - `.user-avatar-wrap` — a fixed 32dp with a 10dp `.status-indicator` at the
 *    bottom right, ringed in `--bg-secondary` so it reads against the panel and
 *    not against the avatar;
 *  - `.user-display-name` — 13px, weight 600, `--header-primary`, ellipsised;
 *  - `.user-controls` — `.user-control-btn` at `padding: 6px`, 4px corners,
 *    `--interactive-normal`, flooding to `--bg-modifier-hover` +
 *    `--interactive-hover`, with `.active-danger` colouring the glyph
 *    `--status-dnd`.
 *
 * The panel is `--bg-secondary`, one step DOWN from the `--bg-sidebar` the list
 * above it sits on, which is what makes the list read as the panel and this read
 * as its footer rather than the two running together.
 *
 * ## The audio device picker
 *
 * The concept's third control is Settings. Orbit's is the audio-device popover,
 * and the two are not interchangeable: this bar is where a mute, a deafen and a
 * device picker all have to live in 130dp, and Settings already sits one click
 * away in the profile popover above them. Everything else about the row — the
 * 6px padding, the 4px corners, the hover wash, the `.active-danger` treatment —
 * is the concept's.
 */
@Composable
internal fun UserProfileBar(
    user: OrbitUser,
    onSettingsClick: () -> Unit,
    onStatusSelected: ((UserStatus) -> Unit)? = null
) {
    var showProfilePopover by remember { mutableStateOf(false) }
    var showVoicePopover by remember { mutableStateOf(false) }

    val callManager = LocalCallManager.current
    // Hoisted so the number of remembered slots does not change when callManager
    // flips between null and non-null.
    val audioEnabledFlow = remember(callManager) {
        callManager?.isLocalAudioEnabled ?: flowOf(true)
    }
    val deafenedFlow = remember(callManager) {
        callManager?.isDeafened ?: flowOf(false)
    }
    val isAudioEnabled by audioEnabledFlow.collectAsState(initial = true)
    val isDeafened by deafenedFlow.collectAsState(initial = false)

    val infoInteraction = remember { MutableInteractionSource() }
    val infoHovered by infoInteraction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(OrbitConceptMetrics.userBarHeight)
            // `.user-panel { background-color: var(--bg-secondary) }`.
            .background(conceptSecondarySurface())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Identity + presence picker ───────────────────────────
        // The menu must be a child of the node that triggered it: OrbitMenu wraps
        // Material's DropdownMenu, which anchors to its parent layout node. As a
        // sibling of the whole bar it opened near the bottom of the drawer, over
        // the room list.
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // `.user-info-section { padding: 4px 6px; border-radius: 4px;
                    // max-width: 130px }`.
                    //
                    // The 130px cap is not applied directly: on a 240dp panel
                    // minus 8dp of padding each side minus the three 30dp
                    // controls and their 2dp gaps, 130dp is exactly what is left.
                    // `weight(1f)` on the enclosing Box with
                    // `Arrangement.SpaceBetween` derives the same number and
                    // keeps holding if a control ever changes size.
                    .height(44.dp)
                    .clip(OrbitRadius.xsShape)
                    .background(if (infoHovered) conceptHoverOverlay() else Color.Transparent)
                    .hoverable(infoInteraction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open your profile",
                        onClick = { showProfilePopover = true }
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // `.user-avatar-wrap { width: 32px; height: 32px }`.
                UserAvatar(
                    displayName = user.displayName,
                    avatarColor = user.avatarColor,
                    status = user.status,
                    avatarUrl = user.avatarUrl,
                    size = 32.dp
                )
                Spacer(Modifier.width(8.dp))
                // `.user-names { display: flex; flex-direction: column; overflow:
                // hidden }`.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName,
                            // `.user-display-name { font-size: 13px; font-weight:
                            // 600; color: var(--header-primary) }`.
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = conceptHeaderPrimary(),
                                fontSize = 13.sp
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
                    // `.user-status-text { font-size: 11px; color:
                    // var(--text-muted) }`.
                    Text(
                        text = statusLabel(user.status),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = conceptTextMuted(),
                            fontSize = 11.sp,
                            lineHeight = 13.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

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

        // ── Audio state ─────────────────────────────────────────
        // `.user-controls { display: flex; align-items: center; gap: 2px }`.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                UserControlButton(
                    icon = with(TablerIcons) { SettingsAutomation },
                    contentDescription = "Audio devices",
                    isDanger = false,
                    onClick = { showVoicePopover = !showVoicePopover }
                )
                OrbitMenu(
                    expanded = showVoicePopover,
                    onDismissRequest = { showVoicePopover = false },
                    width = 268.dp
                ) {
                    VoiceControlPopover(onSettingsClick = {
                        showVoicePopover = false
                        onSettingsClick()
                    })
                }
            }
            UserControlButton(
                icon = if (isAudioEnabled) OrbitIcons.Mic else OrbitIcons.MicOff,
                contentDescription = if (isAudioEnabled) "Mute microphone" else "Unmute microphone",
                isDanger = !isAudioEnabled,
                onClick = { callManager?.toggleMute(isAudioEnabled) }
            )
            UserControlButton(
                icon = if (isDeafened) with(TablerIcons) { HeadphonesOff } else with(TablerIcons) { Headset },
                contentDescription = if (isDeafened) "Undeafen" else "Deafen",
                isDanger = isDeafened,
                onClick = { callManager?.toggleDeafen() }
            )
        }
    }
}

/**
 * `.user-control-btn { background: transparent; border: none; color:
 * var(--interactive-normal); padding: 6px; border-radius: 4px }` with
 * `:hover { background-color: var(--bg-modifier-hover); color:
 * var(--interactive-hover) }` and `.active-danger { color: var(--status-dnd) }`.
 *
 * An 18dp glyph inside 6dp of padding is a 30dp target, which is what the
 * concept's box comes to — and it is the same size as Orbit's previous
 * `OrbitIconButton`, so nothing about the hit area moved.
 */
@Composable
private fun UserControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isDanger: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val fill by animateColorAsStateCompat(
        if (hovered) conceptHoverOverlay() else Color.Transparent,
        label = "userControlFill"
    )
    // `.active-danger` wins over the hover colour: a muted mic stays red whether
    // or not the pointer is on it, because "you are muted" is a state and not a
    // mood.
    val tint by animateColorAsStateCompat(
        when {
            isDanger -> OrbitConceptStatus.dnd
            hovered -> conceptInteractiveHover()
            else -> conceptInteractiveNormal()
        },
        label = "userControlTint"
    )

    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(OrbitRadius.xsShape)
            .background(fill)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
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
    val callManager = LocalCallManager.current
    val settingsManager = ui.theme.LocalSettingsManager.current
    var showInputDevices by remember { mutableStateOf(false) }
    var inputDevices by remember { mutableStateOf(emptyList<String>()) }
    var inputDeviceName by remember { mutableStateOf("System default") }

    // The old `remember(callManager) { refreshInputDevices() }` ran native WebRTC
    // device enumeration and wrote two states *during composition*, on the
    // Compose main thread. An effect on IO does the same work off the UI thread.
    LaunchedEffect(callManager) {
        val devices = withContext(Dispatchers.IO) {
            runCatching { callManager?.webRtcClient?.getAudioInputDevices().orEmpty() }
                .getOrDefault(emptyList())
        }
        inputDevices = devices
        if (inputDeviceName == "System default" || inputDeviceName !in devices) {
            inputDeviceName = devices.firstOrNull() ?: "System default"
        }
    }

    // Previously a local `var inputVolume by remember { mutableStateOf(1f) }` that
    // nothing read: the slider moved and the microphone gain never changed. It
    // now reads the app-wide setting (0..100, the same value Voice & Video
    // settings writes) and pushes it into the call manager, which takes 0..1.
    val inputVolumeFlow = remember(settingsManager) {
        settingsManager?.inputVolume ?: flowOf(100f)
    }
    val inputVolumePercent by inputVolumeFlow.collectAsState(initial = 100f)
    val inputVolume = (inputVolumePercent / 100f).coerceIn(0f, 1f)

    Column(modifier = Modifier.padding(OrbitSpace.sm)) {
        OrbitMenuItem(
            label = inputDeviceName,
            icon = with(TablerIcons) { Microphone },
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
                        icon = with(TablerIcons) { Microphone },
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
                style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Secondary, fontSize = 12.5.sp)
            )
            Text(
                text = "${inputVolumePercent.toInt()}%",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = t.Accent,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1
            )
            Slider(
                value = inputVolume,
                onValueChange = { fraction ->
                    val percent = (fraction * 100f).coerceIn(0f, 100f)
                    callManager?.setMicVolume(fraction)
                    settingsManager?.setInputVolume(percent)
                },
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
            icon = with(TablerIcons) { Settings },
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
            icon = with(TablerIcons) { Settings },
            onClick = onSettingsClick
        )
    }
}

/**
 * `.user-avatar-wrap { position: relative; width/height }` with
 * `.user-avatar-img` filling it and a `.status-indicator` at `bottom: -2px;
 * right: -2px` — 10px, a 2px `--bg-secondary` ring.
 *
 * The ring is drawn as a `border` on a slightly larger circle rather than as an
 * offset child, because a CSS outline and a Compose border are the same shape at
 * two sizes and the offset form is what the previous version used.
 */
@Composable
internal fun UserAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    avatarUrl: String? = null,
    size: Dp = 32.dp
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
                .size(if (size > 40.dp) 12.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = 1.dp)
                .border(2.dp, conceptSecondarySurface(), CircleShape)
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

internal fun statusLabel(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}
