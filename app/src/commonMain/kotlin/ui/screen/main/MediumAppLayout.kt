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
import ui.components.OrbitMessageSkeleton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import ui.icons.*
import ui.theme.*

/**
 * Loading state for a room that hasn't resolved yet.
 *
 * Skeletons at the shape of the content, rather than a spinner: switching rooms
 * used to blank the pane and spin, which reads as a crash on a fast click.
 */
@Composable
internal fun RoomLoadingPlaceholder() {
    Column(modifier = Modifier.fillMaxSize().padding(top = 52.dp)) {
        repeat(5) { OrbitMessageSkeleton(avatar = it == 0 || it == 3) }
    }
}

@Composable
fun MediumLayout(
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
    unreadCounts: Map<String, Int> = emptyMap(),
    unreadByServer: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    val selectedServer = servers.find { it.id == selectedServerId }
    val isLiteMode = LocalIsLiteMode.current
    var selectedWhatsAppTab by remember { mutableStateOf("Chats") }

    val categories = remember(channels) {
        channels.mapNotNull { it.categoryId }.distinct()
            .map { catId ->
                val firstCh = channels.firstOrNull { it.categoryId == catId }
                ChannelCategory(id = catId, name = ui.model.categoryDisplayName(catId), channelId = firstCh?.id ?: "")
            }
    }

    Row(modifier = modifier.fillMaxSize().background(tokens.Bg)) {
        ServerRail(
            servers = servers,
            selectedServerId = selectedServerId,
            onServerClick = onServerClick,
            onAddServerClick = onAddServer,
            selectedWhatsAppTab = selectedWhatsAppTab,
            onWhatsAppTabClick = { selectedWhatsAppTab = it },
            unreadByServer = unreadByServer,
            modifier = Modifier.width(64.dp).fillMaxHeight()
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
                unreadCounts = unreadCounts,
                onChannelClick = onChannelClick,
                onOpenSettings = onOpenSettings,
                onCreateRoomClick = onCreateRoom,
                onOpenRolesClick = onOpenRoles,
                selectedWhatsAppTab = if (isLiteMode) selectedWhatsAppTab else ""
            )
        }

        val selectedChannel = channels.find { it.id == selectedChannelId }
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when {
                isLoadingChannels -> RoomLoadingPlaceholder()

                selectedChannel == null -> ChatEmptyState(
                    serverName = selectedServer?.name ?: "Orbit",
                    channels = channels,
                    onChannelClick = onChannelClick
                )

                selectedChannel.type == ChannelType.TEXT || selectedChannel.type == ChannelType.VOICE ->
                    ChatScreenWithRepo(
                        repository = repository,
                        channel = selectedChannel,
                        showMemberList = false,
                        isDirectMessage = selectedServer?.isDM == true
                    )
            }
        }
    }
}

