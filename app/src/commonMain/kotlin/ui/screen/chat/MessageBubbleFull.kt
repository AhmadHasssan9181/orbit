package ui.screen.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.icons.OrbitIcons
import ui.model.OrbitMessage
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun MessageBubbleFull(message: OrbitMessage, currentUser: OrbitUser?) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        val isCurrentUser = currentUser != null && message.userId == currentUser.id
        WhatsAppMessageBubble(message, isCurrentUser)
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) MessageHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "messageRowBg"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "messageRowOffset"
    )

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .hoverable(interactionSource)
                .background(rowBg)
                .padding(horizontal = 14.dp, vertical = 1.dp)
                .padding(top = 3.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar — 40dp circle like Discord/Revolt
            MessageAvatar(message)
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Username + timestamp row — Discord style
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.username,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = resolveUsernameColor(message),
                            fontSize = 14.sp
                        )
                    )

                    if (isOrbitVerifiedUser(message.username, message.userId)) {
                        Spacer(Modifier.width(4.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(14.dp))
                    }

                    // Bot badge — like Discord/Revolt verified badge
                    if (message.username.contains("bot", ignoreCase = true) ||
                        message.username.contains("Bot", ignoreCase = true)) {
                        Spacer(Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(3.dp))
                                .background(Accent)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "BOT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 8.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }

                    if (ui.theme.LocalShowTimestamps.current) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = formatTimestamp(message.timestamp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Text_Muted,
                                fontSize = 10.sp
                            )
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                // Rich message content renderer
                MessageContentView(
                    content = message.content,
                    isCompact = false
                )

                // Edited indicator
                if (message.isEdited) {
                    Text(
                        text = "(edited)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Text_Muted, fontSize = 9.sp
                        )
                    )
                }

                // Reaction chips
                if (message.reactions.isNotEmpty()) {
                    ReactionChips(reactions = message.reactions)
                }
            }
        }

        // Floating action toolbar on hover — Discord style
        AnimatedVisibility(
            visible = isHovered,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-7).dp, x = (-14).dp),
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        1.dp,
                        Separator.copy(alpha = 0.3f),
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                MessageActionBtn(OrbitIcons.Emoji, "React")
                MessageActionBtn(OrbitIcons.Reply, "Reply")
                MessageActionBtn(Icons.Default.Edit, "Edit")
                MessageActionBtn(Icons.Default.MoreHoriz, "More")
            }
        }
    }
}

@Composable
internal fun MessageActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "messageActionTint"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) AccentGhost.copy(alpha = 0.45f) else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "messageActionBg"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isHovered) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "messageActionScale"
    )
    IconButton(
        onClick = {},
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bgColor)
            .hoverable(interactionSource),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(16.dp).scale(iconScale)
        )
    }
}

@Composable
private fun MessageAvatar(message: OrbitMessage) {
    OrbitDefaultAvatar(
        label = message.username,
        seed = message.userId,
        modifier = Modifier.size(36.dp)
    )
}

@Composable
fun WhatsAppMessageBubble(message: ui.model.OrbitMessage, isCurrentUser: Boolean) {
    val tokens = ui.theme.rememberCosmicTokens()
    val bubbleColor = if (isCurrentUser) tokens.Accent.copy(alpha = 0.15f) else tokens.CardBg
    val alignment = if (isCurrentUser) androidx.compose.ui.Alignment.End else androidx.compose.ui.Alignment.Start

    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = androidx.compose.ui.Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp,
                    bottomStart = if (isCurrentUser) 8.dp else 0.dp,
                    bottomEnd = if (isCurrentUser) 0.dp else 8.dp
                ))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                androidx.compose.material3.Text(
                    text = message.body,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium.copy(
                        color = tokens.Text_White,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = androidx.compose.ui.Modifier.padding(end = 8.dp)
                )
                androidx.compose.material3.Text(
                    text = ui.screen.chat.formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Text_Muted,
                        fontSize = 10.sp
                    ),
                    modifier = androidx.compose.ui.Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}
