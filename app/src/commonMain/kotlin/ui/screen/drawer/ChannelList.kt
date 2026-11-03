package ui.screen.drawer

import ui.components.OrbitAvatarImage

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.ChevronDown
import ui.icons.Settings
import ui.icons.Trash
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.rememberCoroutineScope
import ui.components.OrbitMenu
import ui.components.OrbitMenuItem
import ui.components.OrbitMenuDivider
import ui.components.OrbitDialog
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitTextField
import ui.components.OrbitNotices
import ui.components.rememberOrbitInteraction
import ui.components.orbitClickable
import ui.theme.rememberCosmicTokens
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ui.assets.OrbitDefaultAvatar
import ui.components.OrbitCountBadge
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.ChannelCategory
import ui.model.ChannelGrouping
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import webrtc.LocalCallManager
/**
 * The channel list. The concept's `.channels-scrollable` plus every category and
 * row inside it.
 *
 * The rows are NOT [ui.components.OrbitListRow] any more. The concept's
 * `.channel-row` has no selection bar at all — "you are reading this channel"
 * is carried entirely by `--bg-modifier-selected` — and its own geometry is
 * `padding: 6px 8px` with an 18px glyph, which makes it 30dp tall.
 * `OrbitListRow` is a 3dp accent bar plus 34dp, so keeping it here meant every
 * row was 4dp too tall and drew an edge the concept does not have. The shared
 * row is still what the member list and the settings sidebar use; this panel has
 * its own shape.
 *
 * Everything else here is unchanged and still load-bearing: the grouping comes
 * from [ChannelGrouping], the roster only subscribes for the room we are
 * actually in a call in, and the roster that renders is the LiveKit-reconciled
 * one rather than Matrix's membership record.
 */
@Composable
internal fun ChannelListContent(
    @Suppress("UNUSED_PARAMETER") categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    onChannelClick: (String) -> Unit,
    onCreateRoomClick: (() -> Unit)? = null,
    /**
     * Join or leave the call in the room with this Matrix room id.
     *
     * [isActiveCall] is true when YOU are already in that room's call, which is
     * what makes the row a toggle rather than a one-way join button.
     *
     * Nullable so a caller that cannot do MatrixRTC — a non-Matrix repository,
     * the DM drawer — renders no voice affordance at all rather than a row that
     * does nothing when pressed.
     */
    onJoinCall: ((roomId: String, isActiveCall: Boolean) -> Unit)? = null,
    /**
     * Name of the space these rooms belong to, used as the single heading when
     * the server reports no categories. Without it the heading falls back to a
     * neutral "Channels" rather than an invented type — see
     * `ChannelGrouping.uncategorisedLabel`.
     */
    spaceName: String? = null,
    spaceId: String? = null,
    onChannelDeleted: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Which groups the user has collapsed. Keyed by group key, NOT by index or
    // label, so collapsing "Lounge" does not collapse whatever later takes its
    // slot in the list.
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    // `spaceName` is used for the single heading when the server reports no
    // categories, so the group is titled with the name the user gave the space
    // rather than an invented type.
    val groups = remember(channels, spaceName) { ChannelGrouping.group(channels, spaceName) }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            // `.channels-scrollable { padding: 12px 8px }`.
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 12.dp,
                start = 12.dp,
                end = 12.dp
            ),
            // `.channels-scrollable { gap: 16px }` — the gap BETWEEN groups. The
            // 2px that separates a heading from its rows and the rows from each
            // other is `.category-group { gap: 2px }` and lives inside the group
            // block below, which is why each group is ONE lazy item rather than a
            // header item followed by one item per channel.
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (groups.isEmpty()) {
                item(key = "empty_rooms", contentType = "empty") {
                    Text(
                        text = "No rooms here yet",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = conceptTextMuted(),
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(start = 26.dp, top = OrbitSpace.sm, bottom = OrbitSpace.sm)
                    )
                }
            }

            groups.forEach { group ->
                val isCollapsed = group.key in collapsed.value
                item(key = "grp_${group.key}", contentType = "group") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        ChannelSectionHeader(
                            name = group.label,
                            isExpanded = !isCollapsed,
                            onToggle = {
                                collapsed.value = if (isCollapsed) {
                                    collapsed.value - group.key
                                } else {
                                    collapsed.value + group.key
                                }
                            },
                            // Only the LAST group offers "create". A "+" on every
                            // heading reads as "create inside THIS category", which the
                            // create flow cannot honour -- so it would be a button
                            // that lies.
                            onAddClick = if (group.key == groups.last().key) onCreateRoomClick else null
                        )
                        if (!isCollapsed) {
                            group.channels.forEach { ch ->
                                val chUnread = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0)
                                ChannelItem(
                                    channel = ch,
                                    isSelected = ch.id == selectedChannelId,
                                    currentUser = currentUser,
                                    repository = repository,
                                    unreadCount = chUnread,
                                    onClick = { onChannelClick(ch.id) },
                                    onJoinCall = onJoinCall,
                                    spaceId = spaceId,
                                    onChannelDeleted = onChannelDeleted
                                )
                            }
                        }
                    }
                }
            }
        }
        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * `.category-header { padding: 6px 8px; font-size: 12px; font-weight: 700;
 * color: var(--text-muted); letter-spacing: 0.02em; text-transform: uppercase;
 * border-radius: 4px }`, hover `--header-secondary`.
 *
 * `letter-spacing: 0.02em` at 12px is 0.24sp. Compose's letterSpacing is in
 * sp, not em, so that is what is spelled out here.
 */
