package ui.screen.main

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.items
import ui.model.*
import ui.assets.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import ui.screen.chat.*
import androidx.compose.ui.unit.sp
import ui.icons.*
import ui.theme.*
import repository.*

@Composable
fun CompactLayout(
    servers: List<OrbitServer>,
    channels: List<OrbitChannel>,
    selectedServerId: String,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    selectedBottomTab: BottomNavTab,
    compactOpenChannelId: String?,
    repository: OrbitRepository,
    onServerSelect: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onBackFromChat: () -> Unit,
    onBottomTabSelect: (BottomNavTab) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onCreateRoom: () -> Unit,
    onOpenRoles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()

    // If a channel is open, show full-screen chat
    if (compactOpenChannelId != null) {
        val channel = channels.find { it.id == compactOpenChannelId }
        if (channel != null) {
            Column(modifier = modifier.background(tokens.Bg)) {
                ChatScreenWithRepo(
                    repository = repository,
                    channel = channel,
                    showMemberList = false,
                    isDirectMessage = channel.categoryId == "dm",
                    onBack = onBackFromChat,
                    modifier = Modifier.weight(1f)
                )
            }
            return
        }
    }

    // Otherwise show the list pane with bottom nav
    Scaffold(
        modifier = modifier,
        containerColor = tokens.Bg,
        bottomBar = {
            BottomNavBar(
                selectedTab = selectedBottomTab,
                onTabSelected = onBottomTabSelect
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedBottomTab) {
                BottomNavTab.DMs -> {
                    // Show DM server channels as a full-screen list
                    val dmServer = servers.firstOrNull { it.isDM }
                    if (dmServer != null) {
                        CompactChannelList(
                            server = dmServer,
                            channels = channels.filter { it.categoryId == "dm" || it.type == ChannelType.TEXT },
                            selectedChannelId = selectedChannelId,
                            currentUser = currentUser,
                            repository = repository,
                            onChannelClick = onChannelClick,
                            onOpenSettings = onOpenSettings,
                            isLoadingChannels = isLoadingChannels,
                            onCreateRoom = onCreateRoom
                        )
                    } else {
                        CompactEmptyDMs()
                    }
                }

                BottomNavTab.Servers -> {
                    // Show server list; tapping server loads its channels
                    val nonDmServers = servers.filter { !it.isDM }
                    if (nonDmServers.isEmpty()) {
                        CompactEmptyServers(onAddServer)
                    } else {
                        CompactServerList(
                            servers = nonDmServers,
                            selectedServerId = selectedServerId,
                            channels = channels,
                            selectedChannelId = selectedChannelId,
                            currentUser = currentUser,
                            isLoadingChannels = isLoadingChannels,
                            repository = repository,
                            onServerSelect = onServerSelect,
                            onChannelClick = onChannelClick,
                            onOpenSettings = onOpenSettings,
                            onAddServer = onAddServer,
                            onCreateRoom = onCreateRoom,
                            onOpenRoles = onOpenRoles
                        )
                    }
                }

                BottomNavTab.Search -> {
                    CompactSearchPane(
                        channels = channels,
                        onChannelClick = onChannelClick
                    )
                }

                BottomNavTab.Profile -> {
                    CompactProfilePane(
                        currentUser = currentUser,
                        onOpenSettings = onOpenSettings
                    )
                }
            }
        }
    }
}

