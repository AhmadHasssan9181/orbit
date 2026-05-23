package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.AuthResult
import repository.MatrixOrbitRepository
import repository.OrbitRepository
import ui.layout.LocalWindowSizeClass
import ui.layout.WindowSizeClass
import ui.layout.windowSizeClassFor
import ui.model.*
import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.rememberCosmicTokens
import repository.CredentialManager

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val App_Bg: Color @Composable get() = rememberCosmicTokens().Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent

/**
 * OrbitApp — Root composable.
 * Shows login screen if no repository is set, otherwise shows the main app layout.
 * Adapts between Compact (phone), Medium (tablet), and Expanded (desktop) layouts.
 */
@Composable
fun OrbitApp(modifier: Modifier = Modifier) {
    var repository by remember { mutableStateOf<OrbitRepository?>(null) }
    var authResult by remember { mutableStateOf<AuthResult?>(null) }
    var isLiteMode by remember { mutableStateOf(false) }
    var activeTheme by remember { mutableStateOf(ui.theme.Theme.Default) }

    CompositionLocalProvider(
        LocalIsLiteMode provides isLiteMode,
        LocalOnLiteModeChange provides { isLiteMode = it },
        ui.theme.LocalActiveTheme provides activeTheme,
        ui.theme.LocalOnThemeChange provides { activeTheme = it }
    ) {
        ui.theme.StoatTheme(
            requestedTheme = activeTheme,
            requestedUserInterfaceFont = ui.theme.UserInterfaceFont.Default
        ) {
            // Detect window size and inject into composition tree
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val windowSize = windowSizeClassFor(maxWidth)

                CompositionLocalProvider(LocalWindowSizeClass provides windowSize) {
                    LaunchedEffect(Unit) {
                        val token = CredentialManager.accessToken
                        val hs = CredentialManager.homeserverUrl
                        val uId = CredentialManager.userId
                        if (token != null && hs != null && uId != null) {
                            val repo = MatrixOrbitRepository(hs)
                            repo.setAccessToken(token)
                            repo.setUserId(uId)
                            repository = repo
                            repo.startSync()
                        }
                    }

                    if (repository == null) {
                        LoginScreen(
                            onLoginSuccess = { repo ->
                                repository = repo
                                (repo as? MatrixOrbitRepository)?.let { matrixRepo ->
                                    matrixRepo.startSync()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        MainAppContent(
                            repository = repository!!,
                            windowSize = windowSize,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

// ─── Main App Content — adaptive layout ────────────────────────

@Composable
private fun MainAppContent(
    repository: OrbitRepository,
    windowSize: WindowSizeClass,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // ─── Shared state ──────────────────────────────────────────
    var servers by remember { mutableStateOf<List<OrbitServer>>(emptyList()) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var selectedServerId by remember { mutableStateOf("") }
    var selectedChannelId by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showJoinServer by remember { mutableStateOf(false) }

    var channels by remember { mutableStateOf<List<OrbitChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }

    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }

    // Compact navigation state
    var selectedBottomTab by remember { mutableStateOf(BottomNavTab.DMs) }
    // Navigation stack for compact: null = list, non-null = chat open
    var compactOpenChannelId by remember { mutableStateOf<String?>(null) }

    // ─── Load current user ──────────────────────────────────────
    LaunchedEffect(Unit) {
        val user = repository.getCurrentUser()
        currentUser = user ?: OrbitUser(
            id = "me", username = "me", displayName = "Me",
            avatarColor = 0xFF7C6AF0.toInt(), status = UserStatus.ONLINE
        )
    }

    // ─── Load servers ───────────────────────────────────────────
    LaunchedEffect(Unit) {
        try {
            val loaded = repository.getSpaces()
            servers = if (loaded.isEmpty()) {
                listOf(OrbitServer(
                    id = "dm", name = "Direct Messages",
                    iconColor = 0xFF7C6AF0.toInt(), description = "Your conversations", isDM = true
                ))
            } else loaded
            if (servers.isNotEmpty() && selectedServerId.isEmpty()) {
                selectedServerId = servers.first().id
            }
        } catch (e: Exception) {
            servers = listOf(OrbitServer(
                id = "dm", name = "Direct Messages",
                iconColor = 0xFF7C6AF0.toInt(), description = "Your conversations", isDM = true
            ))
            selectedServerId = "dm"
        }
        isLoadingServers = false
    }

    // Listen for live space updates
    LaunchedEffect(Unit) {
        (repository as? MatrixOrbitRepository)?.observeSpaces()?.collect { newServers ->
            servers = newServers
        }
    }

    // ─── Load channels when server changes ──────────────────────
    LaunchedEffect(selectedServerId) {
        if (selectedServerId.isBlank()) return@LaunchedEffect
        isLoadingChannels = true
        selectedChannelId = ""
        compactOpenChannelId = null
        try {
            val loaded = repository.getChannelsForSpace(selectedServerId)
            channels = loaded
            val firstText = loaded.firstOrNull { it.type == ChannelType.TEXT }
            if (firstText != null && windowSize.isExpanded) {
                // Auto-select only on desktop — on phone/tablet user taps to navigate
                selectedChannelId = firstText.id
            }
        } catch (e: Exception) {
            channels = emptyList()
        }
        isLoadingChannels = false
    }

    // Live channel updates
    LaunchedEffect(selectedServerId) {
        if (selectedServerId.isBlank()) return@LaunchedEffect
        repository.observeChannelList(selectedServerId).collect { newChannels ->
            channels = newChannels
        }
    }

    // ─── Loading screen ─────────────────────────────────────────
    if (isLoadingServers) {
        Box(
            modifier = Modifier.fillMaxSize().background(App_Bg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text("Loading rooms...", color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    // ─── Settings screen ────────────────────────────────────────
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, modifier = Modifier.fillMaxSize())
        return
    }

    // ─── Route to appropriate layout ────────────────────────────
    when (windowSize) {
        WindowSizeClass.Compact -> CompactLayout(
            servers = servers,
            channels = channels,
            selectedServerId = selectedServerId,
            selectedChannelId = selectedChannelId,
            currentUser = currentUser,
            isLoadingChannels = isLoadingChannels,
            selectedBottomTab = selectedBottomTab,
            compactOpenChannelId = compactOpenChannelId,
            repository = repository,
            onServerSelect = { id ->
                selectedServerId = id
                selectedBottomTab = BottomNavTab.DMs
            },
            onChannelClick = { id ->
                selectedChannelId = id
                compactOpenChannelId = id
            },
            onBackFromChat = { compactOpenChannelId = null },
            onBottomTabSelect = { tab -> selectedBottomTab = tab },
            onOpenSettings = { showSettings = true },
            onAddServer = { showJoinServer = true },
            modifier = Modifier.fillMaxSize()
        )

        WindowSizeClass.Medium -> MediumLayout(
            servers = servers,
            channels = channels,
            selectedServerId = selectedServerId,
            selectedChannelId = selectedChannelId,
            currentUser = currentUser,
            isLoadingChannels = isLoadingChannels,
            repository = repository,
            onServerClick = { id -> selectedServerId = id },
            onChannelClick = { id -> selectedChannelId = id },
            onOpenSettings = { showSettings = true },
            onAddServer = { showJoinServer = true },
            modifier = Modifier.fillMaxSize()
        )

        WindowSizeClass.Expanded -> ExpandedLayout(
            servers = servers,
            channels = channels,
            selectedServerId = selectedServerId,
            selectedChannelId = selectedChannelId,
            currentUser = currentUser,
            isLoadingChannels = isLoadingChannels,
            repository = repository,
            onServerClick = { id -> selectedServerId = id },
            onChannelClick = { id -> selectedChannelId = id },
            onOpenSettings = { showSettings = true },
            onAddServer = { showJoinServer = true },
            modifier = Modifier.fillMaxSize()
        )
    }

    if (showJoinServer) {
        JoinServerScreen(
            repository = repository,
            onClose = { showJoinServer = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────
// COMPACT LAYOUT (phones < 600dp)
// Full-screen panes, bottom navigation
// ─────────────────────────────────────────────────────────────

@Composable
private fun CompactLayout(
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
                            onChannelClick = onChannelClick,
                            onOpenSettings = onOpenSettings,
                            isLoadingChannels = isLoadingChannels
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
                            onServerSelect = onServerSelect,
                            onChannelClick = onChannelClick,
                            onOpenSettings = onOpenSettings,
                            onAddServer = onAddServer
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
private fun CompactChannelList(
    server: OrbitServer,
    channels: List<OrbitChannel>,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    isLoadingChannels: Boolean
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
            IconButton(onClick = {}) {
                Icon(Icons.Default.Edit, contentDescription = "New Chat",
                    tint = tokens.Text_Muted, modifier = Modifier.size(20.dp))
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
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CompactServerList(
    servers: List<OrbitServer>,
    selectedServerId: String,
    channels: List<OrbitChannel>,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    onServerSelect: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit
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
                Icon(Icons.Default.Add, contentDescription = "Add Server",
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
                    ChannelCategory(id = catId, name = catId.takeLast(8), channelId = firstCh?.id ?: "")
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
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
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
private fun CompactServerChip(server: OrbitServer, isSelected: Boolean, onClick: () -> Unit) {
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
                Text(
                    text = server.name.take(2).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
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
private fun CompactSearchPane(channels: List<OrbitChannel>, onChannelClick: (String) -> Unit) {
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
                        imageVector = if (ch.type == ChannelType.VOICE) Icons.Default.VolumeUp else Icons.Default.Tag,
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

@Composable
private fun CompactProfilePane(currentUser: OrbitUser?, onOpenSettings: () -> Unit) {
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
                Text(
                    text = (currentUser?.displayName ?: "Me").take(2).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
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
private fun CompactEmptyDMs() {
    val tokens = rememberCosmicTokens()
    Box(modifier = Modifier.fillMaxSize().background(tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ChatBubbleOutline, contentDescription = null,
                tint = tokens.Text_Muted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No direct messages yet", color = tokens.Text_Muted)
        }
    }
}

@Composable
private fun CompactEmptyServers(onAdd: () -> Unit) {
    val tokens = rememberCosmicTokens()
    Box(modifier = Modifier.fillMaxSize().background(tokens.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.GridView, contentDescription = null,
                tint = tokens.Text_Muted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text("No servers joined yet", color = tokens.Text_Muted)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)) {
                Text("Join a Server")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// MEDIUM LAYOUT (tablets 600–839dp)
// ServerRail + modal ChannelDrawer + full-screen chat
// ─────────────────────────────────────────────────────────────

@Composable
private fun MediumLayout(
    servers: List<OrbitServer>,
    channels: List<OrbitChannel>,
    selectedServerId: String,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    repository: OrbitRepository,
    onServerClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selectedServer = servers.find { it.id == selectedServerId }
    val isLiteMode = LocalIsLiteMode.current

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = tokens.Drawer_Bg,
                drawerContentColor = tokens.Text_White
            ) {
                if (selectedServer != null) {
                    val categories = channels.mapNotNull { it.categoryId }.distinct()
                        .map { catId ->
                            val firstCh = channels.firstOrNull { it.categoryId == catId }
                            ChannelCategory(id = catId, name = catId.takeLast(8), channelId = firstCh?.id ?: "")
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
                        onChannelClick = { id ->
                            onChannelClick(id)
                            scope.launch { drawerState.close() }
                        },
                        onOpenSettings = onOpenSettings,
                        selectedWhatsAppTab = if (isLiteMode) "Chats" else ""
                    )
                }
            }
        }
    ) {
        Row(modifier = Modifier.fillMaxSize().background(tokens.Bg)) {
            // ServerRail at 64dp (slimmer than desktop)
            ServerRail(
                servers = servers,
                selectedServerId = selectedServerId,
                onServerClick = onServerClick,
                onAddServerClick = onAddServer,
                modifier = Modifier.width(64.dp).fillMaxHeight()
            )

            // Main content
            val selectedChannel = channels.find { it.id == selectedChannelId }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when {
                    isLoadingChannels -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = tokens.Accent) }

                    selectedChannel == null -> ChatEmptyState(
                        serverName = selectedServer?.name ?: "Orbit",
                        channels = channels,
                        onChannelClick = onChannelClick
                    )

                    selectedChannel.type == ChannelType.TEXT ->
                        ChatScreenWithRepo(
                            repository = repository,
                            channel = selectedChannel,
                            showMemberList = false,
                            onBack = { scope.launch { drawerState.open() } }
                        )

                    selectedChannel.type == ChannelType.VOICE ->
                        VoiceChannelPlaceholder(channel = selectedChannel)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// EXPANDED LAYOUT (desktop / landscape tablet ≥ 840dp)
// Full 3-pane side-by-side — the original layout
// ─────────────────────────────────────────────────────────────

@Composable
private fun ExpandedLayout(
    servers: List<OrbitServer>,
    channels: List<OrbitChannel>,
    selectedServerId: String,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    repository: OrbitRepository,
    onServerClick: (String) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    val isLiteMode = LocalIsLiteMode.current
    var selectedWhatsAppTab by remember { mutableStateOf("Chats") }
    val selectedServer = servers.find { it.id == selectedServerId }

    val categories = remember(channels) {
        channels.mapNotNull { it.categoryId }.distinct()
            .map { catId ->
                val firstCh = channels.firstOrNull { it.categoryId == catId }
                ChannelCategory(id = catId, name = catId.takeLast(8), channelId = firstCh?.id ?: "")
            }
    }

    Row(modifier = modifier.background(tokens.Bg)) {
        ServerRail(
            servers = servers,
            selectedServerId = selectedServerId,
            onServerClick = onServerClick,
            onAddServerClick = onAddServer,
            selectedWhatsAppTab = selectedWhatsAppTab,
            onWhatsAppTabClick = { selectedWhatsAppTab = it }
        )

        if (selectedServer != null) {
            ChannelDrawer(
                server = selectedServer,
                categories = categories,
                channels = channels,
                selectedChannelId = selectedChannelId,
                currentUser = currentUser ?: OrbitUser(
                    id = "me", username = "me", displayName = "Me",
                    avatarColor = 0xFF7C6AF0.toInt(), status = UserStatus.ONLINE
                ),
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
                selectedWhatsAppTab = selectedWhatsAppTab
            )

            val selectedChannel = channels.find { it.id == selectedChannelId }
            when {
                isLoadingChannels ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().background(tokens.Bg),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(color = tokens.Accent) }

                selectedChannel == null ->
                    ChatEmptyState(
                        serverName = selectedServer.name,
                        channels = channels,
                        onChannelClick = onChannelClick
                    )

                selectedChannel.type == ChannelType.TEXT ->
                    ChatScreenWithRepo(
                        repository = repository,
                        channel = selectedChannel,
                        showMemberList = !isLiteMode
                    )

                selectedChannel.type == ChannelType.VOICE ->
                    VoiceChannelPlaceholder(channel = selectedChannel)
            }
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight().background(tokens.Bg),
                contentAlignment = Alignment.Center
            ) {
                Text("No Server Selected", color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ─── Chat screen with real repository ──────────────────────────

/**
 * Chat screen that uses the real repository for messages.
 * Accepts an optional onBack callback for compact/medium layouts.
 */
@Composable
fun ChatScreenWithRepo(
    repository: OrbitRepository,
    channel: OrbitChannel,
    showMemberList: Boolean,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var messages by remember(channel.id) { mutableStateOf<List<OrbitMessage>>(emptyList()) }
    var isLoading by remember(channel.id) { mutableStateOf(true) }
    var members by remember(channel.id) { mutableStateOf<List<OrbitUser>>(emptyList()) }
    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }

    LaunchedEffect(channel.id) {
        isLoading = true
        try {
            currentUser = repository.getCurrentUser()
            val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
            messages = loaded
            if (showMemberList) {
                members = repository.getRoomMembers(channel.id)
            }
        } catch (e: Exception) {
            messages = emptyList()
            members = emptyList()
        }
        isLoading = false
    }

    LaunchedEffect(channel.id) {
        repository.observeMessages(channel.id).collect { newMessages ->
            if (newMessages.isNotEmpty()) {
                messages = (messages + newMessages).distinctBy { it.id }.sortedBy { it.timestamp }
            }
        }
    }

    ChatScreen(
        channel = channel,
        messages = messages,
        members = members,
        currentUser = currentUser,
        onSend = { body ->
            scope.launch {
                try {
                    repository.sendMessage(channel.id, body)
                    val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
                    messages = loaded
                } catch (_: Exception) {}
            }
        },
        onSendAttachment = { bytes, filename, mimeType ->
            scope.launch {
                try {
                    val mxcUrl = repository.uploadMedia(bytes, mimeType, filename).getOrThrow()
                    when {
                        mimeType.startsWith("image/") -> {
                            repository.sendImageMessage(channel.id, mxcUrl, filename, mimeType, null, null, bytes.size.toLong())
                        }
                        mimeType.startsWith("audio/") -> {
                            repository.sendAudioMessage(channel.id, mxcUrl, filename, mimeType, null, bytes.size.toLong())
                        }
                        else -> {
                            repository.sendFileMessage(channel.id, mxcUrl, filename, mimeType, bytes.size.toLong())
                        }
                    }
                    val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
                    messages = loaded
                } catch (_: Exception) {}
            }
        },
        showMemberList = showMemberList,
        onBack = onBack,
        modifier = modifier
    )
}