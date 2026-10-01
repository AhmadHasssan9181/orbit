package ui.screen.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import repository.MatrixOrbitRepository
import repository.SettingsManager
import ui.components.OrbitSearchField
import ui.components.OrbitNotices
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelCategory
import ui.model.OrbitChannel
import ui.model.OrbitServer
import ui.model.OrbitUser
import ui.screen.server.ServerHeader
import ui.screen.server.ServerMenuEntry
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import webrtc.LocalCallManager

/**
 * The channel sidebar. The concept's `.channel-sidebar`.
 *
 * Geometry and chrome are the concept's, rule for rule: 240dp wide
 * (`--sidebar-width`), `--bg-sidebar`, an 8dp radius on its top-left corner only
 * (the one rounded edge on the panel), `overflow: hidden`, and a column of
 * header / scrollable list / voice panel / user bar.
 *
 * ## What the concept's chrome actually costs
 *
 * The old shell was 248dp on `OrbitMetrics.drawerWidth` and had no top-left
 * radius at all, so the drawer met the rail on a hard right angle and read as a
 * second flat column rather than a panel sitting over the workspace.
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
    /**
     * Whether the friends view is the main column, and how to open it.
     *
     * Both are null/false unless a layout has somewhere to show
     * [FriendsPanel]. The concept's DM sidebar has a `Friends` row above the
     * conversations; when there is nowhere for it to go, the row is not drawn
     * rather than drawn as a button that opens nothing.
     */
    friendsTabActive: Boolean = false,
    onSelectFriendsTab: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showInviteDialog by remember { mutableStateOf(false) }
    var channelQuery by remember(server.id) { mutableStateOf("") }
    val t = rememberCosmicTokens()
    // CompactAppLayout hands this drawer `weight(1f)` and expects it to span the
    // pane; a hard-coded width fought the weight and clipped the room list.
    val isCompact = LocalWindowSizeClass.current.isCompact

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
                        .width(OrbitConceptMetrics.sidebarWidth)
                        .clipToBounds()
                }
            )
            .fillMaxHeight()
            // `.channel-sidebar { border-top-left-radius: 8px; overflow: hidden }`.
            // The radius is on ONE corner: it is the rail-to-sidebar join, so the
            // panel reads as sitting over the workspace rather than as a column
            // butted against it.
            .clip(SidebarConcept.sidebarShape)
            .background(conceptSidebarSurface())
    ) {
        // `.server-header` with the concept's dropdown behind it.
        //
        // The concept's menu is Server Boost / Invite People / Server Settings /
        // Notification Settings / Leave Server — all Discord product actions.
        // Orbit can honestly perform one of those (Invite) and, where the layout
        // offers it, Create a room. The rest are not rows of "unavailable": they
        // are not rendered at all, because a menu item that cannot do anything is
        // worse than a missing one.
        val serverMenuEntries: List<ServerMenuEntry> = if (server.isDM) {
            emptyList()
        } else {
            buildList {
                add(ServerMenuEntry(label = "Invite people") { showInviteDialog = true })
                onCreateRoomClick?.let { createRoom ->
                    add(ServerMenuEntry(label = "Create a room") { createRoom() })
                }
            }
        }
        ServerHeader(
            server = server,
            showArrow = !server.isDM,
            menuEntries = serverMenuEntries
        )

        // The "Invite people / Roles & permissions / Create a room" stack is
        // gone. It was three permanent 32dp rows above the room list, roughly
        // a third of the sidebar's height, on a screen whose entire job is to
        // show you the rooms. Discord shows none of them here: Invite lives
        // behind the server name, and the other two behind context menus that
        // are reachable when you need them.
        //
        // Reachability is preserved, not removed:
        //   Invite   -> the server-name dropdown (wired above).
        //   Create   -> the "+" on the last category header, and the same row in
        //               the server-name dropdown.
        //   Roles    -> onOpenRolesClick still reaches the roles screen from
        //               the layout above; see the note in ExpandedAppLayout.
        if (!server.isDM && onOpenRolesClick != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitSpace.sm, vertical = 1.dp),
                horizontalArrangement = Arrangement.End
            ) {
                androidx.compose.material3.Text(
                    text = "Roles & permissions",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                        color = t.Text_Subtle,
                        fontSize = 10.sp,
                        letterSpacing = 0.3.sp
                    ),
                    modifier = Modifier
                        .clip(OrbitRadius.xsShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onOpenRolesClick
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }

        if (server.isDM) {
            val callManager = LocalCallManager.current
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
                friendsTabActive = friendsTabActive,
                onSelectFriendsTab = onSelectFriendsTab,
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
            val joinCallManager = LocalCallManager.current
            val matrixRepository = repository as? MatrixOrbitRepository
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

        // `.mini-screen-preview-card` — between the channel list and the user
        // bar, and only while a share is running, which is what the concept
        // gates it on (`voiceState.connected`).
        MiniScreenPreviewSlot(
            callManager = LocalCallManager.current,
            modifier = Modifier.fillMaxWidth()
        )

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

/**
 * `.mini-screen-preview-card { margin: 6px 8px; background-color: var(--bg-secondary);
 * border: 1px solid var(--border-subtle); border-radius: 6px; padding: 8px 10px }`.
 *
 * Rendered only while a screen share is running, exactly as the concept gates
 * it on `voiceState.connected`.
 *
 * The whole card is the button: the concept puts `onClick={onFocusStream}` on
 * the card and a second, stop-propagating button on the popout glyph. There is
 * no separate popout in Orbit — the share already fills the main column — so
 * both are the same action here and one hit target is the honest rendering of
 * it. `Role.Button` plus the "Go to the shared screen" label keeps it
 * announceable.
 */
@Composable
private fun MiniScreenPreviewSlot(
    callManager: webrtc.CallManager?,
    modifier: Modifier = Modifier
) {
    // Hoisted so the number of remembered slots does not change when
    // callManager flips between null and non-null.
    val sharingFlow = remember(callManager) { callManager?.isScreenSharing ?: flowOf(false) }
    val isSharing by sharingFlow.collectAsState(initial = false)

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // An `if` rather than an early `return`: returning above the two remembered
    // interaction sources would change the number of composed children the moment
    // a share started or stopped.
    if (isSharing) {
        Row(
            modifier = modifier
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clip(OrbitRadius.smShape)
                .background(if (hovered) conceptHoverOverlay() else conceptSecondarySurface())
                .border(
                    1.dp,
                    if (hovered) conceptBorderStrong() else conceptBorderSubtle(),
                    OrbitRadius.smShape
                )
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Go to the shared screen",
                    onClick = { callManager?.expandCallSurface() }
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // `.screen-icon-box { width: 24px; height: 24px; border-radius: 4px;
            // background-color: rgba(26, 188, 156, 0.15) }`.
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(OrbitRadius.xsShape)
                    .background(SidebarConcept.screenShareTealWash),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = OrbitIcons.ScreenShare,
                    contentDescription = null,
                    tint = SidebarConcept.screenShareTeal,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Text(
                text = "Screen 1",
                style = androidx.compose.material3.MaterialTheme.typography.labelLarge.copy(
                    color = conceptHeaderPrimary(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = null,
                // `.screen-popout-btn { color: var(--interactive-normal) }`,
                // hover `#ffffff`.
                tint = if (hovered) conceptInteractiveActive() else conceptInteractiveNormal(),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
