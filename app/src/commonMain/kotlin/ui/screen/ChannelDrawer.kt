package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.data.MockData
import ui.model.*

import ui.theme.LocalIsLiteMode

// ─── Cosmic Design Tokens ───────────────────────────────────────
private val Drawer_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFF0F2F5) else Color(0xFF111318)
private val UserBar_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFEAE6DF) else Color(0xFF0A0C12)
private val Accent: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF075E54) else Color(0xFF7C6AF0)
private val AccentLight: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF128C7E) else Color(0xFFA78BFA)
private val AccentGhost: Color @Composable get() = if (LocalIsLiteMode.current) Color(0x1A075E54) else Color(0x1A7C6AF0)
private val AccentSelected: Color @Composable get() = if (LocalIsLiteMode.current) Color(0x2A075E54) else Color(0x2A7C6AF0)
private val AccentBorder: Color @Composable get() = if (LocalIsLiteMode.current) Color(0x55075E54) else Color(0x557C6AF0)
private val Text_White: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF111B21) else Color(0xFFEAECF5)
private val Text_Secondary: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF54656F) else Color(0xFFB4B8D0)
private val Text_Muted: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF667781) else Color(0xFF6B7094)
private val Separator: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFD1D7DB) else Color(0xFF1C1F2B)

@Composable
fun ChannelDrawer(
    server: OrbitServer,
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser,
    onChannelClick: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    selectedWhatsAppTab: String = "Chats",
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(240.dp)
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
            ServerHeader(server.name, showArrow = !server.isDM)
            GradientSeparator()

            if (server.isDM) {
                DmListContent(
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick,
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

        GradientSeparator()
        UserProfileBar(
            user = currentUser,
            onMicToggle = {},
            onSettingsClick = onOpenSettings ?: {}
        )
    }
}

// ─── ServerHeader ────────────────────────────────────────────────

@Composable
private fun ServerHeader(serverName: String, showArrow: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickable {}
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = serverName,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White,
                fontSize = 15.sp
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        if (showArrow) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(0x12FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ─── ChannelListContent ──────────────────────────────────────────

@Composable
private fun ChannelListContent(
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val expandedCategories = remember { mutableStateOf(setOf<String>()) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        categories.forEach { cat ->
            item(cat.id) {
                CategoryHeader(
                    name = cat.name,
                    isExpanded = cat.id in expandedCategories.value,
                    onToggle = {
                        expandedCategories.value =
                            if (cat.id in expandedCategories.value)
                                expandedCategories.value - cat.id
                            else expandedCategories.value + cat.id
                    }
                )
            }
            val catChannels = channels.filter { it.categoryId == cat.id }
            items(catChannels, key = { it.id }) { ch ->
                ChannelItem(ch, ch.id == selectedChannelId) { onChannelClick(ch.id) }
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

// ─── DmListContent ───────────────────────────────────────────────

@Composable
private fun DmListContent(
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allUsers = MockData.MockUsers
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (!isLiteMode) {
            item {
                Text(
                    text = "DIRECT MESSAGES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Text_Muted,
                        letterSpacing = 0.8.sp
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }
        items(allUsers, key = { it.id }) { user ->
            val dmChannel = channels.find { it.name == user.displayName }
            val mockLastMsg = when (user.displayName) {
                "Nova" -> "The dense message mode is such a smart UX choice."
                "Phoenix" -> "Let's build something cool together!"
                "Orbit Bot" -> "That's a great idea — I'll throw together a prototype."
                "Stella" -> "The Lite Mode gives serious WhatsApp vibes — in a good way."
                "Raven" -> "Matrix backend here we come \uD83C\uDFAF"
                else -> "Aoa, sir \uD83D\uDD38Subject: Update..."
            }
            val mockTime = when(user.displayName) {
                "Nova" -> "2:46 PM"
                "Phoenix" -> "2:52 PM"
                "Orbit Bot" -> "3:10 PM"
                "Stella" -> "3:04 PM"
                "Raven" -> "2:58 PM"
                else -> "2:04 PM"
            }
            dmChannel?.let { ch ->
                DmItem(
                    displayName = user.displayName,
                    avatarColor = user.avatarColor,
                    status = user.status,
                    isSelected = ch.id == selectedChannelId,
                    onClick = { onChannelClick(ch.id) },
                    lastMessage = mockLastMsg,
                    time = mockTime
                )
            }
        }
    }
}

// ─── DmItem ──────────────────────────────────────────────────────

@Composable
private fun DmItem(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
    lastMessage: String = "Tap to chat with $displayName",
    time: String = "12:00 PM"
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> if (isLiteMode) Color(0xFFF0F2F5) else AccentSelected
            isHovered  -> if (isLiteMode) Color(0xFFF5F6F6) else Color(0x0FFFFFFF)
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
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .then(
                    if (isSelected) Modifier.border(
                        width = 1.dp,
                        color = AccentBorder,
                        shape = RoundedCornerShape(8.dp)
                    ) else Modifier
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DmAvatar(displayName, avatarColor, status, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = textColor,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ─── DmAvatar ────────────────────────────────────────────────────

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
                .size(10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, Drawer_Bg, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

// ─── CategoryHeader ──────────────────────────────────────────────

@Composable
private fun CategoryHeader(name: String, isExpanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "chevronRot"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = Text_Muted,
            modifier = Modifier.size(13.dp).rotate(rotation)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Text_Muted,
                letterSpacing = 0.8.sp,
                fontSize = 10.sp
            )
        )
    }
}

// ─── ChannelItem ─────────────────────────────────────────────────

@Composable
private fun ChannelItem(channel: OrbitChannel, isSelected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> AccentSelected
            isHovered  -> Color(0x0DFFFFFF)
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
        targetValue = if (isSelected) Accent else Text_Muted,
        label = "chIcon"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, AccentBorder, RoundedCornerShape(7.dp))
                else Modifier
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left accent bar
        if (isSelected) {
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(Accent, AccentLight)))
            )
            Spacer(Modifier.width(6.dp))
        }

        when (channel.type) {
            ChannelType.TEXT -> Text(
                text = "#",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = iconColor,
                    fontSize = 14.sp
                ),
                modifier = Modifier.padding(end = 6.dp)
            )
            ChannelType.VOICE -> Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(14.dp).padding(end = 6.dp)
            )
        }

        Text(
            text = channel.name,
            style = MaterialTheme.typography.bodySmall.copy(
                color = textColor,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            ),
            maxLines = 1
        )
    }
}

// ─── UserProfileBar ──────────────────────────────────────────────

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
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        UserAvatar(user.displayName, user.avatarColor, user.status)
        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = user.displayName,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    brush = Brush.horizontalGradient(listOf(Color(0xFF7C6AF0), Color(0xFFA78BFA)))
                ),
                maxLines = 1
            )
            Text(
                text = "Online",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF34D399),
                    fontSize = 10.sp
                )
            )
        }

        BarIconBtn(icon = Icons.Default.Mic, desc = "Mic", onClick = onMicToggle)
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
        targetValue = if (isHovered) Color(0xFF7C6AF0) else Color(0xFF6B7094),
        label = "barIconTint"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
        interactionSource = interactionSource
    ) {
        Icon(imageVector = icon, contentDescription = desc, tint = tint, modifier = Modifier.size(16.dp))
    }
}

