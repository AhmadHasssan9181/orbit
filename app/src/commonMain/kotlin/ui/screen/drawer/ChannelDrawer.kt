package ui.screen.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import repository.SettingsManager
import ui.components.OrbitIconButton
import ui.components.OrbitSearchField
import ui.components.OrbitNotices
import ui.components.OrbitNotices.onFailureOrNotify
import ui.model.ChannelCategory
import ui.model.OrbitChannel
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.screen.server.ServerHeader
import ui.theme.OrbitMetrics
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * No ripple on the quiet Roles link: it is chrome, not a primary target.
 *
 * Deliberately NOT remembered -- a `Modifier.clickable` factory that called
 * `remember` would have to be `@Composable`, and this is a plain modifier
 * factory. A fresh `MutableInteractionSource` per application is what the
 * ripple-less overload is for, and at one call site it costs nothing.
 */
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )

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
    repository: repository.OrbitRepository? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var showInviteDialog by remember { mutableStateOf(false) }
    var channelQuery by remember(server.id) { mutableStateOf("") }
    val t = rememberCosmicTokens()
    // CompactAppLayout hands this drawer `weight(1f)` and expects it to span the
    // pane; a hard-coded 248dp fought the weight and clipped the room list.
    val isCompact = ui.layout.LocalWindowSizeClass.current.isCompact

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
            .then(
                if (isCompact) {
                    Modifier.fillMaxWidth()
                } else {
                    // Fixed width with a floor, and the whole thing clipped.
                    //
                    // A fixed width alone still overflows its parent when the parent
                    // is itself narrower than rail + drawer, and the overflow is
                    // drawn rather than trimmed: channel names, the search
                    // placeholder and the section headers were all sliced mid-glyph
                    // at the window edge. widthIn(min) documents the usable budget
                    // and clipToBounds guarantees that whatever does not fit is cut
                    // cleanly at the panel border instead of bleeding across the
                    // chat area beside it.
                    Modifier
                        .width(OrbitMetrics.drawerWidth)
                        .clipToBounds()
                }
            )
            .fillMaxHeight()
            .background(t.Drawer_Bg)
    ) {
        ServerHeader(
            server = server,
            showArrow = !server.isDM,
            // Clicking the server name opens Invite, which is where Discord
            // puts it too (the server-name dropdown). It used to open Invite
            // as well, but only after three full-width buttons had already
            // claimed a third of the sidebar to say the same three things.
            onHeaderClick = { if (!server.isDM) showInviteDialog = true },
            // "Create a room" also hangs off the last category header in the
            // list below, so the ServerHeader "+" is redundant with it.
            onCreateRoomClick = if (server.isDM) null else onCreateRoomClick
        )
        HorizontalDivider(color = t.Separator, thickness = 1.dp)

        // The "Invite people / Roles & permissions / Create a room" stack is
        // gone. It was three permanent 32dp rows above the room list, roughly
        // a third of the sidebar's height, on a screen whose entire job is to
        // show you the rooms. Discord shows none of them here: Invite lives
        // behind the server name, and the other two behind context menus that
        // are reachable when you need them.
        //
        // Reachability is preserved, not removed:
        //   Invite   -> click the server name (wired above).
        //   Create   -> the "+" on the last category header, and the "+" on
        //               the server header (wired above).
        //   Roles    -> onOpenRolesClick still reaches the roles screen from
        //               the layout above; see the note in ExpandedAppLayout.
        if (!server.isDM && onOpenRolesClick != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitSpace.sm, vertical = 1.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "Roles & permissions",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = t.Text_Subtle,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp
                    ),
                    modifier = Modifier
                        .clip(OrbitRadius.xsShape)
                        .clickableNoIndication(onOpenRolesClick)
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
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
                repository = repository,
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
            val joinCallScope = rememberCoroutineScope()
            val joinCallManager = webrtc.LocalCallManager.current
            val matrixRepository = repository as? repository.MatrixOrbitRepository
            ChannelListContent(
                categories = categories,
                channels = visibleChannels,
                selectedChannelId = selectedChannelId,
                currentUser = currentUser,
                repository = repository,
                unreadCounts = unreadCounts,
                // Opening the room of a call you are IN gives the room the main
                // column, and the call carries on in the status pill. Reading the
                // conversation you are calling from is a normal thing to want and
                // it used to be impossible while the call held the column.
                //
                // `.value` is read deliberately: this is a click handler, so it
                // wants the state at the instant of the click, not a snapshot
                // captured at composition.
                onChannelClick = { roomId ->
                    val manager = joinCallManager
                    val inCallHere = manager != null &&
                        manager.activeRoomIdFlow.value == roomId &&
                        manager.callState.value != webrtc.CallState.IDLE &&
                        manager.callState.value != webrtc.CallState.ENDED
                    if (inCallHere) manager.collapseCallSurface()
                    onChannelClick(roomId)
                },
                onCreateRoomClick = onCreateRoomClick,
                spaceName = server.name,
                // The `Voice` row under each room.
                //
                // It opens the room FIRST, then joins. That order matters: the
                // call surface renders in the right-hand panel of the room you are
                // looking at, so joining a call in a room you have not selected
                // would produce a live call with nowhere to show it — a call you
                // are in, with no controls, because you pressed a row in the
                // drawer. Opening the room first also lands you where you can see
                // the chat and the call side by side, which is the whole point of
                // the layout.
                onJoinCall = if (matrixRepository == null || joinCallManager == null) {
                    // No MatrixRTC from here, so show no row rather than one that
                    // fails. (The DM branch below has the same guard shape for
                    // `startCall`.)
                    null
                } else {
                    { roomId, isActiveCall ->
                        // ALWAYS open the room. Never hang up from here.
                        //
                        // This row used to branch: already in the call meant
                        // `endCall()`. That was a toggle dressed as a join button,
                        // and a run log caught it doing exactly that --
                        // `endCall ... <- ChannelDrawer.kt:250 <- ChannelList.kt:527`
                        // -- so pressing the row to *look at* the call disconnected
                        // you from it. Three separate reports of "it drops me" all
                        // traced back to this one branch.
                        //
                        // Leaving a call is the red button in the call controls,
                        // where it is unambiguous. Nothing else hangs up.
                        onChannelClick(roomId)
                        if (isActiveCall) {
                            // Already in it: just bring the call back on screen.
                            joinCallManager.expandCallSurface()
                        } else {
                            // The call surface renders in the main column of the
                            // room you have open, so joining before the room
                            // switches would produce a live call with no visible
                            // controls. Room first, then join.
                            joinCallScope.launch {
                                ui.screen.call.joinRoomGroupCall(
                                    repository = matrixRepository,
                                    callManager = joinCallManager,
                                    roomId = roomId
                                )
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider(color = t.Separator, thickness = 1.dp)
        val drawerScope = rememberCoroutineScope()
        val drawerSettings = remember { SettingsManager() }
        UserProfileBar(
            user = currentUser,
            onSettingsClick = onOpenSettings ?: {},
            onStatusSelected = if (repository != null) { status ->
                drawerScope.launch {
                    // The local setting was written even when the presence
                    // write failed, so the picker showed the new status while
                    // every other client still showed the old one. Write the
                    // server first, then the local mirror.
                    OrbitNotices.notifying("Presence update", "Presence") {
                        repository.setPresence(status)
                    }.onSuccess { drawerSettings.setMyStatus(status.name) }
                }
            } else null
        )
    }
}

