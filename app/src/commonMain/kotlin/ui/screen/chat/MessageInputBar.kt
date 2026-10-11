package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.X
import io.ktor.client.HttpClient
import ui.components.OrbitIconButton
import ui.components.OrbitTooltip
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.icons.OrbitIcons
import ui.screen.media.GifPicker
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptOverlay
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import util.rememberFilePickerLauncher
/**
 * The composer.
 *
 * Rebuilt from the concept's input area:
 *
 * ```
 * .chat-input-area { padding: 0 16px 20px; position: relative; flex-shrink: 0 }
 * .chat-input-box  { background-color: var(--bg-input); border-radius: 8px;
 *                    display: flex; align-items: center; padding: 8px 14px; gap: 12px }
 * .chat-attach-btn{ background: transparent; border: none;
 *                    color: var(--interactive-normal); cursor: pointer;
 *                    display: flex; align-items: center; justify-content: center }
 * .chat-attach-btn:hover { color: var(--interactive-hover) }
 * .chat-input-field{ flex: 1; background: transparent; border: none; outline: none;
 *                    font-size: 15px; color: var(--text-normal) }
 * .chat-input-field::placeholder { color: var(--text-muted) }
 * .chat-input-tools{ display: flex; align-items: center; gap: 8px }
 * .input-tool-btn  { background: transparent; border: none;
 *                    color: var(--interactive-normal); cursor: pointer;
 *                    padding: 4px; border-radius: 4px;
 *                    transition: all .12s ease }
 * .input-tool-btn:hover { color: var(--interactive-active); transform: scale(1.05) }
 * ```
 *
 * The three things that came out of that:
 *
 * **8px of vertical padding and 14px of horizontal**, centred rather than
 * bottom-aligned. The old frame was `Alignment.Bottom` with 4dp of vertical
 * padding, which put the text baseline on the icons' baseline only when the
 * field was exactly one line tall — and grew the wrong way when it was not.
 *
 * **The send button is always there.** It used to animate in with a width
 * expansion, so the row reflowed as you typed and the button you were about to
 * press moved twice before you pressed it. The concept just recolours it:
 * `--brand-blurple` when there is something to send, `--text-muted` when there
 * is not. Same information, no reflow.
 *
 * **A 15px field.** It was 14px, which is a size Orbit picked; 15 is the size the
 * concept's own `.message-text` uses, and the composer sitting a point smaller
 * than the messages above it read as a footnote.
 *
 * The focus ring is gone, because `.chat-input-box` has no `:focus-within` rule
 * — the caret is the affordance, and the accent border was a permanent 1.5dp
 * outline across the bottom of every room.
 */
