package ui.screen

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Removed MockData
import ui.icons.OrbitIcons
import ui.model.*

import ui.theme.LocalIsLiteMode
import ui.theme.rememberCosmicTokens

// â”€â”€â”€ Cosmic Design Tokens â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
private val Drawer_Bg: Color @Composable get() = rememberCosmicTokens().Drawer_Bg
private val UserBar_Bg: Color @Composable get() = rememberCosmicTokens().UserBar_Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent
private val AccentLight: Color @Composable get() = rememberCosmicTokens().AccentLight
private val AccentGhost: Color @Composable get() = rememberCosmicTokens().AccentGhost
private val Text_White: Color @Composable get() = rememberCosmicTokens().Text_White
private val Text_Secondary: Color @Composable get() = rememberCosmicTokens().Text_Secondary
private val Text_Muted: Color @Composable get() = rememberCosmicTokens().Text_Muted
private val Separator: Color @Composable get() = rememberCosmicTokens().Separator
private val ChannelSelected_Bg: Color @Composable get() = rememberCosmicTokens().ChannelSelected_Bg
private val ChannelHover_Bg: Color @Composable get() = rememberCosmicTokens().ChannelHover_Bg

@Composable
fun ChannelDrawer(
    server: OrbitServer,
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser,
    onChannelClick: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onCreateRoomClick: (() -> Unit)? = null,
    onOpenRolesClick: (() -> Unit)? = null,
    selectedWhatsAppTab: String = "Chats",
    modifier: Modifier = Modifier
) {
    var showInviteDialog by remember { mutableStateOf(false) }

    if (showInviteDialog) {
        InviteDialog(
            server = server,
            onDismiss = { showInviteDialog = false }
        )
    }

    Column(
        modifier = modifier
            .width(232.dp)
            .fillMaxSize()
            .background(Drawer_Bg)
    ) {
        val isLiteMode = ui.theme.LocalIsLiteMode.current

        if (isLiteMode) {
            when (selectedWhatsAppTab) {
                "Chats" -> {
                    WhatsAppDrawerHeader()
                    WhatsAppSearchBar()
                    WhatsAppFilterChips()
                    DmListContent(
                        channels = channels,
                        selectedChannelId = selectedChannelId,
                        onChannelClick = onChannelClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                "Calls" -> WhatsAppCallsDrawer()
                "Status" -> WhatsAppStatusDrawer()
                "Communities" -> WhatsAppCommunitiesDrawer()
            }
        } else {
            // â”€â”€ Server header â€” Discord/Revolt style â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            ServerHeader(
                serverName = server.name,
                showArrow = !server.isDM,
                onHeaderClick = {
                    if (!server.isDM) showInviteDialog = true
                },
                onCreateRoomClick = if (server.isDM) null else onCreateRoomClick
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)

            if (!server.isDM) {
                DrawerActionButton(
                    icon = Icons.Default.PersonAdd,
                    text = "Invite People",
                    accent = true,
                    onClick = { showInviteDialog = true }
                )
                if (onOpenRolesClick != null) {
                    DrawerActionButton(
                        icon = Icons.Default.Security,
                        text = "Roles",
                        accent = false,
                        onClick = onOpenRolesClick
                    )
                }
                Spacer(Modifier.height(3.dp))
            }

            if (server.isDM) {
                val callManager = webrtc.LocalCallManager.current
                DmListContent(
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick,
                    onVoiceCall = { callManager?.startCall(it, false) },
                    onVideoCall = { callManager?.startCall(it, true) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                ChannelListContent(
                    categories = categories,
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)
        UserProfileBar(
            user = currentUser,
            onMicToggle = {},
            onSettingsClick = onOpenSettings ?: {}
        )
    }
}

// â”€â”€â”€ Invite Button & Dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DrawerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        label = "drawerActionBg"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            accent -> AccentLight
            isHovered -> Text_White
            else -> Text_Muted
        },
        label = "drawerActionColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = contentColor,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        )
    }
}

@Composable
private fun InviteDialog(server: OrbitServer, onDismiss: () -> Unit) {
    val inviteLink = "orbit://invite/${server.id}"
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = rememberCosmicTokens().CardBg,
        title = {
            Text(
                "Invite friends to ${server.name}",
                style = MaterialTheme.typography.titleMedium.copy(color = Text_White, fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column {
                Text(
                    "Share this link with others to grant them access to this server.",
                    style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 13.sp),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = inviteLink,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = rememberCosmicTokens().Bg,
                        unfocusedContainerColor = rememberCosmicTokens().Bg,
                        focusedTextColor = Text_White,
                        unfocusedTextColor = Text_White,
                        focusedBorderColor = Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("Done", color = Color.White)
            }
        }
    )
}

// â”€â”€â”€ ServerHeader â€” Revolt/Discord style â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ServerHeader(
    serverName: String,
    showArrow: Boolean,
    onHeaderClick: () -> Unit = {},
    onCreateRoomClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onHeaderClick)
            .background(if (isHovered) ChannelHover_Bg else Color.Transparent)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = serverName,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onCreateRoomClick != null) {
                IconButton(
                    onClick = onCreateRoomClick,
                    modifier = Modifier.size(26.dp)
                ) {
                    Icon(
                        imageVector = OrbitIcons.Add,
                        contentDescription = "Create Room",
                        tint = Text_Muted,
                        modifier = Modifier.size(17.dp).alpha(0.9f)
                    )
                }
            }
            if (showArrow) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(17.dp).alpha(0.7f)
                )
            }
        }
    }
}

