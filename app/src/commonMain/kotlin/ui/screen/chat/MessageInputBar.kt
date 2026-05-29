package ui.screen.chat


import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import ui.icons.OrbitIcons
import util.rememberFilePickerLauncher

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun MessageInputBar(
    placeholder: String,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {}
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppInputBar(placeholder, onSend, onSendAttachment, onTyping)
        return
    }

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
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (showEmojiPicker) {
            EmojiPicker(onEmojiSelected = { text += it })
            Spacer(Modifier.height(8.dp))
        }
        if (showGifPicker) {
            GifPicker(commonHttpClient = commonHttpClient, onGifSelected = { bytes ->
                showGifPicker = false
                onSendAttachment?.invoke(bytes, "gif.gif", "image/gif")
            })
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Input_Bg)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button — Discord style "+"
            IconButton(onClick = { launchPicker() }, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = OrbitIcons.Add,
                    contentDescription = "Attach",
                    tint = Text_Muted,
                    modifier = Modifier.size(19.dp)
                )
            }

            BasicTextField_Compat(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                onSend = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                }
            )

            // Right-side icons
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                InputIconBtn(icon = OrbitIcons.Emoji, desc = "Emoji") {
                    showEmojiPicker = !showEmojiPicker
                    showGifPicker = false
                }
                InputIconBtn(icon = OrbitIcons.Gif, desc = "GIF") {
                    showGifPicker = !showGifPicker
                    showEmojiPicker = false
                }

                // Send button — visible only when text is non-blank
                AnimatedVisibility(
                    visible = text.isNotBlank(),
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    SendMessageIconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun SendMessageIconButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Accent.copy(alpha = 0.20f) else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sendButtonBg"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isHovered) 1.12f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sendButtonScale"
    )

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .hoverable(interactionSource),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = OrbitIcons.Send,
            contentDescription = "Send",
            tint = Accent,
            modifier = Modifier.size(19.dp).scale(iconScale)
        )
    }
}

@Composable
internal fun InputIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "inputIconTint"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) AccentGhost.copy(alpha = 0.42f) else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "inputIconBg"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isHovered) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "inputIconScale"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .hoverable(interactionSource),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(19.dp).scale(iconScale)
        )
    }
}

@Composable
internal fun BasicTextField_Compat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSend: (() -> Unit)? = null
) {
    val enterSend = ui.theme.LocalEnterSend.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 36.dp).onKeyEvent { event ->
            if (enterSend && event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                onSend?.invoke()
                true
            } else false
        },
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Send
        ),
        keyboardActions = KeyboardActions(
            onSend = { onSend?.invoke() }
        ),
        placeholder = {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_Muted,
                    fontSize = 14.sp
                )
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = Text_White,
            fontSize = 14.sp
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor   = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedBorderColor      = Color.Transparent,
            unfocusedBorderColor    = Color.Transparent,
            cursorColor             = Accent,
        ),
        maxLines = 8,
        minLines = 1
    )
}

@Composable
private fun WhatsAppInputBar(
    placeholder: String, 
    onSend: (String) -> Unit, 
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {}
) {
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
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (showEmojiPicker) {
            EmojiPicker(onEmojiSelected = { text += it })
            Spacer(Modifier.height(8.dp))
        }
        if (showGifPicker) {
            GifPicker(commonHttpClient = commonHttpClient, onGifSelected = { bytes ->
                showGifPicker = false
                onSendAttachment?.invoke(bytes, "gif.gif", "image/gif")
            })
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(CardBg)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InputIconBtn(OrbitIcons.Emoji, "Emoji") {
                showEmojiPicker = !showEmojiPicker
                showGifPicker = false
            }
            Spacer(Modifier.width(8.dp))
            BasicTextField_Compat(
                value = text,
                onValueChange = { text = it },
                placeholder = "Message",
                modifier = Modifier.weight(1f),
                onSend = {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            InputIconBtn(OrbitIcons.Add, "Attach") {
                launchPicker()
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable {
                    if (text.isNotBlank()) {
                        onSend(text)
                        text = ""
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (text.isBlank()) OrbitIcons.Mic else OrbitIcons.Send,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
}

private val commonHttpClient = HttpClient()

@Composable
internal fun EmojiPicker(onEmojiSelected: (String) -> Unit) {
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩", "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
        "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
        "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
        "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀", "☠️", "👽", "👾"
    )
    
    Surface(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        color = Input_Bg,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            items(emojis.size) { index ->
                val emoji = emojis[index]
                Box(
                    modifier = Modifier.size(40.dp).clickable { onEmojiSelected(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 24.sp)
                }
            }
        }
    }
}

