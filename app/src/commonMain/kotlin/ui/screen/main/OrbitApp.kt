package ui.screen.main

import ui.screen.chat.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import repository.AuthResult
import repository.AuthStatus
import repository.CredentialManager
import repository.MatrixOrbitRepository
import repository.MemberRoleInfo
import repository.OrbitRepository
import repository.SettingsManager
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.assets.OrbitServerAssetIcon
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.layout.WindowSizeClass
import ui.layout.windowSizeClassFor
import ui.model.ChannelCategory
import ui.model.ChannelType
import ui.model.MessageContent
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.CallState
import webrtc.LocalCallManager
import webrtc.createWebRtcClient

import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*
import ui.screen.ChatScreen
import ui.icons.*
import ui.theme.*

// ─── Cosmic Design Tokens ──────────────────────────────────────

private val coilHttpClient = HttpClient(CIO) {
    install(io.ktor.client.plugins.logging.Logging) {
        logger = object : io.ktor.client.plugins.logging.Logger {
            override fun log(message: String) {
                // Gate at the source: LogLevel.INFO already builds header strings,
                // so don't pay for them unless debug logging is actually on.
                if (util.OrbitLog.debugEnabled) println("DEBUG: [Coil-Ktor] $message")
            }
        }
        level = io.ktor.client.plugins.logging.LogLevel.INFO
    }
}.apply {
    // Dynamic header injection interceptor
    requestPipeline.intercept(io.ktor.client.request.HttpRequestPipeline.Before) {
        repository.CredentialManager.accessToken?.let { token ->
            val url = context.url.buildString()
            val host = context.url.host
            val hsHost = repository.CredentialManager.homeserverUrl
                ?.removePrefix("https://")?.removePrefix("http://")
                ?.trimEnd('/')
            // Attach auth for any Matrix API request (path-based) or matching homeserver host
            val isMatrixApi = url.contains("/_matrix/")
            val isHomeserver = hsHost != null && host.contains(hsHost, ignoreCase = true)
            if (isMatrixApi || isHomeserver) {
                context.header("Authorization", "Bearer $token")
            }
        }
    }
}

