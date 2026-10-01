package ui.screen

import ui.screen.chat.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import ui.assets.OrbitDefaultAvatar
import ui.components.OrbitIconButton
import ui.components.OrbitScrollbar
import ui.components.OrbitSearchField
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitMetrics
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import ui.screen.call.LiveKitGroupCallSurface
import ui.screen.call.VoiceChannelScreen
import ui.screen.chat.DirectProfilePanel
import ui.screen.chat.MessageInputBar
import ui.screen.chat.ReplyPreview
import ui.screen.chat.formatTimestampCompact

/**
 * The room surface.
 *
 * Two changes worth calling out. The right-hand panel used to be a bare `when`
 * that swapped between profile, pins and members with no transition at all, so
 * clicking "members" made the entire pane blink; it is now an `AnimatedContent`
 * that slides horizontally in the direction of travel. And the room search bar
 * was a `BasicTextField` in a hand-drawn bordered box with its own focus
 * handling; it is now the shared field, so it focuses, clears and themes like
 * every other input in the app.
 */
@Composable
fun ChatScreen(
    repository: repository.OrbitRepository? = null,
    channel: OrbitChannel,
    messages: List<OrbitMessage>,
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    directProfile: OrbitUser? = null,
    typingUsers: List<String> = emptyList(),
    isLoadingMessages: Boolean = false,
    activeCall: repository.CallEvent? = null,
    activeGroupCallUrl: String? = null,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {},
    onSeen: (String) -> Unit = {},
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onSendReply: ((String, String) -> Unit)? = null,
    /**
     * Called with the original text when a send fails, so the composer can put
     * it back.
     *
     * Needed because [MessageInputBar] clears itself synchronously the moment
     * `onSend` fires, and `onSend` launches a coroutine — so the field cannot
     * wait for an outcome. Without a way to push the text back, a send that
     * fails to reach the homeserver destroys the message outright.
     */
    onSendFailed: ((String) -> Unit)? = null,
    memberRoles: Map<String, repository.MemberRoleInfo> = emptyMap(),
    onKickUser: ((String) -> Unit)? = null,
    onBanUser: ((String) -> Unit)? = null,
    onSetPowerLevel: ((String, Long) -> Unit)? = null,
    notifMode: String = "ALL",
    onCycleNotificationMode: (() -> Unit)? = null,
    firstUnreadId: String? = null,
    showMemberList: Boolean = true,
    onBack: (() -> Unit)? = null,
    /**
     * Request one more page of older history. Supplied by the shell, which owns
     * the message list and the per-room paging state; null disables scrollback.
     */
    onLoadOlder: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val windowSize = LocalWindowSizeClass.current
    val callManager = webrtc.LocalCallManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reduceMotion = ui.theme.LocalReduceMotion.current

    // Each of these used to be `callManager?.x?.collectAsState(...) ?:
    // remember { mutableStateOf(...) }`, which changes the number of remembered
    // slots whenever callManager flips between null and non-null. Hoisting the
    // flow into a single remember keeps the slot count fixed.
    val callStateFlow = remember(callManager) {
        callManager?.callState ?: flowOf(webrtc.CallState.IDLE)
    }
    val isGroupCallFlow = remember(callManager) {
        callManager?.isGroupCall ?: flowOf(false)
    }
    val activeRoomIdFlow = remember(callManager) {
        callManager?.activeRoomIdFlow ?: flowOf<String?>(null)
    }
    val callState by callStateFlow.collectAsState(initial = webrtc.CallState.IDLE)
    val isGroupCall by isGroupCallFlow.collectAsState(initial = false)
    val activeRoomId by activeRoomIdFlow.collectAsState(initial = null)
    // Reconciled (LiveKit ∩ Matrix) participant roster for the panel. Collected
    // here — rather than inside MemberListPanel — so the null-vs-empty decision
    // ("is a call live?" vs "is the call empty?") stays at the one place that
    // already knows both.
    val displayCallMembersFlow = remember(callManager) {
        callManager?.displayCallMembers ?: flowOf(emptyList())
    }
    val liveCallRoster by displayCallMembersFlow.collectAsState(emptyList())
    // Whether the user has pushed the call aside to read the room. Owned by
    // CallManager so the drawer's room row and its Voice row can both reach it
    // without this screen having to expose an event up to the layout.
    val callSurfaceCollapsedFlow = remember(callManager) {
        callManager?.callSurfaceCollapsed ?: flowOf(false)
    }
    val callSurfaceCollapsed by callSurfaceCollapsedFlow.collectAsState(initial = false)
    val isCallActiveInRoom = (activeRoomId == channel.id || activeRoomId == channel.matrixRoomId) &&
        callState != webrtc.CallState.IDLE &&
        callState != webrtc.CallState.ENDED
    // True only when THIS room is the one in the call. A call elsewhere must not
    // displace the room you are reading.
    //
    // Hoisted to function scope because it drives BOTH columns now: the main
    // column decides whether the call surface renders, and the side-panel `when`
    // needs the same predicate to decide whether the panel holds the timeline.
    // It was previously computed *inside* the main column, where the side panel
    // could not see it.
    val callHere = isCallActiveInRoom && isGroupCall && callManager != null
    // Whether the call gets the main column to itself and the timeline moves
    // into the side panel, or the two stack in one column.
    //
    // The panel is a fixed 380dp, so below ~600dp the call would be left with
    // less than the drawer and neither would be usable; on a phone-width window
    // they stack instead. The Members and Pins panels gate on `isExpanded` for
    // the same reason -- but a call cannot, because unlike them you cannot do
    // without it while it is live. Hence `isMediumOrLarger`, not the stricter
    // test.
    //
    // `callSurfaceCollapsed` is the user's choice to read the room instead. The
    // call stays live either way; it just moves to the status pill, which is
    // where it was before this layout existed.
    val callOwnsMainColumn =
        callHere && windowSize.isMediumOrLarger && !callSurfaceCollapsed
    // A room opens as a ROOM.
    //
    // This used to be `channel.type == ChannelType.VOICE && windowSize.isExpanded`,
    // so opening a room that happened to be typed VOICE skipped the timeline
    // entirely and rendered `VoiceChannelScreen` instead. Combined with
    // join-on-open, clicking a room in the drawer dropped you straight into a
    // call: two rooms called "General" and "general", both typed VOICE, both
    // unreachable as chat. Reported with screenshots, twice.
    //
    // The type is not a reason to hide a room's messages. A Matrix room has a
    // timeline whether or not it also has call state, and Element shows you the
    // timeline first and offers the call. So: the call surface takes over only
    // when you are actually in a call, and joining is always a deliberate act.
    val showVoiceChannelUi = false

    // `pendingGroupJoinCallId` used to live here, guarding the in-room "join call"
    // banner against a double click. The banner is gone -- the drawer's `Voice` row
    // is the only entry point to a call now -- so the guard went with it. Leaving it
    // behind would have been a `var` written in one place and cleared in another
    // with no reader in between.
    var searchActive by remember(channel.id) { mutableStateOf(false) }
    var searchQuery by remember(channel.id) { mutableStateOf("") }
    var pinsActive by remember(channel.id) { mutableStateOf(false) }
    var replyingTo by remember(channel.id) { mutableStateOf<OrbitMessage?>(null) }
    // Set by the screen above when a send fails, so the composer can hand the
    // message back instead of the user losing it. Keyed on channel so a draft
    // from #general cannot appear in #random.
    var failedDraft by remember(channel.id) { mutableStateOf<String?>(null) }
    // The header's Members button drives this; the panel itself is still gated
    // on the user setting and the window being wide enough to show it.
    var memberPanelOpen by remember(channel.id) { mutableStateOf(true) }

    /**
     * Whether the call-chat side panel is collapsed to its rail.
     *
     * The panel is mandatory during a call -- the conversation has to stay
     * reachable -- but 380dp is a lot of window to give up to a transcript while
     * the call itself is the thing being looked at, and there was no way to take
     * it back. Collapsing leaves a 48dp rail carrying the channel glyph, the
     * unread count and a button to bring it back.
     *
     * Keyed on the channel so a different room starts open, which matches
     * `memberPanelOpen` rather than surprising you in a new conversation.
     */
    var callChatCollapsed by remember(channel.id) { mutableStateOf(false) }
    val memberPanelAvailable = ui.theme.LocalShowMemberList.current && windowSize.isExpanded
    // Opened by tapping a row in the member list. Kept separate from
    // `directProfile` (which only exists for DMs) so a group-room member can be
    // inspected without pretending they are the room.
    var memberProfile by remember(channel.id) { mutableStateOf<OrbitUser?>(null) }
    val shownProfile = memberProfile ?: directProfile
    // Null when the layout or the setting means there is no panel to show, so the
    // header hides the Members button instead of toggling invisible state.
    val memberPanelToggle: (() -> Unit)? = if (memberPanelAvailable) {
        {
            // Clear a selected member when the button is pressed.
            //
            // This button used to toggle `memberPanelOpen` and nothing else, but
            // a non-null `memberProfile` outranks it in the panel switch below —
            // so pressing it while a profile was open appeared to do nothing at
            // all. That is one of the two ways the panel became a one-way door;
            // it is also the affordance people reach for first, so it has to
            // work.
            if (memberProfile != null) memberProfile = null
            memberPanelOpen = !memberPanelOpen
        }
    } else {
        null
    }

    val displayMessages = if (searchActive && searchQuery.isNotBlank()) {
        val q = searchQuery.trim()
        messages.filter { it.body.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }
    } else {
        messages
    }

    /**
     * "3 results" / "1 result".
     *
     * Named because it is now written in two places — beside the field on a narrow
     * window, and under the header on a wide one — and a plural rule duplicated
     * into two call sites is a bug waiting for the day the two disagree.
     */
    fun searchResultLabel(count: Int) = "$count result${if (count == 1) "" else "s"}"

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(t.Bg)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(ui.theme.LocalRoomMembers provides members) {
            Column(modifier = Modifier.weight(1f)) {
                ChatHeader(
                    channel = channel,
                    directProfile = directProfile,
                    onBack = onBack,
                    onVideoCall = { callManager?.startCall(channel.id, true) },
                    onVoiceCall = { callManager?.startCall(channel.id, false) },
                    searchActive = searchActive,
                    onToggleSearch = { searchActive = !searchActive },
                    // On a window wide enough for it, the search field is IN the
                    // header — which is where `.chat-search-bar` lives in the
                    // concept. On a narrow one there is no room for a 144dp field
                    // beside six icon buttons, so the toggle stays a button and
                    // the row below takes the field instead.
                    searchQuery = if (searchActive && windowSize.isMediumOrLarger) searchQuery else null,
                    onSearchQueryChange = { searchQuery = it },
                    pinsActive = pinsActive,
                    pinCount = messages.count { it.isPinned },
                    onTogglePins = { pinsActive = !pinsActive },
                    memberListActive = memberPanelOpen,
                    onToggleMemberList = memberPanelToggle,
                    notifMode = notifMode,
                    onCycleNotificationMode = onCycleNotificationMode
                )

                if (searchActive && windowSize.isMediumOrLarger && searchQuery.isNotBlank()) {
                    // The field is in the header, so there is no room beside it for
                    // a count; it rides underneath on its own line. `.chat-search-bar`
                    // has no result readout of its own, so this is Orbit's addition
                    // rather than the concept's.
                    Text(
                        text = searchResultLabel(displayMessages.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = t.Text_Muted,
                            fontSize = 11.sp
                        ),
                        maxLines = 1,
                        modifier = Modifier.padding(start = OrbitSpace.lg, top = OrbitSpace.sm)
                    )
                }

                if (searchActive && !windowSize.isMediumOrLarger) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OrbitSearchField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = "Search in ${channel.name}",
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(Modifier.width(OrbitSpace.sm))
                            Text(
                                text = searchResultLabel(displayMessages.size),
                                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 11.sp),
                                maxLines = 1
                            )
                        }
                    }
                }

                if (showVoiceChannelUi) {
                    // `repository` is nullable and may be a non-Matrix
                    // implementation; the previous `as MatrixOrbitRepository`
                    // plus `callManager!!` crashed the whole room on open.
                    val voiceCallManager = callManager
                    val matrixRepository = repository as? repository.MatrixOrbitRepository
                    if (voiceCallManager != null && matrixRepository != null) {
                        VoiceChannelScreen(
                            callManager = voiceCallManager,
                            matrixRepository = matrixRepository,
                            channel = channel,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    } else {
                        VoiceChannelUnavailable()
                    }
                } else {
                    // Chat in the main column, and a live call takes it over.
                    //
                    // Three layouts have been tried here across two days — call
                    // stacked above chat at 55/45, then call in the side panel with
                    // chat in the main column. This is the third and it is the
                    // one that holds: a call is the thing you look at while you are
                    // in it, and the timeline is the thing you glance at, so the
                    // call gets the wide centre and the timeline keeps a readable
                    // column on the right.
                    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // Only when a call is live IN THIS ROOM. A call elsewhere
                        // must not displace the room you are reading.
                        if (callHere) {
                            LiveKitGroupCallSurface(
                                callManager = callManager!!,
                                // Full height when the timeline is not underneath.
                                // When the window is too narrow to give the chat its
                                // own panel, the two stack and the call takes 55% --
                                // see `callOwnsMainColumn`.
                                modifier = Modifier
                                    .weight(if (callOwnsMainColumn) 1f else 0.55f)
                                    .fillMaxWidth(),
                                remoteFallbackName = channel.name
                            )
                        }

                        // The timeline. Suppressed when the call has the main column
                        // to itself, because then it is rendered in the side panel
                        // instead -- not skipped, moved. Exactly one copy of the
                        // timeline is composed at any moment.
                        if (!callOwnsMainColumn) {
                            ChatTimeline(
                                modifier = Modifier.weight(1f),
                                channel = channel,
                                displayMessages = displayMessages,
                                messages = messages,
                                members = members,
                                currentUser = currentUser,
                                typingUsers = typingUsers,
                                isLoadingMessages = isLoadingMessages,
                                firstUnreadId = firstUnreadId,
                                directProfile = directProfile,
                                replyingTo = replyingTo,
                                failedDraft = failedDraft,
                                onSeen = onSeen,
                                onReact = onReact,
                                onEditMessage = onEditMessage,
                                onDeleteMessage = onDeleteMessage,
                                onTogglePin = onTogglePin,
                                onSend = onSend,
                                onSendReply = onSendReply,
                                onSendAttachment = onSendAttachment,
                                onTyping = onTyping,
                                onReply = { id -> replyingTo = messages.firstOrNull { it.id == id } },
                                onCancelReply = { replyingTo = null },
                                onDraftRestored = { failedDraft = null },
                                onMentionUser = { userId ->
                                    // Resolve the mention to a real member where we can;
                                    // otherwise there is no profile to open.
                                    members.firstOrNull {
                                        it.id == userId ||
                                            it.id.endsWith(userId) ||
                                            userId.endsWith(it.id)
                                    }?.let { memberProfile = it }
                                },
                                onLoadOlder = onLoadOlder
                            )
                        }
                    }
                }
            }

            // ── Right-hand panel ───────────────────────────────────
            if (!showVoiceChannelUi) {
                AnimatedContent(
                    targetState = when {
                        // A live call displaces the side panel to hold the chat.
                        //
                        // This is the one case where the panel slot is not
                        // optional. Members, pins and a profile are all things you
                        // open deliberately and can close again; the timeline
                        // during a call is not a thing you open, and putting it
                        // behind a Members toggle means the conversation is
                        // unreachable for exactly as long as the call lasts.
                        callOwnsMainColumn -> if (callChatCollapsed) 4 else 3
                        shownProfile != null && windowSize.isExpanded -> 0
                        pinsActive -> 1
                        memberPanelAvailable && memberPanelOpen -> 2
                        else -> -1
                    },
                    transitionSpec = {
                        val forward = targetState > initialState
                        val ms = if (reduceMotion) 0 else 220
                        val spec = tween<Float>(ms)
                        val specOffset = tween<androidx.compose.ui.unit.IntOffset>(ms)
                        if (initialState == -1) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { it / 6 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { -it / 6 }
                        } else if (targetState == -1) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { -it / 6 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { it / 6 }
                        } else if (forward) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { it / 5 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { -it / 5 }
                        } else {
                            fadeIn(spec) + slideInHorizontally(specOffset) { -it / 5 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { it / 5 }
                        }
                    },
                    label = "sidePanel"
                ) { panel ->
                    when (panel) {
                        // Call buttons need a ROOM id, not a user id, so they are
                        // only offered when this profile really is a DM.
                        0 -> shownProfile?.let {
                            DirectProfilePanel(
                                user = it,
                                callRoomId = if (directProfile != null) channel.id else null,
                                // A DM profile is the room itself, so there is
                                // nothing to go "back" to — only a member-list
                                // profile can be dismissed.
                                onClose = if (directProfile != null) null else { { memberProfile = null } }
                            )
                        }
                        1 -> PinnedMessagesPanel(
                            pinned = messages.filter { it.isPinned },
                            canUnpin = onTogglePin != null,
                            onUnpin = { onTogglePin?.invoke(it) },
                            // The concept's drawer has a close X in its header;
                            // without it the panel is only reachable by re-pressing
                            // the header's pin button.
                            onClose = { pinsActive = false }
                        )
                        2 -> MemberListPanel(
                            members = members,
                            observePresence = repository?.let { it::observePresence },
                            memberRoles = memberRoles,
                            ownUserId = currentUser?.id,
                            onKickUser = onKickUser,
                            onBanUser = onBanUser,
                            onSetPowerLevel = onSetPowerLevel,
                            onSelectUser = { memberProfile = it },
                            // While a call is live in this room the panel is "who is
                            // in the call". Passing the room roster instead is what
                            // made a live 2-person call read `Members 0`: the room
                            // list is empty in DMs and static for the whole call.
                            //
                            // Unreachable while `callHere` — branch 3 holds the
                            // slot then — but kept correct so the panel is right
                            // the moment the call ends.
                            liveCallMembers = if (isCallActiveInRoom && isGroupCall) {
                                liveCallRoster
                            } else {
                                null
                            }
                        )
                        // The room's timeline, in the panel, while the call owns
                        // the main column. Same composable as the main column's —
                        // one definition, two placements, so the two can never
                        // drift apart in behaviour.
                        3 -> ChatSidePanel(
                            channel = channel,
                            liveCallRosterSize = liveCallRoster.size,
                            onCollapse = { callChatCollapsed = true },
                            modifier = Modifier.width(OrbitMetrics.callChatPanelWidth)
                        ) {
                            ChatTimeline(
                                modifier = Modifier.weight(1f),
                                channel = channel,
                                displayMessages = displayMessages,
                                messages = messages,
                                members = members,
                                currentUser = currentUser,
                                typingUsers = typingUsers,
                                isLoadingMessages = isLoadingMessages,
                                firstUnreadId = firstUnreadId,
                                directProfile = directProfile,
                                replyingTo = replyingTo,
                                failedDraft = failedDraft,
                                onSeen = onSeen,
                                onReact = onReact,
                                onEditMessage = onEditMessage,
                                onDeleteMessage = onDeleteMessage,
                                onTogglePin = onTogglePin,
                                onSend = onSend,
                                onSendReply = onSendReply,
                                onSendAttachment = onSendAttachment,
                                onTyping = onTyping,
                                onReply = { id -> replyingTo = messages.firstOrNull { it.id == id } },
                                onCancelReply = { replyingTo = null },
                                onDraftRestored = { failedDraft = null },
                                onMentionUser = { userId ->
                                    members.firstOrNull {
                                        it.id == userId ||
                                            it.id.endsWith(userId) ||
                                            userId.endsWith(it.id)
                                    }?.let { memberProfile = it }
                                },
                                onLoadOlder = onLoadOlder
                            )
                        }
                        // The collapsed rail. Same slot, 48dp instead of 380dp,
                        // carrying enough to say what it is and to come back.
                        4 -> CallChatCollapsedRail(
                            channel = channel,
                            onExpand = { callChatCollapsed = false }
                        )
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * The room's message timeline, composer and typing pill.
 *
 * ## Why this is a top-level composable and not inline
 *
 * It renders in two places: the main column when no call is live, and the
 * side panel when a call has the main column. That means the body had to be
 * lifted out of the column.
 *
 * The first two attempts at this lift declared the composable **inside** the
 * `else` branch of the layout `if`. A declaration inside a branch is scoped to
 * that branch, so the side-panel call site could not see it — the second
 * attempt failed to compile, and the fix that shipped alongside it was a
 * placeholder rather than a working layout. Declaring it at file level, as it
 * is here, means there is exactly one definition and it is in scope everywhere
 * in the file. If you are moving this, keep it at file level.
 *
 * ## One copy at a time
 *
 * The caller must render this in at most one place per composition. The main
 * column and the side panel are gated on opposite sides of the same
 * `callOwnsMainColumn` predicate, so exactly one is composed. Two live copies
 * would mean two `LazyColumn`s over one message list and two composers fighting
 * over one draft.
 */
@Composable
private fun ChatTimeline(
    channel: OrbitChannel,
    displayMessages: List<OrbitMessage>,
    messages: List<OrbitMessage>,
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    typingUsers: List<String>,
    isLoadingMessages: Boolean,
    firstUnreadId: String?,
    directProfile: OrbitUser?,
    replyingTo: OrbitMessage?,
    failedDraft: String?,
    onSeen: (String) -> Unit,
    onReact: ((String, String) -> Unit)?,
    onEditMessage: ((String, String) -> Unit)?,
    onDeleteMessage: ((String) -> Unit)?,
    onTogglePin: ((String) -> Unit)?,
    onSend: (String) -> Unit,
    onSendReply: ((String, String) -> Unit)?,
    onSendAttachment: ((ByteArray, String, String) -> Unit)?,
    onTyping: (Boolean) -> Unit,
    onReply: (String) -> Unit,
    onCancelReply: () -> Unit,
    onDraftRestored: () -> Unit,
    onMentionUser: (String) -> Unit,
    onLoadOlder: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            MessageList(
                messages = displayMessages,
                currentUser = currentUser,
                channelName = if (directProfile != null) directProfile.displayName else channel.name,
                // directProfile only exists for a DM, so it is already the app's
                // answer to "is this one-to-one" -- no second lookup needed.
                isDirect = directProfile != null,
                isLoading = isLoadingMessages,
                modifier = Modifier.fillMaxSize(),
                firstUnreadId = firstUnreadId,
                onSeen = onSeen,
                onReact = onReact,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onTogglePin = onTogglePin,
                onReply = onReply,
                onMentionUser = onMentionUser,
                onLoadOlder = onLoadOlder
            )

            // Typing indicator: a small, quiet, self-clearing pill.
            if (typingUsers.isNotEmpty()) {
                val typingNames = typingUsers.map { id ->
                    members.firstOrNull { it.id == id || id.startsWith(it.id) }?.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: id.substringBefore(':').ifBlank { id }
                }
                val typingText = when {
                    typingNames.size == 1 -> "${typingNames[0]} is typing…"
                    typingNames.size == 2 -> "${typingNames[0]} and ${typingNames[1]} are typing…"
                    else -> "Several people are typing…"
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = OrbitSpace.lg, bottom = OrbitSpace.sm)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(OrbitRadius.pillShape)
                            .background(t.Elevated)
                            .padding(horizontal = OrbitSpace.md, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                    ) {
                        TypingDots()
                        Text(
                            text = typingText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = t.Text_Muted,
                                fontSize = 11.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }

        MessageInputBar(
            channelId = channel.id,
            placeholder = directProfile?.let { "Message @${it.displayName}" } ?: "Message #${channel.name}",
            onSend = { body ->
                val target = replyingTo
                if (target != null && onSendReply != null) {
                    onSendReply(target.id, body)
                    onCancelReply()
                } else {
                    onSend(body)
                }
            },
            onSendAttachment = onSendAttachment,
            onTyping = onTyping,
            onCancelReply = onCancelReply,
            replyPreview = replyingTo?.let { ReplyPreview(it.username, it.body) },
            // A failed send must put the message back. See
            // MessageInputBar.draftToRestore.
            draftToRestore = failedDraft,
            onDraftRestored = onDraftRestored
        )
    }
}

/**
 * The timeline in the side panel, for while a call owns the main column.
 *
 * Chrome matches the other panels so the slot slides between them rather than
 * restacking, and the header states what the window is currently prioritising:
 * the call is in the middle, this is the conversation you keep alongside it.
 */
@Composable
private fun ChatSidePanel(
    channel: OrbitChannel,
    liveCallRosterSize: Int,
    onCollapse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = modifier.fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(t.Separator)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(t.Members_Bg)
        ) {
            SidePanelHeader(
            icon = OrbitIcons.TextChannel,
            title = "#${channel.name}",
            // The participant count belongs to the call, and the call is not in
            // this panel any more. Showing it here would be answering a question
            // the user is not looking at.
            count = null,
            subtitle = if (liveCallRosterSize > 0) "$liveCallRosterSize in call" else null,
            trailing = onCollapse?.let { collapse ->
                {
                    // A chevron pointing INTO the panel, i.e. "take this away".
                    // The concept's own collapse affordance is the panel-width
                    // toggle in the call header; this is the same intent on the
                    // panel itself, so it is reachable without going back there.
                    OrbitIconButton(
                        icon = OrbitIcons.ChevronRight,
                        contentDescription = "Collapse chat panel",
                        onClick = collapse
                    )
                }
            }
        )
        HorizontalDivider(color = t.Separator, thickness = 1.dp)
        content()
    }
        }
    }

/**
 * The call-chat panel collapsed to a 48dp rail.
 *
 * The panel is not optional during a call -- the conversation has to stay
 * reachable -- but 380dp is a lot of window to hand to a transcript while the
 * call is the thing being looked at. This keeps the slot, the channel identity
 * and a way back, and gives the width to the call.
 */
@Composable
private fun CallChatCollapsedRail(
    channel: OrbitChannel,
    onExpand: () -> Unit
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .width(48.dp)
            .fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(t.Separator)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(t.Members_Bg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        OrbitIconButton(
            icon = OrbitIcons.ChevronLeft,
            contentDescription = "Show chat panel",
            onClick = onExpand
        )
        HorizontalDivider(color = t.Separator, thickness = 1.dp)
        // Rotated a full 90 degrees so the channel name reads top-to-bottom, which
        // is the only way a name fits in 48dp without truncating to nothing.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Text(
                text = "#${channel.name}",
                color = t.Text_Muted,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = OrbitSpace.md)
                    .graphicsLayer(rotationZ = 90f)
            )
        }
    }
        }
    }

