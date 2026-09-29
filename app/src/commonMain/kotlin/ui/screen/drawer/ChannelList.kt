package ui.screen.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import livekit.CallRosterReconciliation
import livekit.LiveKitRemoteVideoFrameStore
import repository.CredentialManager
import kotlinx.coroutines.launch
import ui.assets.OrbitDefaultAvatar
import ui.components.OrbitCountBadge
import ui.components.OrbitIconButton
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.ChannelCategory
import ui.model.ChannelGrouping
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * The channel list.
 *
 * Reworked around `OrbitListRow` so it shares geometry, hover behaviour and
 * the selection treatment with the DM list, the member list and the settings
 * sidebar.
 *
 * The grouping is [ChannelGrouping]'s job, not this file's: it used to take a
 * `categories` list, ignore it entirely, and render one flat "Rooms" header above
 * every room -- so a space somebody had carefully arranged into groups read as a
 * single undifferentiated list. The groups are now built from the channels' own
 * `categoryId`/`categoryName`, so the header you see is the one the server
 * actually assigned, and a server with no categories still gets the Text
 * Channels / Voice Channels split rather than nothing.
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
    modifier: Modifier = Modifier
) {
    // Which groups the user has collapsed. Keyed by group key, NOT by index or
    // label, so collapsing "Lounge" does not collapse whatever later takes its
    // slot in the list.
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val t = rememberCosmicTokens()

    // `spaceName` is used for the single heading when the server reports no
    // categories, so the group is titled with the name the user gave the space
    // rather than an invented type.
    val groups = remember(channels, spaceName) { ChannelGrouping.group(channels, spaceName) }

    Box(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                top = OrbitSpace.sm,
                bottom = OrbitSpace.lg,
                start = OrbitSpace.sm,
                end = OrbitSpace.sm
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (groups.isEmpty()) {
                item(key = "empty_rooms", contentType = "empty") {
                    Text(
                        text = "No rooms here yet",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = t.Text_Subtle,
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(start = 26.dp, top = OrbitSpace.sm, bottom = OrbitSpace.sm)
                    )
                }
            }

            groups.forEach { group ->
                val isCollapsed = group.key in collapsed.value
                item(key = "hdr_${group.key}", contentType = "header") {
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
                }
                if (isCollapsed) return@forEach

                items(
                    group.channels,
                    // Composite key, not bare id. A duplicate key throws out of the
                    // content lambda and kills the app, and the channel list really
                    // can contain two entries with the same id (a synthetic voice
                    // channel sharing a host text room's id). The repository now
                    // gives the synthetic one a distinct id, but a list that can
                    // crash the process on a data quirk should not depend on one
                    // caller being careful.
                    key = { "${it.id}#${it.type}#${it.categoryId}" },
                    contentType = { "channel" }
                ) { ch ->
                    val chUnread = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0)
                    ChannelItem(
                        channel = ch,
                        isSelected = ch.id == selectedChannelId,
                        currentUser = currentUser,
                        repository = repository,
                        unreadCount = chUnread,
                        onClick = { onChannelClick(ch.id) },
                        onJoinCall = onJoinCall
                    )
                }
            }
        }
        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** Collapsible section header. The add affordance only appears on hover. */
