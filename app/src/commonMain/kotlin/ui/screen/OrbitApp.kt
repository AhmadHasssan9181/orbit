package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import ui.data.MockData
import ui.model.*

import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val App_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFEAE6DF) else Color(0xFF080A0F)
private val Accent: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF075E54) else Color(0xFF7C6AF0)
private val AccentLt: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF128C7E) else Color(0xFFA78BFA)

/**
 * OrbitApp — Root composable.
 * Layout: [ServerRail 72dp] [ChannelDrawer 240dp] [Chat flex] [MemberList 200dp]
 */
@Composable
fun OrbitApp(modifier: Modifier = Modifier) {
    var isLiteMode by remember { mutableStateOf(false) }

    CompositionLocalProvider(
        LocalIsLiteMode provides isLiteMode,
        LocalOnLiteModeChange provides { isLiteMode = it }
    ) {
        val allServers = MockData.MockServers

    var selectedServerId  by remember { mutableStateOf(allServers.firstOrNull()?.id ?: "") }
    var selectedChannelId by remember { mutableStateOf("") }
    var showSettings      by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            onBack     = { showSettings = false },
            modifier   = Modifier.fillMaxSize()
        )
    } else {
        val selectedServer = allServers.find { it.id == selectedServerId }

        if (selectedServer != null) {
            MainLayout(
                servers           = allServers,
                selectedServerId  = selectedServerId,
                onServerClick     = { id -> selectedServerId = id; selectedChannelId = "" },
                currentUser       = MockData.CurrentUser,
                selectedChannelId = selectedChannelId,
                onChannelClick    = { id -> selectedChannelId = id },
                onOpenSettings    = { showSettings = true },
                modifier          = modifier
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(App_Bg),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Accent)
            }
        }
    }
    }
}

// ─── MainLayout ──────────────────────────────────────────────────

@Composable
private fun MainLayout(
    servers: List<OrbitServer>,
    selectedServerId: String,
    onServerClick: (String) -> Unit,
    currentUser: OrbitUser,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedServer = servers.find { it.id == selectedServerId } ?: return
    val channels   = MockData.getChannelsForServer(selectedServer.id)
    val categories = MockData.getCategoriesForServer(selectedServer.id)
    val isLiteMode = ui.theme.LocalIsLiteMode.current

    var selectedWhatsAppTab by remember { mutableStateOf("Chats") }

    Row(modifier = modifier.fillMaxSize().background(App_Bg)) {
        ServerRail(
            servers          = servers,
            selectedServerId = selectedServerId,
            onServerClick    = onServerClick,
            selectedWhatsAppTab = selectedWhatsAppTab,
            onWhatsAppTabClick = { selectedWhatsAppTab = it }
        )

        ChannelDrawer(
            server            = selectedServer,
            categories        = categories,
            channels          = channels,
            selectedChannelId = selectedChannelId,
            currentUser       = currentUser,
            onChannelClick    = onChannelClick,
            onOpenSettings    = onOpenSettings,
            selectedWhatsAppTab = selectedWhatsAppTab
        )

        val selectedChannel = channels.find { it.id == selectedChannelId }
        when {
            selectedChannel == null ->
                ChatEmptyState(
                    serverName    = selectedServer.name,
                    channels      = channels,
                    onChannelClick = onChannelClick
                )
            selectedChannel.type == ChannelType.TEXT ->
                ChatScreen(
                    channel        = selectedChannel,
                    messages       = MockData.getMessagesForChannel(selectedChannel.id),
                    onSend         = { /* mock */ },
                    showMemberList = !isLiteMode
                )
            selectedChannel.type == ChannelType.VOICE ->
                VoiceChannelPlaceholder(channel = selectedChannel)
        }
    }
}