@Composable
fun CompactChannelList(
    server: OrbitServer,
    channels: List<OrbitChannel>,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    isLoadingChannels: Boolean,
    onCreateRoom: (() -> Unit)? = null
) {
    val tokens = rememberCosmicTokens()

    Column(modifier = Modifier.fillMaxSize().background(tokens.Bg)) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.Header_Bg)
                .padding(horizontal = 16.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleMedium.copy(
                    color = tokens.Text_White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            )
            if (onCreateRoom != null) {
                IconButton(onClick = onCreateRoom) {
                    Icon(Icons.Default.Edit, contentDescription = "New Chat",
                        tint = tokens.Text_Muted, modifier = Modifier.size(20.dp))
                }
            }
        }

        HorizontalDivider(color = tokens.Separator.copy(alpha = 0.3f), thickness = 1.dp)

        if (isLoadingChannels) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.Accent)
            }
        } else if (channels.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No conversations yet", color = tokens.Text_Muted,
                    style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            // DM-style list with avatars
            ChannelDrawer(
                server = server,
                categories = emptyList(),
                channels = channels,
                selectedChannelId = selectedChannelId,
                currentUser = currentUser ?: OrbitUser(
                    id = "me", username = "me", displayName = "Me",
                    avatarColor = 0xFF7C6AF0.toInt(), status = UserStatus.ONLINE
                ),
                repository = repository,
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun CompactServerList(
    servers: List<OrbitServer>,
    selectedServerId: String,
    channels: List<OrbitChannel>,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    repository: repository.OrbitRepository,
    onServerSelect: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    onCreateRoom: () -> Unit,
    onOpenRoles: () -> Unit
) {
    val tokens = rememberCosmicTokens()
    val selectedServer = servers.find { it.id == selectedServerId }

    Column(modifier = Modifier.fillMaxSize().background(tokens.Bg)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.Header_Bg)
                .padding(horizontal = 16.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Servers", style = MaterialTheme.typography.titleMedium.copy(
                color = tokens.Text_White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ))
            IconButton(onClick = onAddServer) {
                Icon(OrbitIcons.Add, contentDescription = "Add Server",
                    tint = tokens.Accent, modifier = Modifier.size(20.dp))
            }
        }
        HorizontalDivider(color = tokens.Separator.copy(alpha = 0.3f), thickness = 1.dp)

        // Horizontal server strip at top
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.Rail_Bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(servers) { server ->
                val isSelected = server.id == selectedServerId
                CompactServerChip(
                    server = server,
                    isSelected = isSelected,
                    onClick = { onServerSelect(server.id) }
                )
            }
        }

        HorizontalDivider(color = tokens.Separator.copy(alpha = 0.3f), thickness = 1.dp)

        // Channel list for selected server
        if (isLoadingChannels) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = tokens.Accent)
            }
        } else if (selectedServer != null) {
            val categories = channels.mapNotNull { it.categoryId }.distinct()
                .map { catId ->
                    val firstCh = channels.firstOrNull { it.categoryId == catId }
                    ChannelCategory(id = catId, name = ui.model.categoryDisplayName(catId), channelId = firstCh?.id ?: "")
                }
            ChannelDrawer(
                server = selectedServer,
                categories = categories,
                channels = channels,
                selectedChannelId = selectedChannelId,
                currentUser = currentUser ?: OrbitUser(
                    id = "me", username = "me", displayName = "Me",
                    avatarColor = 0xFF7C6AF0.toInt(), status = UserStatus.ONLINE
                ),
                repository = repository,
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
                onCreateRoomClick = onCreateRoom,
                onOpenRolesClick = onOpenRoles,
                modifier = Modifier.weight(1f)
            )
        } else {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Select a server", color = tokens.Text_Muted)
            }
        }
    }
}

