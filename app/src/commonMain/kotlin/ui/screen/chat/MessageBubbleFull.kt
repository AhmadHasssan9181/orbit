package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Dots
import ui.icons.Pencil
import ui.icons.Trash
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitButtonTone
import ui.components.OrbitDialog
import ui.components.OrbitMenu
import ui.components.OrbitMenuItem
import ui.components.OrbitTextField
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.OrbitIcons
import ui.model.OrbitMessage
import ui.model.OrbitUser
import ui.theme.LocalShowTimestamps
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptOverlay
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
/**
 * A message.
 *
 * Laid out from the concept's `.message-item`:
 *
 * ```
 * .message-item {
 *   position: relative; display: flex; gap: 16px;
 *   padding: 6px 12px; border-radius: 6px; margin: 0 -12px;
 *   transition: background-color .1s ease;
 * }
 * .message-item:hover { background-color: var(--bg-modifier-hover) }
 * ```
 *
 * Three things are worth calling out, because they are differences rather than
 * restatements.
 *
 * **There is no horizontal travel on hover.** The old version nudged the row 3dp
 * right under the cursor, on the theory that a whisper of movement gives a dense
 * list depth. The concept transitions `background-color` and nothing else, and it
 * is right: a row that moves means the thing under the pointer moves, so a long
 * message slides out from under the caret while you are trying to select it.
 *
 * **The name is painted in the sender's role colour** — see
 * [resolveUsernameColor] — and the timestamp sits *beside* it on the same
 * baseline, 12sp and muted, rather than being pushed to the right margin where it
 * is a column of noise in a room with two participants.
 *
 * **Bold inside the body is a different colour**, `--header-primary`, not just a
 * heavier weight. That lives in `RichTextPalette.strong`.
 *
 * The reading column is still capped: the concept has no cap because it is a
 * fixed-width mockup, and an unbounded measure on a 2560px window is a real
 * regression rather than a fidelity gain.
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
    val light = isLightChatSurface(t)
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
        // `--bg-modifier-hover` (white 6%) / rgba(0,0,0,.04) in the light theme,
        // which is OrbitConceptOverlay.hover() / hoverLight().
        targetValue = if (hovered) {
            if (light) OrbitConceptOverlay.hoverLight() else OrbitConceptOverlay.hover()
        } else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "messageRowBg"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // The hover source is on THIS box, not on the row below it.
            //
            // The toolbar used to hang off the row's hover state while being drawn
            // above the row's top edge. Those two facts are a trap: the instant
            // the pointer crossed from the row toward the toolbar it left the
            // hoverable area, `hovered` went false, and the toolbar began fading --
            // so it was reliably gone before a click could land on it. Visible on
            // hover, unclickable. The toolbar is drawn inside this Box now, so
            // hovering it keeps the whole thing alive.
            .hoverable(interaction)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(messageRowRadius)
                .background(rowBg)
                // `padding: 6px 12px`. The `margin: 0 -12px` half of that rule is
                // the list's own content inset — see messageStreamInset.
                .padding(
                    start = messageRowInset,
                    end = messageRowInset,
                    top = messageRowPaddingV,
                    bottom = messageRowPaddingV
                ),
            verticalAlignment = Alignment.Top
        ) {
            MessageAvatar(message)
            Spacer(Modifier.width(messageRowGap))
            // The gutter this produces must equal `messageTextGutter`, or grouped
            // follow-on lines stop lining up with the first line of the run. Both
            // sides read the same constant now, so they cannot drift again.

            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = ui.theme.OrbitMetrics.maxMessageMeasure)
            ) {
                // ── Identity line ───────────────────────────────
                // `.message-header { display: flex; align-items: baseline;
                // gap: 8px; margin-bottom: 4px }`
                //
                // `Alignment.Bottom` stands in for `align-items: baseline`, which a
                // `Row`'s `verticalAlignment` cannot express — it takes an
                // `Alignment.Vertical` and has no baseline member. Bottom is the
                // closest of the three for a 15sp name beside a 10sp badge and a
                // 12sp timestamp; top would pin the badge to the cap height and
                // centre would float all three.
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                ) {
                    Text(
                        text = message.username,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            // `.message-author { font-size: 15px; font-weight: 600 }`
                            fontWeight = FontWeight.SemiBold,
                            color = resolveUsernameColor(message),
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            // `.message-author:hover { text-decoration: underline }`.
                            //
                            // Driven by the ROW's hover rather than the name's own,
                            // because the name is not a separate hover target — the
                            // row is. That is a superset of the rule (any point over
                            // the row underlines the name) and it is the only shape
                            // it can take here.
                            textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None
                        ),
                        maxLines = 1
                    )

                    if (isOrbitVerifiedUser(message.username, message.userId)) {
                        OrbitVerifiedBadge(modifier = Modifier.size(14.dp))
                    }

                    if (message.username.contains("bot", ignoreCase = true)) {
                        BotBadge()
                    }

                    if (ui.theme.LocalShowTimestamps.current) {
                        // `.message-timestamp { font-size: 12px; color: var(--text-muted);
                        // font-weight: 400 }` — inline, on the same baseline as the
                        // name, which is the difference the concept makes and the
                        // old right-aligned column did not.
                        Text(
                            text = formatTimestampCompact(message.timestamp),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Muted,
                                fontWeight = FontWeight.Normal,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            maxLines = 1
                        )
                    }
                }

                Spacer(Modifier.height(OrbitSpace.xs))

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
                    ReactionChips(
                        reactions = message.reactions,
                        currentUserId = currentUser?.id,
                        // `sendReaction` is already a toggle -- it sends an
                        // annotation with an empty key when you are in the list,
                        // which is how Matrix removes one -- so this is the same
                        // callback the hover toolbar already used, and a tap on a
                        // pill you are in takes your reaction back off.
                        onToggle = onReact?.let { toggle -> { emoji -> toggle(message.id, emoji) } }
                    )
                }
            }
        }

        // ── Floating hover toolbar ──────────────────────────────
        AnimatedVisibility(
            visible = hovered,
            // INSIDE the parent's bounds. The `-6dp` y offset this replaces put
            // the toolbar in the gap between two messages, so reaching for it
            // meant leaving the hoverable area -- and it faded out on the way. A
            // small inset reads the same and is reachable. `right: 16px` from the
            // `.message-item` edge, which is what the -16dp is.
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = 4.dp, x = (-16).dp),
            enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                animationSpec = OrbitMotion.tweenFast(),
                initialScale = 0.94f
            ),
            exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                animationSpec = OrbitMotion.tweenFast(),
                targetScale = 0.96f
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
                    MessageActionBtn(with(TablerIcons) { Pencil }, "Edit") { showEditDialog = true }
                }
                if (canDelete || !isOwn) {
                    MessageActionBtn(with(TablerIcons) { Dots }, "More") { showMoreMenu = true }
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
                icon = with(TablerIcons) { Trash },
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

/**
 * `.message-actions-toolbar` — the hover action bar's frame.
 *
 * ```
 * background-color: var(--bg-chat); border: 1px solid var(--border-subtle);
 * box-shadow: var(--elevation-high); border-radius: 6px; padding: 2px;
 * ```
 *
 * `--elevation-high` is `0 8px 16px rgba(0, 0, 0, 0.24)`, light
 * `0 8px 16px rgba(0, 0, 0, 0.1)`. Compose's `Modifier.shadow` takes a single
 * elevation and derives both the ambient and the spot tint from it, so the alpha
 * is passed explicitly to land on the concept's number instead of Material's.
 *
 * Both message rows share this, which is the point: a grouped message and the
 * message above it used to get two different toolbars, and the second one was
 * three dead buttons.
 */