@Composable
internal fun MessageInputBar(
    // ChatScreen is not recreated on a channel switch, so an unkeyed
    // `remember { "" }` carried a half-typed draft from #general into #random.
    channelId: String,
    placeholder: String,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {},
    onCancelReply: (() -> Unit)? = null,
    replyPreview: ReplyPreview? = null,
    /**
     * Text to put back into the field, set by the parent after a send failed.
     *
     * The field clears itself the instant `onSend` fires, because `onSend`
     * launches a coroutine and cannot report back synchronously. Before this
     * parameter existed, a failed send meant the typed message was gone with no
     * copy anywhere on the machine.
     *
     * This is a value the parent *pushes*, not state the child owns, so the
     * composer never has to know whether a send is in flight. Restoring is
     * one-shot: the parent must clear it via [onDraftRestored], otherwise the
     * `LaunchedEffect` would re-run and clobber whatever the user has typed
     * since.
     */
    draftToRestore: String? = null,
    onDraftRestored: () -> Unit = {}
) {
    val t = rememberCosmicTokens()
    var text by remember(channelId) { mutableStateOf("") }
    var showEmojiPicker by remember(channelId) { mutableStateOf(false) }
    var showGifPicker by remember(channelId) { mutableStateOf(false) }

    LaunchedEffect(draftToRestore) {
        val restored = draftToRestore
        if (!restored.isNullOrEmpty()) {
            text = restored
            onDraftRestored()
        }
    }

    TypingNotifier(text = text, onTyping = onTyping)

    val launchPicker = rememberFilePickerLauncher { bytes, name, mime ->
        onSendAttachment?.invoke(bytes, name, mime)
    }

    val light = isLightChatSurface(t)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // .chat-input-area { padding: 0 16px 20px }
            .padding(start = OrbitSpace.lg, end = OrbitSpace.lg, bottom = 20.dp)
    ) {
        // ── Reply banner ────────────────────────────────────────
        // .reply-banner { background-color: var(--bg-secondary);
        //   border-top-left-radius: 8px; border-top-right-radius: 8px;
        //   padding: 8px 12px; display: flex; align-items: center;
        //   justify-content: space-between; font-size: 13px; color: var(--text-muted) }
        //
        // Only the TOP corners are rounded, because the banner sits directly on top
        // of the composer and the two shapes read as one object. The previous
        // version rounded all four and had an accent bar down the left edge, which
        // is a different component.
        AnimatedVisibility(
            visible = replyPreview != null,
            enter = fadeIn(OrbitMotion.tweenFast()) + expandVertically(androidx.compose.animation.core.tween(160)),
            exit = fadeOut(OrbitMotion.tweenFast()) + shrinkVertically(androidx.compose.animation.core.tween(120))
        ) {
            replyPreview?.let { preview ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(
                            RoundedCornerShape(
                                topStart = 8.dp,
                                topEnd = 8.dp,
                                bottomStart = 0.dp,
                                bottomEnd = 0.dp
                            )
                        )
                        .background(
                            if (light) ChatConcept.pillBorderLight else ChatConcept.secondary
                        )
                        // padding: 8px 12px, with 4dp taken back off the end so the
                        // close button's 24dp target sits flush rather than 12dp in.
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildAnnotatedString {
                            append("Replying to ")
                            withStyle(
                                androidx.compose.ui.text.SpanStyle(
                                    color = if (light) ChatConcept.headerPrimaryLight else t.Text_White,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            ) { append(preview.author) }
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            color = t.Text_Muted
                        ),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (onCancelReply != null) {
                        OrbitIconButton(
                            icon = with(TablerIcons) { X },
                            contentDescription = "Cancel reply",
                            onClick = onCancelReply,
                            size = 24.dp,
                            iconSize = 14.dp,
                            shape = CircleShape
                        )
                    }
                }
            }
        }

        if (showEmojiPicker) {
            EmojiPicker(onEmojiSelected = { text += it })
            Spacer(Modifier.height(OrbitSpace.sm))
        }
        if (showGifPicker) {
            GifPicker(commonHttpClient = commonHttpClient, onGifSelected = { bytes ->
                showGifPicker = false
                onSendAttachment?.invoke(bytes, "gif.gif", "image/gif")
            })
            Spacer(Modifier.height(OrbitSpace.sm))
        }

        ComposerFrame(
            text = text,
            onTextChange = { text = it },
            placeholder = placeholder,
            onSend = {
                if (text.isNotBlank()) {
                    onSend(text)
                    text = ""
                }
            },
            onAttach = { launchPicker() },
            onToggleEmoji = {
                showEmojiPicker = !showEmojiPicker
                showGifPicker = false
            },
            onToggleGif = {
                showGifPicker = !showGifPicker
                showEmojiPicker = false
            },
            emojiPickerOpen = showEmojiPicker,
            canSend = text.isNotBlank()
        )
    }
}

/**
 * m.typing is rate-limited by every homeserver, and the previous
 * `LaunchedEffect(text) { onTyping(true); delay(5000) ... }` fired one event per
 * keystroke — a 40-character message sent ~40. Now the first event waits for a
 * short pause in typing, the 5 s expiry is unchanged, and leaving the composer
 * (or the room) always clears the indicator.
 */
