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
import ui.model.*
import androidx.compose.ui.unit.dp
import webrtc.*
import repository.*
import ui.screen.chat.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import ui.icons.*
import ui.theme.*

@Composable
fun ExpandedLayout(
    servers: List<OrbitServer>,
    channels: List<OrbitChannel>,
    selectedServerId: String,
    selectedChannelId: String,
    currentUser: OrbitUser?,
    isLoadingChannels: Boolean,
    repository: OrbitRepository,
    callManager: CallManager,
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
                repository = repository,
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

                selectedChannel.type == ChannelType.TEXT || selectedChannel.type == ChannelType.VOICE ->
                    ChatScreenWithRepo(
                        repository = repository,
                        channel = selectedChannel,
                        showMemberList = !isLiteMode && !selectedServer.isDM,
                        isDirectMessage = selectedServer.isDM
                    )            }
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

