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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

import ui.theme.LocalIsLiteMode
import ui.theme.rememberCosmicTokens

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
            HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)
            MessageList(messages = messages, currentUser = currentUser, modifier = Modifier.weight(1f))
            MessageInputBar(placeholder = "Message #${channel.name}", onSend = onSend)
        }
        if (showMemberList) MemberListPanel(members = members)
    }
}

// ─── ChatHeader — Discord/Revolt style ───────────────────────────

@Composable
private fun ChatHeader(channel: OrbitChannel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Header_Bg)
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
    desc: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        label = "headerIconTint"
    )
    IconButton(
        onClick = {},
        modifier = Modifier.size(32.dp),
        interactionSource = interactionSource
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp))
    }
}

// ─── MessageList ─────────────────────────────────────────────────

@Composable
private fun MessageList(messages: List<OrbitMessage>, currentUser: OrbitUser?, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val grouped   = groupMessages(messages)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
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

                // Message body
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
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_Secondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
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
            IconButton(onClick = {}, modifier = Modifier.size(36.dp)) {
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
                modifier = Modifier.weight(1f)
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
                Text(
                    text = user.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 11.sp
                    )
                )
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

            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Accent)
                    .clickable {
                        uriHandler.openUri("https://app.element.io/#/room/${channel.id}")
                    }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "Join Voice in Element Web",
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