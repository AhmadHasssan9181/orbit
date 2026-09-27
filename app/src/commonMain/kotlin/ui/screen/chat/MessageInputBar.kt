package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import ui.screen.media.GifPicker
import ui.components.OrbitIconButton
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import util.rememberFilePickerLauncher

/**
 * The composer.
 *
 * The old bar wrapped a Material `OutlinedTextField` (56dp minimum, its own
 * gutter and label spacing) inside a 40dp rounded row, so the field's padding
 * pushed the text baseline out of line with the buttons on either side of it.
 * This is a borderless field inside a frame that grows when focused, with the
 * attach/emoji/GIF controls revealed on hover and the send button swapping in
 * with a width animation so the row never jumps.
 */
@Composable
internal fun MessageInputBar(
    placeholder: String,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {},
    onCancelReply: (() -> Unit)? = null,
    replyPreview: ReplyPreview? = null
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppInputBar(placeholder, onSend, onSendAttachment, onTyping)
        return
    }

    val t = rememberCosmicTokens()
    var text by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onTyping(true)
            kotlinx.coroutines.delay(5000)
            onTyping(false)
        } else {
            onTyping(false)
        }
    }

    val launchPicker = rememberFilePickerLauncher { bytes, name, mime ->
        onSendAttachment?.invoke(bytes, name, mime)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.md)
    ) {
        // ── Reply banner ────────────────────────────────────────
        AnimatedVisibility(
            visible = replyPreview != null,
            enter = fadeIn(OrbitMotion.tweenFast()) + expandVertically(androidx.compose.animation.core.tween(160)),
            exit = fadeOut(OrbitMotion.tweenFast()) + shrinkVertically(androidx.compose.animation.core.tween(120))
        ) {
            replyPreview?.let { preview ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OrbitRadius.mdShape)
                        .background(t.CardBg)
                        .padding(OrbitSpace.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .clip(CircleShape)
                            .background(t.Accent)
                    )
                    Spacer(Modifier.width(OrbitSpace.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Replying to ${preview.author}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = t.Accent,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            maxLines = 1
                        )
                        Text(
                            text = preview.body,
                            style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.sp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    if (onCancelReply != null) {
                        OrbitIconButton(
                            icon = Icons.Default.Close,
                            contentDescription = "Cancel reply",
                            onClick = onCancelReply,
                            size = 26.dp,
                            iconSize = 14.dp,
                            shape = CircleShape
                        )
                    }
                }
                Spacer(Modifier.height(OrbitSpace.xs))
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
            canSend = text.isNotBlank()
        )
    }
}

/** The bordered container the text field and its controls sit inside. */
@Composable
private fun ComposerFrame(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onToggleEmoji: () -> Unit,
    onToggleGif: () -> Unit,
    canSend: Boolean
) {
    val t = rememberCosmicTokens()
    val focusSource = remember { MutableInteractionSource() }
    val focused by focusSource.collectIsFocusedAsState()
    val enterSend = ui.theme.LocalEnterSend.current

    val borderColor by animateDpAsState(
        targetValue = if (focused) 1.5.dp else 1.dp,
        animationSpec = OrbitMotion.SnappyDp,
        label = "composerFrame"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitRadius.mdShape)
            .background(t.Input_Bg)
            .border(borderColor, if (focused) t.Accent else t.Separator, OrbitRadius.mdShape)
            .padding(start = OrbitSpace.xs, end = OrbitSpace.xs, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OrbitIconButton(
            icon = OrbitIcons.Add,
            contentDescription = "Attach a file",
            onClick = onAttach,
            tooltip = "Attach",
            size = 32.dp,
            iconSize = 18.dp,
            shape = OrbitRadius.smShape
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 32.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (text.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Subtle, fontSize = 14.sp),
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
                    color = t.Text_White,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                ),
                cursorBrush = SolidColor(t.Accent),
                singleLine = false,
                maxLines = 8,
                interactionSource = focusSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
        }

        OrbitIconButton(
            icon = OrbitIcons.Emoji,
            contentDescription = "Emoji",
            onClick = onToggleEmoji,
            tooltip = "Emoji",
            size = 32.dp,
            iconSize = 18.dp,
            shape = OrbitRadius.smShape,
            active = showEmojiActive()
        )
        OrbitIconButton(
            icon = OrbitIcons.Gif,
            contentDescription = "GIF",
            onClick = onToggleGif,
            tooltip = "GIF",
            size = 32.dp,
            iconSize = 18.dp,
            shape = OrbitRadius.smShape
        )

        // Send swaps in with a width animation, so the row never reflows.
        AnimatedVisibility(
            visible = canSend,
            enter = fadeIn(OrbitMotion.tweenFast()) + androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(160)),
            exit = fadeOut(OrbitMotion.tweenFast()) + androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(120))
        ) {
            OrbitIconButton(
                icon = OrbitIcons.Send,
                contentDescription = "Send message",
                onClick = onSend,
                size = 32.dp,
                iconSize = 17.dp,
                shape = OrbitRadius.smShape,
                accent = t.Accent
            )
        }
    }
}

