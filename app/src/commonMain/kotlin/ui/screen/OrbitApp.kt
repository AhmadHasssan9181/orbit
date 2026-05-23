package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import repository.AuthResult
import repository.MatrixOrbitRepository
import repository.OrbitRepository
import ui.model.*
import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.rememberCosmicTokens

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val App_Bg: Color @Composable get() = rememberCosmicTokens().Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent

/**
 * OrbitApp — Root composable.
 * Shows login screen if no repository is set, otherwise shows the main app layout.
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
            if (repository == null) {
                // Show login screen
                LoginScreen(
                    onLoginSuccess = { repo ->
                        repository = repo
                        // Extract auth info from the repo
                        (repo as? MatrixOrbitRepository)?.let { matrixRepo ->
                            matrixRepo.startSync()
                        }
                    },
                    modifier = modifier.fillMaxSize()
                )
            } else {
                MainAppContent(
                    repository = repository!!,
                    modifier = modifier
                )
            }
        }
    }
}

// ─── Main App Content — loads real data from Matrix ────────────

@Composable
private fun MainAppContent(
    repository: OrbitRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // ─── State: servers (spaces/joined rooms) ──────────────────
    var servers by remember { mutableStateOf<List<OrbitServer>>(emptyList()) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var selectedServerId by remember { mutableStateOf("") }
    var selectedChannelId by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    // ─── State: channels for selected server ───────────────────
    var channels by remember { mutableStateOf<List<OrbitChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }

    // ─── State: current user ───────────────────────────────────
    var currentUser by remember {
        mutableStateOf(
            OrbitUser(
                id = "me",
                username = "me",
                displayName = "Me",
                avatarColor = 0xFF7C6AF0.toInt(),
                status = UserStatus.ONLINE
            )
        )
    }

    // ─── Load servers on first composition ─────────────────────
    LaunchedEffect(Unit) {
        try {
            val loaded = repository.getSpaces()
            servers = if (loaded.isEmpty()) {
                // If no spaces found, create a single "Direct Messages" entry
                listOf(
                    OrbitServer(
                        id = "dm",
                        name = "Direct Messages",
                        iconColor = 0xFF7C6AF0.toInt(),
                        description = "Your conversations",
                        isDM = true
                    )
                )
            } else {
                loaded
            }
            if (servers.isNotEmpty() && selectedServerId.isEmpty()) {
                selectedServerId = servers.first().id
            }
        } catch (e: Exception) {
            // On error, show empty state
            servers = listOf(
                OrbitServer(
                    id = "dm",
                    name = "Direct Messages",
                    iconColor = 0xFF7C6AF0.toInt(),
                    description = "Your conversations",
                    isDM = true
                )
            )
            selectedServerId = "dm"
        }
        isLoadingServers = false
    }

    // ─── Load channels when selected server changes ────────────
    LaunchedEffect(selectedServerId) {
        if (selectedServerId.isBlank()) return@LaunchedEffect
        isLoadingChannels = true
        selectedChannelId = ""
        try {
            val loaded = repository.getChannelsForSpace(selectedServerId)
            channels = loaded
            // Auto-select first text channel
            val firstText = loaded.firstOrNull { it.type == ChannelType.TEXT }
            if (firstText != null) {
                selectedChannelId = firstText.id
            }
        } catch (e: Exception) {
            channels = emptyList()
        }
        isLoadingChannels = false
    }

    // ─── Loading state ─────────────────────────────────────────
    if (isLoadingServers) {
        Box(
            modifier = Modifier.fillMaxSize().background(App_Bg),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Accent)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Loading rooms...",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        return
    }

    // ─── Settings screen ───────────────────────────────────────
    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    // ─── Main layout ───────────────────────────────────────────
    val selectedServer = servers.find { it.id == selectedServerId }
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    var selectedWhatsAppTab by remember { mutableStateOf("Chats") }

    // Build categories from channels (group by categoryId)
    val categories = remember(channels) {
        channels
            .mapNotNull { it.categoryId }
            .distinct()
            .map { catId ->
                val firstCh = channels.firstOrNull { it.categoryId == catId }
                ChannelCategory(
                    id = catId,
                    name = catId.takeLast(8), // short ID as fallback name
                    channelId = firstCh?.id ?: ""
                )
            }
    }

    Row(modifier = Modifier.fillMaxSize().background(App_Bg)) {
        ServerRail(
            servers = servers,
            selectedServerId = selectedServerId,
            onServerClick = { id -> selectedServerId = id },
            selectedWhatsAppTab = selectedWhatsAppTab,
            onWhatsAppTabClick = { selectedWhatsAppTab = it }
        )

        if (selectedServer != null) {
            ChannelDrawer(
                server = selectedServer,
                categories = categories,
                channels = channels,
                selectedChannelId = selectedChannelId,
                currentUser = currentUser,
                onChannelClick = { id -> selectedChannelId = id },
                onOpenSettings = { showSettings = true },
                selectedWhatsAppTab = selectedWhatsAppTab
            )

            val selectedChannel = channels.find { it.id == selectedChannelId }
            when {
                isLoadingChannels ->
                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight().background(App_Bg),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Accent)
                    }

                selectedChannel == null ->
                    ChatEmptyState(
                        serverName = selectedServer.name,
                        channels = channels,
                        onChannelClick = { id -> selectedChannelId = id }
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
                modifier = Modifier.weight(1f).fillMaxHeight().background(App_Bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select a server to get started",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// ─── Chat screen with real repository ──────────────────────────

/**
 * Chat screen that uses the real repository for messages.
 */
@Composable
private fun ChatScreenWithRepo(
    repository: OrbitRepository,
    channel: OrbitChannel,
    showMemberList: Boolean
) {
    val scope = rememberCoroutineScope()
    var messages by remember(channel.id) { mutableStateOf<List<OrbitMessage>>(emptyList()) }
    var isLoading by remember(channel.id) { mutableStateOf(true) }

    // Load initial messages
    LaunchedEffect(channel.id) {
        isLoading = true
        try {
            val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
            messages = loaded
        } catch (e: Exception) {
            messages = emptyList()
        }
        isLoading = false
    }

    // Observe live messages
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
        onSend = { body ->
            scope.launch {
                try {
                    val result = repository.sendMessage(channel.id, body)
                    // Reload messages after sending
                    val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
                    messages = loaded
                } catch (_: Exception) {}
            }
        },
        showMemberList = showMemberList
    )
}