@Composable
internal fun ChannelSectionHeader(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // `transform: rotate(-90deg)` when collapsed, `transition: transform 0.15s`.
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = OrbitMotion.Snappy,
        label = "sectionChevron"
    )
    // `.category-header:hover { color: var(--header-secondary) }`.
    val labelColor by animateColorAsStateCompat(
        if (hovered) conceptHeaderSecondary() else conceptTextMuted()
    )

    // The hover wash and the click target are the HEADER's own box, padding
    // included, which is what `padding: 6px 8px` on a clickable element means in
    // CSS. Padding applied before `background`/`hoverable` would inset both, so
    // the padding lives on the Row inside and the Box carries the surface.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitRadius.xsShape)
            .background(if (hovered) conceptHoverOverlay() else Color.Transparent)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (isExpanded) "Collapse $name" else "Expand $name",
                onClick = onToggle
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // `.category-header-title { display: flex; align-items: center; gap: 4px }`.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = with(TablerIcons) { ChevronDown },
                    contentDescription = null,
                    // The concept does not colour the chevron separately; it
                    // inherits the heading's own colour, which is why it steps to
                    // `--header-secondary` with the label on hover.
                    tint = labelColor,
                    modifier = Modifier
                        .size(12.dp)
                        .rotate(rotation)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = name.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = labelColor,
                        fontSize = 12.sp,
                        letterSpacing = 0.24.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onAddClick != null) {
                // `<Plus size={14} title="Create Channel" />`. Always present in
                // the concept rather than revealed on hover, because the heading
                // is a muted 12px and a hover-only control on it is a control
                // nobody finds.
                //
                // `IconButton` rather than a bare `clickable`: its ripple is what
                // makes a 20dp target feel pressable, and `size(20.dp)` on the
                // modifier overrides its own minimum because the incoming
                // constraints are then exact.
                androidx.compose.material3.IconButton(
                    onClick = onAddClick,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = OrbitIcons.Add,
                        contentDescription = "Create a room in $name",
                        tint = conceptTextMuted(),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * `.channel-row`, and everything that hangs off one room.
 *
 * The data plumbing below is unchanged from the version this replaced and is
 * still the interesting part of the file:
 *
 *  - the roster belongs under a room with a LIVE call and only once the LiveKit
 *    room has said who is in it ([showRoster]);
 *  - only that one room is polled, because `observeCallMembers` is a 3-second
 *    full-room-state fetch per subscription;
 *  - the roster that renders is [webrtc.CallManager.displayCallMembers], which is
 *    the same reconciled list the call surface tiles use.
 */
@Composable
internal fun ChannelItem(
    channel: OrbitChannel,
    isSelected: Boolean,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    onJoinCall: ((roomId: String, isActiveCall: Boolean) -> Unit)? = null,
    spaceId: String? = null,
    onChannelDeleted: ((String) -> Unit)? = null
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val callManager = LocalCallManager.current
    // Hoisted out of the `?:` so the number of remembered slots does not change
    // when callManager flips between null and non-null.
    val callStateFlow = remember(callManager) {
        callManager?.callState ?: flowOf(webrtc.CallState.IDLE)
    }
    val callState by callStateFlow.collectAsState(initial = webrtc.CallState.IDLE)
    // The Matrix room id, NOT channel.id. A synthetic voice channel has
    // id = "voice:<roomId>" (it needs a distinct id so the list does not collide
    // with the text room it borrows) while its matrixRoomId is the real room --
    // and CallManager.activeRoomId is always a MATRIX room id, set from the room
    // the call was joined in. Comparing it against channel.id alone therefore
    // never matched for a synthetic voice channel, so `isActiveCallInChannel` was
    // false exactly where the local user IS in the call.
    val roomId = channel.matrixRoomId.ifBlank { channel.id }
    // COLLECTED, not read as `.value`.
    //
    // Reading a StateFlow's value outside a `collectAsState` returns whatever the
    // flow held at the moment the row happened to compose and nothing ever
    // recomposes it, so a row composed before the call started kept
    // `activeRoomId == null` for the whole call. That made
    // `isActiveCallInChannel` false in exactly the situation it exists to detect.
    val activeRoomIdFlow = remember(callManager) {
        callManager?.activeRoomIdFlow ?: flowOf<String?>(null)
    }
    val activeRoomId by activeRoomIdFlow.collectAsState(initial = null)
    val isActiveCallInChannel =
        activeRoomId != null &&
            (activeRoomId == roomId || activeRoomId == channel.id) &&
            callState != webrtc.CallState.IDLE &&
            callState != webrtc.CallState.ENDED
    // Connected, not merely "in a call". CONNECTING/RINGING is the window where
    // the LiveKit participant set is still empty, and every row in it would
    // otherwise fall back to the stale Matrix roster -- see `visibleMembers`.
    val isConnectedInChannel = isActiveCallInChannel && callState == webrtc.CallState.CONNECTED
    // The window between pressing Join and being connected. Distinguished from
    // `isConnectedInChannel` so the row can say so rather than reporting a count.
    val isConnectingInChannel = isActiveCallInChannel && callState == webrtc.CallState.CONNECTING
    val isLive = channel.activeCallUrl != null || isActiveCallInChannel
    val displayName = remember(channel.name, channel.type) { channelDisplayName(channel) }

    // The roster belongs UNDER A VOICE CHANNEL, and only under a voice channel.
    //
    // It used to render under anything with a non-empty member list, which is how
    // a text room ended up showing a call roster nobody was in. A text room cannot
    // have call participants at all: MatrixRTC calls live in the room state, but
    // Orbit surfaces the roster only on voice channels. Rendering it on a text
    // row is never correct, so the condition is the channel TYPE rather than
    // "does this room happen to have stale membership records".
    //
    // `isVoice` no longer decides whether a roster may show.
    //
    // It used to, on the reasoning that "a text room cannot have call
    // participants". That reasoning came from the Text/Voice split, which is
    // gone: a Matrix room is one object with both a timeline and call state, and
    // a room that has been a voice room once keeps that state for hours
    // afterwards. Gating on the type therefore hid the roster from exactly the
    // rooms people were in.
    //
    // A roster may only render once the LiveKit room has told us who is in it.
    // `isConnectedInChannel` rather than `isActiveCallInChannel`: the roster is
    // empty while connecting, so rendering the container then would be an empty
    // indented box.
    //
    // Note this is now the ONLY gate. A call running in a room we are not in
    // gets the live dot and no member list -- see `mayHaveParticipants` for why
    // that list could never have been trustworthy anyway.
    val showRoster = isConnectedInChannel

    // observeCallMembers() starts a 3-second HTTP poll of the FULL room state --
    // hundreds of KB every 3s. Subscribing for every room that merely *had* a
    // call bounded the poller count by "rooms that have ever had a call", which
    // on a real account is most of them, and each one never stopped.
    //
    // A run log caught the result: clicking a room during a call started a
    // poller for a room whose call had begun 121 days earlier, and it ran every
    // three seconds for the rest of the session. Clicking a room is not a reason
    // to start polling it.
    //
    // So: subscribe only for the room we are actually IN a call in, which is the
    // only place the roster is both affordable and trustworthy. For a call
    // running in some other room we show the live dot and nothing else -- the
    // only available source there is the same 12-hour-expiry membership record
    // that used to render six ghosts under a two-person call, and there is no
    // LiveKit room for us to be authoritative about. Element shows a join
    // indicator for other rooms' calls rather than a member count, for the same
    // reason.
    //
    // Keyed on the channel so it cannot flap between recompositions.
    val mayHaveParticipants = remember(channel.id, isActiveCallInChannel) {
        isActiveCallInChannel
    }
    var members by remember(channel.id) { mutableStateOf<List<ui.model.OrbitUser>>(emptyList()) }
    LaunchedEffect(repository, roomId, mayHaveParticipants) {
        if (!mayHaveParticipants) {
            members = emptyList()
            return@LaunchedEffect
        }
        val repo = repository ?: return@LaunchedEffect
        // cancelAndJoin so a switch of room cannot leave the previous poller's
        // last emission racing this one.
        val job = launch { repo.observeCallMembers(roomId).collect { members = it } }
        awaitCancellation()
        job.cancelAndJoin()
    }
    // Deliberately NO DisposableEffect calling stopCallRosterPolling here. The
    // poller is repository-scoped and SHARED: it feeds the participant roster AND
    // the device roster that drives the E2EE media-key exchange. This row is a
    // lazy-list item, so scrolling the drawer would have cancelled the poller
    // out from under the LIVE call and frozen its participant list. Hangup
    // already stops it, via CallManager.onGroupCallObserversStop.

    // WHO IS IN THE CALL, for DISPLAY. `members` above is the raw m.call.member
    // MEMBERSHIP record: multi-hour expiry, one entry per device per call, never
    // withdrawn when the person actually leaves. Rendering it verbatim is what
    // put 4-8 names under every voice channel for a call with 2 people.
    //
    // `displayCallMembers` is `CallManager`'s own reconciled roster -- the LiveKit
    // participant set intersected with the Matrix records, emptied whenever no
    // call is live and gated on CONNECTED rather than merely "not idle". It is the
    // same value the call surface tiles and the side-panel header count read, so
    // the drawer cannot disagree with either. Nothing here intersects anything
    // itself, which is the point: there is one reconciliation and this is not it.
    val displayCallMembersFlow = remember(callManager) {
        callManager?.displayCallMembers ?: flowOf(emptyList())
    }
    val displayCallMembers by displayCallMembersFlow.collectAsState(emptyList())
    val visibleMembers = when {
        // Connected in this room: the LiveKit room is authoritative.
        isConnectedInChannel -> displayCallMembers
        // Joining: the LiveKit participant set is empty by definition, so any
        // roster we could render here would be the stale Matrix list -- the six
        // names under a two-person call. Show nobody until we are actually
        // connected.
        isActiveCallInChannel -> emptyList()
        // A call running in a room we are NOT in. `members` is empty by design
        // here: we do not subscribe for such rooms at all, because the poller
        // behind it costs a full room-state download every 3 seconds to produce
        // the stale list that used to render as ghosts. See
        // `mayHaveParticipants`.
        else -> emptyList()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            ConceptChannelRow(
                channel = channel,
                displayName = displayName,
                isSelected = isSelected,
                isLive = isLive,
                unreadCount = unreadCount,
                onClick = {
                    if (channel.type == ChannelType.VOICE && onJoinCall != null) {
                        onJoinCall(roomId, isActiveCallInChannel)
                    } else {
                        onClick()
                    }
                },
                onSecondaryClick = {
                    showContextMenu = true
                },
                onEditClick = {
                    showEditDialog = true
                }
            )

            OrbitMenu(
                expanded = showContextMenu,
                onDismissRequest = { showContextMenu = false },
                width = 180.dp
            ) {
                OrbitMenuItem(
                    label = "Edit Channel",
                    icon = with(TablerIcons) { Settings },
                    onClick = {
                        showContextMenu = false
                        showEditDialog = true
                    }
                )
                OrbitMenuDivider()
                OrbitMenuItem(
                    label = "Delete Channel",
                    icon = with(TablerIcons) { Trash },
                    danger = true,
                    onClick = {
                        showContextMenu = false
                        showDeleteDialog = true
                    }
                )
            }
        }

        // For TEXT channels: only show the inline Voice row if someone has started an active live call in an external/Element room.
        // In Orbit-managed servers, TEXT channels are strictly pure text chat (no voice rows under them).
        if (!channel.isOrbitManaged && channel.type == ChannelType.TEXT && isLive && onJoinCall != null) {
            VoiceChannelRow(
                isLive = !isConnectingInChannel,
                isHere = isActiveCallInChannel,
                isConnecting = isConnectingInChannel,
                participantCount = if (isConnectedInChannel) visibleMembers.size else 0,
                onClick = { onJoinCall(roomId, isActiveCallInChannel) }
            )
        }

        // Voice Channels and Voice (unchanged): roster nests under the channel it
        // belongs to, indented, which is the single most recognisable thing about
        // Discord's sidebar. It used to hang under text rooms too, which both
        // looked wrong and is where the "everyone is in this call" ghost list came
        // from.
        if (showRoster && visibleMembers.isNotEmpty()) {
            VoiceMemberStack(
                members = visibleMembers,
                currentUser = currentUser,
                // Only while CONNECTED. `showLocalFallback` exists to draw "You"
                // in a roster the homeserver has not caught up with yet, and
                // before connecting there is no roster at all, so it would draw
                // you as the sole participant of a call you have not joined.
                showLocalFallback = isConnectedInChannel,
                callManager = callManager
            )
        }

        if (showEditDialog) {
            EditChannelDialog(
                channel = channel,
                spaceId = spaceId,
                repository = repository,
                onDismiss = { showEditDialog = false },
                onOpenDelete = {
                    showEditDialog = false
                    showDeleteDialog = true
                }
            )
        }

        if (showDeleteDialog) {
            DeleteChannelDialog(
                channel = channel,
                spaceId = spaceId,
                repository = repository,
                onDismiss = { showDeleteDialog = false },
                onDeleted = {
                    showDeleteDialog = false
                    onChannelDeleted?.invoke(channel.id)
                }
            )
        }
    }
}

/**
 * The row itself: `.channel-row`.
 *
 * Four values carry the whole interaction model and all four are the concept's:
 *
 * | state    | background                  | colour              |
 * |----------|-----------------------------|---------------------|
 * | rest     | transparent                 | interactive-normal  |
 * | hover    | `--bg-modifier-hover`       | interactive-hover   |
 * | selected | `--bg-modifier-selected`    | interactive-active  |
 *
 * and the selected row is weight 600 where the rest are 500. There is no
 * selection bar: the concept's selected state is a wash across the full row
 * width, not an accent edge.
 */
@Composable
private fun ConceptChannelRow(
    channel: OrbitChannel,
    displayName: String,
    isSelected: Boolean,
    isLive: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
    onEditClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsStateCompat(
        when {
            isSelected -> conceptSelectedOverlay()
            hovered -> conceptHoverOverlay()
            else -> Color.Transparent
        }
    )
    val fg by animateColorAsStateCompat(
        when {
            isSelected -> conceptInteractiveActive()
            hovered -> conceptInteractiveHover()
            else -> conceptInteractiveNormal()
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // `.channel-row { padding: 6px 8px; border-radius: 4px }`. The
            // padding goes LAST so the wash, the hover and the click target are
            // the row's full box rather than the box minus its own padding --
            // 6 + 18 + 6 is the 30dp the concept's row occupies.
            .defaultMinSize(minHeight = 30.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction)
            .then(
                if (onSecondaryClick != null) {
                    Modifier.pointerInput(channel.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                    event.changes.forEach { it.consume() }
                                    onSecondaryClick()
                                }
                            }
                        }
                    }
                } else Modifier
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Open $displayName",
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // `.channel-row-content { display: flex; align-items: center; gap: 8px;
        // overflow: hidden; text-overflow: ellipsis; white-space: nowrap }`.
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // `.channel-icon { color: var(--interactive-muted) }` at rest, and
            // `--interactive-hover` on both hover and selected.
            Icon(
                imageVector = if (channel.type == ChannelType.VOICE) {
                    OrbitIcons.VoiceChannel
                } else {
                    OrbitIcons.TextChannel
                },
                contentDescription = null,
                tint = if (hovered || isSelected) {
                    conceptInteractiveHover()
                } else {
                    SidebarConcept.interactiveMuted
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = fg,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 14.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isLive) {
                LiveDot(label = "Live call in progress", color = OrbitConceptStatus.online)
            }
            if (unreadCount > 0 && !isSelected) {
                OrbitCountBadge(count = unreadCount, color = conceptBrand)
            }
            if (hovered && onEditClick != null) {
                androidx.compose.material3.IconButton(
                    onClick = onEditClick,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = with(TablerIcons) { Settings },
                        contentDescription = "Edit Channel",
                        tint = if (isSelected) conceptInteractiveActive() else conceptInteractiveHover(),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * The `Voice` row under a room in the drawer.
 *
 * Indented 8dp, one step quieter than the room above it: the room is the thing
 * you navigate to, the voice row is the thing you press once.
 *
 * Three states, and no dead ones:
 *
 * - **idle** — nobody in the room. Pressing starts the call there, which is why
 *   the label is a plain noun: this is "the call for #general", not a claim that
 *   one is already running.
 * - **live** — somebody is in it. Says so, with the count, so the row is not a
 *   guess.
 * - **here** — *you* are in it. Accented, and pressing it brings the call back
 *   on screen. It does **not** hang up.
 *
 * That last point is load-bearing, and it got wrong twice. First the row always
 * called `joinRoomGroupCall` while a comment claimed it disconnected; a run log
 * showed two presses producing `Already in call m.call, ensuring native media
 * session is ready` and nothing else. So it was made a real toggle -- and the
 * toggle was the bug: `endCall ... <- ChannelDrawer.kt:250 <- ChannelList.kt:527`
 * in a later run log is this row disconnecting the user who pressed it to *look
 * at* the call. Leaving is the red button in the call controls, and nothing else.
 * `onClick` now carries `isActiveCall` only so the caller can skip the join and
 * restore the call surface.
 */
@Composable
private fun VoiceChannelRow(
    isLive: Boolean,
    isHere: Boolean,
    isConnecting: Boolean = false,
    participantCount: Int,
    onClick: () -> Unit
) {
    // Order matters: the connecting label is checked first so it cannot be
    // pre-empted by `isHere`, which is also true while connecting.
    val label = when {
        isConnecting -> "Voice · connecting…"
        isHere && participantCount > 1 -> "Voice · $participantCount here"
        isHere -> "Voice · you are here"
        isLive && participantCount > 0 -> "Voice · $participantCount joined"
        isLive -> "Voice · live"
        else -> "Voice"
    }
    // The label cannot carry the action -- "Voice" is right whether this joins a
    // call or brings a live one back on screen, and those two have very
    // different consequences to get wrong. So the accessible name says which.
    val action = when {
        isConnecting -> "Connecting to the call"
        isHere -> "Show the call"
        else -> "Join the call"
    }
    val tint = when {
        isConnecting -> conceptTextMuted()
        isHere -> conceptBrand
        isLive -> OrbitConceptStatus.online
        else -> SidebarConcept.interactiveMuted
    }

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsStateCompat(
        when {
            isHere -> conceptSelectedOverlay()
            hovered -> conceptHoverOverlay()
            else -> Color.Transparent
        }
    )

    Row(
        modifier = Modifier
            // Padding BEFORE fillMaxWidth: the indent then shrinks the width the
            // row fills. The other way round, fillMaxWidth claims the parent's
            // full width and the padding is drawn outside the row's own surface,
            // so the hover wash would start 8dp in from a full-width edge.
            .padding(start = 8.dp)
            .fillMaxWidth()
            .height(26.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = action,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = OrbitIcons.VoiceChannel,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (isHere) conceptInteractiveActive() else tint,
                fontSize = 13.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (isLive) {
            LiveDot(label = "Live", color = OrbitConceptStatus.online)
        }
    }
}

/**
 * A calm "someone is in this voice channel" dot. The previous version scaled a
 * whole "LIVE" pill between 0.97× and 1.04× forever, which drew the eye to
 * every voice room on the screen at all times.
 */
@Composable
private fun LiveDot(label: String, color: Color) {
    val reduce = LocalReduceMotion.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "liveDot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotPulse"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(14.dp)
            .semantics { contentDescription = label }
    ) {
        if (!reduce) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f * pulse))
            )
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * `.voice-users-list { padding-left: 28px; gap: 2px; margin-top: 2px;
 * margin-bottom: 4px }`.
 *
 * The 28dp indent is the concept's, not Orbit's old 20dp: it lines the roster's
 * avatars up under the room row's own 18dp glyph plus its 8dp gap.
 */
@Composable
private fun VoiceMemberStack(
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    showLocalFallback: Boolean,
    callManager: webrtc.CallManager?
) {
    val isAudioEnabled by callManager?.isLocalAudioEnabled?.collectAsState()
        ?: remember { mutableStateOf(true) }
    val speakers = callManager?.activeSpeakers?.collectAsState()?.value ?: emptySet()
    val remoteMuted = callManager?.remoteMicMutedIds?.collectAsState()?.value ?: emptySet()

    val showSelf = showLocalFallback && currentUser != null && members.none { it.id == currentUser.id }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp)
            .padding(top = 2.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        members.forEach { member ->
            val speaking = speakers.any { it.substringBeforeLast(':') == member.id }
            val selfMuted = member.id == currentUser?.id && !isAudioEnabled
            RosterRow(
                member = member,
                speaking = speaking,
                muted = selfMuted || ui.screen.call.identityIsMicMuted(member.id, remoteMuted)
            )
        }

        if (showSelf && currentUser != null) {
            RosterRow(
                member = currentUser,
                speaking = speakers.any { it.substringBeforeLast(':') == currentUser.id },
                muted = !isAudioEnabled
            )
        }
    }
}

/**
 * `.voice-user-item { padding: 4px 6px; border-radius: 4px; font-size: 13px;
 * color: var(--text-muted) }`, hover `--bg-modifier-hover` + `--text-normal`.
 *
 * `.voice-user-avatar { width: 20px; height: 20px; border-radius: 50% }` and
 * `.speaking { box-shadow: 0 0 0 2px var(--status-online) }` — a 2dp ring, so
 * it is drawn as a `border` rather than a shadow: a ring that casts its own
 * shadow is 2dp of blur nobody asked for.
 *
 * The name gets `weight(1f, fill = false)` rather than a bare weight. The mute
 * icon is laid out before it and is entitled to the row's intrinsic width; a bare
 * weight would then be left with whatever was left over, which for a row with a
 * long name and no mute icon is not the same number.
 */
@Composable
private fun RosterRow(
    member: OrbitUser,
    speaking: Boolean,
    muted: Boolean
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fg by animateColorAsStateCompat(
        when {
            hovered -> conceptTextNormal()
            speaking -> conceptInteractiveHover()
            else -> conceptTextMuted()
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitRadius.xsShape)
            .background(if (hovered) conceptHoverOverlay() else Color.Transparent)
            .hoverable(interaction)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp)) {
            if (member.avatarUrl != null) {
                OrbitAvatarImage(
                    url = member.avatarUrl,
                    contentDescription = member.displayName,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    // size() LAST is load-bearing: this sits in a Row whose Text
                    // sibling carries weight, and a preceding fillMaxWidth() would
                    // claim the row's full width and starve the name to a
                    // one-character column. Reversing the two reintroduces that.
                    modifier = Modifier.size(20.dp).clip(CircleShape)
                )
            } else {
                OrbitDefaultAvatar(
                    label = member.displayName,
                    seed = member.id,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (speaking) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .border(2.dp, OrbitConceptStatus.online, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                color = fg,
                fontSize = 13.sp
            ),
            maxLines = 1,
            // Ellipsis, never a hard cut. A name sliced mid-glyph at the panel edge
            // reads as a rendering fault; "Ahmad Has..." reads as a name.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (muted) {
            Icon(
                imageVector = OrbitIcons.MicOff,
                contentDescription = "Muted",
                tint = OrbitConceptStatus.dnd,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(13.dp)
            )
        }
    }
}

/** Matches the "- voice" / "_voice_channel" suffixes servers append to voice rooms. */
private val VoiceChannelSuffixRegex = Regex("(?i)([-_\\s]+voice(?:[-_\\s]+channel)?)$")

internal fun channelDisplayName(channel: OrbitChannel): String {
    if (channel.type != ChannelType.VOICE) return channel.name
    val withoutCall = channel.name.removeSuffix(" (Call)")
    val cleaned = withoutCall.replace(VoiceChannelSuffixRegex, "").trim('-', '_', ' ')
    return cleaned.ifBlank { withoutCall }
}

@Composable
private fun EditChannelDialog(
    channel: OrbitChannel,
    spaceId: String?,
    repository: repository.OrbitRepository?,
    onDismiss: () -> Unit,
    onOpenDelete: () -> Unit
) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    var channelName by remember { mutableStateOf(channel.name) }
    var channelType by remember { mutableStateOf(channel.type) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canSave = !isSaving && channelName.trim().isNotBlank()

    OrbitDialog(
        title = "Edit Channel",
        subtitle = "Settings for #${channel.name}",
        icon = with(TablerIcons) { Settings },
        confirmLabel = if (isSaving) "Saving…" else "Save Changes",
        dismissLabel = "Cancel",
        confirmEnabled = canSave,
        confirmTone = OrbitButtonTone.Primary,
        onConfirm = {
            if (!canSave) return@OrbitDialog
            val repo = repository as? repository.MatrixOrbitRepository
            isSaving = true
            errorMessage = null
            scope.launch {
                val result = repo?.updateChannel(
                    spaceId = spaceId ?: "",
                    channelId = channel.id,
                    name = channelName.trim(),
                    type = channelType
                ) ?: Result.failure(IllegalStateException("Repository unavailable"))
                isSaving = false
                if (result.isSuccess) {
                    OrbitNotices.info("Channel updated")
                    onDismiss()
                } else {
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to update channel"
                }
            }
        },
        onDismissRequest = onDismiss,
        width = 440.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.sm),
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.md)
        ) {
            Text(
                text = "CHANNEL TYPE",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Subtle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OrbitRadius.smShape)
                    .background(t.CardBg)
                    .border(1.dp, t.Separator, OrbitRadius.smShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ChannelTypeOption(
                    title = "Text",
                    description = "Post messages, images, and files",
                    icon = OrbitIcons.TextChannel,
                    selected = channelType == ChannelType.TEXT,
                    onClick = { channelType = ChannelType.TEXT },
                    modifier = Modifier.weight(1f)
                )
                ChannelTypeOption(
                    title = "Voice",
                    description = "Hang out with voice and video",
                    icon = OrbitIcons.VoiceChannel,
                    selected = channelType == ChannelType.VOICE,
                    onClick = { channelType = ChannelType.VOICE },
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                text = "CHANNEL NAME",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Subtle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
            )

            OrbitTextField(
                value = channelName,
                onValueChange = {
                    channelName = it.replace(" ", "-").lowercase()
                    errorMessage = null
                },
                placeholder = if (channelType == ChannelType.VOICE) "general-voice" else "general",
                leadingIcon = if (channelType == ChannelType.VOICE) OrbitIcons.VoiceChannel else OrbitIcons.TextChannel,
                modifier = Modifier.fillMaxWidth()
            )

            if (errorMessage != null) {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Danger,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OrbitRadius.smShape)
                    .background(t.DangerGhost)
                    .border(1.dp, t.Danger.copy(alpha = 0.25f), OrbitRadius.smShape)
                    .padding(OrbitSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Delete Channel",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = t.Danger,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Text(
                        text = "Permanently remove this channel from the server.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = t.Text_Muted,
                            fontSize = 11.sp
                        )
                    )
                }
                OrbitButton(
                    text = "Delete",
                    icon = with(TablerIcons) { Trash },
                    tone = OrbitButtonTone.Danger,
                    onClick = onOpenDelete
                )
            }
        }
    }
}

