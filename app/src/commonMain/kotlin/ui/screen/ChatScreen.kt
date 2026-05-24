package ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Remove MockData
import ui.model.*
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

import ui.theme.LocalIsLiteMode
import ui.theme.rememberCosmicTokens
import ui.layout.LocalWindowSizeClass
import ui.layout.WindowSizeClass

// ─── Cosmic Design Tokens ───────────────────────────────────────
private val Chat_Bg: Color @Composable get() = rememberCosmicTokens().Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent
private val AccentLight: Color @Composable get() = rememberCosmicTokens().AccentLight
private val AccentGhost: Color @Composable get() = rememberCosmicTokens().AccentGhost
private val Text_White: Color @Composable get() = rememberCosmicTokens().Text_White
private val Text_Secondary: Color @Composable get() = rememberCosmicTokens().Text_Secondary
private val Text_Muted: Color @Composable get() = rememberCosmicTokens().Text_Muted
private val Separator: Color @Composable get() = rememberCosmicTokens().Separator
private val Header_Bg: Color @Composable get() = rememberCosmicTokens().Header_Bg
private val Input_Bg: Color @Composable get() = rememberCosmicTokens().Input_Bg
private val Members_Bg: Color @Composable get() = rememberCosmicTokens().Members_Bg
private val MessageHover_Bg: Color @Composable get() = rememberCosmicTokens().MessageHover_Bg
private val CardBg: Color @Composable get() = rememberCosmicTokens().CardBg

// ─── ChatScreen ──────────────────────────────────────────────────

@Composable
fun ChatScreen(
    channel: OrbitChannel,
    messages: List<OrbitMessage>,
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    typingUsers: List<String> = emptyList(),
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {},
    onSeen: (String) -> Unit = {},
    showMemberList: Boolean = true,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val windowSize = LocalWindowSizeClass.current
    val callManager = webrtc.LocalCallManager.current

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Chat_Bg)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ChatHeader(
                channel = channel,
                onBack = onBack,
                onVideoCall = { callManager?.startCall(channel.id, true) },
                onVoiceCall = { callManager?.startCall(channel.id, false) }
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)
            Box(modifier = Modifier.weight(1f)) {
                MessageList(
                    messages = messages,
                    currentUser = currentUser,
                    modifier = Modifier.fillMaxSize(),
                    onSeen = onSeen
                )
                
                // Typing Indicator Overlay
                if (typingUsers.isNotEmpty()) {
                    val typingText = when {
                        typingUsers.size == 1 -> "${typingUsers[0]} is typing..."
                        typingUsers.size == 2 -> "${typingUsers[0]} and ${typingUsers[1]} are typing..."
                        else -> "Several people are typing..."
                    }
                    Surface(
                        modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp, start = 16.dp),
                        color = Chat_Bg.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = typingText,
                            style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 11.sp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            MessageInputBar(
                placeholder = "Message #${channel.name}",
                onSend = onSend,
                onSendAttachment = onSendAttachment,
                onTyping = onTyping
            )
        }
        if (showMemberList && windowSize.isExpanded) MemberListPanel(members = members)
    }
}

// ─── ChatHeader — Discord/Revolt style ───────────────────────────

@Composable
private fun ChatHeader(
    channel: OrbitChannel,
    onBack: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null
) {
    val windowSize = LocalWindowSizeClass.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Header_Bg)
            .height(if (windowSize.isCompact) 56.dp else 48.dp)
            .padding(horizontal = if (windowSize.isCompact) 4.dp else 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Back button for compact (phone) and medium (tablet) layouts
            if (onBack != null && !windowSize.isExpanded) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Text_White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            val isLiteMode = ui.theme.LocalIsLiteMode.current
            if (isLiteMode) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(AccentGhost),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
            } else {
                // Channel icon — Discord uses grey hash, Revolt uses coloured
                when (channel.type) {
                    ChannelType.TEXT -> Icon(
                        imageVector = Icons.Default.Tag,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                    ChannelType.VOICE -> Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_White
                ),
                maxLines = 1
            )

            if (channel.topic.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(Text_Muted.copy(alpha = 0.3f))
                )
                Text(
                    text = channel.topic,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Text_Muted,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderIconBtn(Icons.Default.Call, "Voice Call", onClick = { onVoiceCall?.invoke() })
            HeaderIconBtn(Icons.Default.Videocam, "Video Call", onClick = { onVideoCall?.invoke() })
            HeaderIconBtn(Icons.Default.Search, "Search")
            HeaderIconBtn(Icons.Default.Notifications, "Notifications")
            HeaderIconBtn(Icons.Default.PushPin, "Pins")
            HeaderIconBtn(Icons.Default.People, "Members")
        }
    }
}

