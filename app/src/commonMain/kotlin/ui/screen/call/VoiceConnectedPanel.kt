package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitTooltip
import ui.icons.TablerIcons
import ui.icons.DeviceDesktop
import ui.icons.Music
import ui.icons.Video
import ui.icons.VideoOff
import ui.icons.OrbitIcons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.flow.flowOf
import ui.components.OrbitSwitch
import ui.icons.X
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens
/**
 * The "Voice Connected" panel: who you are connected to, and the four controls
 * you reach for without leaving the room.
 *
 * The concept draws this at the bottom of the channel sidebar, above the user
 * bar, where it is visible for the whole call. Orbit's group surface draws it
 * bottom-left over the stage, which is the same job done in the one place the
 * stage has spare room, and the same CSS does the work.
 *
 * Structure is `.voice-connected-bar` exactly: a `--bg-secondary` panel between
 * two `--border-subtle` hairlines, 8px/12px of padding, a 6px gap between the
 * status line and the tools row.
 */
@Composable
fun VoiceConnectedPanel(
    channelName: String,
    participantCount: Int,
    isMuted: Boolean,
    isScreenSharing: Boolean,
    isVideoEnabled: Boolean,
    onToggleMute: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleCamera: () -> Unit,
    onToggleSoundboard: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    isSoundboardOpen: Boolean = false,
    pingMs: Long = -1L,
    connectionBars: Int? = null,
    onReturnToCall: () -> Unit = {}
) {
    val t = rememberCosmicTokens()

    val settingsManager = ui.theme.LocalSettingsManager.current
    val nsEngine by (settingsManager?.noiseSuppressionEngine ?: flowOf("DeepFilterNet")).collectAsState("DeepFilterNet")
    val isNsActive = nsEngine != "Off"
    var showNoiseSuppressionMenu by remember { mutableStateOf(false) }

    val calculatedBars = connectionBars?.let {
        minOf(it, when {
            pingMs < 0L -> 3
            pingMs > 300L -> 1
            pingMs > 150L -> 2
            else -> 3
        })
    } ?: when {
        pingMs < 0L -> 3
        pingMs > 300L -> 1
        pingMs > 150L -> 2
        else -> 3
    }

    val (statusColor, qualityLabel) = when (calculatedBars) {
        3 -> Pair(OrbitConceptStatus.online, "Good")
        2 -> Pair(ui.theme.Amber500, "Average")
        else -> Pair(OrbitConceptStatus.dnd, "Poor")
    }
    val tooltipText = if (pingMs >= 0L) "Ping: $pingMs ms ($qualityLabel)" else "RTC Connecting..."

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // .voice-connection-status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Return to call",
                    onClick = onReturnToCall
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OrbitTooltip(text = tooltipText) {
                VoiceWifiSignalBadge(
                    bars = calculatedBars,
                    statusColor = statusColor
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (pingMs < 0L) "RTC Connecting" else "Voice Connected",
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channelName,
                    color = t.Text_Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (participantCount > 1) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(3.dp)
                        .clip(CircleShape)
                        .background(t.Text_Muted)
                )
                Text(
                    text = participantCount.toString(),
                    color = t.Text_Muted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            VoiceQuickDisconnect(onDisconnect)
        }

        // .voice-tools-row-compact — space-around, 6px gap, 4px top margin.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            VoiceToolButton(
                icon = if (isNsActive) OrbitIcons.Waveform else OrbitIcons.WaveformOff,
                contentDescription = if (isNsActive) "Noise Suppression: $nsEngine (Active)" else "Noise Suppression: Off",
                active = isNsActive,
                danger = false,
                onClick = { showNoiseSuppressionMenu = !showNoiseSuppressionMenu }
            )
            VoiceToolButton(
                icon = with(TablerIcons) { DeviceDesktop },
                contentDescription = "Share or stop sharing your screen",
                active = isScreenSharing,
                danger = false,
                onClick = onToggleScreenShare
            )
            VoiceToolButton(
                icon = if (isVideoEnabled) with(TablerIcons) { Video } else with(TablerIcons) { VideoOff },
                contentDescription = if (isVideoEnabled) "Turn off camera" else "Turn on camera",
                active = isVideoEnabled,
                danger = false,
                onClick = onToggleCamera
            )
            VoiceToolButton(
                icon = with(TablerIcons) { Music },
                contentDescription = "Soundboard",
                active = isSoundboardOpen,
                danger = false,
                onClick = onToggleSoundboard
            )
        }

        if (showNoiseSuppressionMenu) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(x = 248, y = -20),
                onDismissRequest = { showNoiseSuppressionMenu = false },
                properties = PopupProperties(focusable = true)
            ) {
                NoiseSuppressionCard(
                    currentEngine = nsEngine,
                    onSelectEngine = { newEngine ->
                        settingsManager?.setNoiseSuppressionEngine(newEngine)
                    },
                    onToggleEnabled = { enabled ->
                        if (!enabled) {
                            settingsManager?.setNoiseSuppressionEngine("Off")
                        } else {
                            val target = if (nsEngine == "Off") "DeepFilterNet" else nsEngine
                            settingsManager?.setNoiseSuppressionEngine(target)
                        }
                    },
                    onDismiss = { showNoiseSuppressionMenu = false }
                )
            }
        }
    }
}