// â”€â”€â”€ ChannelListContent â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ChannelListContent(
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val expandedCategories = remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(categories) {
        if (expandedCategories.value == null && categories.isNotEmpty()) {
            expandedCategories.value = categories.map { it.id }.toSet()
        }
    }
    val currentExpanded = expandedCategories.value ?: emptySet()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 5.dp, horizontal = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        categories.forEach { cat ->
            val isExpanded = cat.id in currentExpanded
            item(cat.id) {
                CategoryHeader(
                    name = cat.name,
                    isExpanded = isExpanded,
                    onToggle = {
                        val current = expandedCategories.value ?: emptySet()
                        expandedCategories.value =
                            if (cat.id in current)
                                current - cat.id
                            else current + cat.id
                    }
                )
            }
            if (isExpanded) {
                val catChannels = channels.filter { it.categoryId == cat.id }
                items(catChannels, key = { it.id }) { ch ->
                    ChannelItem(ch, ch.id == selectedChannelId) { onChannelClick(ch.id) }
                }
            }
        }

        val uncategorized = channels.filter { ch ->
            ch.categoryId == null || ch.categoryId !in categories.map { it.id }
        }
        if (uncategorized.isNotEmpty()) {
            items(uncategorized, key = { it.id }) { ch ->
                ChannelItem(ch, ch.id == selectedChannelId) { onChannelClick(ch.id) }
            }
        }
    }
}

// â”€â”€â”€ DmListContent â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DmListContent(
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onVoiceCall: ((String) -> Unit)? = null,
    onVideoCall: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 5.dp, horizontal = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (!isLiteMode) {
            item {
                Text(
                    text = "DIRECT MESSAGES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Text_Muted,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                )
            }
        }
        items(channels, key = { it.id }) { ch ->
            DmItem(
                displayName = ch.name,
                avatarColor = 0xFF7C6AF0.toInt(), // Replace with hashToColor or actual if you have it
                status = UserStatus.ONLINE, // Placeholder
                isSelected = ch.id == selectedChannelId,
                onClick = { onChannelClick(ch.id) },
                onVoiceCall = { onVoiceCall?.invoke(ch.id) },
                onVideoCall = { onVideoCall?.invoke(ch.id) },
                lastMessage = ch.topic.ifBlank { "Tap to chat with ${ch.name}" },
                time = ""
            )
        }
    }
}