@Composable
private fun HeaderIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        label = "headerIconTint"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(32.dp),
        interactionSource = interactionSource
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ─── MessageList ─────────────────────────────────────────────────

@Composable
private fun MessageList(
    messages: List<OrbitMessage>,
    currentUser: OrbitUser?,
    modifier: Modifier = Modifier,
    onSeen: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val grouped   = groupMessages(messages)
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (grouped.isNotEmpty()) {
            listState.animateScrollToItem(grouped.size - 1)
        }
    }
    
    // Read Receipt Logic
    LaunchedEffect(listState.firstVisibleItemIndex, messages.size) {
        if (messages.isNotEmpty() && grouped.isNotEmpty()) {
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisibleItemIndex >= grouped.size - 1) {
                onSeen(messages.last().id)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(grouped, key = { it.key }) { group ->
                when (group) {
                    is MessageGroup.Single -> MessageBubbleFull(group.message, currentUser)
                    is MessageGroup.Dense  -> {
                        if (ui.theme.LocalIsLiteMode.current) {
                            for (msg in group.messages) {
                                MessageBubbleFull(msg, currentUser)
                            }
                        } else {
                            MessageBubbleFull(group.messages[0], currentUser)
                            for (i in 1 until group.messages.size) {
                                DenseMessageBody(group.messages[i])
                            }
                        }
                    }
                }
            }
        }

        val showScrollToBottom by remember {
            derivedStateOf { listState.canScrollForward }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollToBottom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        if (grouped.isNotEmpty()) {
                            listState.animateScrollToItem(grouped.size - 1)
                        }
                    }
                },
                containerColor = Accent,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom", modifier = Modifier.size(24.dp))
            }
        }
    }
}

// ─── Message grouping ────────────────────────────────────────────

private sealed interface MessageGroup {
    val key: String
    data class Single(val message: OrbitMessage) : MessageGroup {
        override val key = "s_${message.id}"
    }
    data class Dense(val messages: List<OrbitMessage>) : MessageGroup {
        override val key = "d_${messages.firstOrNull()?.id}"
    }
}

private fun groupMessages(messages: List<OrbitMessage>): List<MessageGroup> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<MessageGroup>()
    var curUserId = messages[0].userId
    var curGroup  = mutableListOf(messages[0])

    for (i in 1 until messages.size) {
        val msg = messages[i]
        val newGroup = msg.userId != curUserId || curGroup.size >= 5
        if (newGroup) {
            result.add(if (curGroup.size == 1) MessageGroup.Single(curGroup[0]) else MessageGroup.Dense(curGroup.toList()))
            curGroup = mutableListOf()
            curUserId = msg.userId
        }
        curGroup.add(msg)
    }
    if (curGroup.isNotEmpty())
        result.add(if (curGroup.size == 1) MessageGroup.Single(curGroup[0]) else MessageGroup.Dense(curGroup.toList()))
    return result
}

// ─── MessageBubbleFull — Discord/Revolt style ────────────────────

@Composable
private fun MessageBubbleFull(message: OrbitMessage, currentUser: OrbitUser?) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        val isCurrentUser = currentUser != null && message.userId == currentUser.id
        WhatsAppMessageBubble(message, isCurrentUser)
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                .background(if (isHovered) MessageHover_Bg else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 2.dp)
                .padding(top = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar — 40dp circle like Discord/Revolt
            MessageAvatar(message)
            Spacer(Modifier.width(16.dp))

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

                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTimestamp(message.timestamp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Text_Muted,
                            fontSize = 10.sp
                        )
                    )
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
        if (isHovered) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-8).dp, x = (-16).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        1.dp,
                        Separator.copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                MessageActionBtn(Icons.Default.EmojiEmotions, "React")
                MessageActionBtn(Icons.Default.Reply, "Reply")
                MessageActionBtn(Icons.Default.Edit, "Edit")
                MessageActionBtn(Icons.Default.MoreHoriz, "More")
            }
        }
    }
}

