package ui.screen.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import repository.SettingsManager
import ui.components.OrbitIconButton
import ui.components.OrbitSearchField
import ui.model.ChannelCategory
import ui.model.OrbitChannel
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.screen.server.ServerHeader
import ui.theme.OrbitMetrics
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * The channel drawer.
 *
 * Two structural changes here beyond restyling:
 *
 *  1. "Browse Channels" was a button wired to `{}` — a visible dead control in
 *     the most-used panel of the app. It is now a real search field that
 *     filters the room list, which is what the label always implied.
 *  2. The four action rows are now quiet, hover-revealed affordances instead of
 *     four permanently-coloured buttons sitting directly under the server name.
 */
@Composable
fun ChannelDrawer(
    server: OrbitServer,
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser,
    onChannelClick: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onCreateRoomClick: (() -> Unit)? = null,
    onOpenRolesClick: (() -> Unit)? = null,
    selectedWhatsAppTab: String = "Chats",
    repository: repository.OrbitRepository? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var showInviteDialog by remember { mutableStateOf(false) }
    var channelQuery by remember(server.id) { mutableStateOf("") }
    val t = rememberCosmicTokens()

    val visibleChannels = remember(channels, channelQuery) {
        if (channelQuery.isBlank()) {
            channels
        } else {
            val q = channelQuery.trim().lowercase()
            channels.filter { it.name.lowercase().contains(q) }
        }
    }

    if (showInviteDialog) {
        InviteDialog(server = server, onDismiss = { showInviteDialog = false })
    }

    Column(
        modifier = modifier
            .width(OrbitMetrics.drawerWidth)
            .fillMaxHeight()
            .background(t.Drawer_Bg)
    ) {
        val isLiteMode = ui.theme.LocalIsLiteMode.current

        if (isLiteMode) {
            when (selectedWhatsAppTab) {
                "Chats" -> {
                    WhatsAppDrawerHeader()
                    WhatsAppSearchBar()
                    WhatsAppFilterChips()
                    DmListContent(
                        channels = channels,
                        selectedChannelId = selectedChannelId,
                        onChannelClick = onChannelClick,
                        modifier = Modifier.weight(1f)
                    )
                }
                "Calls" -> WhatsAppCallsDrawer()
                "Status" -> WhatsAppStatusDrawer()
                "Communities" -> WhatsAppCommunitiesDrawer()
            }
        } else {
            ServerHeader(
                server = server,
                showArrow = !server.isDM,
                onHeaderClick = { if (!server.isDM) showInviteDialog = true },
                onCreateRoomClick = if (server.isDM) null else onCreateRoomClick
            )
            HorizontalDivider(color = t.Separator, thickness = 1.dp)

            if (!server.isDM) {
                // Quick actions — quiet until wanted.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.xs),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp)
                ) {
                    DrawerActionButton(
                        icon = Icons.Default.PersonAdd,
                        text = "Invite people",
                        accent = true,
                        onClick = { showInviteDialog = true }
                    )
                    if (onOpenRolesClick != null) {
                        DrawerActionButton(
                            icon = Icons.Default.Security,
                            text = "Roles & permissions",
                            accent = false,
                            onClick = onOpenRolesClick
                        )
                    }
                    if (onCreateRoomClick != null) {
                        DrawerActionButton(
                            icon = Icons.Default.Add,
                            text = "Create a room",
                            accent = false,
                            onClick = onCreateRoomClick
                        )
                    }
                }
                Spacer(Modifier.height(OrbitSpace.xs))
            }

            if (server.isDM) {
                val callManager = webrtc.LocalCallManager.current
                Column(modifier = Modifier.padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.sm)) {
                    OrbitSearchField(
                        value = channelQuery,
                        onValueChange = { channelQuery = it },
                        placeholder = "Search conversations",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                DmListContent(
                    channels = visibleChannels,
                    selectedChannelId = selectedChannelId,
                    unreadCounts = unreadCounts,
                    onChannelClick = onChannelClick,
                    onVoiceCall = { callManager?.startCall(it, false) },
                    onVideoCall = { callManager?.startCall(it, true) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.xs)) {
                    OrbitSearchField(
                        value = channelQuery,
                        onValueChange = { channelQuery = it },
                        placeholder = "Find a room",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ChannelListContent(
                    categories = categories,
                    channels = visibleChannels,
                    selectedChannelId = selectedChannelId,
                    currentUser = currentUser,
                    repository = repository,
                    unreadCounts = unreadCounts,
                    onChannelClick = onChannelClick,
                    onCreateRoomClick = onCreateRoomClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = t.Separator, thickness = 1.dp)
        val drawerScope = rememberCoroutineScope()
        val drawerSettings = remember { SettingsManager() }
        UserProfileBar(
            user = currentUser,
            onMicToggle = {},
            onSettingsClick = onOpenSettings ?: {},
            onStatusSelected = if (repository != null) { status ->
                drawerScope.launch {
                    runCatching { repository.setPresence(status) }
                    drawerSettings.setMyStatus(status.name)
                }
            } else null
        )
    }
}
