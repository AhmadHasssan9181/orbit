package ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
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
            .width(252.dp)
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
                server = server,
                showArrow = !server.isDM,
                onHeaderClick = {
                    if (!server.isDM) showInviteDialog = true
                },
                onCreateRoomClick = if (server.isDM) null else onCreateRoomClick
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)

            if (!server.isDM) {
                DrawerActionButton(
                    icon = Icons.Default.Search,
                    text = "Browse Channels",
                    accent = false,
                    onClick = {}
                )
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
                DirectMessageSearchBar()
                DirectMessageNav()
                HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)
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
                    onCreateRoomClick = onCreateRoomClick,
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
    val tokens = rememberCosmicTokens()
    val inviteLink = "orbit://invite/${server.id}"
    
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = tokens.CardBg,
        title = {
            Text(
                "Invite friends to ${server.name}",
                style = MaterialTheme.typography.titleMedium.copy(color = Text_White, fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column {
                OrbitEmptyIllustration(
                    scene = OrbitEmptyScene.Invite,
                    modifier = Modifier.fillMaxWidth().height(126.dp)
                )
                Spacer(Modifier.height(10.dp))
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
                        focusedContainerColor = tokens.Bg,
                        unfocusedContainerColor = tokens.Bg,
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

// ——— ServerHeader — Revolt/Discord style ————————————————————————————

@Composable
private fun ServerHeader(
    server: OrbitServer,
    showArrow: Boolean,
    onHeaderClick: () -> Unit = {},
    onCreateRoomClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val headerHeight = if (server.bannerUrl != null) 100.dp else 46.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onHeaderClick)
            .background(if (isHovered && server.bannerUrl == null) ChannelHover_Bg else Color.Transparent)
    ) {
        if (server.bannerUrl != null) {
            coil3.compose.AsyncImage(
                model = server.bannerUrl,
                contentDescription = "Server Banner",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient overlay to ensure text is readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = if (server.bannerUrl != null) 8.dp else 0.dp)
                .height(if (server.bannerUrl != null) 24.dp else 46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_White
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
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
}

// â”€â”€â”€ ChannelListContent â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun ChannelListContent(
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onCreateRoomClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var textExpanded by remember { mutableStateOf(true) }
    var voiceExpanded by remember { mutableStateOf(true) }
    val textChannels = remember(channels) { channels.filter { it.type == ChannelType.TEXT } }
    val voiceChannels = remember(channels) { channels.filter { it.type == ChannelType.VOICE } }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        item(key = "text_header") {
            ChannelSectionHeader(
                name = "Text channels",
                isExpanded = textExpanded,
                onToggle = { textExpanded = !textExpanded },
                onAddClick = onCreateRoomClick
            )
        }
        if (textExpanded) {
            items(textChannels, key = { it.id }) { ch ->
                ChannelItem(ch, ch.id == selectedChannelId) { onChannelClick(ch.id) }
            }
        }

        item(key = "voice_spacer") { Spacer(Modifier.height(10.dp)) }
        item(key = "voice_header") {
            ChannelSectionHeader(
                name = "Voice channels",
                isExpanded = voiceExpanded,
                onToggle = { voiceExpanded = !voiceExpanded },
                onAddClick = onCreateRoomClick
            )
        }
        if (voiceExpanded) {
            items(voiceChannels, key = { it.id }) { ch ->
                ChannelItem(ch, ch.id == selectedChannelId) { onChannelClick(ch.id) }
            }
            if (voiceChannels.isEmpty()) {
                item(key = "empty_voice") {
                    Text(
                        text = "No voice channels yet",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Text_Muted.copy(alpha = 0.72f),
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ DmListContent â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DirectMessageSearchBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111214))
            .border(1.dp, Separator.copy(alpha = 0.34f), RoundedCornerShape(8.dp))
            .clickable {}
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "Find or start a conversation",
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DirectMessageNav() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        DirectMessageNavItem(Icons.Default.People, "Friends")
        DirectMessageNavItem(Icons.Default.Star, "Nitro")
        DirectMessageNavItem(Icons.Default.Storefront, "Shop")
        DirectMessageNavItem(Icons.Default.Explore, "Quests")
    }
}

@Composable
private fun DirectMessageNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) ChannelHover_Bg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isHovered) Text_White else Text_Muted,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = if (isHovered) Text_White else Text_Secondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

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
    val cardShape = RoundedCornerShape(if (isLiteMode) 14.dp else 10.dp)

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> Accent.copy(alpha = 0.16f)
            isHovered  -> ChannelHover_Bg
            else       -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> Accent.copy(alpha = 0.42f)
            isHovered -> Text_White.copy(alpha = 0.10f)
            else -> Text_White.copy(alpha = 0.04f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isHovered) Text_White else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmText"
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            isHovered -> 0.45f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmIndicator"
    )

    if (isLiteMode) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(cardShape)
                    .background(bgColor)
                    .border(1.dp, borderColor, cardShape)
                    .hoverable(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(34.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Accent.copy(alpha = indicatorAlpha))
                )
                Spacer(Modifier.width(10.dp))
                DmAvatar(displayName, avatarColor, status, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Text_White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isOrbitVerifiedUser(displayName)) {
                                Spacer(Modifier.width(5.dp))
                                OrbitVerifiedBadge(modifier = Modifier.size(15.dp))
                            }
                        }
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
            Box(modifier = Modifier.fillMaxWidth().padding(start = 76.dp).height(0.5.dp).background(Separator.copy(alpha = 0.55f)))
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp, vertical = 1.dp)
                .clip(cardShape)
                .background(bgColor)
                .border(1.dp, borderColor, cardShape)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Accent.copy(alpha = indicatorAlpha))
            )
            Spacer(Modifier.width(7.dp))
            DmAvatar(displayName, avatarColor, status, size = 30.dp)
            Spacer(Modifier.width(9.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(displayName)) {
                    Spacer(Modifier.width(5.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                }
            }
            // Action icons on hover like Discord
            AnimatedVisibility(
                visible = isHovered,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
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
        OrbitDefaultAvatar(
            label = displayName,
            seed = displayName,
            color = Color(avatarColor),
            modifier = Modifier.fillMaxSize()
        )
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
private fun ChannelSectionHeader(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddClick: (() -> Unit)? = null
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sectionChevronRot"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .hoverable(interactionSource),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = if (isHovered) Text_Secondary else Text_Muted,
                modifier = Modifier.size(13.dp).rotate(rotation)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHovered) Text_Secondary else Text_Muted,
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onAddClick != null) {
            IconButton(onClick = onAddClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = OrbitIcons.Add,
                    contentDescription = "Create Channel",
                    tint = if (isHovered) Text_Secondary else Text_Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun ChannelItem(channel: OrbitChannel, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isLive = channel.activeCallUrl != null
    val liveGreen = Color(0xFF23A55A)
    val displayName = remember(channel.name, channel.type) { channelDisplayName(channel) }

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> if (channel.type == ChannelType.VOICE) liveGreen.copy(alpha = 0.16f) else ChannelSelected_Bg
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
            isSelected && channel.type == ChannelType.VOICE -> liveGreen
            isLive -> liveGreen
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    when {
                        isSelected && channel.type == ChannelType.VOICE -> liveGreen
                        isSelected -> Accent
                        isLive -> liveGreen.copy(alpha = 0.85f)
                        else -> Color.Transparent
                    }
                )
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
            text = displayName,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (channel.type == ChannelType.VOICE && isLive) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(liveGreen.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = liveGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        } else if (isHovered) {
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

private fun channelDisplayName(channel: OrbitChannel): String {
    if (channel.type != ChannelType.VOICE) return channel.name
    val withoutCall = channel.name.removeSuffix(" (Call)")
    val cleaned = withoutCall.replace(
        Regex("(?i)([-_\\s]+voice(?:[-_\\s]+channel)?)$"),
        ""
    ).trim('-', '_', ' ')
    return cleaned.ifBlank { withoutCall }
}

@Composable
private fun UserProfileBar(
    user: OrbitUser,
    onMicToggle: () -> Unit,
    onSettingsClick: () -> Unit
) {
    var showProfilePopover by remember { mutableStateOf(false) }
    var showVoicePopover by remember { mutableStateOf(false) }
    val callManager = webrtc.LocalCallManager.current
    val isAudioEnabledState = callManager?.isLocalAudioEnabled?.collectAsState(initial = true)
        ?: remember { mutableStateOf(true) }
    val isAudioEnabled by isAudioEnabledState
    val profileInteractionSource = remember { MutableInteractionSource() }
    val isProfileHovered by profileInteractionSource.collectIsHoveredAsState()
    val profileShape = RoundedCornerShape(10.dp)
    val profileBg by animateColorAsState(
        targetValue = if (showProfilePopover || isProfileHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileBg"
    )
    val profileBorder by animateColorAsState(
        targetValue = if (showProfilePopover) Accent.copy(alpha = 0.38f) else Text_White.copy(alpha = if (isProfileHovered) 0.08f else 0f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(UserBar_Bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(profileShape)
                    .background(profileBg)
                    .border(1.dp, profileBorder, profileShape)
                    .hoverable(profileInteractionSource)
                    .clickable(
                        interactionSource = profileInteractionSource,
                        indication = null,
                        onClick = { showProfilePopover = true }
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl)
                Spacer(Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = user.displayName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Text_White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isOrbitVerifiedUser(user.username, user.displayName)) {
                            Spacer(Modifier.width(4.dp))
                            OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                        }
                    }
                    Text(
                        text = statusLabel(user.status),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = statusColor(user.status),
                            fontSize = 10.sp
                        )
                    )
                }
            }

            DropdownMenu(
                expanded = showProfilePopover,
                onDismissRequest = { showProfilePopover = false },
                modifier = Modifier
                    .width(252.dp)
                    .background(UserBar_Bg)
            ) {
                ProfilePopoverContent(
                    user = user,
                    onSettingsClick = {
                        showProfilePopover = false
                        onSettingsClick()
                    }
                )
            }
        }

        Box {
            BarIconBtn(
                icon = if (isAudioEnabled) OrbitIcons.Mic else OrbitIcons.MicOff,
                desc = if (isAudioEnabled) "Mute" else "Unmute",
                activeTint = if (isAudioEnabled) null else Color(0xFFF23F42),
                onClick = {
                    callManager?.toggleMute(isAudioEnabled)
                    onMicToggle()
                }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(UserBar_Bg)
                    .border(1.dp, Separator.copy(alpha = 0.55f), CircleShape)
                    .clickable { showVoicePopover = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Voice controls", tint = Text_Muted, modifier = Modifier.size(12.dp))
            }
            DropdownMenu(
                expanded = showVoicePopover,
                onDismissRequest = { showVoicePopover = false },
                modifier = Modifier
                    .width(278.dp)
                    .background(UserBar_Bg)
            ) {
                VoiceControlPopover(onSettingsClick = {
                    showVoicePopover = false
                    onSettingsClick()
                })
            }
        }
        BarIconBtn(icon = Icons.Default.Headset, desc = "Deafen", onClick = {})
        BarIconBtn(icon = Icons.Default.Settings, desc = "Settings", onClick = onSettingsClick)
    }
}

@Composable
private fun VoiceControlPopover(onSettingsClick: () -> Unit) {
    val callManager = webrtc.LocalCallManager.current
    var inputVolume by remember { mutableStateOf(1f) }
    var showInputDevices by remember { mutableStateOf(false) }
    var inputDevices by remember(callManager) { mutableStateOf(emptyList<String>()) }
    var inputDeviceName by remember(callManager) { mutableStateOf("Windows Default (Internal Microphone)") }

    fun refreshInputDevices() {
        inputDevices =
            runCatching { callManager?.webRtcClient?.getAudioInputDevices().orEmpty() }.getOrDefault(emptyList())
        inputDeviceName = inputDevices.firstOrNull() ?: "Windows Default (Internal Microphone)"
    }

    LaunchedEffect(callManager) {
        refreshInputDevices()
    }

    LaunchedEffect(showInputDevices) {
        if (showInputDevices) refreshInputDevices()
    }

    Column(
        modifier = Modifier
            .background(UserBar_Bg)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box {
            VoiceSettingRow(
                title = "Input Device",
                value = inputDeviceName,
                onClick = { showInputDevices = true }
            )
            DropdownMenu(
                expanded = showInputDevices,
                onDismissRequest = { showInputDevices = false },
                modifier = Modifier
                    .width(250.dp)
                    .background(UserBar_Bg)
            ) {
                if (inputDevices.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No input devices found", color = Text_Muted, fontSize = 13.sp) },
                        onClick = { showInputDevices = false }
                    )
                } else {
                    inputDevices.forEach { device ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = device,
                                    color = Text_White,
                                    fontSize = 13.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                inputDeviceName = device
                                callManager?.setAudioInputDevice(device)
                                showInputDevices = false
                            },
                            trailingIcon = {
                                if (device == inputDeviceName) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
        VoiceSettingRow(
            title = "Input Profile",
            value = "Custom",
            onClick = {}
        )

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Text(
            "Input Volume",
            style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        )
        Slider(
            value = inputVolume,
            onValueChange = { inputVolume = it },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Accent,
                inactiveTrackColor = Separator.copy(alpha = 0.7f)
            )
        )

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSettingsClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voice Settings",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.Settings, contentDescription = null, tint = Text_Muted, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun VoiceSettingRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Text_White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Text_Muted,
                    fontSize = 13.sp
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Text_Muted, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ProfilePopoverContent(
    user: OrbitUser,
    onSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .background(UserBar_Bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(user.displayName, user.avatarColor, user.status, user.avatarUrl, size = 42.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Text_White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOrbitVerifiedUser(user.username, user.displayName)) {
                        Spacer(Modifier.width(5.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    text = "@${user.username}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider(color = Separator.copy(alpha = 0.45f), thickness = 1.dp)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Accent.copy(alpha = 0.10f))
                .border(1.dp, Accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(user.status))
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = statusLabel(user.status),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Text_Secondary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            )
        }

        ProfilePopoverAction(
            icon = Icons.Default.Settings,
            label = "Profile settings",
            onClick = onSettingsClick
        )
    }
}

@Composable
private fun ProfilePopoverAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "profileActionBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = AccentLight, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Text_Secondary,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
private fun BarIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    activeTint: Color? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = activeTint ?: if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barIconTint"
    )
    val bg by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "barIconBg"
    )

    Box(contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-31).dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(UserBar_Bg)
                    .border(1.dp, Separator.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp)
            ) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1
                )
            }
        }

        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg),
            interactionSource = interactionSource
        ) {
            Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(17.dp))
        }
    }
}

// â”€â”€â”€ UserAvatar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun UserAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    avatarUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 32.dp
) {
    Box(modifier = Modifier.size(size)) {
        if (avatarUrl != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, Text_White.copy(alpha = 0.08f), CircleShape)
                    .padding(1.dp)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                coil3.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            OrbitDefaultAvatar(
                label = displayName,
                seed = displayName,
                color = Color(avatarColor),
                modifier = Modifier.fillMaxSize()
            )
        }
        val dotColor = statusColor(status)
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 13.dp else 10.dp)
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

private fun statusLabel(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
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