@Composable
private fun MessageActionBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    IconButton(
        onClick = {},
        modifier = Modifier.size(28.dp).hoverable(interactionSource),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (isHovered) Text_White else Text_Muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ─── DenseMessageBody — compact follow-up (no avatar) ────────────

@Composable
private fun DenseMessageBody(message: OrbitMessage) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                .background(if (isHovered) MessageHover_Bg else Color.Transparent)
                .padding(start = 72.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Show timestamp on hover, like Discord
            if (isHovered) {
                Text(
                    text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontSize = 9.sp
                    ),
                    modifier = Modifier
                        .width(48.dp)
                        .offset(x = (-56).dp)
                )
            }
            MessageContentView(
                content = message.content,
                isCompact = true
            )
        }

        // Floating action toolbar on hover
        if (isHovered) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-4).dp, x = (-16).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        1.dp,
                        Separator.copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                MessageActionBtn(Icons.Default.EmojiEmotions, "React")
                MessageActionBtn(Icons.Default.Reply, "Reply")
                MessageActionBtn(Icons.Default.MoreHoriz, "More")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Rich message content renderer
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun MessageContentView(
    content: ui.model.MessageContent,
    isCompact: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (content) {
            is ui.model.MessageContent.Text -> TextMessageBody(content, isCompact)
            is ui.model.MessageContent.Image -> ImageMessageCard(content)
            is ui.model.MessageContent.Audio -> AudioMessageCard(content)
            is ui.model.MessageContent.Video -> VideoMessageCard(content)
            is ui.model.MessageContent.File -> FileMessageCard(content)
            is ui.model.MessageContent.Notice -> NoticeBody(content)
            is ui.model.MessageContent.Emote -> EmoteBody(content)
            is ui.model.MessageContent.Sticker -> StickerCard(content)
            is ui.model.MessageContent.Reply -> {
                ReplyQuoteBlock(content)
                MessageContentView(content.actualContent, isCompact)
            }
            is ui.model.MessageContent.Edit -> {
                MessageContentView(content.newContent, isCompact)
            }
            is ui.model.MessageContent.Reaction -> { /* Reactions are aggregated, not rendered as bubbles */ }
            is ui.model.MessageContent.Redacted -> RedactedBody()
            is ui.model.MessageContent.Unsupported -> UnsupportedBody(content)
        }
    }
}

@Composable
private fun TextMessageBody(content: ui.model.MessageContent.Text, isCompact: Boolean) {
    // Basic inline annotation — bold **text**, italic _text_, `code`
    val annotated = remember(content.body) { buildAnnotatedMessageText(content.body) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Secondary,
            fontSize = if (isCompact) 14.sp else 14.sp,
            lineHeight = 20.sp
        )
    )
}

private fun buildAnnotatedMessageText(body: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString { append(body) }
}

@Composable
private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val url = content.thumbnailUrl ?: content.url
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {
        coil3.compose.AsyncImage(
            model = url,
            contentDescription = content.body.ifBlank { "Image" },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    }
    if (content.body.isNotBlank()) {
        Text(
            text = content.body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
    }
}

@Composable
private fun AudioMessageCard(content: ui.model.MessageContent.Audio) {
    val tokens = rememberCosmicTokens()
    
    val player = remember { util.AudioPlayer() }
    val isPlaying by player.isPlaying.collectAsState()
    val progress by player.progress.collectAsState()

    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val durationText = remember(content.durationMs) {
        if (content.durationMs != null) {
            val secs = content.durationMs / 1000
            "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        } else "—:——"
    }
    // Animated waveform bars
    val waveform = content.waveform?.take(32) ?: (1..32).map { (30 + (it * 13) % 80) }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.CardBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(min = 200.dp, max = 300.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Play/Pause button
        IconButton(
            onClick = {
                if (isPlaying) player.pause() else player.play(content.url)
            },
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tokens.Accent)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Waveform bars
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val maxAmplitude = waveform.maxOrNull()?.toFloat() ?: 100f
            waveform.forEachIndexed { index, amp ->
                val fraction = (amp / maxAmplitude).coerceIn(0.1f, 1f)
                val isPlayed = (index.toFloat() / waveform.size) <= progress
                val barColor = if (isPlayed) tokens.Accent else tokens.Text_Muted.copy(alpha = 0.6f)
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height((fraction * 24).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(barColor)
                )
            }
        }

        // Duration
        Text(
            text = durationText,
            style = MaterialTheme.typography.labelSmall.copy(
                color = tokens.Text_Muted, fontSize = 11.sp
            )
        )
    }
    if (content.body.isNotBlank()) {
        Text(
            text = content.body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted, fontSize = 11.sp
            )
        )
    }
}

