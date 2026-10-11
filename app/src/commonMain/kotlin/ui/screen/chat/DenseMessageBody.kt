package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Dots
import ui.icons.OrbitIcons
import ui.model.OrbitMessage
import ui.theme.LocalShowTimestamps
import ui.theme.OrbitConceptOverlay
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens
/**
 * A grouped message — the follow-on lines of a run, and the whole of a room in
 * compact mode. That makes it the *majority* of messages in a chat, which is why
 * the hover toolbar here used to be three dead buttons.
 *
 * The row itself is the concept's `.message-item` with the avatar column left
 * empty: same 6dp/12dp padding, same 6dp radius, same `--bg-modifier-hover`
 * fill, same 16dp gap between the gutter and the text. The gutter is
 * `messageTextGutter` — the same constant `MessageBubbleFull` derives from the
 * avatar — so a run of messages from one person reads as one column.
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
    val light = isLightChatSurface(t)
    val showTimestamps = LocalShowTimestamps.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) {
            if (light) OrbitConceptOverlay.hoverLight() else OrbitConceptOverlay.hover()
        } else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "denseMessageBg"
    )

    var showReactionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Hover lives on this Box rather than on the row below, for the same
            // reason as in MessageBubbleFull: the toolbar hangs off this hover state
            // and used to be drawn 4dp ABOVE the row, so reaching for it meant
            // leaving the hoverable area and it faded out on the way. Grouped
            // messages are the MAJORITY of a chat, so this was the one that showed
            // up most often.
            .hoverable(interactionSource)
    ) {
        // The hover timestamp, drawn OVER the avatar gutter rather than as a
        // column of the Row.
        //
        // It used to be a 48dp Row child with `offset(x = -56.dp)`. The offset
        // moves a child VISUALLY but does not give the space back, so the Row
        // still reserved 48dp and every grouped message body started at
        // gutter + 48 -- a full 48dp to the right of the message above it. The
        // `padding(start = ...)` was correct and the extra 48dp was invisible in
        // the source, which is why this survived a gutter fix that was itself
        // right.
        //
        // Its comment claimed the always-present slot stopped the body shoving
        // sideways on hover, which was a real problem -- and the fix stabilised
        // the shove at the wrong value instead of removing it. Aligning to the
        // parent Box removes the shift entirely: there is no longer a slot to
        // reserve, so hovering cannot move the body whether the timestamp is
        // showing or not.
        val hoverStamp by animateFloatAsState(
            targetValue = if (isHovered && showTimestamps) 1f else 0f,
            animationSpec = OrbitMotion.Snappy,
            label = "denseMessageStampAlpha"
        )
        if (hoverStamp > 0.01f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = hoverTimestampInset)
                    .width(48.dp)
            ) {
                Text(
                    text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        // .message-timestamp { font-size: 12px; color: var(--text-muted) }
                        color = t.Text_Muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(messageRowRadius)
                .background(rowBg)
                .padding(
                    start = messageTextGutter,
                    end = messageRowInset,
                    top = 1.dp,
                    bottom = 1.dp
                ),
            verticalAlignment = Alignment.Top
        ) {
            MessageContentView(
                content = message.content,
                isCompact = true,
                onMentionUser = onMentionUser
            )
        }

        // Floating action toolbar on hover
        AnimatedVisibility(
            visible = isHovered,
            // Inside the parent's bounds, for the reason given on the Box above.
            // `right: 16px` from the `.message-item` edge, as in MessageBubbleFull.
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 2.dp, x = (-16).dp),
            enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                animationSpec = OrbitMotion.tweenFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                animationSpec = OrbitMotion.tweenFast(),
                targetScale = 0.94f
            )
        ) {
            MessageToolbarFrame(light = light) {
                if (onReply != null) {
                    MessageActionBtn(OrbitIcons.Reply, "Reply") { onReply(message.id) }
                }
                if (onReact != null) {
                    MessageActionBtn(OrbitIcons.Emoji, "Add reaction") { showReactionMenu = true }
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
                    MessageActionBtn(with(TablerIcons) { Dots }, "More") { showMoreMenu = true }
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