/**
 * Shown instead of the voice surface when the call stack is not wired up. The
 * previous code reached for `callManager!!` and a hard cast, so opening a voice
 * room in a non-Matrix session took the whole window down.
 */
@Composable
private fun VoiceChannelUnavailable() {
    val t = rememberCosmicTokens()
    // fillMaxSize, not weight: this is a sibling of the voice panel rather than
    // a child of a Row/Column, so there is no parent weight to take.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Voice is unavailable in this session",
            style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Muted),
            modifier = Modifier.padding(OrbitSpace.lg)
        )
    }
}

/** Three dots that rise in sequence — the universally understood "typing". */
@Composable
private fun TypingDots() {
    val t = rememberCosmicTokens()
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(760, delayMillis = index * 150, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "typingDot$index"
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(OrbitRadius.pillShape)
                    .background(t.Text_Muted.copy(alpha = alpha))
            )
        }
    }
}

/** `width: '380px'` on `.pinned-drawer`. */
private val PinnedDrawerWidth = 380.dp

/** `borderRadius: '8px'` on the drawer's message cards. */
private val PinnedCardShape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

/**
 * The pinned-messages drawer, from the concept's `PinnedDrawer.jsx`.
 *
 * Every value below is that file's, one to one:
 *
 *  - `width: '380px'` → [PinnedDrawerWidth]
 *  - `backgroundColor: var(--bg-secondary)` → `OrbitConceptDark.secondary`
 *  - `borderLeft: 1px solid var(--border-subtle)` → the 1dp strip down the left
 *  - `boxShadow: var(--elevation-floating)` (0 16px 32px rgba(0,0,0,0.4)) →
 *    `shadow(16.dp)`, which is as close as Compose's elevation model gets
 *  - header `height: 48px` → `OrbitConceptMetrics.headerHeight`
 *  - content `padding: 16px` / `gap: 16px`
 *  - card `backgroundColor: var(--bg-chat)`, `borderRadius: 8px`,
 *    `padding: 12px`, `border: 1px solid var(--border-subtle)`
 *  - body `fontSize: 13px`, `lineHeight: 1.4`
 *
 * It previously borrowed [SidePanelHeader] instead: 44dp tall, 13sp, a count
 * badge and no close control, with each message as a bare
 * divider-separated row. That is the app-wide panel header, and the concept's
 * drawer is a different shape — it has cards on a lighter surface inside a
 * darker drawer, and it closes.
 *
 * ## Not reproduced
 *
 * The concept's per-message "Jump" button calls `onClose` and nothing else —
 * there is no scroll target behind it. Orbit has no pinned-message scroll
 * plumbing either, so it is omitted rather than shipped as a control that
 * pretends to navigate.
 */