// â”€â”€â”€ DmItem â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DmItem(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    lastMessage: String = "Tap to chat with $displayName",
    time: String = "12:00 PM"
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> ChannelSelected_Bg
            isHovered  -> ChannelHover_Bg
            else       -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isHovered) Text_White else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmText"
    )

    if (isLiteMode) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bgColor)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DmAvatar(displayName, avatarColor, status, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Text_White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            ),
                            maxLines = 1
                        )
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Text_Muted,
                                fontSize = 12.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = lastMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Text_Muted,
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(start = 76.dp).height(0.5.dp).background(Separator))
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DmAvatar(displayName, avatarColor, status, size = 30.dp)
            Spacer(Modifier.width(9.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = textColor,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            // Action icons on hover like Discord
            if (isHovered) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onVoiceCall?.invoke() }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = OrbitIcons.Call,
                            contentDescription = "Voice Call",
                            tint = Text_White,
                            modifier = Modifier.size(16.dp).alpha(0.8f)
                        )
                    }
                    IconButton(onClick = { onVideoCall?.invoke() }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = OrbitIcons.Video,
                            contentDescription = "Video Call",
                            tint = Text_White,
                            modifier = Modifier.size(16.dp).alpha(0.8f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Text_Muted,
                        modifier = Modifier.size(14.dp).alpha(0.6f)
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ DmAvatar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DmAvatar(displayName: String, avatarColor: Int, status: UserStatus, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(modifier = Modifier.size(size)) {
        val base = Color(avatarColor)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.6f)))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(2).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = if (size > 40.dp) 16.sp else 10.sp
                )
            )
        }
        val dotColor = statusColor(status)
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 14.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, Drawer_Bg, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

// â”€â”€â”€ CategoryHeader â€” Discord/Revolt style â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun CategoryHeader(name: String, isExpanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "chevronRot"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = if (isHovered) Text_Secondary else Text_Muted,
            modifier = Modifier.size(12.dp).rotate(rotation)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (isHovered) Text_Secondary else Text_Muted,
                letterSpacing = 0.5.sp,
                fontSize = 10.sp
            )
        )
    }
}

// â”€â”€â”€ ChannelItem â€” Discord/Revolt style â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ChannelItem(channel: OrbitChannel, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> ChannelSelected_Bg
            isHovered  -> ChannelHover_Bg
            else       -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chBg"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isSelected -> Text_White
            isHovered  -> Text_Secondary
            else       -> Text_Muted
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chText"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isSelected -> Text_White
            isHovered  -> Text_Secondary
            else       -> Text_Muted
        },
        label = "chIcon"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (isSelected) Accent else Color.Transparent)
        )
        Spacer(Modifier.width(5.dp))
        when (channel.type) {
            ChannelType.TEXT -> Icon(
                imageVector = OrbitIcons.TextChannel,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(17.dp)
            )
            ChannelType.VOICE -> Icon(
                imageVector = OrbitIcons.VoiceChannel,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(Modifier.width(6.dp))

        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Action icons on hover (like Discord)
        if (isHovered) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(14.dp).alpha(0.6f)
                )
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(14.dp).alpha(0.6f)
                )
            }
        }
    }
}

// â”€â”€â”€ UserProfileBar â€” Discord-style â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun UserProfileBar(
    user: OrbitUser,
    onMicToggle: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UserBar_Bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl)
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Text_White
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Online",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF34D399),
                    fontSize = 10.sp
                )
            )
        }

        BarIconBtn(icon = OrbitIcons.Mic, desc = "Mic", onClick = onMicToggle)
        BarIconBtn(icon = Icons.Default.Headset, desc = "Deafen", onClick = {})
        BarIconBtn(icon = Icons.Default.Settings, desc = "Settings", onClick = onSettingsClick)
    }
}

@Composable
private fun BarIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        label = "barIconTint"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        interactionSource = interactionSource
    ) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(17.dp))
    }
}

// â”€â”€â”€ UserAvatar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun UserAvatar(displayName: String, avatarColor: Int, status: UserStatus, avatarUrl: String? = null) {
    Box(modifier = Modifier.size(32.dp)) {
        val base = Color(avatarColor)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, Text_White.copy(alpha = 0.08f), CircleShape)
                .padding(1.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.7f)))),
            contentAlignment = Alignment.Center
        ) {
            if (avatarUrl != null) {
                coil3.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = displayName.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 10.sp
                    )
                )
            }
        }
        val dotColor = statusColor(status)
        Box(
            modifier = Modifier
                .size(10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, UserBar_Bg, CircleShape)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

// â”€â”€â”€ Status color helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun statusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE        -> Color(0xFF34D399)
    UserStatus.IDLE          -> Color(0xFFFBBF24)
    UserStatus.DO_NOT_DISTURB -> Color(0xFFF87171)
    UserStatus.OFFLINE       -> Color(0xFF3D4160)
}