@Composable
private fun TypingNotifier(text: String, onTyping: (Boolean) -> Unit) {
    val currentOnTyping by rememberUpdatedState(onTyping)

    DisposableEffect(Unit) {
        onDispose { currentOnTyping(false) }
    }

    LaunchedEffect(text) {
        if (text.isBlank()) {
            currentOnTyping(false)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        currentOnTyping(true)
        kotlinx.coroutines.delay(5000)
        currentOnTyping(false)
    }
}

/**
 * `.chat-input-box` — the frame the text field and its controls sit inside.
 *
 * `gap: 12px` in the stylesheet is expressed as the padding and the control
 * widths rather than as `Arrangement.spacedBy`, because the field between the
 * attach button and the tools is `weight(1f)` and a 12dp gap on both sides of a
 * weighted child is a different number from 12dp between two fixed children.
 * The end padding is 8dp, not 14: the last control carries 4dp of its own
 * padding, so 8 + 4 is the 14 the rule asks for at the row's edge.
 */
@Composable
private fun ComposerFrame(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleEmoji: () -> Unit,
    onToggleGif: () -> Unit,
    emojiPickerOpen: Boolean,
    canSend: Boolean
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    // The field's own interaction source, so the IME action and the Enter-to-send
    // key handler below are the only things that need it — the frame itself has no
    // focus rule, because `.chat-input-box` has no `:focus-within` rule either.
    val focusSource = remember { MutableInteractionSource() }
    val enterSend = ui.theme.LocalEnterSend.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(t.Input_Bg)
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // .chat-attach-btn — no background at rest or on hover; only the tint
        // changes. That is a real difference from `OrbitIconButton`, which floods
        // `--bg-modifier-hover` behind every icon it draws, and it is right here:
        // the composer already has a fill, and a second fill inside it reads as a
        // button sitting on a button.
        ComposerToolBtn(
            icon = OrbitIcons.Add,
            contentDescription = "Attach a file",
            tooltip = "Attach",
            iconSize = 22.dp,
            onClick = onAttach
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 22.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        // .chat-input-field::placeholder { color: var(--text-muted) }
                        color = t.Text_Muted,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onKeyEvent { event ->
                        if (enterSend && event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                            onSend()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    // .chat-input-field { font-size: 15px; color: var(--text-normal) }
                    color = t.Text_Secondary,
                    fontSize = 15.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(t.Accent),
                singleLine = false,
                maxLines = 8,
                interactionSource = focusSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
        }

        // .chat-input-tools { gap: 8px }
        ComposerToolBtn(
            icon = OrbitIcons.Emoji,
            contentDescription = "Emoji",
            tooltip = "Emoji",
            iconSize = 20.dp,
            active = emojiPickerOpen,
            onClick = onToggleEmoji
        )
        ComposerToolBtn(
            icon = OrbitIcons.Gif,
            contentDescription = "GIF",
            tooltip = "GIF",
            iconSize = 20.dp,
            onClick = onToggleGif
        )
        // `<button type="submit" style="color: inputText.trim()
        //     ? 'var(--brand-blurple)' : 'var(--text-muted)'}">` — always present,
        // recoloured. A solid accent disc on a control that is in view at all
        // times would be the loudest thing in the composer, so the tint carries
        // the state and the shape stays quiet.
        ComposerToolBtn(
            icon = OrbitIcons.Send,
            contentDescription = "Send message",
            tooltip = "Send",
            iconSize = 18.dp,
            enabled = canSend,
            // Passing the accent unconditionally and letting `enabled` decide the
            // tint is what makes the two states one control: the button does not
            // change identity when the field goes from empty to full, so a screen
            // reader does not have to re-announce it.
            accent = t.Accent,
            onClick = onSend
        )
    }
}

/**
 * `.input-tool-btn` — one of the composer's icon controls.
 *
 * ```
 * background: transparent; border: none; color: var(--interactive-normal);
 * cursor: pointer; padding: 4px; border-radius: 4px; transition: all .12s ease;
 * ```
 * ```
 * .input-tool-btn:hover { color: var(--interactive-active); transform: scale(1.05) }
 * ```
 *
 * The `scale(1.05)` is in the stylesheet and is the composer's one bit of
 * personality: a control that grows very slightly under the cursor. It is why
 * this is not `OrbitIconButton`, which has no resting fill and no scale — right
 * for a toolbar button, wrong for the thing you are about to press.
 */
@Composable
private fun ComposerToolBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tooltip: String,
    iconSize: Dp,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true,
    accent: Color? = null
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val resting = accent
        ?: if (light) ChatConcept.interactiveNormalLight else ChatConcept.interactiveNormal
    val scale by animateFloatAsState(
        targetValue = if (hovered && enabled) 1.05f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "composerToolScale"
    )
    // The send button's "cannot send" tint is `--text-muted` (the placeholder
    // colour), which is what the concept's inline `style` does; every other tool
    // goes grey at `--interactive-normal` instead, because for them "not
    // available" is not the state — they are simply quiet.
    val tint = when {
        !enabled -> if (accent != null) t.Text_Muted else resting
        hovered -> if (light) ChatConcept.headerPrimaryLight else Color.White  // --interactive-active
        active -> t.Accent
        else -> resting
    }

    val core: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(OrbitRadius.xsShape)
                .scale(scale)
                .hoverable(interaction.hoverSource, enabled)
                .orbitClickable(interaction, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
    OrbitTooltip(tooltip, core)
}

/** What the composer shows above itself when replying. */
data class ReplyPreview(val author: String, val body: String)

/**
 * Shared HTTP client for composer-adjacent fetches (currently the GIF picker).
 *
 * SECURITY: this was `HttpClient()` with **no plugins installed**, which in Ktor
 * means no request, connect or socket timeout. Tenor is a third-party endpoint, so
 * a stalled response left the GIF picker spinning forever with no error and no way
 * out. Timeouts are now explicit; see ui.screen.media.tenorHttpClient for the
 * matching byte caps.
 */
private val commonHttpClient = ui.screen.media.tenorHttpClient()

/**
 * The emoji picker.
 *
 * The concept's is a floating popup — `position: absolute; bottom: 76px;
 * right: 24px; background: var(--bg-floating); border: 1px solid
 * var(--border-subtle); border-radius: 10px; padding: 12px; width: 260px` — with
 * a `POPULAR EMOJIS` caption in 12px/700/uppercase and a fixed six-column grid.
 *
 * It stays inline above the composer rather than floating, because an absolute
 * overlay anchored to the input area has to be dismissed by clicking outside it,
 * which is a second interaction model for what is really just a panel. The
 * values that came across are the ones you can see: 260dp wide, 12dp of padding,
 * a 12px/700/uppercase caption in `--text-muted`, and six columns.
 */
@Composable
internal fun EmojiPicker(onEmojiSelected: (String) -> Unit) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😗",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😒", "😔", "😟", "🙁",
        "😣", "😖", "😫", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "😱", "😨", "😰",
        "🤗", "🤔", "🤭", "🤫", "😶", "😐", "😑", "😬", "🙄", "😮", "😲", "🥱", "😴", "😵", "🤐", "🥴",
        "🤢", "🤮", "🤧", "😷", "🤒", "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️"
    )

    Column(
        modifier = Modifier
            .width(260.dp)
            .height(212.dp)
            // border-radius: 10px; padding: 12px; var(--bg-floating)
            .clip(RoundedCornerShape(10.dp))
            .background(t.Elevated)
            .border(
                1.dp,
                if (light) ChatConcept.borderSubtleLight else OrbitConceptBorder.subtle(),
                RoundedCornerShape(10.dp)
            )
            .padding(12.dp)
    ) {
        Text(
            text = "POPULAR EMOJIS",
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.Text_Muted,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                letterSpacing = 0.sp
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // grid-template-columns: repeat(6, 1fr); gap: 6px
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(emojis.size, key = { emojis[it] }, contentType = { "emoji" }) { index ->
                val emoji = emojis[index]
                EmojiGridCell(emoji) { onEmojiSelected(emoji) }
            }
        }
    }
}

@Composable
private fun EmojiGridCell(emoji: String, onClick: () -> Unit) {
    val light = isLightChatSurface(rememberCosmicTokens())
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // A 22px glyph in 4px of padding inside a 6px radius, per the concept's
    // inline `style` on the quick-pick buttons.
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (hovered) {
                    if (light) OrbitConceptOverlay.hoverLight() else OrbitConceptOverlay.hover()
                } else Color.Transparent
            )
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = emoji,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 22.sp)
    }
}