@Composable
private fun showEmojiActive(): Boolean = false

/** What the composer shows above itself when replying. */
data class ReplyPreview(val author: String, val body: String)

@Composable
private fun WhatsAppInputBar(
    placeholder: String,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {}
) {
    val t = rememberCosmicTokens()
    var text by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }

    val launchPicker = rememberFilePickerLauncher { bytes, name, mime ->
        onSendAttachment?.invoke(bytes, name, mime)
    }

    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onTyping(true)
            kotlinx.coroutines.delay(5000)
            onTyping(false)
        } else {
            onTyping(false)
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.sm)) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(OrbitRadius.pillShape)
                    .background(t.CardBg)
                    .padding(horizontal = OrbitSpace.sm, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrbitIconButton(
                    icon = OrbitIcons.Emoji,
                    contentDescription = "Emoji",
                    onClick = {
                        showEmojiPicker = !showEmojiPicker
                        showGifPicker = false
                    },
                    size = 30.dp,
                    iconSize = 17.dp,
                    shape = CircleShape
                )
                Spacer(Modifier.width(OrbitSpace.xs))
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 30.dp)
                        .onKeyEvent { event ->
                            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                                if (text.isNotBlank()) { onSend(text); text = "" }
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.Text_White, fontSize = 14.sp),
                    cursorBrush = SolidColor(t.Accent),
                    singleLine = false,
                    maxLines = 6,
                    decorationBox = { inner ->
                        Box {
                            if (text.isBlank()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Subtle, fontSize = 14.sp)
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(OrbitSpace.xs))
                OrbitIconButton(
                    icon = OrbitIcons.Add,
                    contentDescription = "Attach a file",
                    onClick = { launchPicker() },
                    size = 30.dp,
                    iconSize = 17.dp,
                    shape = CircleShape
                )
            }
            Spacer(Modifier.width(OrbitSpace.sm))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (text.isBlank()) t.CardBgHover else t.Accent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (text.isNotBlank()) { onSend(text); text = "" }
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (text.isBlank()) OrbitIcons.Mic else OrbitIcons.Send,
                    contentDescription = null,
                    tint = if (text.isBlank()) t.Text_Secondary else ui.components.onColorFor(t.Accent),
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

private val commonHttpClient = HttpClient()

@Composable
internal fun EmojiPicker(onEmojiSelected: (String) -> Unit) {
    val t = rememberCosmicTokens()
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😗",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😒", "😔", "😟", "🙁",
        "😣", "😖", "😫", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "😱", "😨", "😰",
        "🤗", "🤔", "🤭", "🤫", "😶", "😐", "😑", "😬", "🙄", "😮", "😲", "🥱", "😴", "😵", "🤐", "🥴",
        "🤢", "🤮", "🤧", "😷", "🤒", "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(OrbitRadius.mdShape)
            .background(t.Elevated)
            .border(1.dp, t.Separator, OrbitRadius.mdShape)
            .padding(OrbitSpace.xs)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(38.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(emojis.size) { index ->
                val emoji = emojis[index]
                EmojiGridCell(emoji) { onEmojiSelected(emoji) }
            }
        }
    }
}

@Composable
private fun EmojiGridCell(emoji: String, onClick: () -> Unit) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(OrbitRadius.xsShape)
            .background(if (hovered) t.Hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, fontSize = 20.sp)
    }
}
