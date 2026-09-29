package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitDialog
import ui.components.OrbitIconButton
import ui.components.OrbitMenu
import ui.components.OrbitMenuItem
import ui.components.OrbitTextField
import ui.icons.OrbitIcons
import ui.model.OrbitMessage
import ui.model.OrbitUser
import ui.theme.LocalShowTimestamps
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * A message.
 *
 * The single biggest readability problem on desktop was unbounded measure: a
 * one-line message stretched the full width of a 2560px window, and the name,
 * timestamp and body all sat on one 12sp-baseline row. Now the reading column
 * is capped, the identity line has real hierarchy, hover reveals a floating
 * toolbar built from the shared icon button, and the reaction/more menus use
 * the token set instead of a hard-coded `#1E1F22` that stayed Discord-grey in
 * every theme.
 */
@Composable
internal fun MessageBubbleFull(
    message: OrbitMessage,
    currentUser: OrbitUser?,
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null,
    onMentionUser: ((String) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val isOwn = currentUser != null && message.userId == currentUser.id
    val canEdit = isOwn && onEditMessage != null && message.content is ui.model.MessageContent.Text
    val canDelete = isOwn && onDeleteMessage != null

    var showReactionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val rowBg by animateColorAsState(
        targetValue = if (hovered) t.MessageHover_Bg else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "messageRowBg"
    )
    // A whisper of horizontal travel on hover is the whole trick that makes a
    // dense list feel like it has depth.
    val rowOffset by animateFloatAsState(
        targetValue = if (hovered) 3f else 0f,
        animationSpec = OrbitMotion.Snappy,
        label = "messageRowOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 1.dp, bottom = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offsetX(rowOffset)
                .hoverable(interaction)
                .background(rowBg)
                .padding(horizontal = OrbitSpace.lg, vertical = 3.dp)
                .padding(top = 3.dp),
            verticalAlignment = Alignment.Top
        ) {
            MessageAvatar(message)
            Spacer(Modifier.width(OrbitSpace.md))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = ui.theme.OrbitMetrics.maxMessageMeasure)
            ) {
                // ── Identity line ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.xs)
                ) {
                    Text(
                        text = message.username,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = resolveUsernameColor(message),
                            fontSize = 14.sp,
                            lineHeight = 18.sp
                        ),
                        maxLines = 1
                    )

                    if (isOrbitVerifiedUser(message.username, message.userId)) {
                        OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                    }

                    if (message.username.contains("bot", ignoreCase = true)) {
                        BotBadge()
                    }

                    if (ui.theme.LocalShowTimestamps.current) {
                        Spacer(Modifier.width(OrbitSpace.xs))
                        Text(
                            text = formatTimestampCompact(message.timestamp),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = t.Text_Subtle,
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        )
                    }
                }

                Spacer(Modifier.height(1.dp))

                MessageContentView(
                    content = message.content,
                    isCompact = false,
                    onMentionUser = onMentionUser
                )

                if (message.isEdited) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = "edited",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = t.Text_Subtle,
                            fontSize = 10.sp
                        )
                    )
                }

                if (message.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    ReactionChips(reactions = message.reactions)
                }
            }
        }

        // ── Floating hover toolbar ──────────────────────────────
        AnimatedVisibility(
            visible = hovered,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-6).dp, x = (-14).dp),
            enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                animationSpec = OrbitMotion.tweenFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                animationSpec = OrbitMotion.tweenFast(),
                targetScale = 0.96f
            )
        ) {
            Row(
                modifier = Modifier
                    .clip(OrbitRadius.mdShape)
                    .background(t.Elevated)
                    .border(1.dp, t.SeparatorStrong, OrbitRadius.mdShape)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onReply != null) {
                    OrbitIconButton(
                        icon = OrbitIcons.Reply,
                        contentDescription = "Reply",
                        onClick = { onReply(message.id) },
                        size = 28.dp,
                        iconSize = 15.dp,
                        shape = OrbitRadius.xsShape
                    )
                }
                if (onReact != null) {
                    OrbitIconButton(
                        icon = OrbitIcons.Emoji,
                        contentDescription = "Add reaction",
                        onClick = { showReactionMenu = true },
                        size = 28.dp,
                        iconSize = 15.dp,
                        shape = OrbitRadius.xsShape
                    )
                    ReactionPickerMenu(
                        expanded = showReactionMenu,
                        alreadyAdded = { emoji ->
                            currentUser?.id != null && message.reactions[emoji]?.contains(currentUser.id) == true
                        },
                        onPick = { emoji ->
                            showReactionMenu = false
                            if (currentUser == null || message.reactions[emoji]?.contains(currentUser.id) != true) {
                                onReact(message.id, emoji)
                            }
                        },
                        onDismiss = { showReactionMenu = false }
                    )
                }
                if (canEdit) {
                    OrbitIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "Edit",
                        onClick = { showEditDialog = true },
                        size = 28.dp,
                        iconSize = 15.dp,
                        shape = OrbitRadius.xsShape
                    )
                }
                if (canDelete || !isOwn) {
                    OrbitIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "More",
                        onClick = { showMoreMenu = true },
                        size = 28.dp,
                        iconSize = 15.dp,
                        shape = OrbitRadius.xsShape
                    )
                    MessageMoreMenu(
                        expanded = showMoreMenu,
                        canDelete = canDelete,
                        messageBody = message.body,
                        isPinned = message.isPinned,
                        pinEnabled = onTogglePin != null,
                        onTogglePin = {
                            showMoreMenu = false
                            onTogglePin?.invoke(message.id)
                        },
                        onDelete = {
                            showMoreMenu = false
                            showDeleteConfirm = true
                        },
                        onCopyText = { showMoreMenu = false },
                        onDismiss = { showMoreMenu = false }
                    )
                }
            }
        }

        if (showEditDialog) {
            MessageEditDialog(
                initialBody = message.body,
                onConfirm = { newBody ->
                    showEditDialog = false
                    if (newBody.isNotBlank()) onEditMessage?.invoke(message.id, newBody)
                },
                onDismiss = { showEditDialog = false }
            )
        }

        if (showDeleteConfirm) {
            ui.components.OrbitDialog(
                title = "Delete this message?",
                subtitle = "It will be removed for everyone in the room. This cannot be undone.",
                icon = Icons.Default.Delete,
                onDismissRequest = { showDeleteConfirm = false },
                confirmLabel = "Delete",
                confirmTone = OrbitButtonTone.Danger,
                onConfirm = {
                    showDeleteConfirm = false
                    onDeleteMessage?.invoke(message.id)
                },
                width = 400.dp
            ) { }
        }
    }
}