// ─── UserAvatar ──────────────────────────────────────────────────

@Composable
private fun UserAvatar(displayName: String, avatarColor: Int, status: UserStatus) {
    Box(modifier = Modifier.size(36.dp)) {
        val base = Color(avatarColor)
        // Gradient ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFF7C6AF0), Color(0xFFA78BFA))))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.7f)))),
                contentAlignment = Alignment.Center
            ) {
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
                .size(11.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, UserBar_Bg, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}

// ─── GradientSeparator ───────────────────────────────────────────

@Composable
private fun GradientSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(0xFF1C1F2B), Color.Transparent)
                )
            )
    )
}

// ─── Status color helper ─────────────────────────────────────────

private fun statusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE        -> Color(0xFF34D399)
    UserStatus.IDLE          -> Color(0xFFFBBF24)
    UserStatus.DO_NOT_DISTURB -> Color(0xFFF87171)
    UserStatus.OFFLINE       -> Color(0xFF3D4160)
}

// ─── WhatsApp Drawer Components ──────────────────────────────────

@Composable
private fun WhatsAppDrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Orbit",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = ui.theme.LocalIsLiteMode.current.let { if(it) Color(0xFF111B21) else Color.White },
                fontSize = 22.sp
            )
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Default.AddBox, contentDescription = "New Chat", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Menu", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun WhatsAppSearchBar() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFFF0F2F5))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(androidx.compose.material.icons.Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF54656F), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Search or start a new chat",
                style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF54656F), fontSize = 14.sp)
            )
        }
    }
}

