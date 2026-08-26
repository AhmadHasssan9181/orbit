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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
internal fun MessageBubbleFull(
    message: OrbitMessage,
    currentUser: OrbitUser?,
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        val isCurrentUser = currentUser != null && message.userId == currentUser.id
        WhatsAppMessageBubble(message, isCurrentUser)
        return
    }

    val isOwn = currentUser != null && message.userId == currentUser.id
    val canEdit = isOwn && onEditMessage != null && message.content is ui.model.MessageContent.Text
    val canDelete = isOwn && onDeleteMessage != null

    var showReactionMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
                if (onReact != null) {
                    Box {
                        MessageActionBtn(OrbitIcons.Emoji, "React") { showReactionMenu = true }
                        ReactionPickerMenu(
                            expanded = showReactionMenu,
                            alreadyAdded = { emoji -> currentUser?.id != null && message.reactions[emoji]?.contains(currentUser.id) == true },
                            onPick = { emoji ->
                                showReactionMenu = false
                                if (currentUser == null || message.reactions[emoji]?.contains(currentUser.id) != true) {
                                    onReact(message.id, emoji)
                                }
                            },
                            onDismiss = { showReactionMenu = false }
                        )
                    }
                }
                if (canEdit) {
                    MessageActionBtn(Icons.Default.Edit, "Edit") { showEditDialog = true }
                }
                if (canDelete || !isOwn) {
                    Box {
                        MessageActionBtn(Icons.Default.MoreHoriz, "More") { showMoreMenu = true }
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
        }

        // Edit dialog (own text messages)
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

        // Delete confirmation (own messages)
        if (showDeleteConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete message") },
                text = { Text("This will permanently remove the message for everyone.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showDeleteConfirm = false
                        onDeleteMessage?.invoke(message.id)
                    }) { Text("Delete", color = Color(0xFFF23F42)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel")
                    }
                },
                containerColor = Color(0xFF1E1F22),
                titleContentColor = Color.White,
                textContentColor = Text_Muted
            )
        }
    }
}

private val QuickReactionEmojis = listOf("👍", "❤️", "😂", "🔥", "🎉", "😮", "😢", "👀")

@Composable
private fun ReactionPickerMenu(
    expanded: Boolean,
    alreadyAdded: (String) -> Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1F22)
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            QuickReactionEmojis.take(4).forEach { emoji ->
                androidx.compose.material3.IconButton(onClick = { onPick(emoji) }, modifier = Modifier.size(36.dp)) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            QuickReactionEmojis.drop(4).forEach { emoji ->
                androidx.compose.material3.IconButton(onClick = { onPick(emoji) }, modifier = Modifier.size(36.dp)) {
                    Text(emoji, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun MessageMoreMenu(
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
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1F22)
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Copy Text", color = Color.White, fontSize = 13.sp) },
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(messageBody))
                onCopyText()
            }
        )
        if (pinEnabled) {
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        if (isPinned) "Unpin Message" else "Pin Message",
                        color = Color.White, fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        ui.icons.OrbitIcons.Pin,
                        contentDescription = null,
                        tint = if (isPinned) Accent else Text_Muted,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = onTogglePin
            )
        }
        if (canDelete) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Delete Message", color = Color(0xFFF23F42), fontSize = 13.sp) },
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
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = editBody,
                onValueChange = { editBody = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 6
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onConfirm(editBody.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        containerColor = Color(0xFF1E1F22),
        titleContentColor = Color.White,
        textContentColor = Text_Muted
    )
}

@Composable
internal fun MessageActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit = {}
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
        onClick = onClick,
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
    val member = ui.theme.LocalRoomMembers.current.firstOrNull {
        it.id == message.userId || message.userId.endsWith(it.id) || it.id.endsWith(message.userId)
    }
    if (member?.avatarUrl != null) {
        coil3.compose.AsyncImage(
            model = member.avatarUrl,
            contentDescription = member.displayName,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
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
