package ui.screen.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import repository.SettingsManager
import ui.model.*

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

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
    modifier: Modifier = Modifier
) {
    var showInviteDialog by remember { mutableStateOf(false) }

    if (showInviteDialog) {
        InviteDialog(
            server = server,
            onDismiss = { showInviteDialog = false }
        )
    }

    Column(
        modifier = modifier
            .width(252.dp)
            .fillMaxSize()
            .background(Drawer_Bg)
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
            // ─── Server header — Discord/Revolt style ────────────
            ServerHeader(
                server = server,
                showArrow = !server.isDM,
                onHeaderClick = {
                    if (!server.isDM) showInviteDialog = true
                },
                onCreateRoomClick = if (server.isDM) null else onCreateRoomClick
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)

            if (!server.isDM) {
                DrawerActionButton(
                    icon = Icons.Default.Search,
                    text = "Browse Channels",
                    accent = false,
                    onClick = {}
                )
                DrawerActionButton(
                    icon = Icons.Default.PersonAdd,
                    text = "Invite People",
                    accent = true,
                    onClick = { showInviteDialog = true }
                )
                if (onOpenRolesClick != null) {
                    DrawerActionButton(
                        icon = Icons.Default.Security,
                        text = "Roles",
                        accent = false,
                        onClick = onOpenRolesClick
                    )
                }
                Spacer(Modifier.height(3.dp))
            }

            if (server.isDM) {
                val callManager = webrtc.LocalCallManager.current
                DirectMessageSearchBar()
                DirectMessageNav()
                HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)
                DmListContent(
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    onChannelClick = onChannelClick,
                    onVoiceCall = { callManager?.startCall(it, false) },
                    onVideoCall = { callManager?.startCall(it, true) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                ChannelListContent(
                    categories = categories,
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    currentUser = currentUser,
                    repository = repository,
                    onChannelClick = onChannelClick,
                    onCreateRoomClick = onCreateRoomClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        HorizontalDivider(color = Separator.copy(alpha = 0.3f), thickness = 1.dp)
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