/**
 * Discord-style Krisp popover card offering DeepFilterNet and RNNoise.
 * Standard WebRTC Wiener filter NS is completely removed to eliminate breath-cutting.
 */
@Composable
private fun NoiseSuppressionCard(
    currentEngine: String,
    onSelectEngine: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val t = rememberCosmicTokens()
    val isEnabled = currentEngine != "Off"

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ui.theme.Ink650.copy(alpha = 0.96f),
        shadowElevation = 18.dp,
        border = BorderStroke(1.dp, t.OnDark.copy(alpha = 0.08f)),
        modifier = Modifier.width(320.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Waveform Icon + Title + Switch + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = OrbitIcons.Waveform,
                    contentDescription = null,
                    tint = if (isEnabled) OrbitConceptStatus.online else t.Text_Muted,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Noise Suppression",
                    color = t.Text_White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                OrbitSwitch(
                    checked = isEnabled,
                    onCheckedChange = onToggleEnabled
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = with(TablerIcons) { X },
                        contentDescription = "Close",
                        tint = t.Text_Muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = "Orbit neural noise suppression eliminates keyboard typing, fan humming, and background noise without cutting off your breath consonants.",
                color = t.Text_Muted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )

            HorizontalDivider(
                color = t.Separator.copy(alpha = 0.5f),
                thickness = 1.dp
            )

            // Option 1: DeepFilterNet
            NoiseModelOptionCard(
                title = "DeepFilterNet",
                badge = "AI Enhanced",
                badgeColor = t.Accent,
                description = "Full-band 48 kHz multi-frame deep complex filtering with breath preservation. Recommended for maximum vocal fidelity in noisy environments.",
                isSelected = isEnabled && (currentEngine == "DeepFilterNet" || currentEngine != "RNNoise"),
                enabled = isEnabled,
                onClick = {
                    if (isEnabled) {
                        onSelectEngine("DeepFilterNet")
                    } else {
                        onToggleEnabled(true)
                        onSelectEngine("DeepFilterNet")
                    }
                }
            )

            // Option 2: RNNoise
            NoiseModelOptionCard(
                title = "RNNoise",
                badge = "Low Latency",
                badgeColor = ui.theme.Steel500,
                description = "22 Bark-scale band recurrent neural network (GRU). Ultra-lightweight on CPU with negligible latency (ideal for low-spec systems).",
                isSelected = isEnabled && currentEngine == "RNNoise",
                enabled = isEnabled,
                onClick = {
                    if (isEnabled) {
                        onSelectEngine("RNNoise")
                    } else {
                        onToggleEnabled(true)
                        onSelectEngine("RNNoise")
                    }
                }
            )

            Text(
                text = "Standard WebRTC filter has been removed to prevent vocal gating & breath clipping.",
                color = t.Text_Muted.copy(alpha = 0.7f),
                fontSize = 10.5.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun NoiseModelOptionCard(
    title: String,
    badge: String,
    badgeColor: Color,
    description: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()

    val cardBg = when {
        !enabled -> t.CardBg.copy(alpha = 0.4f)
        isSelected -> badgeColor.copy(alpha = 0.12f)
        isHovered -> t.CardBgHover
        else -> t.CardBg
    }

    val borderColor = when {
        !enabled -> t.Separator.copy(alpha = 0.3f)
        isSelected -> badgeColor
        isHovered -> t.SeparatorStrong
        else -> t.Separator.copy(alpha = 0.6f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = if (enabled) t.Text_White else t.Text_Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (enabled) badgeColor.copy(alpha = 0.2f) else t.Text_Muted.copy(alpha = 0.15f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badge,
                        color = if (enabled) badgeColor else t.Text_Muted,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = when {
                                !enabled -> t.Text_Muted.copy(alpha = 0.4f)
                                isSelected -> badgeColor
                                else -> t.Text_Muted
                            },
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected && enabled) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(badgeColor)
                        )
                    }
                }
            }
            Text(
                text = description,
                color = if (enabled) t.Text_Muted else t.Text_Muted.copy(alpha = 0.5f),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
    }
}

/** `.voice-quick-disconnect` — the hang-up inside the panel's status line. */
@Composable
private fun VoiceQuickDisconnect(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) CallDisconnectHover else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "quickDisconnectBg"
    )
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CallIconPillShape)
            .background(background)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Disconnect",
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = OrbitIcons.CallEnd,
            contentDescription = "Disconnect",
            tint = OrbitConceptStatus.dnd,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * `.voice-tool-compact-btn` — 34x32, 4px radius, `rgba(255, 255, 255, 0.05)`,
 * `--interactive-normal`; hover `rgba(255, 255, 255, 0.1)` and
 * `--interactive-active`; `.active-green` fills `--status-online`;
 * `.active-danger` colours the glyph `--status-dnd`.
 */