@Composable
private fun WhatsAppFilterChips() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WhatsAppChip("All", isSelected = true)
        WhatsAppChip("Unread 1", isSelected = false)
        WhatsAppChip("Favourites", isSelected = false)
        WhatsAppChip("Groups", isSelected = false)
    }
}

@Composable
private fun WhatsAppChip(text: String, isSelected: Boolean) {
    val bgColor = if (isSelected) Color(0xFFD9FDD3) else Color(0xFFF0F2F5)
    val textColor = if (isSelected) Color(0xFF111B21) else Color(0xFF54656F)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun WhatsAppCallsDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Calls", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF111B21), fontSize = 22.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = "New Call", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Menu", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
            }
        }
        WhatsAppSearchBar()
        
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(10) { i ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable{}.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF00A884)), contentAlignment = Alignment.Center) {
                        Text("C$i", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Mock User $i", style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF111B21), fontWeight = FontWeight.Bold, fontSize = 16.sp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (i % 2 == 0) androidx.compose.material.icons.Icons.Default.CallMade else androidx.compose.material.icons.Icons.Default.CallReceived, contentDescription = null, tint = if (i % 2 == 0) Color(0xFF00A884) else Color(0xFFF87171), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Today, 10:${10 + i} AM", style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF54656F), fontSize = 14.sp))
                        }
                    }
                    Icon(androidx.compose.material.icons.Icons.Default.Call, contentDescription = null, tint = Color(0xFF00A884), modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun WhatsAppStatusDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Status", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF111B21), fontSize = 22.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = "New Status", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Menu", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().clickable{}.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE2E8F0)), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Default.Person, contentDescription = null, tint = Color.White)
                Box(modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape).background(Color(0xFF00A884)), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My status", style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF111B21), fontWeight = FontWeight.Bold, fontSize = 16.sp))
                Text("No updates", style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF54656F), fontSize = 14.sp))
            }
        }

        Text(
            text = "RECENT",
            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF00A884), fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(3) { i ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable{}.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(48.dp).border(2.dp, Color(0xFF00A884), CircleShape).padding(2.dp).clip(CircleShape).background(Color(0xFF94A3B8)), contentAlignment = Alignment.Center) {
                        Text("S$i", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Contact $i", style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF111B21), fontWeight = FontWeight.Bold, fontSize = 16.sp))
                        Text("Today, 9:${40 + i} AM", style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF54656F), fontSize = 14.sp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppCommunitiesDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Communities", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = Color(0xFF111B21), fontSize = 22.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Default.MoreVert, contentDescription = "Menu", tint = Color(0xFF54656F), modifier = Modifier.size(24.dp))
            }
        }
        
        Row(
            modifier = Modifier.fillMaxWidth().clickable{}.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFD1D7DB)), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Default.Groups, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(14.dp))
            Text("New community", style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF111B21), fontWeight = FontWeight.Bold, fontSize = 16.sp))
        }
    }
}