// â”€â”€â”€ Lite Mode Drawer Components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun WhatsAppDrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(rememberCosmicTokens().Header_Bg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Orbit",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White,
                fontSize = 20.sp
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "New Chat", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun WhatsAppSearchBar() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(rememberCosmicTokens().Input_Bg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Text_Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Search conversations...",
                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 13.sp)
            )
        }
    }
}

@Composable
private fun WhatsAppFilterChips() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LiteChip("All", isSelected = true)
        LiteChip("Unread", isSelected = false)
        LiteChip("Favourites", isSelected = false)
        LiteChip("Groups", isSelected = false)
    }
}

@Composable
private fun LiteChip(text: String, isSelected: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Accent.copy(alpha = 0.15f)
        else rememberCosmicTokens().CardBg,
        label = "chipBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Accent else Text_Muted,
        label = "chipText"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun WhatsAppCallsDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Calls") {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(OrbitIcons.Add, contentDescription = "New Call", tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
        WhatsAppSearchBar()

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            val names = listOf("Nova", "Phoenix", "Stella", "Raven", "Orbit Bot", "Alice", "Bob", "Charlie", "Diana", "Eve")
            items(names.size) { i ->
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(interactionSource)
                        .background(if (isHovered) rememberCosmicTokens().ChannelHover_Bg else Color.Transparent)
                        .clickable {}
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hue = (i * 47) % 360
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(
                                hslToColorLocal(hue.toFloat()),
                                hslToColorLocal((hue + 30f) % 360)
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(names[i].take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(names[i], style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (i % 2 == 0) Icons.Default.CallMade else Icons.Default.CallReceived,
                                contentDescription = null,
                                tint = if (i % 2 == 0) Accent else Color(0xFFF87171),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Today, ${10 + i}:${10 + i * 3} AM", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
                        }
                    }
                    IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                        Icon(OrbitIcons.Call, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppStatusDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Status") {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(OrbitIcons.Add, contentDescription = "New Status", tint = Accent, modifier = Modifier.size(20.dp))
            }
        }

        // My status
        Row(
            modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Accent, AccentLight))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(OrbitIcons.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My status", style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                Text("Tap to add status update", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
            }
        }

        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 14.dp))

        Text(
            text = "RECENT",
            style = MaterialTheme.typography.labelSmall.copy(color = Accent, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            val names = listOf("Phoenix", "Stella", "Nova")
            items(names.size) { i ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hue = ((i + 3) * 67) % 360
                    Box(
                        modifier = Modifier.size(48.dp)
                            .border(2.dp, Accent, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(
                                hslToColorLocal(hue.toFloat()),
                                hslToColorLocal((hue + 25f) % 360)
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(names[i].take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(names[i], style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                        Text("Today, 9:${40 + i * 7} AM", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppCommunitiesDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Communities")

        // New community
        Row(
            modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(OrbitIcons.Members, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("New community", style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }

        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 14.dp))

        // Empty state
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(OrbitIcons.TextChannel, contentDescription = null, tint = Text_Muted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No communities yet", style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted))
                Text("Create or join a community to get started", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted.copy(alpha = 0.6f), fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun LiteDrawerHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(rememberCosmicTokens().Header_Bg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            actions()
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// â”€â”€â”€ HSL helper (local to ChannelDrawer) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private fun hslToColorLocal(hue: Float): Color {
    val s = 0.65f; val l = 0.55f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun h2r(t: Float): Float {
        var x = t; if (x < 0) x += 1f; if (x > 1) x -= 1f
        return when {
            x < 1f / 6f -> p + (q - p) * 6f * x
            x < 0.5f -> q
            x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
            else -> p
        }
    }
    return Color(
        h2r(hue / 360f + 1f / 3f).coerceIn(0f, 1f),
        h2r(hue / 360f).coerceIn(0f, 1f),
        h2r(hue / 360f - 1f / 3f).coerceIn(0f, 1f)
    )
}
