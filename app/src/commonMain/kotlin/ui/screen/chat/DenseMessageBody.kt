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
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.model.OrbitMessage
import ui.theme.LocalShowTimestamps
import ui.theme.OrbitSpace
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

/**
 * A grouped message — the follow-on lines of a run, and the whole of a room in
 * compact mode. That makes it the *majority* of messages in a chat, which is why
 * the hover toolbar here used to be three dead buttons.
 */
@Composable
internal fun DenseMessageBody(
    message: OrbitMessage,
    onReact: ((String, String) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onMentionUser: ((String) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val showTimestamps = LocalShowTimestamps.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) t.MessageHover_Bg else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "denseMessageBg"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = OrbitMotion.SnappyDp,
        label = "denseMessageOffset"
    )

    var showReactionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .hoverable(interactionSource)
                .background(rowBg)
                .padding(start = messageTextGutter, end = OrbitSpace.lg, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Fixed-width always-present slot: adding a Row child only while
            // hovered used to shove the body 48dp right on every mouse move.
            val hoverStamp by animateFloatAsState(
                targetValue = if (isHovered && showTimestamps) 1f else 0f,
                animationSpec = OrbitMotion.Snappy,
                label = "denseMessageStampAlpha"
            )
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .offset(x = (-56).dp)
            ) {
                if (hoverStamp > 0.01f) {
                    Text(
                        text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                            color = t.Text_Muted,
                            fontSize = 9.sp
                        ),
                        modifier = Modifier.alpha(hoverStamp)
                    )
                }
            }
            MessageContentView(
                content = message.content,
                isCompact = true,
                onMentionUser = onMentionUser
            )
        }

        // Floating action toolbar on hover
        AnimatedVisibility(
            visible = isHovered,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-4).dp, x = (-16).dp),
            enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                animationSpec = OrbitMotion.tweenFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                animationSpec = OrbitMotion.tweenFast(),
                targetScale = 0.94f
            )
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(t.Elevated)
                    .border(
                        1.dp,
                        t.Separator,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (onReply != null) {
                    MessageActionBtn(OrbitIcons.Reply, "Reply") { onReply(message.id) }
                }
                if (onReact != null) {
                    MessageActionBtn(OrbitIcons.Emoji, "React") { showReactionMenu = true }
                    ReactionPickerMenu(
                        expanded = showReactionMenu,
                        alreadyAdded = { emoji -> message.reactions[emoji]?.isNotEmpty() == true },
                        onPick = { emoji ->
                            showReactionMenu = false
                            onReact(message.id, emoji)
                        },
                        onDismiss = { showReactionMenu = false }
                    )
                }
                // "More" only earns its place when there is a real action behind
                // it, so it is rendered conditionally rather than as a no-op.
                if (onTogglePin != null || onDeleteMessage != null) {
                    MessageActionBtn(Icons.Default.MoreHoriz, "More") { showMoreMenu = true }
                    MessageMoreMenu(
                        expanded = showMoreMenu,
                        canDelete = onDeleteMessage != null,
                        messageBody = message.body,
                        isPinned = message.isPinned,
                        pinEnabled = onTogglePin != null,
                        onTogglePin = {
                            showMoreMenu = false
                            onTogglePin?.invoke(message.id)
                        },
                        onDelete = {
                            showMoreMenu = false
                            onDeleteMessage?.invoke(message.id)
                        },
                        onCopyText = { showMoreMenu = false },
                        onDismiss = { showMoreMenu = false }
                    )
                }
            }
        }
    }
}
