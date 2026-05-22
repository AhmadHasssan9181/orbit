package ui.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.data.MockData
import ui.model.*
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

import ui.theme.LocalIsLiteMode

// ─── Cosmic Design Tokens ───────────────────────────────────────
private val Chat_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFEAE6DF) else Color(0xFF141720)
private val Header_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFF0F2F5) else Color(0xFF111318)
private val Input_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFFFFFFF) else Color(0xFF1C1F2B)
private val Members_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFF0F2F5) else Color(0xFF0F1117)
private val Accent: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF075E54) else Color(0xFF7C6AF0)
private val AccentLight: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF128C7E) else Color(0xFFA78BFA)
private val AccentGhost: Color @Composable get() = if (LocalIsLiteMode.current) Color(0x1A075E54) else Color(0x1A7C6AF0)
private val Text_White: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF111B21) else Color(0xFFEAECF5)
private val Text_Secondary: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF54656F) else Color(0xFFB4B8D0)
private val Text_Muted: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF667781) else Color(0xFF6B7094)
private val Separator: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFD1D7DB) else Color(0xFF1C1F2B)

// ─── ChatScreen ──────────────────────────────────────────────────

@Composable
fun ChatScreen(
    channel: OrbitChannel,
    messages: List<OrbitMessage>,
    onSend: (String) -> Unit,
    showMemberList: Boolean = true,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Chat_Bg)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ChatHeader(channel)
            // Gradient divider below header
            Box(
                modifier = Modifier.fillMaxWidth().height(1.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Accent.copy(alpha = 0.3f), Color.Transparent)))
            )
            MessageList(messages = messages, modifier = Modifier.weight(1f))
            MessageInputBar(placeholder = "Message #${channel.name}", onSend = onSend)
        }
        if (showMemberList) MemberListPanel()
    }
}

// ─── ChatHeader ──────────────────────────────────────────────────

@Composable
private fun ChatHeader(channel: OrbitChannel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Header_Bg)
            .height(52.dp)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Channel type badge or WhatsApp Avatar
            val isLiteMode = ui.theme.LocalIsLiteMode.current
            if (isLiteMode) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFCFD9DF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentGhost),
                    contentAlignment = Alignment.Center
                ) {
                    when (channel.type) {
                        ChannelType.TEXT -> Text(
                            text = "#",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Accent
                            )
                        )
                        ChannelType.VOICE -> Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))
            Text(
                text = channel.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_White,
                    fontSize = 15.sp
                ),
                maxLines = 1
            )

            if (channel.topic.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(Text_Muted.copy(alpha = 0.4f))
                )
                Text(
                    text = channel.topic,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Text_Muted,
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            HeaderIconBtn(Icons.Default.Search, "Search")
            HeaderIconBtn(Icons.Default.Notifications, "Notifications")
            HeaderIconBtn(Icons.Default.People, "Members")
        }
    }
}

@Composable
private fun HeaderIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Accent else Text_Muted,
        label = "headerIconTint"
    )
    IconButton(
        onClick = {},
        modifier = Modifier.size(30.dp),
        interactionSource = interactionSource
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ─── MessageList ─────────────────────────────────────────────────

@Composable
private fun MessageList(messages: List<OrbitMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val grouped   = groupMessages(messages)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(grouped, key = { it.key }) { group ->
            when (group) {
                is MessageGroup.Single -> MessageBubbleFull(group.message)
                is MessageGroup.Dense  -> {
                    if (ui.theme.LocalIsLiteMode.current) {
                        for (msg in group.messages) {
                            MessageBubbleFull(msg)
                        }
                    } else {
                        MessageBubbleFull(group.messages[0])
                        for (i in 1 until group.messages.size) {
                            DenseMessageBody(group.messages[i])
                        }
                    }
                }
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

// ─── MessageBubbleFull ───────────────────────────────────────────

@Composable
private fun MessageBubbleFull(message: OrbitMessage) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        val isCurrentUser = message.userId == MockData.CurrentUser.id
        WhatsAppMessageBubble(message, isCurrentUser)
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0x0AFFFFFF) else Color.Transparent,
        label = "msgHover"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        MessageAvatar(message)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = message.username,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = resolveUsernameColor(message),
                        fontSize = 14.sp
                    )
                )

                if (message.username.contains("bot", ignoreCase = true) ||
                    message.username.contains("Bot", ignoreCase = true)) {
                    Spacer(Modifier.width(5.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Brush.horizontalGradient(listOf(Accent, AccentLight)))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "APP",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
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

            Spacer(Modifier.height(3.dp))
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_Secondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            )
        }
    }
}

// ─── DenseMessageBody ────────────────────────────────────────────