@Composable
fun CompactServerChip(server: OrbitServer, isSelected: Boolean, onClick: () -> Unit) {
    val tokens = rememberCosmicTokens()
    Column(
        modifier = Modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (isSelected) 16.dp else 24.dp))
                .background(
                    if (isSelected)
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(tokens.Accent, tokens.AccentLight)
                        )
                    else
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(server.iconColor), Color(server.iconColor).copy(alpha = 0.7f))
                        )
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (server.avatarUrl != null) {
                coil3.compose.AsyncImage(
                    model = server.avatarUrl,
                    contentDescription = server.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                OrbitServerAssetIcon(
                    label = server.name,
                    color = Color(server.iconColor),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = server.name,
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (isSelected) tokens.Accent else tokens.Text_Muted,
                fontSize = 9.sp,
                fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.Bold
                else androidx.compose.ui.text.font.FontWeight.Normal
            ),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun CompactSearchPane(channels: List<OrbitChannel>, onChannelClick: (String) -> Unit) {
    val tokens = rememberCosmicTokens()
    var query by remember { mutableStateOf("") }

    val filtered = remember(query, channels) {
        if (query.isBlank()) channels
        else channels.filter { it.name.contains(query, ignoreCase = true) || it.topic.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(tokens.Bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(tokens.Header_Bg)
                .padding(horizontal = 16.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Search", style = MaterialTheme.typography.titleMedium.copy(
                color = tokens.Text_White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            ))
        }
        HorizontalDivider(color = tokens.Separator.copy(alpha = 0.3f))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search channels...", color = tokens.Text_Muted) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null, tint = tokens.Text_Muted)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.Accent,
                unfocusedBorderColor = tokens.Separator,
                cursorColor = tokens.Accent,
                focusedTextColor = tokens.Text_White,
                unfocusedTextColor = tokens.Text_White
            ),
            singleLine = true
        )

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    OrbitEmptyIllustration(
                        scene = OrbitEmptyScene.Search,
                        modifier = Modifier.width(240.dp).height(150.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (query.isBlank()) "Search your channels" else "No channels found",
                        color = tokens.Text_Muted
                    )
                }
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered) { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChannelClick(ch.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (ch.type == ChannelType.VOICE) OrbitIcons.VoiceChannel else OrbitIcons.TextChannel,
                            contentDescription = null,
                            tint = tokens.Text_Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(ch.name, style = MaterialTheme.typography.bodyMedium.copy(color = tokens.Text_White))
                            if (ch.topic.isNotBlank()) {
                                Text(ch.topic, style = MaterialTheme.typography.bodySmall.copy(color = tokens.Text_Muted),
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(color = tokens.Separator.copy(alpha = 0.15f), modifier = Modifier.padding(start = 46.dp))
                }
            }
        }
    }
}

@Composable
fun CompactProfilePane(currentUser: OrbitUser?, onOpenSettings: () -> Unit) {
    val tokens = rememberCosmicTokens()
    Column(
        modifier = Modifier.fillMaxSize().background(tokens.Bg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        // Avatar
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(tokens.Accent, tokens.AccentLight)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (currentUser?.avatarUrl != null) {
                coil3.compose.AsyncImage(
                    model = currentUser.avatarUrl,
                    contentDescription = currentUser.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                OrbitDefaultAvatar(
                    label = currentUser?.displayName ?: "Me",
                    seed = currentUser?.id ?: "me",
                    color = Color(currentUser?.avatarColor ?: 0xFF7C6AF0.toInt()),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = currentUser?.displayName ?: "Me",
            style = MaterialTheme.typography.headlineSmall.copy(
                color = tokens.Text_White,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        )
        Text(
            text = currentUser?.username ?: "",
            style = MaterialTheme.typography.bodyMedium.copy(color = tokens.Text_Muted)
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onOpenSettings,
            colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Settings")
        }
    }
}

@Composable
fun CompactEmptyDMs() {
    val tokens = rememberCosmicTokens()
    Box(modifier = Modifier.fillMaxSize().background(tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.DirectMessages,
                modifier = Modifier.width(240.dp).height(150.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("No direct messages yet", color = tokens.Text_Muted)
        }
    }
}

@Composable
fun CompactEmptyServers(onAdd: () -> Unit) {
    val tokens = rememberCosmicTokens()
    Box(modifier = Modifier.fillMaxSize().background(tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.Servers,
                modifier = Modifier.width(240.dp).height(150.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("No servers joined yet", color = tokens.Text_Muted)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)) {
                Text("Join a Server")
            }
        }
    }
}