@Composable
private fun PinnedMessagesPanel(
    pinned: List<OrbitMessage>,
    canUnpin: Boolean,
    onUnpin: (String) -> Unit,
    onClose: () -> Unit
) {
    val listState = rememberLazyListState()
    val roomMembers = ui.theme.LocalRoomMembers.current

    Row(
        modifier = Modifier
            .width(PinnedDrawerWidth)
            .fillMaxHeight()
            // box-shadow: var(--elevation-floating)
            .shadow(16.dp)
    ) {
        // border-left: 1px solid var(--border-subtle)
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(OrbitConceptBorder.subtle())
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // backgroundColor: var(--bg-secondary)
                .background(OrbitConceptDark.secondary)
        ) {
            // Header: height 48px, padding 0 16px, space-between.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OrbitConceptMetrics.headerHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null,
                    tint = OrbitConceptDark.textPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(OrbitSpace.sm))
                Text(
                    text = "Pinned Messages",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = OrbitConceptDark.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OrbitIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = "Close pinned messages",
                    onClick = onClose,
                    size = 28.dp,
                    iconSize = 18.dp,
                    shape = OrbitRadius.xsShape
                )
            }
            // border-bottom: 1px solid var(--border-subtle)
            HorizontalDivider(color = OrbitConceptBorder.subtle(), thickness = 1.dp)

            if (pinned.isEmpty()) {
                // padding: 16px, gap: 16px — then margin-top: 40px on the block.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(OrbitSpace.lg)
                ) {
                    Column(
                        modifier = Modifier.padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = null,
                            tint = OrbitConceptDark.textMuted,
                            // size 32, opacity 0.5
                            modifier = Modifier.size(32.dp).alpha(0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No pinned messages in this channel yet.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = OrbitConceptDark.textMuted,
                                fontSize = 13.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                Box(Modifier.weight(1f)) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // padding: 16px
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(OrbitSpace.lg)
                    ) {
                        items(pinned.size, key = { pinned[it].id }) { index ->
                            val message = pinned[index]
                            // backgroundColor: var(--bg-chat); border-radius: 8px;
                            // padding: 12px; border: 1px solid var(--border-subtle)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(PinnedCardShape)
                                    .background(OrbitConceptDark.chat)
                                    .border(1.dp, OrbitConceptBorder.subtle(), PinnedCardShape)
                                    .padding(12.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        PinnedAuthorAvatar(
                                            username = message.username,
                                            userId = message.userId,
                                            member = roomMembers.firstOrNull {
                                                it.id == message.userId ||
                                                    it.id.endsWith(message.userId) ||
                                                    message.userId.endsWith(it.id)
                                            }
                                        )
                                        Text(
                                            text = message.username,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                color = OrbitConceptDark.textPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 13.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = formatTimestampCompact(message.timestamp),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = OrbitConceptDark.textMuted,
                                                fontSize = 11.sp
                                            ),
                                            maxLines = 1
                                        )
                                    }
                                    if (canUnpin) {
                                        OrbitIconButton(
                                            icon = Icons.Default.Close,
                                            contentDescription = "Unpin message",
                                            onClick = { onUnpin(message.id) },
                                            size = 22.dp,
                                            iconSize = 14.dp,
                                            shape = OrbitRadius.xsShape
                                        )
                                    }
                                }
                                Spacer(Modifier.height(OrbitSpace.sm))
                                Text(
                                    text = message.body,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = OrbitConceptDark.textNormal,
                                        fontSize = 13.sp,
                                        // line-height: 1.4
                                        lineHeight = 18.2.sp
                                    )
                                )
                            }
                        }
                    }
                    OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