/**
 * OrbitApp — Root composable.
 * Shows login screen if no repository is set, otherwise shows the main app layout.
 * Adapts between Compact (phone), Medium (tablet), and Expanded (desktop) layouts.
 */
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
fun OrbitApp(
    modifier: Modifier = Modifier
) {
    var repository by remember { mutableStateOf<OrbitRepository?>(null) }
    var authResult by remember { mutableStateOf<AuthResult?>(null) }
    
    val settingsManager = remember { SettingsManager() }
    val isLiteMode by settingsManager.isLiteMode.collectAsState(false)
    val activeTheme by settingsManager.activeTheme.collectAsState(ui.theme.Theme.Default)
    val messageDensity by settingsManager.messageDensity.collectAsState("Comfortable")
    val fontSize by settingsManager.fontSize.collectAsState(50f)
    val reduceMotion by settingsManager.reduceMotion.collectAsState(false)
    val mentionsOnly by settingsManager.mentionsOnly.collectAsState(true)
    val notificationSound by settingsManager.notificationSound.collectAsState("Chime")
    val chatWallpaper by settingsManager.chatWallpaper.collectAsState("auto")
    val colorSaturation by settingsManager.colorSaturation.collectAsState(100f)

    val showTimestamps by settingsManager.showTimestamps.collectAsState(true)
    val compactMode by settingsManager.compactMode.collectAsState(false)
    val showRoleColors by settingsManager.showRoleColors.collectAsState(true)
    val showMemberList by settingsManager.showMemberList.collectAsState(true)
    val enterSend by settingsManager.enterSend.collectAsState(true)
    val use24HourClock by settingsManager.use24HourClock.collectAsState(false)
    val dateFormat by settingsManager.dateFormat.collectAsState("DD/MM/YYYY")
    val scope = rememberCoroutineScope()

    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = coilHttpClient))
            }
            .crossfade(true)
            .build()
    }

    CompositionLocalProvider(
        LocalIsLiteMode provides isLiteMode,
        LocalOnLiteModeChange provides { settingsManager.setLiteMode(it) },
        ui.theme.LocalActiveTheme provides activeTheme,
        ui.theme.LocalOnThemeChange provides { settingsManager.setTheme(it) },
        ui.theme.LocalMessageDensity provides messageDensity,
        ui.theme.LocalFontSize provides fontSize,
        ui.theme.LocalReduceMotion provides reduceMotion,
        ui.theme.LocalMentionsOnly provides mentionsOnly,
        ui.theme.LocalNotificationSound provides notificationSound,
        ui.theme.LocalChatWallpaper provides chatWallpaper,
        ui.theme.LocalShowTimestamps provides showTimestamps,
        ui.theme.LocalCompactMode provides compactMode,
        ui.theme.LocalShowRoleColors provides showRoleColors,
        ui.theme.LocalShowMemberList provides showMemberList,
        ui.theme.LocalEnterSend provides enterSend,
        ui.theme.LocalUse24HourClock provides use24HourClock,
        ui.theme.LocalDateFormat provides dateFormat
    ) {
        val baseDensity = androidx.compose.ui.platform.LocalDensity.current
        val customDensity = androidx.compose.ui.unit.Density(baseDensity.density, fontScale = fontSize / 50f)
        
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides customDensity) {
            ui.theme.StoatTheme(
                requestedTheme = activeTheme,
                requestedUserInterfaceFont = ui.theme.UserInterfaceFont.Default
            ) {
            // Detect window size and inject into composition tree
            BoxWithConstraints(
                modifier = modifier.fillMaxSize()
            ) {
                val windowSize = windowSizeClassFor(maxWidth)

                CompositionLocalProvider(LocalWindowSizeClass provides windowSize) {
                    val authStatus by (repository?.authStatus?.collectAsState(AuthStatus.Authenticated) ?: remember { mutableStateOf(AuthStatus.Authenticated) })

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

                    if (repository == null || authStatus is AuthStatus.Unauthenticated) {
                        LoginScreen(
                            onLoginSuccess = { repo ->
                                repository = repo
                                (repo as? MatrixOrbitRepository)?.let { matrixRepo ->
                                    matrixRepo.startSync()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (authStatus is AuthStatus.Error) {
                        SessionErrorScreen(
                            error = (authStatus as AuthStatus.Error).message,
                            onRetry = {
                                (repository as? MatrixOrbitRepository)?.startSync()
                            },
                            onLogout = {
                                scope.launch {
                                    repository?.logout()
                                    repository = null
                                }
                            }
                        )
                    } else {
                        MainAppContent(
                            repository = repository!!,
                            settingsManager = settingsManager,
                            windowSize = windowSize,
                            onLogoutRequested = {
                                scope.launch {
                                    repository?.logout()
                                    repository = null
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
            }
        }
    }
}

// ─── Main App Content — adaptive layout ────────────────────────

fun String.redactedIceUrl(): String {
    return substringBefore('?')
}

@Composable
private fun MainAppContent(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    windowSize: WindowSizeClass,
    onLogoutRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val verificationState by (repository.activeDeviceVerification?.collectAsState(null) ?: remember { mutableStateOf(null) })
    VerificationOverlay(verificationState as? de.connect2x.trixnity.client.verification.ActiveDeviceVerification)

    // ─── WebRTC Call Manager ────────────────────────────────────
    val callManager = WebRtcEffects(repository, settingsManager, scope)
    // ─── Shared state ──────────────────────────────────────────
    var servers by remember { mutableStateOf<List<OrbitServer>>(emptyList()) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var selectedServerId by remember { mutableStateOf("") }
    var selectedChannelId by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showJoinServer by remember { mutableStateOf(false) }
    var showCreateRoom by remember { mutableStateOf(false) }
    var showServerRoles by remember { mutableStateOf(false) }

    var channels by remember { mutableStateOf<List<OrbitChannel>>(emptyList()) }
    var isLoadingChannels by remember { mutableStateOf(false) }

    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }

    // Presence: chosen status persists locally and is (re)applied to the homeserver.
    val myStatusStored by settingsManager.myStatus.collectAsState("ONLINE")
    val myActiveStatus = runCatching { UserStatus.valueOf(myStatusStored) }.getOrDefault(UserStatus.ONLINE)

    LaunchedEffect(myActiveStatus) {
        runCatching { repository.setPresence(myActiveStatus) }
    }

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

    val displayCurrentUser = currentUser?.copy(status = myActiveStatus)

    // ─── Unread tracking ────────────────────────────────────────
    // Counts keyed by matrix room id; incremented from the global sync
    // arrivals stream while the room isn't open, cleared when it is opened.
    val unreadCounts = remember { mutableStateMapOf<String, Int>() }
    var channelServerById by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val notificationModes = remember { mutableStateMapOf<String, String>() }
    val firstUnreadByRoom = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(servers) {
        val index = mutableMapOf<String, String>()
        servers.forEach { server ->
            runCatching { repository.getChannelsForSpace(server.id) }.getOrNull()?.forEach { ch ->
                index[ch.id] = server.id
                ch.matrixRoomId?.let { index[it] = server.id }
                launch {
                    runCatching {
                        settingsManager.roomNotificationMode(ch.id).collect { mode ->
                            notificationModes[ch.id] = mode
                            ch.matrixRoomId?.let { mr -> notificationModes[mr] = mode }
                        }
                    }
                }
            }
        }
        channelServerById = index
    }

    LaunchedEffect(Unit) {
        repository.observeIncomingMessages().collect { (roomId, msg) ->
            if (msg.content is MessageContent.Edit || msg.content is MessageContent.Reaction) return@collect
            when (notificationModes[roomId] ?: "ALL") {
                "MUTE" -> return@collect
                "MENTIONS" -> {
                    val ownId = currentUser?.id ?: return@collect
                    val localpart = ownId.substringBefore(':')
                    val mentioned = msg.body.contains("@$localpart") || msg.body.contains("@room")
                    if (!mentioned) return@collect
                }
            }
            val ownId = currentUser?.id
            if (ownId != null && (msg.userId == ownId || msg.userId.endsWith(ownId) || ownId.endsWith(msg.userId))) return@collect
            if (roomId == selectedChannelId || roomId == compactOpenChannelId) return@collect
            unreadCounts[roomId] = (unreadCounts[roomId] ?: 0) + 1
            // Arrivals stream is oldest-first: remember the first message that
            // landed while the room was away, so the in-chat separator can point at it.
            if (roomId !in firstUnreadByRoom) firstUnreadByRoom[roomId] = msg.id
        }
    }

    var lastOpenRoomIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(selectedChannelId, compactOpenChannelId) {
        val nowOpen = setOfNotNull(selectedChannelId.takeIf { it.isNotBlank() }, compactOpenChannelId)
        // Unread markers survive the channel being OPEN (that's when the separator
        // renders); they are dropped once you leave the room again.
        (lastOpenRoomIds - nowOpen).forEach { closedId ->
            firstUnreadByRoom.remove(closedId)
            channels.firstOrNull { it.id == closedId }?.matrixRoomId?.let { firstUnreadByRoom.remove(it) }
        }
        lastOpenRoomIds = nowOpen
        selectedChannelId.takeIf { it.isNotBlank() }?.let { unreadCounts.remove(it) }
        compactOpenChannelId?.let { unreadCounts.remove(it) }
    }

    val unreadByServer = remember(unreadCounts.toMap(), channelServerById) {
        val totals = mutableMapOf<String, Int>()
        unreadCounts.forEach { (roomId, count) ->
            channelServerById[roomId]?.let { sid -> totals[sid] = (totals[sid] ?: 0) + count }
        }
        totals
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
            modifier = Modifier
                .fillMaxSize()
                .background(rememberCosmicTokens().Bg),
            contentAlignment = Alignment.Center
        ) {
            AppBootState()
        }
        return
    }

    // Alt+←/→ navigation history over channel switches, plus Ctrl+K.
    var navBackStack by remember { mutableStateOf(listOf<String>()) }
    var navForwardStack by remember { mutableStateOf(listOf<String>()) }
    var showQuickSwitcher by remember { mutableStateOf(false) }

    fun navigateTo(id: String) {
        if (selectedChannelId.isNotBlank() && selectedChannelId != id) {
            navBackStack = navBackStack + selectedChannelId
            navForwardStack = emptyList()
        }
        selectedChannelId = id
        compactOpenChannelId = id
    }

    fun navigateBack() {
        val stack = navBackStack
        if (stack.isEmpty()) return
        val target = stack.last()
        navBackStack = stack.dropLast(1)
        if (selectedChannelId.isNotBlank()) navForwardStack = navForwardStack + selectedChannelId
        selectedChannelId = target
        compactOpenChannelId = target
    }

    fun navigateForward() {
        val stack = navForwardStack
        if (stack.isEmpty()) return
        val target = stack.last()
        navForwardStack = stack.dropLast(1)
        if (selectedChannelId.isNotBlank()) navBackStack = navBackStack + selectedChannelId
        selectedChannelId = target
        compactOpenChannelId = target
    }

    // Quick switcher searches every server's channels, loaded when opened.
    var quickSwitcherEntries by remember { mutableStateOf<List<Pair<OrbitServer, OrbitChannel>>>(emptyList()) }
    LaunchedEffect(showQuickSwitcher, servers) {
        if (!showQuickSwitcher) return@LaunchedEffect
        quickSwitcherEntries = servers.flatMap { server ->
            try {
                repository.getChannelsForSpace(server.id).map { server to it }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    DisposableEffect(Unit) {
        webrtc.CallShortcuts.onNavigateBack = { navigateBack() }
        webrtc.CallShortcuts.onNavigateForward = { navigateForward() }
        webrtc.CallShortcuts.onQuickSwitcher = { showQuickSwitcher = true }
        onDispose {
            webrtc.CallShortcuts.onNavigateBack = null
            webrtc.CallShortcuts.onNavigateForward = null
            webrtc.CallShortcuts.onQuickSwitcher = null
        }
    }

    val handleChannelClick: (String) -> Unit = { id ->
        navigateTo(id)
    }

    // ─── Settings screen ────────────────────────────────────────
    if (showSettings) {
        SettingsScreen(
            repository = repository,
            settingsManager = settingsManager,
            onBack = { showSettings = false },
            onLogout = {
                onLogoutRequested()
                showSettings = false
            },
            modifier = Modifier.fillMaxSize()
        )
        return
    }

    // ─── Route to appropriate layout ────────────────────────────
    CompositionLocalProvider(
        LocalCallManager provides callManager,
        ui.theme.LocalRoomUnreadMarkers provides firstUnreadByRoom.toMap()
    ) {
        Box(modifier = modifier) {
            when (windowSize) {
                WindowSizeClass.Compact -> CompactLayout(
                    servers = servers,
                    channels = channels,
                    selectedServerId = selectedServerId,
                    selectedChannelId = selectedChannelId,
                    currentUser = displayCurrentUser,
                    isLoadingChannels = isLoadingChannels,
                    selectedBottomTab = selectedBottomTab,
                    compactOpenChannelId = compactOpenChannelId,
                    repository = repository,
                    onServerSelect = { id ->
                        selectedServerId = id
                        selectedBottomTab = BottomNavTab.DMs
                    },
                    onChannelClick = handleChannelClick,
                    onBackFromChat = { compactOpenChannelId = null },
                    onBottomTabSelect = { tab -> selectedBottomTab = tab },
                    onOpenSettings = { showSettings = true },
                    onAddServer = { showJoinServer = true },
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
                    unreadCounts = unreadCounts.toMap(),
                    unreadByServer = unreadByServer,
                    modifier = Modifier.fillMaxSize()
                )

                WindowSizeClass.Medium -> MediumLayout(
                    servers = servers,
                    channels = channels,
                    selectedServerId = selectedServerId,
                    selectedChannelId = selectedChannelId,
                    currentUser = displayCurrentUser,
                    isLoadingChannels = isLoadingChannels,
                    repository = repository,
                    callManager = callManager,
                    onServerClick = { id -> selectedServerId = id },
                    onChannelClick = handleChannelClick,
                    onOpenSettings = { showSettings = true },
                    onAddServer = { showJoinServer = true },
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
                    unreadCounts = unreadCounts.toMap(),
                    unreadByServer = unreadByServer,
                    modifier = Modifier.fillMaxSize()
                )

                WindowSizeClass.Expanded -> ExpandedLayout(
                    servers = servers,
                    channels = channels,
                    selectedServerId = selectedServerId,
                    selectedChannelId = selectedChannelId,
                    currentUser = displayCurrentUser,
                    isLoadingChannels = isLoadingChannels,
                    repository = repository,
                    callManager = callManager,
                    onServerClick = { id -> selectedServerId = id },
                    onChannelClick = handleChannelClick,
                    onOpenSettings = { showSettings = true },
                    onAddServer = { showJoinServer = true },
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
                    unreadCounts = unreadCounts.toMap(),
                    unreadByServer = unreadByServer,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (showJoinServer) {
                JoinServerScreen(
                    repository = repository,
                    onClose = { showJoinServer = false },
                    onServerCreated = { serverId ->
                        selectedServerId = serverId
                        selectedBottomTab = BottomNavTab.Servers
                    },
                    onServerJoined = { serverId ->
                        selectedServerId = serverId
                        selectedBottomTab = BottomNavTab.Servers
                    }
                )
            }

            val createRoomServer = servers.find { it.id == selectedServerId && !it.isDM }
            if (showCreateRoom && createRoomServer != null) {
                CreateRoomDialog(
                    repository = repository,
                    server = createRoomServer,
                    onClose = { showCreateRoom = false },
                    onRoomCreated = { channelId ->
                        selectedChannelId = channelId
                        selectedBottomTab = BottomNavTab.Servers
                        if (windowSize == WindowSizeClass.Compact) {
                            compactOpenChannelId = channelId
                        }
                        scope.launch {
                            channels = repository.getChannelsForSpace(createRoomServer.id)
                        }
                    }
                )
            }

            val rolesServer = servers.find { it.id == selectedServerId && !it.isDM }
            if (showServerRoles && rolesServer != null) {
                ServerRolesDialog(
                    repository = repository,
                    server = rolesServer,
                    onDismiss = { showServerRoles = false }
                )
            }

            if (showQuickSwitcher) {
                QuickSwitcherDialog(
                    entries = quickSwitcherEntries,
                    onSelect = { id ->
                        showQuickSwitcher = false
                        navigateTo(id)
                    },
                    onDismiss = { showQuickSwitcher = false }
                )
            }

            val activeRoomId by callManager.activeRoomIdFlow.collectAsState()
            val callState by callManager.callState.collectAsState()
            val isGroupCall by callManager.isGroupCall.collectAsState()
            val activeCallChannel = channels.find { it.matrixRoomId == activeRoomId || it.id == activeRoomId }
            val callerName = activeCallChannel?.name ?: "Friend"

            // Keep a minimized call mounted even if its channel can't be resolved
            // (e.g. navigated into a server whose channel list differs), so the
            // mini view has somewhere to render instead of the call vanishing.
            val callActive = callState != CallState.IDLE && callState != CallState.ENDED
            val shouldShowPopup =
                (activeCallChannel != null && activeCallChannel.categoryId == "dm") ||
                    (callActive && isGroupCall) ||
                    // P2P calls may live outside the dm category or have their channel
                    // unresolvable entirely — they must still surface their UI.
                    (callActive && !isGroupCall)
            // While the user is looking at a GROUP call's own channel, the embedded
            // VoiceChannelScreen/ChatScreen surface already renders there — a
            // second fullscreen overlay here blanketed the entire window and made
            // navigation impossible. P2P calls have no embedded surface at all,
            // so this popup is their ONLY UI and must render even in-channel
            // (otherwise an incoming DM invite rings invisibly until it expires).
            val viewingCallChannel = activeCallChannel != null && selectedChannelId == activeCallChannel.id

            LaunchedEffect(selectedChannelId, activeRoomId, channels) {
                if (activeRoomId != null) {
                    val callChannel = channels.find { it.matrixRoomId == activeRoomId || it.id == activeRoomId }
                    if (callChannel == null || selectedChannelId != callChannel.id) {
                        callManager.isMinimized.value = true
                    } else {
                        // Back inside the call's own channel: the embedded surface renders
                        // expanded here, and a stale minimized flag would draw a second
                        // floating bar next to nothing.
                        callManager.isMinimized.value = false
                    }
                }
            }

            if (shouldShowPopup && (!viewingCallChannel || !isGroupCall)) {
                if (isGroupCall) {
                    LiveKitGroupCallSurface(
                        callManager = callManager,
                        modifier = Modifier.fillMaxSize(),
                        remoteFallbackName = callerName,
                        forceMinimized = true,
                        onRestore = {
                            val target = activeCallChannel?.id
                            if (target != null) navigateTo(target)
                            callManager.isMinimized.value = false
                        }
                    )
                } else {
                    CallScreen(
                        callManager = callManager,
                        callerName = callerName,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// COMPACT LAYOUT (phones < 600dp)
// Full-screen panes, bottom navigation
// ─────────────────────────────────────────────────────────────

@Composable
fun ChatScreenWithRepo(
    repository: OrbitRepository,
    channel: OrbitChannel,
    showMemberList: Boolean,
    isDirectMessage: Boolean = false,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var messages by remember(channel.id) { mutableStateOf<List<OrbitMessage>>(emptyList()) }
    var isLoading by remember(channel.id) { mutableStateOf(true) }
    var members by remember(channel.id) { mutableStateOf<List<OrbitUser>>(emptyList()) }
    var memberRoles by remember(channel.id) { mutableStateOf<Map<String, MemberRoleInfo>>(emptyMap()) }
    var rolesVersion by remember(channel.id) { mutableStateOf(0) }
    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }
    var typingUsers by remember(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var readReceipts by remember(channel.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeCall by remember(channel.id) { mutableStateOf<repository.CallEvent.Invite?>(null) }
    var activeGroupCallUrl by remember(channel.id) { mutableStateOf<String?>(null) }

    // Mirror the shell's chosen presence into the chat surface.
    val statusSettings = remember { SettingsManager() }
    val myStatusStored by statusSettings.myStatus.collectAsState("ONLINE")
    val myActiveStatus = runCatching { UserStatus.valueOf(myStatusStored) }.getOrDefault(UserStatus.ONLINE)
    val displayCurrentUser = currentUser?.copy(status = myActiveStatus)

    // Per-room notification mode cycled from the header bell.
    val roomNotifFlow = remember(channel.id) { statusSettings.roomNotificationMode(channel.id) }
    val roomNotifMode by roomNotifFlow.collectAsState("ALL")

    // First message that arrived while this room was away this session (shell-owned).
    val unreadMarkers = ui.theme.LocalRoomUnreadMarkers.current
    val firstUnreadId = unreadMarkers[channel.matrixRoomId ?: ""] ?: unreadMarkers[channel.id]

    LaunchedEffect(channel.id) {
        isLoading = true
        try {
            currentUser = repository.getCurrentUser()
            val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
            messages = loaded
            if (showMemberList || isDirectMessage) {
                members = repository.getRoomMembers(channel.id)
            }
        } catch (e: Exception) {
            messages = emptyList()
            members = emptyList()
        }
        isLoading = false
    }

    // Roles load once per membership change; moderation actions bump rolesVersion
    // to force a re-snapshot (power-level edits don't change member counts).
    LaunchedEffect(channel.id, members.size, rolesVersion) {
        if (members.isNotEmpty()) {
            memberRoles = runCatching {
                repository.getMemberRoles(channel.id, members.map { it.id })
            }.getOrDefault(emptyMap())
        } else {
            memberRoles = emptyMap()
        }
    }

    LaunchedEffect(channel.id) {
        repository.observeMessages(channel.id).collect { newMessages ->
            println("DEBUG: [UI] Received ${newMessages.size} new messages for ${channel.id}")
            if (newMessages.isNotEmpty()) {
                messages = (messages + newMessages)
                    .groupBy { it.transactionId ?: it.id }
                    .map { it.value.last() }
                    .sortedBy { it.timestamp }
            }
        }
    }

    LaunchedEffect(channel.id) {
        repository.observeTyping(channel.id).collect { typingUsers = it }
    }

    LaunchedEffect(channel.id) {
        repository.observeReadReceipts(channel.id).collect { readReceipts = it }
    }

    LaunchedEffect(channel.id) {
        repository.observeActiveCall(channel.id).collect { activeCall = it }
    }

    LaunchedEffect(channel.id) {
        repository.observeActiveGroupCall(channel.matrixRoomId).collect { activeGroupCallUrl = it }
    }

    val directProfile = if (isDirectMessage) {
        members.firstOrNull { it.id != currentUser?.id } ?: OrbitUser(
            id = channel.id,
            username = channel.name,
            displayName = channel.name,
            avatarColor = 0xFF7C6AF0.toInt(),
            status = UserStatus.ONLINE,
            avatarUrl = channel.avatarUrl
        )
    } else null

    val annotatedMessages = remember(messages, readReceipts, currentUser) {
        annotateSeenMessages(messages, readReceipts, currentUser)
    }

    ChatScreen(
        repository = repository,
        channel = channel,
        messages = annotatedMessages,
        members = members,
        currentUser = displayCurrentUser,
        directProfile = directProfile,
        typingUsers = typingUsers,
        isLoadingMessages = isLoading,
        activeCall = activeCall,
        activeGroupCallUrl = activeGroupCallUrl,
        onSend = { body ->
            scope.launch {
                try {
                    val trimmed = body.trim()
                    when {
                        // Slash commands — /me sends an m.emote, /shrug appends the classic shrug
                        trimmed.startsWith("/me ") || trimmed == "/me" -> {
                            val action = trimmed.removePrefix("/me").trim()
                            if (action.isNotEmpty()) repository.sendEmoteMessage(channel.id, action).getOrThrow()
                        }
                        trimmed.startsWith("/shrug") -> {
                            val suffix = trimmed.removePrefix("/shrug").trim()
                            val text = "¯\\_(ツ)_/¯" + if (suffix.isNotEmpty()) " $suffix" else ""
                            repository.sendMessage(channel.id, text).getOrThrow()
                        }
                        else -> repository.sendMessage(channel.id, body)
                    }
                    val loaded = repository.getMessagesForChannel(channel.id, limit = 50)
                    messages = loaded
                } catch (e: Exception) {
                    util.OrbitLog.e("ChatSend", "message send failed: ${e.message}")
                }
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
                } catch (e: Exception) {
                    util.OrbitLog.e("ChatSend", "attachment send failed: ${e.message}")
                }
            }
        },
        onTyping = { isTyping ->
            scope.launch { repository.setTyping(channel.id, isTyping) }
        },
        onSeen = { eventId ->
            scope.launch { repository.sendReadReceipt(channel.id, eventId) }
        },
        onReact = { eventId, emoji ->
            scope.launch {
                runCatching { repository.sendReaction(channel.id, eventId, emoji) }
                messages = repository.getMessagesForChannel(channel.id, limit = 50)
            }
        },
        onEditMessage = { eventId, newBody ->
            scope.launch {
                runCatching { repository.editMessage(channel.id, eventId, newBody) }
                messages = repository.getMessagesForChannel(channel.id, limit = 50)
            }
        },
        onDeleteMessage = { eventId ->
            scope.launch {
                runCatching { repository.deleteMessage(channel.id, eventId) }
                messages = repository.getMessagesForChannel(channel.id, limit = 50)
            }
        },
        onTogglePin = { eventId ->
            scope.launch {
                val currentlyPinned = messages.firstOrNull { it.id == eventId }?.isPinned == true
                runCatching { repository.pinMessage(channel.id, eventId, !currentlyPinned) }
                messages = repository.getMessagesForChannel(channel.id, limit = 50)
            }
        },
        onSendReply = { replyToEventId, body ->
            scope.launch {
                runCatching { repository.sendReplyMessage(channel.id, replyToEventId, body) }
                messages = repository.getMessagesForChannel(channel.id, limit = 50)
            }
        },
        memberRoles = memberRoles,
        onKickUser = { userId ->
            scope.launch {
                runCatching { repository.kickUser(channel.id, userId, null).getOrThrow() }
                runCatching { members = repository.getRoomMembers(channel.id) }
                rolesVersion++
            }
        },
        onBanUser = { userId ->
            scope.launch {
                runCatching { repository.banUser(channel.id, userId, null).getOrThrow() }
                runCatching { members = repository.getRoomMembers(channel.id) }
                rolesVersion++
            }
        },
        onSetPowerLevel = { userId, level ->
            scope.launch {
                runCatching { repository.setUserPowerLevel(channel.id, userId, level) }
                    .onFailure { util.OrbitLog.e("Moderation", "power level change failed: ${it.message}") }
                rolesVersion++
            }
        },
        notifMode = roomNotifMode,
        onCycleNotificationMode = {
            val next = when (roomNotifMode) {
                "ALL" -> "MENTIONS"
                "MENTIONS" -> "MUTE"
                else -> "ALL"
            }
            scope.launch { runCatching { statusSettings.setRoomNotificationMode(channel.id, next) } }
        },
        firstUnreadId = firstUnreadId,
        showMemberList = showMemberList,
        onBack = onBack,
        modifier = modifier
    )
}

// A message is "seen" when another user's latest read receipt points at or after
// it in the currently loaded window. Reaction pseudo-events are never marked.
private fun annotateSeenMessages(
    messages: List<OrbitMessage>,
    readReceipts: Map<String, String>,
    currentUser: OrbitUser?
): List<OrbitMessage> {
    if (currentUser == null || readReceipts.isEmpty() || messages.isEmpty()) return messages
    val positions = HashMap<String, Int>()
    messages.forEachIndexed { i, m -> positions.putIfAbsent(m.id, i) }
    val lastReadPos = HashMap<String, Int>()
    readReceipts.forEach { (userId, eventId) ->
        positions[eventId]?.let { pos ->
            lastReadPos[userId] = maxOf(lastReadPos[userId] ?: -1, pos)
        }
    }
    if (lastReadPos.isEmpty()) return messages
    return messages.mapIndexed { i, m ->
        if (m.userId != currentUser.id || m.content is MessageContent.Reaction) return@mapIndexed m
        val readers = lastReadPos.filterValues { it >= i }.keys.toList()
        if (readers.isEmpty() && m.readReceipts.isEmpty()) m else m.copy(readReceipts = readers)
    }
}

/**
 * Ctrl+K quick switcher.
 *
 * Rebuilt around the shared components. The previous version was a `Surface`
 * with three hard-coded `Color.White.copy(alpha = …)` values that never
 * changed with the theme, a Material `OutlinedTextField` inside it (so the
 * field drew its own border inside a bordered card), and no keyboard
 * navigation at all — arrow keys did nothing, so "quick" meant "type, then
 * click with the mouse". It now has ↑/↓, Enter, and Escape-to-close.
 */
@Composable
private fun QuickSwitcherDialog(
    entries: List<Pair<OrbitServer, OrbitChannel>>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val t = rememberCosmicTokens()
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    val results = remember(query, entries) {
        if (query.isBlank()) {
            entries.take(12)
        } else {
            val q = query.trim().lowercase()
            entries
                .filter { (_, channel) -> channel.name.lowercase().contains(q) }
                .sortedBy { (_, channel) -> channel.name.lowercase().indexOf(q) }
                .take(12)
        }
    }

    LaunchedEffect(query) { selectedIndex = 0 }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = t.Elevated,
            border = BorderStroke(1.dp, t.SeparatorStrong),
            shadowElevation = 24.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 64.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex.coerceAtLeast(0))
                            true
                        }
                        Key.DirectionUp -> {
                            selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter -> {
                            results.getOrNull(selectedIndex)?.let { onSelect(it.second.id) }
                            true
                        }
                        androidx.compose.ui.input.key.Key.Escape -> {
                            onDismiss()
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = t.Text_Subtle,
                        modifier = Modifier.size(17.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.isEmpty()) {
                            Text(
                                "Jump to a room…",
                                color = t.Text_Subtle,
                                fontSize = 14.sp
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = t.Text_White,
                                fontSize = 14.sp
                            ),
                            cursorBrush = SolidColor(t.Accent),
                            singleLine = true
                        )
                    }
                    Text(
                        text = "Esc",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = t.Text_Subtle,
                            fontSize = 10.sp
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(t.Sunken)
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                HorizontalDivider(
                    color = t.Separator,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                if (results.isEmpty()) {
                    Text(
                        "No rooms match “$query”",
                        color = t.Text_Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        itemsIndexed(results, key = { _, pair -> pair.second.id }) { index, (server, channel) ->
                            val isActive = index == selectedIndex
                            QuickSwitcherRow(
                                channelName = channel.name,
                                serverName = server.name,
                                isActive = isActive,
                                onClick = { onSelect(channel.id) },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSwitcherRow(
    channelName: String,
    serverName: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    OrbitListRow(
        title = channelName,
        onClick = onClick,
        modifier = modifier,
        selected = isActive,
        height = 36.dp,
        horizontalPadding = 8.dp,
        showSelectionBar = false,
        leading = OrbitRowLeading(icon = OrbitIcons.TextChannel),
        titleStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (isActive) t.Text_White else t.Text_Secondary,
            fontSize = 13.5.sp
        ),
        trailing = {
            Text(
                text = serverName,
                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 11.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}


