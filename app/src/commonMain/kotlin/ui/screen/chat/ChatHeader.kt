package ui.screen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitIconButton
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Room header.
 *
 * Previously a flat 48dp strip: a bare hash glyph, a bold title, and a topic
 * that pushed the actions off the edge of the window on any room with a long
 * name. The topic is now a second line that truncates, the actions never move,
 * and the whole bar carries a hover state so the pointer's position over the
 * header means something.
 */
@Composable
internal fun ChatHeader(
    channel: OrbitChannel,
    directProfile: OrbitUser? = null,
    onBack: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null,
    searchActive: Boolean = false,
    onToggleSearch: () -> Unit = {},
    pinsActive: Boolean = false,
    onTogglePins: () -> Unit = {},
    memberListActive: Boolean = false,
    onToggleMemberList: (() -> Unit)? = null,
    notifMode: String = "ALL",
    onCycleNotificationMode: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val windowSize = LocalWindowSizeClass.current
    val compact = windowSize.isCompact
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val barBg by animateColorAsState(
        targetValue = if (hovered) t.Header_Bg.copy(alpha = 0.98f) else t.Header_Bg,
        animationSpec = OrbitMotion.SnappyColor,
        label = "headerBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(barBg)
            .hoverable(interaction)
            .height(if (compact) 56.dp else 52.dp)
            .padding(horizontal = if (compact) OrbitSpace.xs else OrbitSpace.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Identity ────────────────────────────────────────────
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null && !windowSize.isExpanded) {
                OrbitIconButton(
                    icon = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = 34.dp,
                    iconSize = 18.dp,
                    shape = CircleShape
                )
                Spacer(Modifier.width(OrbitSpace.xs))
            }

            when {
                directProfile != null -> SmallUserAvatar(directProfile, size = 30.dp)
                else -> Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(OrbitRadius.smShape)
                        .background(t.Sunken),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = if (channel.type == ChannelType.TEXT) OrbitIcons.TextChannel else OrbitIcons.VoiceChannel,
                        contentDescription = null,
                        tint = t.Text_Muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.width(OrbitSpace.md))

            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = directProfile?.displayName ?: channelDisplayNameForHeader(channel),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = t.Text_White,
                        fontSize = 14.sp,
                        letterSpacing = 0.sp,
                        lineHeight = 18.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = when {
                    directProfile != null && directProfile.username.isNotBlank() -> "@${directProfile.username}"
                    channel.topic.isNotBlank() -> channel.topic
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = t.Text_Muted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.width(OrbitSpace.md))

        // ── Actions ─────────────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            if (directProfile != null) {
                OrbitIconButton(
                    icon = OrbitIcons.Call,
                    contentDescription = "Start a voice call",
                    onClick = { onVoiceCall?.invoke() },
                    tooltip = "Voice call",
                    size = 32.dp,
                    iconSize = 17.dp
                )
                OrbitIconButton(
                    icon = OrbitIcons.Video,
                    contentDescription = "Start a video call",
                    onClick = { onVideoCall?.invoke() },
                    tooltip = "Video call",
                    size = 32.dp,
                    iconSize = 17.dp
                )
            }
            OrbitIconButton(
                icon = Icons.Default.Search,
                contentDescription = "Search this room",
                onClick = onToggleSearch,
                active = searchActive,
                tooltip = "Search",
                size = 32.dp,
                iconSize = 16.dp
            )
            if (onCycleNotificationMode != null) {
                OrbitIconButton(
                    icon = if (notifMode == "MUTE") Icons.Default.NotificationsOff else Icons.Default.Notifications,
                    contentDescription = when (notifMode) {
                        "MUTE" -> "Notifications muted"
                        "MENTIONS" -> "Notifications: mentions only"
                        else -> "Notifications: all messages"
                    },
                    onClick = onCycleNotificationMode,
                    active = notifMode != "ALL",
                    tooltip = when (notifMode) {
                        "MUTE" -> "Muted"
                        "MENTIONS" -> "Mentions only"
                        else -> "All messages"
                    },
                    size = 32.dp,
                    iconSize = 16.dp
                )
            }
            OrbitIconButton(
                icon = OrbitIcons.Pin,
                contentDescription = "Pinned messages",
                onClick = onTogglePins,
                active = pinsActive,
                tooltip = "Pins",
                size = 32.dp,
                iconSize = 16.dp
            )
            // Hidden when the layout has no member panel to show, rather than
            // offering a button that toggles state nobody can see.
            if (onToggleMemberList != null) {
                OrbitIconButton(
                    icon = OrbitIcons.Members,
                    contentDescription = "Members",
                    onClick = onToggleMemberList,
                    active = memberListActive,
                    tooltip = "Members",
                    size = 32.dp,
                    iconSize = 16.dp
                )
            }
        }
    }
}

/** Voice rooms get their " (Call)" suffix stripped in the header too. */
private fun channelDisplayNameForHeader(channel: OrbitChannel): String =
    if (channel.type == ChannelType.VOICE) {
        channel.name.removeSuffix(" (Call)")
    } else {
        channel.name
    }
