package ui.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import repository.AuthResult
import repository.AuthStatus
import repository.CredentialManager
import repository.MatrixOrbitRepository
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
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.rememberCosmicTokens
import webrtc.CallManager
import webrtc.LocalCallManager
import webrtc.createWebRtcClient

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val App_Bg: Color @Composable get() = rememberCosmicTokens().Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent

/**
 * OrbitApp — Root composable.
 * Shows login screen if no repository is set, otherwise shows the main app layout.
 * Adapts between Compact (phone), Medium (tablet), and Expanded (desktop) layouts.
 */
@Suppress("UnusedBoxWithConstraintsScope")
@Composable
fun OrbitApp(
    onStartScreenShare: (( (onResult: (Any) -> Unit) -> Unit ))? = null,
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
    val colorSaturation by settingsManager.colorSaturation.collectAsState(100f)

    val showTimestamps by settingsManager.showTimestamps.collectAsState(true)
    val compactMode by settingsManager.compactMode.collectAsState(false)
    val showRoleColors by settingsManager.showRoleColors.collectAsState(true)
    val showMemberList by settingsManager.showMemberList.collectAsState(true)
    val enterSend by settingsManager.enterSend.collectAsState(true)
    val use24HourClock by settingsManager.use24HourClock.collectAsState(false)
    val dateFormat by settingsManager.dateFormat.collectAsState("DD/MM/YYYY")
    val scope = rememberCoroutineScope()

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
                            onStartScreenShare = onStartScreenShare,
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

private fun String.redactedIceUrl(): String {
    return substringBefore('?')
}

@Composable
private fun MainAppContent(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    windowSize: WindowSizeClass,
    onStartScreenShare: (( (onResult: (Any) -> Unit) -> Unit ))? = null,
    onLogoutRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val verificationState by (repository.activeDeviceVerification?.collectAsState(null) ?: remember { mutableStateOf(null) })
    VerificationOverlay(verificationState as? de.connect2x.trixnity.client.verification.ActiveDeviceVerification)

    // ─── WebRTC Call Manager ────────────────────────────────────
    val webRtcClient = remember { createWebRtcClient() }
    val callManager = remember { CallManager(webRtcClient, scope) }
    
    val orbitZeroEnabled by settingsManager.orbitZeroAiNoiseSuppression.collectAsState(true)
    LaunchedEffect(orbitZeroEnabled) {
        webRtcClient.setOrbitZeroEnabled(orbitZeroEnabled)
    }

    LaunchedEffect(callManager, repository) {
        callManager.refreshIceServers = {
            repository.getTurnServers()
                .onSuccess { servers ->
                    val urls = servers.flatMap { it.uris }
                    println("DEBUG: [WebRTC] Matrix ICE discovery returned ${urls.size} urls: ${urls.joinToString { it.redactedIceUrl() }}")
                }
                .onFailure { error ->
                    println("DEBUG: [WebRTC] Matrix ICE discovery failed: ${error.message}")
                }
                .getOrElse { emptyList() }
        }

        // Warm the TURN/STUN config early, and CallManager refreshes it again before each call.
        scope.launch {
            callManager.iceServers = callManager.refreshIceServers?.invoke().orEmpty()
        }

        // Listen for signaling events
        scope.launch {
            repository.observeCallEvents().collect { event ->
                val ownPartyId = CredentialManager.deviceId
                if (ownPartyId != null && event.partyId == ownPartyId) return@collect
                if (event.partyId == null) {
                    val user = repository.getCurrentUser()
                    if (user != null && event.senderId == user.id) return@collect
                }

                when (event) {
                    is repository.CallEvent.Invite -> callManager.handleCallInvite(event.roomId, event.callId, event.sdp)
                    is repository.CallEvent.Answer -> {
                        callManager.handleCallAnswer(event.sdp)
                        event.partyId?.let { selectedPartyId ->
                            repository.sendCallEvent(event.roomId, "m.call.select_answer", buildJsonObject {
                                put("call_id", event.callId)
                                put("selected_party_id", selectedPartyId)
                                put("version", "1")
                            })
                        }
                    }
                    is repository.CallEvent.Negotiate -> {
                        callManager.handleCallNegotiate(event.roomId, event.callId, event.type, event.sdp)
                    }
                    is repository.CallEvent.Candidate -> callManager.handleIceCandidate(
                        webrtc.IceCandidateModel(event.sdp, event.sdpMid, event.sdpMLineIndex)
                    )
                    is repository.CallEvent.Hangup -> {
                        if (callManager.callId == event.callId) {
                            callManager.endCall(notifyRemote = false)
                        }
                    }
                }
            }
        }

        callManager.sendInvite = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.invite", buildJsonObject {
                put("call_id", callId)
                put("offer", buildJsonObject {
                    put("type", "offer")
                    put("sdp", sdp)
                })
                put("version", 0)

                put("lifetime", 30000)
            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call invite: ${error.message}")
            }
        }

        callManager.sendAnswer = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.answer", buildJsonObject {
                put("call_id", callId)
                put("answer", buildJsonObject {
                    put("type", "answer")
                    put("sdp", sdp)
                })
                put("version", 0)

            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call answer: ${error.message}")
            }
        }

        callManager.sendNegotiate = { roomId, callId, type, sdp ->
            repository.sendCallEvent(roomId, "m.call.negotiate", buildJsonObject {
                put("call_id", callId)
                put("description", buildJsonObject {
                    put("type", type)
                    put("sdp", sdp)
                })
                put("version", 0)

                put("lifetime", 30000)
            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call negotiate: ${error.message}")
            }
        }

        callManager.sendHangup = { roomId, callId ->
            repository.sendCallEvent(roomId, "m.call.hangup", buildJsonObject {
                put("call_id", callId)
                put("version", 0)

            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call hangup: ${error.message}")
            }
        }

        // --- ICE Candidate Batching Logic ---
        val candidateBatch = mutableListOf<webrtc.IceCandidateModel>()
        var batchJob: kotlinx.coroutines.Job? = null

        callManager.sendCandidate = { roomId, callId, candidate ->
            candidateBatch.add(candidate)

            if (batchJob?.isActive != true) {
                batchJob = scope.launch {
                    kotlinx.coroutines.delay(500) // Debounce window to catch burst

                    val toSend = candidateBatch.toList()
                    candidateBatch.clear()

                    if (toSend.isNotEmpty()) {
                        println("DEBUG: [WebRTC] Sending ${toSend.size} local ICE candidates")
                        repository.sendCallEvent(roomId, "m.call.candidates", buildJsonObject {
                            put("call_id", callId)
                            put("version", 0)
            
                            put("candidates", buildJsonArray {
                                toSend.forEach { cand ->
                                    add(buildJsonObject {
                                        put("candidate", cand.sdp)
                                        put("sdpMid", cand.sdpMid)
                                        put("sdpMLineIndex", cand.sdpMLineIndex)
                                    })
                                }
                            })
                        }).onFailure { error ->
                            println("DEBUG: [WebRTC] Failed to send ICE candidates: ${error.message}")
                        }
                    }
                }
            }
        }
    }

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
    CompositionLocalProvider(LocalCallManager provides callManager) {
        Box(modifier = modifier) {
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
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
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
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
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
                    onCreateRoom = { showCreateRoom = true },
                    onOpenRoles = { showServerRoles = true },
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

            val activeCallServer = servers.find { it.id == callManager.activeRoomId }
            val callerName = activeCallServer?.name ?: "Friend"

            CallScreen(
                callManager = callManager,
                callerName = callerName,
                onStartScreenShare = {
                    if (onStartScreenShare != null) {
                        onStartScreenShare { data ->
                            callManager.startScreenShare(data)
                        }
                    } else {
                        callManager.startScreenShare()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
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
private fun CompactEmptyDMs() {
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
private fun CompactEmptyServers(onAdd: () -> Unit) {
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
    onCreateRoom: () -> Unit,
    onOpenRoles: () -> Unit,
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
                        onCreateRoomClick = onCreateRoom,
                        onOpenRolesClick = onOpenRoles,
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
    onCreateRoom: () -> Unit,
    onOpenRoles: () -> Unit,
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
                onCreateRoomClick = onCreateRoom,
                onOpenRolesClick = onOpenRoles,
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
    var typingUsers by remember(channel.id) { mutableStateOf<List<String>>(emptyList()) }
    var activeCall by remember(channel.id) { mutableStateOf<repository.CallEvent.Invite?>(null) }
    var activeGroupCallUrl by remember(channel.id) { mutableStateOf<String?>(null) }

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
        repository.observeActiveCall(channel.id).collect { activeCall = it }
    }

    LaunchedEffect(channel.id) {
        repository.observeActiveGroupCall(channel.id).collect { activeGroupCallUrl = it }
    }

    ChatScreen(
        channel = channel,
        messages = messages,
        members = members,
        currentUser = currentUser,
        typingUsers = typingUsers,
        activeCall = activeCall,
        activeGroupCallUrl = activeGroupCallUrl,
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
        onTyping = { isTyping ->
            scope.launch { repository.setTyping(channel.id, isTyping) }
        },
        onSeen = { eventId ->
            scope.launch { repository.sendReadReceipt(channel.id, eventId) }
        },
        showMemberList = showMemberList,
        onBack = onBack,
        modifier = modifier
    )
}