@Composable
internal fun ChannelSectionHeader(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = OrbitMotion.Snappy,
        label = "sectionChevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(start = OrbitSpace.xs, end = OrbitSpace.xxs)
            .hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(OrbitRadius.xsShape)
                .hoverable(interaction)
                .clickableNoRipple(onToggle)
                .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = if (hovered) t.Text_Secondary else t.Text_Subtle,
                modifier = Modifier.size(14.dp).rotate(rotation)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (hovered) t.Text_Secondary else t.Text_Muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onAddClick != null) {
            // Revealed on header hover, matching the documented behaviour. A
            // permanently-visible "+" on every category reads as a per-category
            // control and makes the drawer busier than it needs to be.
            AnimatedVisibility(
                visible = hovered,
                enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.7f),
                exit = fadeOut(tween(90))
            ) {
                OrbitIconButton(
                    icon = OrbitIcons.Add,
                    contentDescription = "Create a room in $name",
                    onClick = onAddClick,
                    size = 24.dp,
                    iconSize = 14.dp,
                    shape = CircleShape,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )

@Composable
internal fun ChannelItem(
    channel: OrbitChannel,
    isSelected: Boolean,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    unreadCount: Int = 0,
    onClick: () -> Unit,
    onJoinCall: ((roomId: String, isActiveCall: Boolean) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val callManager = webrtc.LocalCallManager.current
    // Hoisted out of the `?:` so the number of remembered slots does not change
    // when callManager flips between null and non-null.
    val callStateFlow = remember(callManager) {
        callManager?.callState ?: flowOf(webrtc.CallState.IDLE)
    }
    val callState by callStateFlow.collectAsState(initial = webrtc.CallState.IDLE)
    // The Matrix room id, NOT channel.id. A synthetic voice channel has
    // id = "voice:<roomId>" (it needs a distinct id so the LazyColumn does not
    // collide with the text room it borrows) while its matrixRoomId is the real
    // room -- and CallManager.activeRoomId is always a MATRIX room id, set from
    // the room the call was joined in. Comparing it against channel.id alone
    // therefore never matched for a synthetic voice channel, so `isActiveCallInChannel`
    // was false exactly where the local user IS in the call: no LiveDot, no
    // "You" row, and (before the reconciliation below) no LiveKit truth applied.
    val roomId = channel.matrixRoomId.ifBlank { channel.id }
    // COLLECTED, not read as `.value`.
    //
    // This was `callManager?.activeRoomIdFlow?.value` -- a plain property read on
    // a StateFlow, outside any `collectAsState`. It returns whatever the flow
    // held at the moment the row happened to compose and nothing ever
    // recomposes it, so a row composed before the call started kept
    // `activeRoomId == null` for the whole call. That made
    // `isActiveCallInChannel` false in exactly the situation it exists to detect,
    // which skipped the LiveKit intersection below and rendered the raw
    // six-record Matrix membership list while the call surface beside it showed
    // two people. Confirmed from a screenshot: the drawer read "Voice · 6
    // joined" under a call surface with two tiles, and the side panel's
    // correctly-collected `displayCallMembers` read "2 in call".
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
    // row is never correct, so the condition is now the channel TYPE rather than
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
    // three seconds for the rest of the session. Clicking a room is not a
    // reason to start polling it.
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
    // LazyColumn item, so scrolling the drawer would have cancelled the poller
    // out from under the LIVE call and frozen its participant list. Hangup
    // already stops it, via CallManager.onGroupCallObserversStop.

    // WHO IS IN THE CALL, for DISPLAY. `members` above is the raw m.call.member
    // MEMBERSHIP record: multi-hour expiry, one entry per device per call, never
    // withdrawn when the person actually leaves. Rendering it verbatim is what
    // put 4-8 names under every voice channel for a call with 2 people.
    //
    // While OUR call is live in this room the LiveKit room is authoritative and
    // is already reconciled for the E2EE key exchange (nudgeMissingPeers); apply
    // the same intersection here. The `livekitIds.isEmpty() ||` guard mirrors
    // that function exactly, so a native-wiring regression degrades to the old
    // (imperfect) Matrix roster rather than blanking the list.
    //
    // Rooms we are NOT in keep the Matrix roster verbatim: there is no LiveKit
    // room for them to be authoritative about, and the remote-participant set is
    // global to the single active session, so intersecting would be meaningless.
    val liveKitParticipantsFlow = remember(callManager) {
        LiveKitRemoteVideoFrameStore.remoteParticipants
    }
    val liveKitParticipantIds by liveKitParticipantsFlow.collectAsState(emptySet())
    // WHO IS IN THE CALL, and this is the only authority the drawer has for it.
    //
    // `displayCallMembers` is `CallManager`'s own reconciled roster -- the same
    // value the call surface tiles and the side-panel header count read, so the
    // drawer cannot disagree with either. It is emptied whenever no call is live
    // and is gated on CONNECTED rather than merely "not idle", so a call that is
    // still negotiating shows nobody instead of everybody who ever joined.
    val displayCallMembersFlow = remember(callManager) {
        callManager?.displayCallMembers ?: flowOf(emptyList())
    }
    val displayCallMembers by displayCallMembersFlow.collectAsState(emptyList())
    val visibleMembers = when {
        // Connected in this room: the LiveKit room is authoritative, and it is
        // already intersected with the Matrix membership records upstream.
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

    Column {
        OrbitListRow(
            title = displayName,
            onClick = onClick,
            selected = isSelected,
            height = 32.dp,
            horizontalPadding = 8.dp,
            accentColor = if (isLive) t.Live else t.Accent,
            // A live room carries its own selection bar, the same treatment a
            // selected channel gets, because in Discord "you are in this call" and
            // "you are reading this channel" are the same visual idea and share
            // the same bar. Without it the only live signal was a 6dp dot
            // floating at the end of the row, which read as decoration rather than
            // as state.
            showSelectionBar = isSelected || isLive,
            // Every room is the same kind of thing — a room you can read and, when
            // a call is on, talk in. So every row gets the room glyph, and voice
            // is signalled by the live dot and the roster underneath instead of by
            // a different icon. Two icons implied two kinds of room, which is the
            // confusion this whole change exists to remove.
            leading = OrbitRowLeading(icon = OrbitIcons.TextChannel),
            trailing = {
                if (isLive) {
                    LiveDot(label = "Live", color = t.Live)
                }
                // Unreads show on any room, live or not. It used to be suppressed
                // on voice channels, which was another small consequence of
                // treating rooms as two kinds: a room with an active call could
                // never show you that you had unread messages.
                if (unreadCount > 0 && !isSelected) {
                    OrbitCountBadge(count = unreadCount, color = t.Accent)
                }
            }
        )

        // The Voice row.
        //
        // A separate row under every room, and pressing it is the ONLY way to
        // start or join a call. The in-room "join call" banner that used to sit
        // above the timeline is gone: two affordances for one action is how you
        // end up with a call running that the UI is still inviting you to start.
        //
        // It is deliberately NOT a room. An earlier version of this file
        // synthesised a `ChannelType.VOICE` entry pointing at the first text
        // room, which is what produced a space with three rooms called "General"
        // where clicking one opened another. This row has no `OrbitChannel`, no
        // id in the `channels` list, and no category: it is a **call action on
        // the room above it**. Clicking the room row still opens its chat, so the
        // two are genuinely different actions on the same room.
        if (onJoinCall != null) {
            VoiceChannelRow(
                // A dot for a call you are connected to, or one running elsewhere.
                // While you are still CONNECTING this is deliberately false: the
                // row says "connecting" instead, because a pulsing live dot next
                // to a roster that is not there yet is a lie in two places at
                // once.
                isLive = isLive && !isConnectingInChannel,
                isHere = isActiveCallInChannel,
                isConnecting = isConnectingInChannel,
                // Only ever the CONNECTED roster. Before that this is zero, which
                // is the point: the count must not be a number of stale
                // membership records.
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
    }
}

/**
 * The `Voice` row under a room in the drawer.
 *
 * Indented 20dp to line its glyph up under the room row's own glyph, and one
 * step quieter: the room is the thing you navigate to, the voice row is the
 * thing you press once.
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
    val t = rememberCosmicTokens()
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
        isConnecting -> t.Text_Muted
        isHere -> t.Accent
        isLive -> t.Live
        else -> t.Text_Subtle
    }
    Column(modifier = Modifier.padding(start = 8.dp)) {
        OrbitListRow(
            title = label,
            onClick = onClick,
            onClickLabel = action,
            selected = isHere,
            height = 26.dp,
            horizontalPadding = 8.dp,
            accentColor = tint,
            // A selection bar on BOTH the room and its voice row when you are in
            // the call would read as two channels being selected. The room row
            // already draws one; this one relies on its colour and label.
            showSelectionBar = false,
            leading = OrbitRowLeading(icon = OrbitIcons.VoiceChannel),
            trailing = { if (isLive) LiveDot(label = "Live", color = t.Live) }
        )
    }
}

/**
 * A calm "someone is in this voice channel" dot. The previous version scaled a
 * whole "LIVE" pill between 0.97× and 1.04× forever, which drew the eye to
 * every voice room on the screen at all times.
 */
@Composable
private fun LiveDot(label: String, color: Color) {
    val t = rememberCosmicTokens()
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
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
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

/** Indented roster shown under a voice channel that has participants. */
@Composable
private fun VoiceMemberStack(
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    showLocalFallback: Boolean,
    callManager: webrtc.CallManager?
) {
    val t = rememberCosmicTokens()
    val isAudioEnabled by callManager?.isLocalAudioEnabled?.collectAsState() ?: remember { mutableStateOf(true) }
    val speakers = callManager?.activeSpeakers?.collectAsState()?.value ?: emptySet()
    val remoteMuted = callManager?.remoteMicMutedIds?.collectAsState()?.value ?: emptySet()

    val showSelf = showLocalFallback && currentUser != null && members.none { it.id == currentUser.id }

    // Indent 20dp, not 30dp.
    //
    // The old indent plus 6dp row padding plus an 18dp avatar plus an 8dp spacer
    // consumed 62dp of a 248dp drawer before a single character of a name was
    // drawn. At 20dp avatar and 12dp gap the budget drops to 48dp, which is the
    // difference between "Ahmad Hassan" and "Ahma" on a real display name.
    Column(modifier = Modifier.padding(start = 20.dp, bottom = 2.dp)) {
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
 * One line of the nested voice roster: avatar, name, mute icon.
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
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(OrbitRadius.xsShape)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp)) {
            if (member.avatarUrl != null) {
                coil3.compose.AsyncImage(
                    model = member.avatarUrl,
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
                        .border(1.5.dp, t.Success, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(OrbitSpace.xs))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                color = if (speaking) t.Text_White else t.Text_Muted,
                fontSize = 12.5.sp
            ),
            maxLines = 1,
            // Ellipsis, never a hard cut. A name sliced mid-glyph at the panel edge
            // reads as a rendering fault; "Ahmad Has..." reads as a name.
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (muted) {
            androidx.compose.material3.Icon(
                imageVector = OrbitIcons.MicOff,
                contentDescription = "Muted",
                tint = t.Danger,
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
