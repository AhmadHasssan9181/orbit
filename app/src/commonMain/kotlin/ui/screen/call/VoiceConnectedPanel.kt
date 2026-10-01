package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
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
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val hairline = OrbitConceptBorder.subtle()

    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(callVoicePanelSurface(t))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // .voice-connection-status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // .voice-status-heading — 12px, weight 700, --status-online,
                    // with a 14px activity glyph in the same green. The concept
                    // does NOT change this line when the mic is muted, and it is
                    // right not to: the connection is still up, and the mute is
                    // already said three times over — by this panel's own mic
                    // button, by the dock, and by the red pill on the tile.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GraphicEq,
                            contentDescription = null,
                            tint = OrbitConceptStatus.online,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Voice Connected",
                            color = OrbitConceptStatus.online,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // .voice-status-channel — 11px, --text-muted.
                    Text(
                        text = channelName,
                        color = t.Text_Muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // The count sits on the status line rather than after the channel
                // name, because the channel name is the thing that can be long and
                // the count is the thing that must never be clipped.
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
                // .voice-quick-disconnect — transparent, --status-dnd, 4px padding,
                // 4px radius, hover rgba(242, 63, 67, 0.15).
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
                    icon = if (isMuted) OrbitIcons.MicOff else OrbitIcons.Mic,
                    contentDescription = if (isMuted) "Unmute microphone" else "Mute microphone",
                    active = false,
                    danger = isMuted,
                    onClick = onToggleMute
                )
                VoiceToolButton(
                    icon = Icons.Filled.Monitor,
                    contentDescription = "Share or stop sharing your screen",
                    // `.voice-tool-compact-btn.active-green` — the concept hard-codes
                    // this one green because in the concept it only ever means "go
                    // and look at the stream". Here the same green means the share
                    // is running, which is the more useful half of that truth.
                    active = isScreenSharing,
                    danger = false,
                    onClick = onToggleScreenShare
                )
                VoiceToolButton(
                    icon = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    contentDescription = if (isVideoEnabled) "Turn off camera" else "Turn on camera",
                    active = isVideoEnabled,
                    danger = false,
                    onClick = onToggleCamera
                )
                VoiceToolButton(
                    icon = Icons.Filled.MusicNote,
                    contentDescription = "Soundboard",
                    active = false,
                    danger = false,
                    onClick = onToggleSoundboard
                )
            }
        }
        // `border-top` and `border-bottom` only, and only those: the panel is a
        // BAND between the channel list above it and the user bar below it, and a
        // rule down its sides too would turn it into a card.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(hairline)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(hairline)
        )
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