/**
 * `width: 24px; height: 24px; border-radius: 50%` on a pinned message's author.
 *
 * Resolved from the room roster rather than invented: a message carries a user id
 * and a name, and `LocalRoomMembers` is already provided by `ChatScreen`.
 */
@Composable
private fun PinnedAuthorAvatar(
    username: String,
    userId: String,
    member: OrbitUser?
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(OrbitConceptDark.secondary)
    ) {
        val avatarUrl = member?.avatarUrl
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = username,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            OrbitDefaultAvatar(
                label = username,
                seed = userId,
                color = member?.let { Color(it.avatarColor) },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Shared chrome for the right-hand panel headers. */
@Composable
internal fun SidePanelHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(ui.theme.OrbitConceptMetrics.headerHeight)
            .padding(horizontal = OrbitSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = t.Text_Muted, modifier = Modifier.size(15.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = t.Text_White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.sp
            ),
            maxLines = 1
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(OrbitRadius.pillShape)
                    .background(t.Sunken)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = t.Text_Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        if (subtitle != null) {
            // Sits after the title, before the flexible spacer, so a panel with
            // both a count badge and a subtitle reads "Members · 12 · 3 in call"
            // rather than putting the subtitle at the far edge of the panel.
            Spacer(Modifier.width(OrbitSpace.sm))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Subtle,
                    fontSize = 11.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