@Composable
private fun VideoMessageCard(content: ui.model.MessageContent.Video) {
    val tokens = rememberCosmicTokens()
    val durationText = remember(content.durationMs) {
        if (content.durationMs != null) {
            val secs = content.durationMs / 1000
            "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        } else null
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {
        if (content.thumbnailUrl != null) {
            coil3.compose.AsyncImage(
                model = content.thumbnailUrl,
                contentDescription = content.body,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().height(120.dp).background(tokens.CardBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null, tint = tokens.Text_Muted,
                    modifier = Modifier.size(48.dp))
            }
        }
        // Play overlay
        Box(
            modifier = Modifier.matchParentSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play video",
                    tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        // Duration chip
        if (durationText != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(durationText, style = MaterialTheme.typography.labelSmall.copy(
                    color = Color.White, fontSize = 10.sp))
            }
        }
    }
    if (content.body.isNotBlank()) {
        Text(content.body, style = MaterialTheme.typography.bodySmall.copy(
            color = Text_Muted, fontSize = 11.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
    }
}

@Composable
private fun FileMessageCard(content: ui.model.MessageContent.File) {
    val tokens = rememberCosmicTokens()
    val sizeText = remember(content.size) {
        content.size?.let { sz ->
            when {
                sz >= 1_048_576 -> "${sz / 1_048_576} MB"
                sz >= 1_024 -> "${sz / 1_024} KB"
                else -> "$sz B"
            }
        }
    }
    val icon = when {
        content.mimeType?.startsWith("image/") == true -> Icons.Default.Image
        content.mimeType?.startsWith("video/") == true -> Icons.Default.VideoFile
        content.mimeType?.startsWith("audio/") == true -> Icons.Default.AudioFile
        content.mimeType?.contains("pdf") == true -> Icons.Default.PictureAsPdf
        content.mimeType?.contains("zip") == true ||
                content.mimeType?.contains("archive") == true -> Icons.Default.FolderZip
        else -> Icons.Default.AttachFile
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .border(1.dp, tokens.Separator.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .widthIn(min = 180.dp, max = 300.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tokens.Accent, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.filename,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (sizeText != null || content.mimeType != null) {
                Text(
                    text = listOfNotNull(sizeText, content.mimeType?.split("/")?.lastOrNull()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Text_Muted, fontSize = 10.sp
                    )
                )
            }
        }
        Icon(Icons.Default.Download, contentDescription = "Download", tint = tokens.Text_Muted,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NoticeBody(content: ui.model.MessageContent.Notice) {
    Text(
        text = content.body,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Muted,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 13.sp
        )
    )
}

@Composable
private fun EmoteBody(content: ui.model.MessageContent.Emote) {
    Text(
        text = "* ${content.body}",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Secondary,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 14.sp
        )
    )
}

@Composable
private fun StickerCard(content: ui.model.MessageContent.Sticker) {
    coil3.compose.AsyncImage(
        model = content.url,
        contentDescription = content.body.ifBlank { "Sticker" },
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .size(128.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ReplyQuoteBlock(content: ui.model.MessageContent.Reply) {
    val tokens = rememberCosmicTokens()
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.Accent.copy(alpha = 0.7f))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            val senderDisplay = if (content.quotedSenderId.isNotBlank())
                content.quotedSenderId.split(":").first().removePrefix("@")
            else "Unknown"
            Text(
                text = senderDisplay,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = tokens.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            )
            Text(
                text = content.quotedBody.ifBlank { "(no preview)" },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_Muted, fontSize = 12.sp
                ),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(reactions: Map<String, List<String>>) {
    val tokens = rememberCosmicTokens()
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        reactions.forEach { (emoji, senderIds) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.Accent.copy(alpha = 0.12f))
                    .border(1.dp, tokens.Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                Text(
                    text = "${senderIds.size}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Accent,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun RedactedBody() {
    Text(
        text = "🗑 This message was deleted",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Muted.copy(alpha = 0.6f),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 13.sp
        )
    )
}

@Composable
private fun UnsupportedBody(content: ui.model.MessageContent.Unsupported) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Text_Muted.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Block, contentDescription = null, tint = Text_Muted,
            modifier = Modifier.size(12.dp))
        Text(
            text = if (content.fallbackBody.isNotBlank()) content.fallbackBody
            else "[Unsupported: ${content.type}]",
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted.copy(alpha = 0.7f), fontSize = 12.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
    }
}

// ─── MessageAvatar ───────────────────────────────────────────────


@Composable
private fun MessageAvatar(message: OrbitMessage) {
    val hue   = remember(message.userId) { Math.abs(message.userId.hashCode()) % 360 }
    val start = remember(hue) { hslToColor(hue.toFloat()) }
    val end   = remember(hue) { hslToColor((hue + 30).toFloat() % 360) }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(start, end))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message.username.take(2).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 13.sp
            )
        )
    }
}

// ─── MessageInputBar — Discord/Revolt style ──────────────────────

@Composable
private fun MessageInputBar(
    placeholder: String,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {}
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppInputBar(placeholder, onSend, onTyping)
        return
    }

    var text by remember { mutableStateOf("") }
    
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onTyping(true)
            kotlinx.coroutines.delay(5000)
            onTyping(false)
        } else {
            onTyping(false)
        }
    }
    
    val launchPicker = util.rememberFilePickerLauncher { bytes, name, mime ->
        onSendAttachment?.invoke(bytes, name, mime)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Input_Bg)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button — Discord style "+"
            IconButton(onClick = { launchPicker() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach",
                    tint = Text_Muted,
                    modifier = Modifier.size(20.dp)
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
                InputIconBtn(icon = Icons.Default.EmojiEmotions, desc = "Emoji") {}
                InputIconBtn(icon = Icons.Default.Gif, desc = "GIF") {}

                // Send button — visible only when text is non-blank
                AnimatedVisibility(
                    visible = text.isNotBlank(),
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally()
                ) {
                    IconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = Accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InputIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        label = "inputIconTint"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp).hoverable(interactionSource),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

// Thin wrapper so we can keep using OutlinedTextField inside input area without outline
@Composable
private fun BasicTextField_Compat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSend: (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 36.dp).onKeyEvent { event ->
            if (event.key == Key.Enter && event.type == KeyEventType.KeyDown && !event.isShiftPressed) {
                onSend?.invoke()
                true
            } else false
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            imeAction = androidx.compose.ui.text.input.ImeAction.Send
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
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

// ─── MemberListPanel — Discord/Revolt style ──────────────────────

@Composable
private fun MemberListPanel(members: List<OrbitUser>) {
    val allUsers  = members
    val online    = allUsers.filter { it.status == UserStatus.ONLINE }
    val idle      = allUsers.filter { it.status == UserStatus.IDLE }
    val dnd       = allUsers.filter { it.status == UserStatus.DO_NOT_DISTURB }
    val offline   = allUsers.filter { it.status == UserStatus.OFFLINE }

    Column(
        modifier = Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (online.isNotEmpty()) {
                item { MemberSectionLabel("ONLINE — ${online.size}") }
                items(online, key = { it.id }) { MemberItem(it) }
            }
            if (idle.isNotEmpty()) {
                item { MemberSectionLabel("IDLE — ${idle.size}") }
                items(idle, key = { it.id }) { MemberItem(it) }
            }
            if (dnd.isNotEmpty()) {
                item { MemberSectionLabel("DO NOT DISTURB") }
                items(dnd, key = { it.id }) { MemberItem(it) }
            }
            if (offline.isNotEmpty()) {
                item { MemberSectionLabel("OFFLINE — ${offline.size}") }
                items(offline, key = { it.id }) { MemberItem(it) }
            }
        }
    }
}

@Composable
private fun MemberSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            color = Text_Muted,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
    )
}

@Composable
private fun MemberItem(user: OrbitUser) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) MessageHover_Bg else Color.Transparent,
        label = "memberHover"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar + status dot
        Box(modifier = Modifier.size(32.dp)) {
            val hue   = remember(user.id) { Math.abs(user.id.hashCode()) % 360 }
            val color = remember(hue) { hslToColor(hue.toFloat()) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
                contentAlignment = Alignment.Center
            ) {
                if (user.avatarUrl != null) {
                    coil3.compose.AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = user.displayName,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = user.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    )
                }
            }
            val dotColor = memberStatusColor(user.status)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .border(2.dp, Members_Bg, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = if (user.status == UserStatus.OFFLINE)
                        Text_Muted.copy(alpha = 0.6f) else Text_Secondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun memberStatusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE         -> Color(0xFF34D399)
    UserStatus.IDLE           -> Color(0xFFFBBF24)
    UserStatus.DO_NOT_DISTURB -> Color(0xFFF87171)
    UserStatus.OFFLINE        -> Color(0xFF3D4160)
}

// ─── ChatEmptyState ──────────────────────────────────────────────

@Composable
fun ChatEmptyState(
    serverName: String,
    channels: List<OrbitChannel>,
    onChannelClick: (String) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Chat_Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Giant hash icon — Discord welcome style
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.3f), Color.Transparent))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(40.dp)
                )
            }

            Text(
                text = "Welcome to $serverName",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Text_White
                )
            )

            Text(
                text = "Pick a channel to start chatting",
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
            )

            Spacer(Modifier.height(4.dp))

            // Channel cards
            val textChannels = channels.filter { it.type == ChannelType.TEXT }
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                textChannels.take(6).forEach { ch ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isHovered) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .hoverable(interactionSource)
                            .clickable { onChannelClick(ch.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tag,
                            contentDescription = null,
                            tint = Text_Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ch.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isHovered) Text_White else Text_Secondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        if (ch.topic.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = ch.topic,
                                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── VoiceChannelPlaceholder ─────────────────────────────────────

@Composable
fun VoiceChannelPlaceholder(channel: OrbitChannel) {
    val callManager = webrtc.LocalCallManager.current
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue  = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Chat_Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                // Pulsing ring
                Box(
                    modifier = Modifier
                        .size((80 * pulseScale).dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = pulseAlpha))
                )
                // Core mic circle
                Box(
                    modifier = Modifier
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Accent, AccentLight.copy(alpha = 0.7f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Text(
                text = channel.name,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Text_White
                )
            )

            Text(
                text = "Voice Channel — connected via Orbit WebRTC ⚡",
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { callManager?.startCall(channel.id, isVideo = false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Join Voice")
                }
                
                Button(
                    onClick = { callManager?.startCall(channel.id, isVideo = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Video Call")
                }
            }
        }
    }
}

// ─── Username color (HSL, vivid) ─────────────────────────────────

@Composable
private fun resolveUsernameColor(message: OrbitMessage): Color =
    remember(message.userId) { hslToColor(Math.abs(message.userId.hashCode()).toFloat() % 360f) }

// ─── Timestamp ───────────────────────────────────────────────────

private fun formatTimestamp(ts: Instant): String {
    val dt    = ts.toLocalDateTime(TimeZone.currentSystemDefault())
    val amPm  = if (dt.hour >= 12) "PM" else "AM"
    val h12   = if (dt.hour % 12 == 0) 12 else dt.hour % 12
    val min   = dt.minute.toString().padStart(2, '0')
    return "Today at $h12:$min $amPm"
}

// ─── HSL helpers ─────────────────────────────────────────────────

private fun hslToColor(hue: Float): Color {
    val s = 0.70f; val l = 0.68f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val r = hueToRgb(p, q, hue / 360f + 1f / 3f).coerceIn(0f, 1f)
    val g = hueToRgb(p, q, hue / 360f).coerceIn(0f, 1f)
    val b = hueToRgb(p, q, hue / 360f - 1f / 3f).coerceIn(0f, 1f)
    return Color(r, g, b)
}

private fun hueToRgb(p: Float, q: Float, t: Float): Float {
    var x = t
    if (x < 0) x += 1f; if (x > 1) x -= 1f
    return when {
        x < 1f / 6f -> p + (q - p) * 6f * x
        x < 0.5f    -> q
        x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
        else        -> p
    }
}

// ─── WhatsApp Style Components ───────────────────────────────────

@Composable
private fun WhatsAppMessageBubble(message: OrbitMessage, isCurrentUser: Boolean) {
    val bubbleColor = if (isCurrentUser) Accent.copy(alpha = 0.15f) else CardBg
    val alignment = if (isCurrentUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(
                    topStart = 8.dp,
                    topEnd = 8.dp,
                    bottomStart = if (isCurrentUser) 8.dp else 0.dp,
                    bottomEnd = if (isCurrentUser) 0.dp else 8.dp
                ))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Text_White,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun WhatsAppInputBar(placeholder: String, onSend: (String) -> Unit, onTyping: (Boolean) -> Unit = {}) {
    var text by remember { mutableStateOf("") }
    
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            onTyping(true)
            kotlinx.coroutines.delay(5000)
            onTyping(false)
        } else {
            onTyping(false)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
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
            InputIconBtn(Icons.Default.EmojiEmotions, "Emoji") {}
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
            InputIconBtn(Icons.Default.AddCircle, "Attach") {}
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
                imageVector = if (text.isBlank()) Icons.Default.Mic else Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}