@Composable
private fun VoiceToolButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    danger: Boolean,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            active -> CallPillActiveGreen
            hovered -> CallVoiceToolHover
            else -> CallVoiceToolRest
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "voiceToolBg"
    )
    val tint = when {
        active -> Color.White
        danger -> CallVoiceToolDanger
        hovered -> t.Text_White
        else -> t.Text_Secondary
    }

    Box(
        modifier = Modifier
            .size(width = CallVoiceToolWidth, height = CallVoiceToolHeight)
            .clip(CallIconPillShape)
            .background(background)
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
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Discord-style WiFi/signal quality badge with 3 radiating concentric arcs.
 *
 * Shows real-time connection state:
 * - 3 bars: Good (< 150ms, green)
 * - 2 bars: Average (150-300ms, amber)
 * - 1 bar: Poor (> 300ms / high loss, red)
 */
@Composable
fun VoiceWifiSignalBadge(
    bars: Int,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bgAlpha = if (hovered) 0.22f else 0.12f
    val containerBg by animateColorAsState(
        targetValue = statusColor.copy(alpha = bgAlpha),
        animationSpec = OrbitMotion.SnappyColor,
        label = "wifiBadgeBg"
    )
    val badgeShape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .size(32.dp)
            .clip(badgeShape)
            .background(containerBg)
            .border(1.dp, statusColor.copy(alpha = 0.22f), badgeShape)
            .hoverable(interaction),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val w = size.width
            val scale = w / 20f
            val cx = 4.5f * scale
            val cy = 15.5f * scale
            val strokeWidth = 2f * scale
            val dimColor = statusColor.copy(alpha = 0.22f)

            // Origin dot at bottom-left
            drawCircle(
                color = if (bars >= 1) statusColor else dimColor,
                radius = 1.3f * scale,
                center = Offset(cx, cy)
            )

            // Inner Arc (1 bar)
            val r1 = 4.2f * scale
            drawArc(
                color = if (bars >= 1) statusColor else dimColor,
                startAngle = 270f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r1, cy - r1),
                size = Size(r1 * 2f, r1 * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Middle Arc (2 bars)
            val r2 = 8.2f * scale
            drawArc(
                color = if (bars >= 2) statusColor else dimColor,
                startAngle = 270f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r2, cy - r2),
                size = Size(r2 * 2f, r2 * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Outer Arc (3 bars)
            val r3 = 12.2f * scale
            drawArc(
                color = if (bars >= 3) statusColor else dimColor,
                startAngle = 270f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(cx - r3, cy - r3),
                size = Size(r3 * 2f, r3 * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
    }
}

