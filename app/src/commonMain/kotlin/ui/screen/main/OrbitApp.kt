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
import ui.components.OrbitNoticeHost
import ui.components.OrbitNotices
// Member extension on Result — needs its own import even though the object is
// imported, exactly like any extension declared inside an object.
import ui.components.OrbitNotices.onFailureOrNotify
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import ui.model.applyLiveAmendments
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
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
import ui.screen.orbitGlobalShortcuts
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
                if (util.OrbitLog.debugEnabled) util.OrbitLog.d("Coil-Ktor", message)
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
    val activeTheme by settingsManager.activeTheme.collectAsState(ui.theme.Theme.Default)
    val fontSize by settingsManager.fontSize.collectAsState(50f)
    val reduceMotion by settingsManager.reduceMotion.collectAsState(false)
    val chatWallpaper by settingsManager.chatWallpaper.collectAsState("auto")

    val showTimestamps by settingsManager.showTimestamps.collectAsState(true)
    val compactMode by settingsManager.compactMode.collectAsState(false)
    val showRoleColors by settingsManager.showRoleColors.collectAsState(true)
    val showMemberList by settingsManager.showMemberList.collectAsState(true)
    val enterSend by settingsManager.enterSend.collectAsState(true)
    val use24HourClock by settingsManager.use24HourClock.collectAsState(false)
    val dateFormat by settingsManager.dateFormat.collectAsState("DD/MM/YYYY")
    val scope = rememberCoroutineScope()

    // `setSingletonImageLoaderFactory` is a @Composable that re-runs whenever the
    // factory instance changes, and a lambda literal is a new instance on every
    // recomposition — so the builder ran (and the global was re-set) each time.
    // A remembered factory makes it once per composition lifetime.
    val imageLoaderFactory: (coil3.PlatformContext) -> ImageLoader = remember(coilHttpClient) {
        { context: coil3.PlatformContext ->
            ImageLoader.Builder(context)
                .components {
                    add(KtorNetworkFetcherFactory(httpClient = coilHttpClient))
                }
                .crossfade(true)
                .build()
        }
    }
    setSingletonImageLoaderFactory(imageLoaderFactory)

    CompositionLocalProvider(
        ui.theme.LocalSettingsManager provides settingsManager,
        ui.theme.LocalActiveTheme provides activeTheme,
        ui.theme.LocalOnThemeChange provides { settingsManager.setTheme(it) },
        ui.theme.LocalReduceMotion provides reduceMotion,
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
                    val authStatusFlow = remember(repository) {
                        repository?.authStatus ?: flowOf(AuthStatus.Authenticated)
                    }
                    val authStatus by authStatusFlow.collectAsState(initial = AuthStatus.Authenticated)

                    LaunchedEffect(Unit) {
                        val token = CredentialManager.accessToken
                        val hs = CredentialManager.homeserverUrl
                        val uId = CredentialManager.userId
                        if (token != null && hs != null && uId != null) {
                            // The constructor normalises and validates the
                            // homeserver URL and throws on a bad one. A stored
                            // but invalid URL used to throw out of this effect
                            // with no handler, taking down the app at startup and
                            // leaving no way to reach the login screen.
                            val repo = runCatching { MatrixOrbitRepository(hs) }.getOrElse { error ->
                                println("WARN [OrbitApp] stored homeserver '$hs' is unusable: ${error.message}")
                                CredentialManager.clear()
                                return@LaunchedEffect
                            }
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

    val verificationFlow = remember(repository) {
        repository.activeDeviceVerification ?: flowOf(null)
    }
    val verificationState by verificationFlow.collectAsState(initial = null)
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
        // Presence is best-effort and the user did not explicitly ask for it, so
        // this reports without a Retry — nagging someone because their status did
        // not reach the server is worse than the status being briefly wrong.
        OrbitNotices.notifying("Presence update", "Presence") {
            repository.setPresence(myActiveStatus)
        }
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

    // derivedStateOf, NOT remember(map) { map.toMap() }: SnapshotStateMap does
    // not override equals, so keying a remember on the map instance compares by
    // reference -- the block would run exactly once and freeze these at their
    // initial empty values, so unread badges, server-rail dots and the "NEW"
    // separator would never update. derivedStateOf re-runs on content change
    // and only notifies downstream when the resulting Map actually differs,
    // which is also what stops the per-recomposition allocation this replaced.
    val unreadCountsSnapshot by remember {
        derivedStateOf { unreadCounts.toMap() }
    }
    val firstUnreadSnapshot by remember {
        derivedStateOf { firstUnreadByRoom.toMap() }
    }

    LaunchedEffect(servers) {
        val index = mutableMapOf<String, String>()
        servers.forEach { server ->
            runCatching { repository.getChannelsForSpace(server.id) }.getOrNull()?.forEach { ch ->
                index[ch.id] = server.id
                ch.matrixRoomId?.let { index[it] = server.id }
            }
        }
        channelServerById = index
    }

    // One collector for every room, in its own effect keyed on the room, instead
    // of a never-ending child per channel launched from a `LaunchedEffect(servers)`
    // that cancels and recreates all of them on every server update.
    val notificationRoomIds = remember(channelServerById) { channelServerById.keys.toList() }
    notificationRoomIds.forEach { roomId ->
        LaunchedEffect(roomId, settingsManager) {
            runCatching {
                settingsManager.roomNotificationMode(roomId).collect { mode ->
                    notificationModes[roomId] = mode
                }
            }
        }
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

    val unreadByServer: Map<String, Int> = remember(unreadCountsSnapshot, channelServerById) {
        val totals = mutableMapOf<String, Int>()
        // Read the derived snapshot, not the live map: reading the live map here
        // would register a snapshot dependency on data that is not in this
        // remember's key list.
        unreadCountsSnapshot.forEach { (roomId, count) ->
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
            // Hard timeout, and `finally` for the flag rather than a trailing
            // assignment.
            //
            // Both matter. `getChannelsForSpace` does a full room-state read plus a
            // profile lookup per DM conversation, and any one of those can stall on
            // a network that accepts the connection and then goes quiet. With a
            // plain `try/catch` and a trailing `isLoadingChannels = false`, a stall
            // meant the flag was never cleared and the UI sat on its loading
            // placeholder forever -- which is exactly the "direct messages keep on
            // loading" report: the DM path resolves the most profiles, so it is the
            // first to hit it.
            //
            // `finally` additionally covers cancellation. Switching servers cancels
            // this effect mid-load; catching broadly and continuing would have left
            // the previous server's channels under the new server's name.
            val loaded: List<OrbitChannel>? = withTimeoutOrNull(20_000L) {
                repository.getChannelsForSpace(selectedServerId)
            }
            if (loaded == null) {
                util.OrbitLog.w(
                    "OrbitApp",
                    "channel load for $selectedServerId timed out after 20s; showing empty list"
                )
                channels = emptyList()
            } else {
                channels = loaded
                val firstText = loaded.firstOrNull { it.type == ChannelType.TEXT }
                if (firstText != null && windowSize.isExpanded) {
                    // Auto-select only on desktop — on phone/tablet user taps to navigate
                    selectedChannelId = firstText.id
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            util.OrbitLog.w("OrbitApp", "channel load for $selectedServerId failed: ${e.message}")
            channels = emptyList()
        } finally {
            isLoadingChannels = false
        }
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

    // Settings is rendered INSIDE the provider below, as one more overlay.
    //
    // It used to be an early `return` sitting between `WebRtcEffects(...)` and
    // the `CompositionLocalProvider`, and that one statement was the cause of
    // two separately reported bugs.
    //
    // `WebRtcEffects` is a composable whose `onDispose` calls
    // `callManager.endCall(notifyRemote = false)` and then `callManager.release()`.
    // Returning early left the composition, so opening Settings disposed it and
    // did exactly both: the call was dropped, and the native media session was
    // released while frames were still arriving. The second is not a clean
    // shutdown -- the process died with `0xC0000374` (STATUS_HEAP_CORRUPTION),
    // because native buffers were freed underneath an in-flight reader.
    //
    // The same early return also meant `LocalCallManager` was never provided on
    // this path at all, so nothing in Settings could reach the call.
    //
    // Every screen switch now happens inside the provider, so the call stack
    // outlives all of them and only the app going away tears it down.

    // ─── Route to appropriate layout ────────────────────────────
    CompositionLocalProvider(
        LocalCallManager provides callManager,
        ui.theme.LocalRoomUnreadMarkers provides firstUnreadSnapshot
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
                    unreadCounts = unreadCountsSnapshot,
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
                    unreadCounts = unreadCountsSnapshot,
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
                    unreadCounts = unreadCountsSnapshot,
                    unreadByServer = unreadByServer,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ─── Settings screen ────────────────────────────────────
            //
            // An overlay like the dialogs below it, NOT an early return. See the
            // note above: returning here used to dispose `WebRtcEffects`, which
            // ends the call and releases the native media session mid-frame.
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
            if (showCreateRoom) {
                // Reset the flag in the else branch too: selecting a DM (or a
                // server that has not loaded yet) used to leave showCreateRoom
                // true, so the dialog popped open later on its own.
                if (createRoomServer != null) {
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
                } else {
                    showCreateRoom = false
                }
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

            // ── No floating call widget ────────────────────────────────────
            //
            // There used to be a draggable pill pinned to the bottom-right
            // corner, and a `forceMinimized` surface behind it. It is gone.
            //
            // Discord's model: a call IS the channel. You click the voice
            // channel and the call fills the main column; there is no second
            // representation of it floating over the window. The pill broke
            // that in two ways — it covered the message list and composer, and
            // it made a live call look like a *notification* rather than
            // somewhere you are.
            //
            // Group calls render inline through `VoiceChannelScreen` (and
            // `ChatScreen` for a text room with a live MatrixRTC call), so no
            // overlay is needed at all.
            //
            // P2P/DM calls still need a surface of their own: a DM has no
            // MatrixRTC call state, so nothing inline renders for it and an
            // incoming invite would ring invisibly until it expired. That one
            // keeps a full-bleed surface in the root Box, but `isEmbedded`
            // stops it drawing its own corner card on top of itself.
            val showP2pSurface = callActive && !isGroupCall

            if (showP2pSurface) {
                CallScreen(
                    callManager = callManager,
                    callerName = callerName,
                    isEmbedded = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // LAST child of the root Box so it draws over the call surfaces,
            // which are fillMaxSize and would otherwise sit on top of it.
            OrbitNoticeHost()
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
    // Scrollback, keyed per room so switching rooms resets it rather than
    // carrying "no more history" from a short room into a long one.
    var isLoadingHistory by remember(channel.id) { mutableStateOf(false) }
    var hasMoreHistory by remember(channel.id) { mutableStateOf(true) }
    var members by remember(channel.id) { mutableStateOf<List<OrbitUser>>(emptyList()) }
    var memberRoles by remember(channel.id) { mutableStateOf<Map<String, MemberRoleInfo>>(emptyMap()) }
    var rolesVersion by remember(channel.id) { mutableStateOf(0) }
    var currentUser by remember { mutableStateOf<OrbitUser?>(null) }
    var typingUsers by remember(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var readReceipts by remember(channel.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var activeCall by remember(channel.id) { mutableStateOf<repository.CallEvent.Invite?>(null) }
    var activeGroupCallUrl by remember(channel.id) { mutableStateOf<String?>(null) }
    // A send that failed hands its text back to the composer. The field clears
    // itself the instant send is pressed, so without this the message is gone.
    var failedDraft by remember(channel.id) { mutableStateOf<String?>(null) }

    /**
     * Resend one message, used by the Retry button on a failed send.
     *
     * Local rather than inline in the notice so that the slash-command
     * interpretation is identical between the original attempt and the retry —
     * a retry that silently sent the raw `/me waves` as a normal message would
     * be a worse bug than the one being fixed.
     */
    suspend fun resendMessage(roomId: String, body: String, trimmed: String): Result<Unit> {
        val result = runCatching {
            when {
                trimmed.startsWith("/me ") || trimmed == "/me" -> {
                    val action = trimmed.removePrefix("/me").trim()
                    if (action.isNotEmpty()) {
                        repository.sendEmoteMessage(roomId, action).getOrThrow()
                    }
                }
                trimmed.startsWith("/shrug") -> {
                    val suffix = trimmed.removePrefix("/shrug").trim()
                    val text = "¯\\_(ツ)_/¯" + if (suffix.isNotEmpty()) " $suffix" else ""
                    repository.sendMessage(roomId, text).getOrThrow()
                }
                else -> repository.sendMessage(roomId, body).getOrThrow()
            }
            // The `when` is the value of the try-block, and its branches are
            // Results of different types (String txnId vs Unit). Collapse it to
            // Unit so `result` is Result<Unit> and the retry path typechecks.
            @Suppress("UNUSED_EXPRESSION")
            Unit
        }
        if (result.isSuccess) {
            // Clear the restored draft: the message landed, so the composer
            // must not put the old text back.
            failedDraft = null
            messages = repository.getMessagesForChannel(roomId, limit = 50)
        }
        return result
    }

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
            val loaded = repository.getMessagesForChannel(channel.id, limit = HISTORY_PAGE_SIZE)
            messages = loaded
            // A short page means the server had nothing older to give. Remembering
            // that stops the "load older" affordance from reappearing forever at
            // the top of a room that simply has no more history.
            hasMoreHistory = loaded.size >= HISTORY_PAGE_SIZE
            if (showMemberList || isDirectMessage) {
                members = repository.getRoomMembers(channel.id)
            }
        } catch (e: Exception) {
            messages = emptyList()
            members = emptyList()
            hasMoreHistory = false
        }
        isLoading = false
    }

    // Scrollback.
    //
    // The repository has always supported `fromToken` and it has always been
    // correct -- it is the cursor event, and the query walks BACKWARDS from it.
    // No caller ever passed one, so every room stopped at 50 messages and there
    // was no way to reach anything older, in any room, ever.
    //
    // Merging is by event id with the loaded page PREPENDED. Prepending rather
    // than replacing is the whole point: the live collector above owns the
    // bottom of the list, and a plain assignment would throw away anything that
    // arrived while the page was in flight.
    val loadOlderMessages: suspend () -> Unit = {
        val cursor = messages.firstOrNull()?.id
        if (cursor == null || isLoadingHistory || !hasMoreHistory) {
            util.OrbitLog.d("UI", "load-older ignored room=${channel.id} " +
                "cursor=${cursor ?: "<none>"} loading=$isLoadingHistory more=$hasMoreHistory")
        } else {
            isLoadingHistory = true
            try {
                val older = repository.getMessagesForChannel(
                    channelId = channel.id,
                    limit = HISTORY_PAGE_SIZE,
                    fromToken = cursor,
                )
                if (older.isEmpty()) {
                    hasMoreHistory = false
                } else {
                    val known = messages.mapTo(HashSet()) { it.id }
                    val fresh = older.filter { known.add(it.id) }
                    messages = older + messages
                    util.OrbitLog.d(
                        "UI",
                        "load-older room=${channel.id} fetched=${older.size} new=${fresh.size}"
                    )
                }
            } catch (e: Exception) {
                util.OrbitLog.e("UI", "load-older failed room=${channel.id}: ${e.message}")
            } finally {
                isLoadingHistory = false
            }
        }
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
            util.OrbitLog.d("UI", "Received ${newMessages.size} new messages for ${channel.id}")
            if (newMessages.isNotEmpty()) {
                // Reactions and edits are folded into their parent rather than
                // appended. The history path does this in the repository, but
                // the live path used to append the pseudo-messages as if they
                // were ordinary ones -- so in an open channel every reaction
                // anyone sent appeared as a lone "thumbs up" bubble while the
                // parent's chip never moved, and someone else's edit showed as a
                // second bubble with the new text while the original kept the
                // old one. Only your own edits/reactions repaired it, because
                // those force a full history reload.
                messages = applyLiveAmendments(messages, newMessages)
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
        onLoadOlder = loadOlderMessages,
        onSend = { body ->
            scope.launch {
                val trimmed = body.trim()
                val result: Result<Unit> = runCatching {
                    when {
                        // Slash commands — /me sends an m.emote, /shrug appends the classic shrug
                        trimmed.startsWith("/me ") || trimmed == "/me" -> {
                            val action = trimmed.removePrefix("/me").trim()
                            if (action.isNotEmpty()) {
                                repository.sendEmoteMessage(channel.id, action).getOrThrow()
                            }
                        }
                        trimmed.startsWith("/shrug") -> {
                            val suffix = trimmed.removePrefix("/shrug").trim()
                            val text = "¯\\_(ツ)_/¯" + if (suffix.isNotEmpty()) " $suffix" else ""
                            repository.sendMessage(channel.id, text).getOrThrow()
                        }
                        else -> repository.sendMessage(channel.id, body).getOrThrow()
                    }
                }

                // The composer already cleared itself and nothing was written.
                // Three things now happen, and all three used to not happen:
                //   1. the message goes back into the field
                //   2. the user is told, instead of the app looking successful
                //   3. there is a Retry that resends this exact text
                if (result.isFailure) {
                    OrbitNotices.error(
                        text = "Message not sent: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        subsystem = "ChatSend",
                        retryLabel = "Retry",
                        retry = { scope.launch { resendMessage(channel.id, body, trimmed) } }
                    )
                    failedDraft = body
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onSendAttachment = { bytes, filename, mimeType ->
            scope.launch {
                // The upload and the send are two separate operations against two
                // different endpoints. A failure in either loses the file — there
                // is no draft of an attachment anywhere, so Retry is the only copy
                // and it re-runs both steps. `mxcUrl` is deliberately NOT reused
                // on retry: a half-finished upload may have left media on the
                // server, and re-uploading is cheaper than referencing an orphan.
                // Returns Unit and throws on failure, so `runCatching { … }`
                // around it yields the Result<Unit> the retry path wants without
                // any of the branches disagreeing on a type.
                suspend fun attempt() {
                    val mxcUrl = repository.uploadMedia(bytes, mimeType, filename).getOrThrow()
                    when {
                        mimeType.startsWith("image/") ->
                            repository.sendImageMessage(channel.id, mxcUrl, filename, mimeType, null, null, bytes.size.toLong()).getOrThrow()
                        mimeType.startsWith("audio/") ->
                            repository.sendAudioMessage(channel.id, mxcUrl, filename, mimeType, null, bytes.size.toLong()).getOrThrow()
                        else ->
                            repository.sendFileMessage(channel.id, mxcUrl, filename, mimeType, bytes.size.toLong()).getOrThrow()
                    }
                }

                val result = runCatching { attempt() }
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Attachment not sent: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatSend",
                        "Retry",
                        { scope.launch { runCatching { attempt() }.onFailureOrNotify("Attachment", "ChatSend") } }
                    )
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
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
                // Every one of these used to be `runCatching { ... }` with the
                // Result dropped, followed by an unconditional re-read from the
                // server. A failure therefore looked exactly like success: the
                // re-read brought the unchanged state back and the UI reported
                // nothing. Now the failure is surfaced and the re-read is skipped,
                // because re-reading after a failed write just paints over the
                // optimistic state the user was shown.
                val result = repository.sendReaction(channel.id, eventId, emoji)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Reaction not added: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatReact",
                        "Retry",
                        { scope.launch { repository.sendReaction(channel.id, eventId, emoji)
                            .onFailureOrNotify("Reaction", "ChatReact") } }
                    )
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onEditMessage = { eventId, newBody ->
            scope.launch {
                val result = repository.editMessage(channel.id, eventId, newBody)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Edit failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatEdit",
                        "Retry",
                        { scope.launch { repository.editMessage(channel.id, eventId, newBody)
                            .onFailureOrNotify("Edit", "ChatEdit") } }
                    )
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onDeleteMessage = { eventId ->
            scope.launch {
                val result = repository.deleteMessage(channel.id, eventId)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Message not deleted: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatDelete",
                        "Retry",
                        { scope.launch { repository.deleteMessage(channel.id, eventId)
                            .onFailureOrNotify("Delete", "ChatDelete") } }
                    )
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onTogglePin = { eventId ->
            scope.launch {
                val currentlyPinned = messages.firstOrNull { it.id == eventId }?.isPinned == true
                val result = repository.pinMessage(channel.id, eventId, !currentlyPinned)
                if (result.isFailure) {
                    val verb = if (currentlyPinned) "Unpin" else "Pin"
                    OrbitNotices.error(
                        "$verb failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatPin",
                        "Retry",
                        { scope.launch { repository.pinMessage(channel.id, eventId, !currentlyPinned)
                            .onFailureOrNotify(verb, "ChatPin") } }
                    )
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onSendReply = { replyToEventId, body ->
            scope.launch {
                val result = repository.sendReplyMessage(channel.id, replyToEventId, body)
                if (result.isFailure) {
                    // Same reasoning as onSend: the reply box has already closed
                    // and cleared, so the text comes back to the composer too.
                    OrbitNotices.error(
                        "Reply not sent: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "ChatSend",
                        "Retry",
                        { scope.launch { repository.sendReplyMessage(channel.id, replyToEventId, body)
                            .onFailureOrNotify("Reply", "ChatSend") } }
                    )
                    failedDraft = body
                } else {
                    messages = repository.getMessagesForChannel(channel.id, limit = 50)
                }
            }
        },
        onSendFailed = { failedDraft = it },
        memberRoles = memberRoles,
        onKickUser = { userId ->
            scope.launch {
                // These two called `.getOrThrow()` INSIDE the `runCatching`, so
                // the exception was raised and then thrown away — the UI
                // reported success for a kick that never happened. Worse than
                // a silent failure: the user believes they enforced something.
                val result = repository.kickUser(channel.id, userId, null)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Kick failed — you may not have permission: " +
                            (result.exceptionOrNull()?.message ?: "unknown error"),
                        "RoomMod",
                        "Retry",
                        { scope.launch { repository.kickUser(channel.id, userId, null)
                            .onFailureOrNotify("Kick", "RoomMod") } }
                    )
                } else {
                    members = repository.getRoomMembers(channel.id)
                    rolesVersion++
                }
            }
        },
        onBanUser = { userId ->
            scope.launch {
                val result = repository.banUser(channel.id, userId, null)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Ban failed — you may not have permission: " +
                            (result.exceptionOrNull()?.message ?: "unknown error"),
                        "RoomMod",
                        "Retry",
                        { scope.launch { repository.banUser(channel.id, userId, null)
                            .onFailureOrNotify("Ban", "RoomMod") } }
                    )
                } else {
                    members = repository.getRoomMembers(channel.id)
                    rolesVersion++
                }
            }
        },
        onSetPowerLevel = { userId, level ->
            scope.launch {
                // This one DID log the failure — to the console, where nobody
                // looks. It is the same class of bug as the others: correct
                // repository, discarded outcome, no user-visible signal.
                val result = repository.setUserPowerLevel(channel.id, userId, level)
                if (result.isFailure) {
                    OrbitNotices.error(
                        "Role change failed: ${result.exceptionOrNull()?.message ?: "unknown error"}",
                        "RoomMod",
                        "Retry",
                        { scope.launch { repository.setUserPowerLevel(channel.id, userId, level)
                            .onFailureOrNotify("Role change", "RoomMod") } }
                    )
                } else {
                    rolesVersion++
                }
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
                        // Unhandled here falls through to the .orbitGlobalShortcuts()
                        // below, which sits later in this same chain and so
                        // receives the event only after this handler declines it.
                        // Ctrl+K, the call shortcuts and push-to-talk used to be
                        // dead for as long as the switcher was open.
                        else -> false
                    }
                }
                .orbitGlobalShortcuts()
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



/**
 * How many messages one page of history is.
 *
 * The repository default was 50 and every call site passed it explicitly, which
 * is why the number was invisible as a decision. It is now named, and both the
 * initial load and each "load older" page use it, so the page size is one value
 * rather than eight copies of a literal.
 */
private const val HISTORY_PAGE_SIZE = 50