@Composable
private fun Modifier.offsetX(px: Float): Modifier {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return this.offset(x = with(density) { px.toDp() })
}

@Composable
private fun BotBadge() {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(t.Accent)
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        Text(
            text = "BOT",
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.OnAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 8.5.sp,
                letterSpacing = 0.5.sp,
                lineHeight = 11.sp
            )
        )
    }
}

private val QuickReactionEmojis = listOf("👍", "❤️", "😂", "🔥", "🎉", "😮", "😢", "👀")

@Composable
internal fun ReactionPickerMenu(
    expanded: Boolean,
    alreadyAdded: (String) -> Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val t = rememberCosmicTokens()
    OrbitMenu(expanded = expanded, onDismissRequest = onDismiss, width = 184.dp) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            QuickReactionEmojis.take(4).forEach { emoji ->
                EmojiChoice(emoji, highlighted = alreadyAdded(emoji)) { onPick(emoji) }
            }
        }
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            QuickReactionEmojis.drop(4).forEach { emoji ->
                EmojiChoice(emoji, highlighted = alreadyAdded(emoji)) { onPick(emoji) }
            }
        }
    }
}

@Composable
private fun EmojiChoice(emoji: String, highlighted: Boolean, onClick: () -> Unit) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) t.AccentGhost else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "emojiBg"
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            fontSize = 18.sp,
            modifier = if (highlighted) Modifier.background(t.AccentGhost, RoundedCornerShape(4.dp)) else Modifier
        )
    }
}

@Composable
internal fun MessageMoreMenu(
    expanded: Boolean,
    canDelete: Boolean,
    messageBody: String,
    isPinned: Boolean,
    pinEnabled: Boolean,
    onTogglePin: () -> Unit = {},
    onDelete: () -> Unit,
    onCopyText: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    OrbitMenu(expanded = expanded, onDismissRequest = onDismiss, width = 196.dp) {
        OrbitMenuItem(
            label = "Copy text",
            onClick = {
                clipboard.setText(AnnotatedString(messageBody))
                onCopyText()
            }
        )
        if (pinEnabled) {
            OrbitMenuItem(
                label = if (isPinned) "Unpin message" else "Pin message",
                icon = ui.icons.OrbitIcons.Pin,
                onClick = onTogglePin
            )
        }
        if (canDelete) {
            OrbitMenuItem(
                label = "Delete message",
                icon = Icons.Default.Delete,
                danger = true,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun MessageEditDialog(
    initialBody: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editBody by remember { mutableStateOf(initialBody) }
    OrbitDialog(
        title = "Edit message",
        icon = Icons.Default.Edit,
        onDismissRequest = onDismiss,
        confirmLabel = "Save",
        onConfirm = { onConfirm(editBody.trim()) },
        confirmEnabled = editBody.isNotBlank(),
        width = 480.dp
    ) {
        OrbitTextField(
            value = editBody,
            onValueChange = { editBody = it },
            placeholder = "Message",
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun MessageActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    // No default: a toolbar button with a defaulted handler is exactly how the
    // grouped-message toolbar ended up shipping three controls that did nothing.
    onClick: () -> Unit
) {
    OrbitIconButton(
        icon = icon,
        contentDescription = desc,
        onClick = onClick,
        size = 28.dp,
        iconSize = 15.dp,
        shape = OrbitRadius.xsShape
    )
}

@Composable
private fun MessageAvatar(message: OrbitMessage) {
    val member = ui.theme.LocalRoomMembers.current.firstOrNull {
        it.id == message.userId || message.userId.endsWith(it.id) || it.id.endsWith(message.userId)
    }
    if (member?.avatarUrl != null) {
        coil3.compose.AsyncImage(
            model = member.avatarUrl,
            contentDescription = member.displayName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
        )
    } else {
        OrbitDefaultAvatar(
            label = member?.displayName ?: message.username,
            seed = message.userId,
            color = member?.avatarColor?.let { Color(it) },
            modifier = Modifier.size(36.dp)
        )
    }
}
