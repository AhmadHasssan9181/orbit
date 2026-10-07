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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import ui.icons.*
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
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
    unreadCounts: Map<String, Int> = emptyMap(),
    unreadByServer: Map<String, Int> = emptyMap(),
    /**
     * Whether the friends view is the main column.
     *
     * See [ui.screen.drawer.ChannelDrawer.friendsTabActive]. Defaults to false so
     * every existing call site compiles unchanged; when false no `Friends` row is
     * drawn in the DM sidebar either, so the control and the destination appear
     * and disappear together.
     */
    friendsTabActive: Boolean = false,
    onSelectFriendsTab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()
    val selectedServer = servers.find { it.id == selectedServerId }
    // The rail's compass tile. `ServerRail` has always taken an
    // `onExploreClick` and was never given one, so the tile was a control that
    // did nothing.
    var showExplore by remember { mutableStateOf(false) }

    val categories = remember(channels) {
        channels.mapNotNull { it.categoryId }.distinct()
            .map { catId ->
                val firstCh = channels.firstOrNull { it.categoryId == catId }
                ChannelCategory(id = catId, name = ui.model.categoryDisplayName(catId), channelId = firstCh?.id ?: "")
            }
    }

    Row(modifier = modifier.fillMaxSize().background(tokens.Rail_Bg)) {
        ServerRail(
            servers = servers,
            selectedServerId = selectedServerId,
            onServerClick = onServerClick,
            onAddServerClick = onAddServer,
            onExploreClick = { showExplore = true },
            onOpenSettings = onOpenSettings,
            unreadByServer = unreadByServer
        )

        // The unified big card starting immediately after ServerRail and extending all the way to the right edge!
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 8.dp, bottom = 8.dp, start = 6.dp, end = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Drawer_Bg)
                .border(1.dp, tokens.Separator.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
        ) {
            if (selectedServer != null) {
                Row(modifier = Modifier.fillMaxSize()) {
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
                        friendsTabActive = friendsTabActive,
                        onSelectFriendsTab = onSelectFriendsTab,
                        )

                    val selectedChannel = channels.find { it.id == selectedChannelId }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when {
                            isLoadingChannels ->
                                RoomLoadingPlaceholder()

                            // The friends view takes the main column while its sidebar row is
                            // active. A DM row is never "selected" in that state, so there is
                            // nothing to arbitrate between the two.
                            friendsTabActive ->
                                FriendsPanel(
                                    repository = repository,
                                    dmChannels = channels,
                                    onOpenChannel = onChannelClick,
                                    modifier = Modifier.fillMaxSize()
                                )

                            selectedChannel == null ->
                                OrbitHomeDashboard(
                                    server = selectedServer,
                                    onCreateServer = onAddServer,
                                    onDiscover = { showExplore = true },
                                    onOpenSettings = onOpenSettings,
                                    onChannelClick = onChannelClick,
                                    channels = channels
                                )

                            selectedChannel.type == ChannelType.TEXT || selectedChannel.type == ChannelType.VOICE ->
                                ChatScreenWithRepo(
                                    repository = repository,
                                    channel = selectedChannel,
                                    showMemberList = !selectedServer.isDM,
                                    isDirectMessage = selectedServer.isDM,
                                    modifier = Modifier.fillMaxSize()
                                )
                        }
                    }
                }
            } else {
                OrbitHomeDashboard(
                    server = null,
                    onCreateServer = onAddServer,
                    onDiscover = { showExplore = true },
                    onOpenSettings = onOpenSettings,
                    onChannelClick = onChannelClick,
                    channels = channels
                )
            }
        }
    }

    // Outside the Row: it is its own native window, so where it is composed from
    // makes no difference, and this keeps it clear that it is not part of the
    // layout's geometry.
    if (showExplore) {
        ExploreServersDialog(
            repository = repository,
            onClose = { showExplore = false },
            onOpenServer = onServerClick
        )
    }
}

/**
 * A single hairline between two panes.
 *
 * Without it the rail, the drawer and the chat all sat on adjacent values of
 * the same neutral ramp and the three columns blurred into one surface; the
 * eye had nothing to latch the layout onto.
 */
@Composable
private fun VerticalRule() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(rememberCosmicTokens().Separator)
    )
}