@Composable
private fun DenseMessageBody(message: OrbitMessage) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0x0AFFFFFF) else Color.Transparent,
        label = "denseMsgHover"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(start = 50.dp, end = 0.dp, top = 1.dp, bottom = 1.dp)
    ) {
        Text(
            text = message.body,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = Text_Secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp
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

// ─── MessageInputBar ─────────────────────────────────────────────

@Composable
private fun MessageInputBar(placeholder: String, onSend: (String) -> Unit) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppInputBar(placeholder, onSend)
        return
    }

    var text by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Input_Bg)
                .border(
                    width = 1.dp,
                    brush = Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.3f), AccentLight.copy(alpha = 0.2f))),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attach button
            InputIconBtn(icon = Icons.Default.AddCircle, desc = "Attach") {}

            Spacer(Modifier.width(8.dp))

            BasicTextField_Compat(
                value = text,
                onValueChange = { text = it },
                placeholder = placeholder,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(8.dp))

            // Trailing buttons
            if (text.isBlank()) {
                InputIconBtn(icon = Icons.Default.EmojiEmotions, desc = "Emoji") {}
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "GIF",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    ),
                    modifier = Modifier.clickable {}
                )
            } else {
                // Send button — accent pill
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Accent, AccentLight)))
                        .clickable {
                            onSend(text)
                            text = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
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
        targetValue = if (isHovered) Accent else Text_Muted,
        label = "inputIconTint"
    )
    Icon(
        imageVector = icon,
        contentDescription = desc,
        tint = tint,
        modifier = Modifier
            .size(22.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    )
}

// Thin wrapper so we can keep using OutlinedTextField inside input area without outline
@Composable
private fun BasicTextField_Compat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 36.dp),
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

// ─── MemberListPanel ─────────────────────────────────────────────

@Composable
private fun MemberListPanel() {
    val allUsers  = MockData.MockUsers + MockData.CurrentUser
    val online    = allUsers.filter { it.status == UserStatus.ONLINE }
    val idle      = allUsers.filter { it.status == UserStatus.IDLE }
    val dnd       = allUsers.filter { it.status == UserStatus.DO_NOT_DISTURB }
    val offline   = allUsers.filter { it.status == UserStatus.OFFLINE }

    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = Text_Muted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "MEMBERS — ${allUsers.size}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_Muted,
                    letterSpacing = 0.8.sp,
                    fontSize = 10.sp
                )
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Separator, Color.Transparent)))
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 6.dp),
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
            fontWeight = FontWeight.Bold,
            color = Text_Muted,
            fontSize = 10.sp,
            letterSpacing = 0.6.sp
        ),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun MemberItem(user: OrbitUser) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0x0EFFFFFF) else Color.Transparent,
        label = "memberHover"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mini avatar + status dot
        Box(modifier = Modifier.size(28.dp)) {
            val hue   = remember(user.id) { Math.abs(user.id.hashCode()) % 360 }
            val color = remember(hue) { hslToColor(hue.toFloat()) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.7f)))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = user.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 10.sp
                    )
                )
            }
            val dotColor = memberStatusColor(user.status)
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .align(Alignment.BottomEnd)
                    .border(1.5.dp, Members_Bg, CircleShape)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Spacer(Modifier.width(8.dp))
        Text(
            text = user.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (user.status == UserStatus.OFFLINE) Text_Muted else Text_Secondary,
                fontSize = 13.sp,
                fontWeight = if (isHovered) FontWeight.Medium else FontWeight.Normal
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
        modifier = Modifier.fillMaxSize().background(Chat_Bg),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Giant glowing # icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Accent.copy(alpha = 0.3f), Color.Transparent))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.ExtraBold,
                        brush = Brush.verticalGradient(listOf(Accent, AccentLight)),
                        fontSize = 40.sp
                    )
                )
            }

            Text(
                text = "Welcome to $serverName",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    brush = Brush.horizontalGradient(listOf(Text_White, AccentLight))
                )
            )

            Text(
                text = "Pick a channel below to start the conversation ✨",
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
            )

            Spacer(Modifier.height(4.dp))

            // Channel list cards
            val textChannels = channels.filter { it.type == ChannelType.TEXT }
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                textChannels.take(6).forEach { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1C1F2B))
                            .border(1.dp, Color(0xFF252838), RoundedCornerShape(10.dp))
                            .clickable { onChannelClick(ch.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "# ",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Accent
                            )
                        )
                        Text(
                            text = ch.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Text_Secondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        if (ch.topic.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "— ${ch.topic}",
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
                text = "Voice Channel — nobody here yet 👀",
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.horizontalGradient(listOf(Accent, AccentLight)))
                    .clickable {}
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Join Voice",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
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
    val bubbleColor = if (isCurrentUser) Color(0xFFD9FDD3) else Color(0xFFFFFFFF)
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
                        color = Color(0xFF111B21),
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color(0xFF667781),
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun WhatsAppInputBar(placeholder: String, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InputIconBtn(Icons.Default.EmojiEmotions, "Emoji") {}
            Spacer(Modifier.width(8.dp))
            BasicTextField_Compat(
                value = text,
                onValueChange = { text = it },
                placeholder = "Message",
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            InputIconBtn(Icons.Default.AddCircle, "Attach") {}
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF00A884))
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