@Composable
private fun ChannelTypeOption(
    title: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            selected -> t.AccentGhostStrong
            hovered -> t.Hover
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "typeOptBg"
    )

    Column(
        modifier = modifier
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction.hoverSource)
            .orbitClickable(interaction, onClick = onClick)
            .padding(OrbitSpace.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) t.Accent else t.Text_Secondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (selected) t.Text_White else t.Text_Secondary,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 13.sp
                )
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall.copy(
                color = t.Text_Muted,
                fontSize = 11.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DeleteChannelDialog(
    channel: OrbitChannel,
    spaceId: String?,
    repository: repository.OrbitRepository?,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    val t = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    var isDeleting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    OrbitDialog(
        title = "Delete Channel",
        subtitle = "Are you sure you want to delete #${channel.name}? This action cannot be undone.",
        icon = with(TablerIcons) { Trash },
        confirmLabel = if (isDeleting) "Deleting…" else "Delete Channel",
        dismissLabel = "Cancel",
        confirmTone = OrbitButtonTone.Danger,
        confirmEnabled = !isDeleting,
        onConfirm = {
            if (isDeleting) return@OrbitDialog
            val repo = repository as? repository.MatrixOrbitRepository
            isDeleting = true
            errorMessage = null
            scope.launch {
                val result = repo?.deleteChannel(
                    spaceId = spaceId ?: "",
                    channelId = channel.id
                ) ?: Result.failure(IllegalStateException("Repository unavailable"))
                isDeleting = false
                if (result.isSuccess) {
                    OrbitNotices.info("Deleted #${channel.name}")
                    onDeleted()
                } else {
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to delete channel"
                }
            }
        },
        onDismissRequest = onDismiss,
        width = 400.dp
    ) {
        if (errorMessage != null) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.xs)) {
                Text(
                    text = errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Danger,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}