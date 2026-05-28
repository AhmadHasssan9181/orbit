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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.request.get
import io.ktor.client.statement.readBytes
import kotlinx.coroutines.flow.collectLatest
// Remove MockData
import ui.model.*
import ui.components.NativeVideoPlayer
import ui.components.openUrlInBrowser
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

import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.assets.OrbitChatDoodleBackground
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.icons.OrbitIcons
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
    directProfile: OrbitUser? = null,
    typingUsers: List<String> = emptyList(),
    activeCall: repository.CallEvent? = null,
    activeGroupCallUrl: String? = null,
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
                directProfile = directProfile,
                onBack = onBack,
                onVideoCall = { callManager?.startCall(channel.id, true) },
                onVoiceCall = { callManager?.startCall(channel.id, false) }
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)
            
            // Active Call Banner
            (activeCall as? repository.CallEvent.Invite)?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Accent.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Accent.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(OrbitIcons.Call, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Ongoing Call",
                                style = MaterialTheme.typography.titleSmall.copy(color = Text_White, fontWeight = FontWeight.Bold)
                            )
                        }
                        val isMyCall = currentUser?.id == activeCall.senderId
                        if (isMyCall) {
                            Button(
                                onClick = { callManager?.isMinimized?.value = false },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Text("Return to Call", color = Color.White)
                            }
                        } else {
                            Button(
                                onClick = { callManager?.answerCallInvite(activeCall.roomId, activeCall.callId, activeCall.sdp) },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) {
                                Text("Join Call", color = Color.White)
                            }
                        }
                    }
                }
            }
            
            // Group Call Banner
            if (activeGroupCallUrl != null) {
                GroupCallBanner(
                    url = activeGroupCallUrl,
                    roomId = channel.id,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                placeholder = directProfile?.let { "Message @${it.displayName}" } ?: "Message #${channel.name}",
                onSend = onSend,
                onSendAttachment = onSendAttachment,
                onTyping = onTyping
            )
        }
        when {
            directProfile != null && windowSize.isExpanded -> DirectProfilePanel(directProfile)
            ui.theme.LocalShowMemberList.current && windowSize.isExpanded -> MemberListPanel(members = members)
        }
    }
}

// ─── ChatHeader — Discord/Revolt style ───────────────────────────