@Composable
internal fun MessageToolbarFrame(
    light: Boolean,
    content: @Composable RowScope.() -> Unit
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(6.dp),
                clip = false,
                ambientColor = if (light) ChatConcept.shadowHighLight else ChatConcept.shadowHigh,
                spotColor = if (light) ChatConcept.shadowHighLight else ChatConcept.shadowHigh
            )
            .clip(RoundedCornerShape(6.dp))
            .background(t.Bg)
            .border(
                1.dp,
                if (light) ChatConcept.pillBorderLight else OrbitConceptBorder.subtle(),
                RoundedCornerShape(6.dp)
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * `.message-role-tag` — the role badge beside a display name.
 *
 * ```
 * font-size: 10px; font-weight: 700; background-color: var(--brand-blurple);
 * color: #fff; padding: 1px 4px; border-radius: 3px; text-transform: uppercase;
 * ```
 *
 * It was 8.5sp with 0.5sp of letter-spacing, which is a different badge: the
 * letter-spacing is not in the stylesheet, and at 8.5sp the word "BOT" was a
 * smudge next to a 15sp name rather than a tag on it.
 */
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
                fontSize = 10.sp,
                letterSpacing = 0.sp,
                lineHeight = 13.sp
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
                icon = with(TablerIcons) { Trash },
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
        icon = with(TablerIcons) { Pencil },
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

/**
 * `.msg-action-btn` — one button in a message's hover toolbar.
 *
 * ```
 * background: transparent; border: none; color: var(--interactive-normal);
 * padding: 6px; border-radius: 4px; cursor: pointer;
 * display: flex; align-items: center; justify-content: center;
 * ```
 * ```
 * .msg-action-btn:hover { background-color: var(--bg-modifier-hover);
 *                         color: var(--interactive-hover) }
 * ```
 *
 * This was the shared `OrbitIconButton`, whose resting tint is
 * `--text-muted`. `--interactive-normal` is lighter than that, and it is the
 * value the concept draws every quiet icon control in at, so the difference
 * between "an icon in a toolbar" and "an icon in the app chrome" survives.
 *
 * No default `onClick`: a toolbar button with a defaulted handler is exactly how
 * the grouped-message toolbar ended up shipping three controls that did nothing.
 */
@Composable
internal fun MessageActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val shape = OrbitRadius.xsShape
    val bg by animateColorAsState(
        targetValue = if (hovered) {
            if (light) OrbitConceptOverlay.hoverLight() else OrbitConceptOverlay.hover()
        } else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "msgActionBg"
    )
    val tint = when {
        hovered -> t.Text_Secondary                    // --interactive-hover
        else -> if (light) ChatConcept.interactiveNormalLight else ChatConcept.interactiveNormal
    }

    Box(
        modifier = Modifier
            .size(28.dp)                                    // 16px icon + 6px padding
            .clip(shape)
            .background(bg)
            .hoverable(interaction.hoverSource)
            .orbitClickable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * `.message-avatar-wrap` — the 40dp round avatar beside a first message.
 *
 * The concept's wrapper is a click target that opens the sender's profile. This
 * one is not: opening a profile from a message row needs a callback threaded from
 * the screen, and the concept's `onOpenUserProfile` has no Orbit equivalent yet.
 * It is left non-interactive rather than given a click that does nothing.
 */
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
                .size(MessageAvatarSize)
                .clip(androidx.compose.foundation.shape.CircleShape)
        )
    } else {
        OrbitDefaultAvatar(
            label = member?.displayName ?: message.username,
            seed = message.userId,
            color = member?.avatarColor?.let { Color(it) },
            modifier = Modifier.size(MessageAvatarSize)
        )
    }
}