@Composable
private fun ChatHeader(
    channel: OrbitChannel,
    directProfile: OrbitUser? = null,
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
            if (directProfile != null) {
                SmallUserAvatar(directProfile, size = 28.dp)
            } else if (isLiteMode) {
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
                        imageVector = OrbitIcons.TextChannel,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                    ChannelType.VOICE -> Icon(
                        imageVector = OrbitIcons.VoiceChannel,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Text(
                text = directProfile?.displayName ?: channel.name,
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
            HeaderIconBtn(OrbitIcons.Call, "Voice Call", onClick = { onVoiceCall?.invoke() })
            HeaderIconBtn(OrbitIcons.Video, "Video Call", onClick = { onVideoCall?.invoke() })
            HeaderIconBtn(Icons.Default.Search, "Search")
            HeaderIconBtn(OrbitIcons.Notifications, "Notifications")
            HeaderIconBtn(OrbitIcons.Pin, "Pins")
            HeaderIconBtn(OrbitIcons.Members, "Members")
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
    val reduceMotion = ui.theme.LocalReduceMotion.current

    LaunchedEffect(messages.size) {
        if (grouped.isNotEmpty()) {
            if (reduceMotion) {
                listState.scrollToItem(grouped.size - 1)
            } else {
                listState.animateScrollToItem(grouped.size - 1)
            }
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
        OrbitChatDoodleBackground(
            selectedId = ui.theme.LocalChatWallpaper.current,
            modifier = Modifier.matchParentSize()
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            items(grouped, key = { it.key }) { group ->
                if (ui.theme.LocalCompactMode.current) {
                    when (group) {
                        is MessageGroup.Single -> DenseMessageBody(group.message)
                        is MessageGroup.Dense -> {
                            for (msg in group.messages) DenseMessageBody(msg)
                        }
                    }
                } else {
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
                            if (reduceMotion) {
                                listState.scrollToItem(grouped.size - 1)
                            } else {
                                listState.animateScrollToItem(grouped.size - 1)
                            }
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
private fun MessageActionBtn(
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

// ─── DenseMessageBody — compact follow-up (no avatar) ────────────

@Composable
private fun DenseMessageBody(message: OrbitMessage) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) MessageHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "denseMessageBg"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "denseMessageOffset"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .hoverable(interactionSource)
                .background(rowBg)
                .padding(start = 72.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Show timestamp on hover, like Discord
            if (isHovered && ui.theme.LocalShowTimestamps.current) {
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
        AnimatedVisibility(
            visible = isHovered,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-4).dp, x = (-16).dp),
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f)
        ) {
            Row(
                modifier = Modifier
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
                MessageActionBtn(OrbitIcons.Emoji, "React")
                MessageActionBtn(OrbitIcons.Reply, "Reply")
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
            onState = { state ->
                if (state is coil3.compose.AsyncImagePainter.State.Error) {
                    println("DEBUG: [Coil] Error loading image: ${state.result.throwable.message}")
                    println("DEBUG: [Coil] URL was: $url")
                }
            },
            contentDescription = content.body.ifBlank { "Image" },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        // Download button overlay
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable { openUrlInBrowser(content.url) }
                .padding(6.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(20.dp))
        }
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
    var isPlaying by remember { mutableStateOf(false) }

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
            .heightIn(min = 220.dp)
    ) {
        if (isPlaying) {
            NativeVideoPlayer(url = content.url, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)))
            // Close button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { isPlaying = false }
                    .padding(6.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        } else {
            if (content.thumbnailUrl != null) {
                coil3.compose.AsyncImage(
                    model = content.thumbnailUrl,
                    contentDescription = content.body,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp).background(tokens.CardBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.VideoFile, contentDescription = null, tint = tokens.Text_Muted,
                        modifier = Modifier.size(48.dp))
                }
            }
            // Play overlay
            Box(
                modifier = Modifier.matchParentSize().clickable { isPlaying = true },
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
            // Download button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { openUrlInBrowser(content.url) }
                    .padding(6.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(20.dp))
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
    OrbitDefaultAvatar(
        label = message.username,
        seed = message.userId,
        modifier = Modifier.size(36.dp)
    )
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
    
    val launchPicker = util.rememberFilePickerLauncher { bytes, name, mime ->
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
                    IconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            imageVector = OrbitIcons.Send,
                            contentDescription = "Send",
                            tint = Accent,
                            modifier = Modifier.size(19.dp)
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

// Thin wrapper so we can keep using OutlinedTextField inside input area without outline
@Composable
private fun BasicTextField_Compat(
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
private fun SmallUserAvatar(user: OrbitUser, size: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.size(size)) {
        if (user.avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = user.avatarUrl,
                contentDescription = user.displayName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        } else {
            OrbitDefaultAvatar(
                label = user.displayName,
                seed = user.id,
                color = Color(user.avatarColor),
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 14.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, Members_Bg, CircleShape)
                .clip(CircleShape)
                .background(memberStatusColor(user.status))
        )
    }
}

@Composable
private fun DirectProfilePanel(user: OrbitUser) {
    Column(
        modifier = Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(user.avatarColor).copy(alpha = 0.95f),
                            Accent.copy(alpha = 0.85f),
                            Color(0xFF36D7D7)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ProfileCircleAction(Icons.Default.PersonAdd, "Add Friend")
                ProfileCircleAction(Icons.Default.MoreHoriz, "More")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Box(modifier = Modifier.height(54.dp).fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .offset(y = (-44).dp)
                        .size(88.dp)
                        .border(7.dp, Members_Bg, CircleShape)
                ) {
                    SmallUserAvatar(user, size = 88.dp)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Text_White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 22.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                    Spacer(Modifier.width(6.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(17.dp))
                }
            }

            Text(
                text = user.username.ifBlank { user.id },
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Secondary, fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(18.dp))
            ProfileInfoCard("Status", statusText(user.status))
            Spacer(Modifier.height(10.dp))
            ProfileInfoCard("Member Since", "Matrix direct message")
            Spacer(Modifier.height(10.dp))
            ProfileLinkRow("Mutual Servers")
            ProfileLinkRow("Mutual Friends")

            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {},
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text("View Full Profile", color = Text_White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ProfileCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable {},
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProfileInfoCard(title: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, color = Text_Secondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(value, color = Text_White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ProfileLinkRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .clickable {}
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Text_Secondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Text_Muted, modifier = Modifier.size(20.dp))
    }
}

private fun statusText(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}

@Composable
private fun MemberListPanel(members: List<OrbitUser>) {
    val allUsers  = members
    val online    = allUsers.filter { it.status == UserStatus.ONLINE }
    val idle      = allUsers.filter { it.status == UserStatus.IDLE }
    val dnd       = allUsers.filter { it.status == UserStatus.DO_NOT_DISTURB }
    val offline   = allUsers.filter { it.status == UserStatus.OFFLINE }

    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
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
            fontSize = 10.sp,
            letterSpacing = 0.5.sp
        ),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
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
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar + status dot
        Box(modifier = Modifier.size(32.dp)) {
            if (user.avatarUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                        .padding(1.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    coil3.compose.AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = user.displayName,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                OrbitDefaultAvatar(
                    label = user.displayName,
                    seed = user.id,
                    color = Color(user.avatarColor),
                    modifier = Modifier.fillMaxSize()
                )
            }
            val dotColor = memberStatusColor(user.status)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .border(2.dp, Members_Bg, CircleShape)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (user.status == UserStatus.OFFLINE)
                            Text_Muted.copy(alpha = 0.6f) else Text_Secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                    Spacer(Modifier.width(4.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            MemberRolePill(label = "Member", color = Color(user.avatarColor))
        }
    }
}

@Composable
private fun MemberRolePill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        OrbitChatDoodleBackground(
            selectedId = ui.theme.LocalChatWallpaper.current,
            modifier = Modifier.matchParentSize(),
            intensity = 0.78f
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.Chat,
                modifier = Modifier.width(260.dp).height(162.dp)
            )

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
                            imageVector = OrbitIcons.TextChannel,
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        OrbitChatDoodleBackground(
            selectedId = ui.theme.LocalChatWallpaper.current,
            modifier = Modifier.matchParentSize(),
            intensity = 0.78f
        )
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
                        imageVector = OrbitIcons.VoiceChannel,
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
                    Icon(OrbitIcons.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Join Voice")
                }
                
                Button(
                    onClick = { callManager?.startCall(channel.id, isVideo = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(OrbitIcons.Video, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Video Call")
                }
            }
        }
    }
}

// ─── Username color (HSL, vivid) ─────────────────────────────────

@Composable
private fun resolveUsernameColor(message: OrbitMessage): Color {
    val showRoleColors = ui.theme.LocalShowRoleColors.current
    val fallbackColor = Text_White
    return remember(message.userId, showRoleColors) { 
        if (showRoleColors) hslToColor(Math.abs(message.userId.hashCode()).toFloat() % 360f) 
        else fallbackColor
    }
}

// ─── Timestamp ───────────────────────────────────────────────────

@Composable
private fun formatTimestamp(ts: Instant): String {
    val use24Hour = ui.theme.LocalUse24HourClock.current
    val dt    = ts.toLocalDateTime(TimeZone.currentSystemDefault())
    
    val timeStr = if (use24Hour) {
        val h24 = dt.hour.toString().padStart(2, '0')
        val min = dt.minute.toString().padStart(2, '0')
        "$h24:$min"
    } else {
        val amPm  = if (dt.hour >= 12) "PM" else "AM"
        val h12   = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val min   = dt.minute.toString().padStart(2, '0')
        "$h12:$min $amPm"
    }
    return "Today at $timeStr"
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
private fun WhatsAppInputBar(
    placeholder: String, 
    onSend: (String) -> Unit, 
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {}
) {
    var text by remember { mutableStateOf("") }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showGifPicker by remember { mutableStateOf(false) }
    
    val launchPicker = util.rememberFilePickerLauncher { bytes, name, mime ->
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

private val commonHttpClient = io.ktor.client.HttpClient()

@Composable
fun EmojiPicker(onEmojiSelected: (String) -> Unit) {
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
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(40.dp),
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


@Composable
fun GroupCallBanner(url: String, roomId: String, modifier: Modifier = Modifier) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val callManager = webrtc.LocalCallManager.current
    Row(modifier = modifier.background(Accent.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).border(1.dp, Accent.copy(alpha=0.3f), RoundedCornerShape(12.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Active Group Call", color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Click to join the ongoing group call.", color = Text_Muted, fontSize = 13.sp)
        }
        Button(
            onClick = { 
                if (url.startsWith("matrix-call://")) {
                    callManager?.startCall(roomId, true)
                } else {
                    uriHandler.openUri(url)
                }
            }, 
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Join Call")
        }
    }
}
