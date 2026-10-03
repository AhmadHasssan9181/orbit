package ui.screen.drawer

import webrtc.LocalCallManager

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.model.UserStatus
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitRadius
import ui.theme.rememberCosmicTokens

/**
 * One person in the friends list.
 *
 * Orbit has no friends *feature* — Matrix has no friend list, only shared rooms
 * — so this is a name, a presence and, when there is one, the 1:1 room to open.
 * [roomId] is what makes "Message" and "Call" real: without it the row would be
 * a name with three buttons that cannot reach anybody.
 */
@Immutable
data class OrbitFriend(
    val user: OrbitUser,
    /** Matrix room id of the 1:1 with this person, when there is one. */
    val roomId: String? = null,
    /**
     * What they are doing, shown under the name.
     *
     * The concept mocks up "Playing Valorant" / "Listening to Spotify". Matrix
     * publishes presence and nothing else, so this is a caller-supplied label
     * and falls back to the presence the homeserver reported.
     */
    val activity: String? = null,
    /** True while that room has a live call, which is the one thing Orbit does know. */
    val inCall: Boolean = false
)

/** Which of the concept's four header tabs is showing. */
internal enum class FriendsTab { Online, All, Pending, Add }

/**
 * The friends view. The concept's `FriendsView.jsx`.
 *
 * ## What Orbit can and cannot fill this with
 *
 * The concept's list comes from `mockData.js`: hard-coded friends, hard-coded
 * activities, and a hard-coded "Active Now" column of three third-party cards
 * (Valorant, Spotify, Twitch). Orbit has none of that data, so:
 *
 *  - **the list** is the people you actually have a 1:1 room with, resolved
 *    through `repository.resolveDmContacts` — the same call the DM list already
 *    makes, so both read the same people;
 *  - **the "Active Now" column** is built from the friends who are present. A
 *    friend with a live call in their room gets the call treatment; the rest get
 *    the ordinary online treatment. The card geometry, the accents and the
 *    button are the concept's; the *content* is real rather than invented.
 *
 * Nothing here fabricates presence, an activity, or a room.
 */
@Composable
fun FriendsView(
    friends: List<OrbitFriend>,
    onOpenDm: (OrbitFriend) -> Unit,
    onStartCall: (OrbitFriend, video: Boolean) -> Unit,
    onOpenProfile: ((OrbitUser) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var activeTab by remember { mutableStateOf(FriendsTab.Online) }
    var searchFilter by remember { mutableStateOf("") }
    var addFriendInput by remember { mutableStateOf("") }
    /** Outcome of the last "Send Friend Request", or null before one is sent. */
    var addFriendResult by remember { mutableStateOf<String?>(null) }

    val onlineFriends = remember(friends) { friends.filter { it.user.status != UserStatus.OFFLINE } }
    val pool = when (activeTab) {
        FriendsTab.Online -> onlineFriends
        // There is no friend-request inbox to read, so this tab is empty rather
        // than a second copy of the All list under a heading that lies.
        FriendsTab.Pending -> emptyList()
        else -> friends
    }
    val displayedFriends = remember(pool, searchFilter) {
        val q = searchFilter.trim().lowercase()
        if (q.isEmpty()) {
            pool
        } else {
            pool.filter {
                it.user.displayName.lowercase().contains(q) ||
                    it.user.username.lowercase().contains(q)
            }
        }
    }
    // "Active Now" is the same population the Online tab shows: the people who
    // are not offline. The concept's three cards are a hand-picked subset of its
    // friends list, so deriving it from presence rather than hard-coding it is
    // the same relationship with real data.
    val activeNow = remember(friends) {
        friends
            .filter { it.user.status != UserStatus.OFFLINE }
            .sortedByDescending { it.inCall }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // `.chat-container { background-color: var(--bg-chat) }`.
            .background(conceptChatSurface())
    ) {
        FriendsHeader(
            activeTab = activeTab,
            onTabSelected = { activeTab = it }
        )

        // `.chat-body { flex: 1; display: flex; overflow: hidden }`.
        // `weight(1f)` rather than `fillMaxSize()`: the body shares a Column with
        // the header above it, and `fillMaxSize` here would claim the column's
        // full height and push the header out of it.
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // `flex: 1; padding: 24px 30px; overflow-y: auto`.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 30.dp, end = 30.dp, top = 24.dp, bottom = 24.dp)
            ) {
                if (activeTab == FriendsTab.Add) {
                    AddFriendPane(
                        input = addFriendInput,
                        onInputChange = {
                            addFriendInput = it
                            // The result describes the previous input, so it goes
                            // the moment the field changes.
                            addFriendResult = null
                        },
                        onSubmit = {
                            val handle = addFriendInput.trim()
                            if (handle.isEmpty()) return@AddFriendPane
                            addFriendResult = friends
                                .firstOrNull { it.matchesHandle(handle) }
                                ?.let { match ->
                                    onOpenDm(match)
                                    "Opening your conversation with ${match.user.displayName}."
                                }
                                ?: "No conversation with $handle yet. Matrix has no friend-request " +
                                "endpoint, so Orbit cannot start one."
                        },
                        result = addFriendResult
                    )
                } else {
                    FriendsListPane(
                        friends = displayedFriends,
                        activeTab = activeTab,
                        searchFilter = searchFilter,
                        onSearchFilterChange = { searchFilter = it },
                        onOpenDm = onOpenDm,
                        onStartCall = onStartCall,
                        onOpenProfile = onOpenProfile
                    )
                }
            }

            // `width: 340px; border-left: 1px solid var(--border-subtle);
            // padding: 24px 16px; overflow-y: auto`.
            //
            // `borderLeft` only, so the rule is a 1dp sibling rather than a
            // `Modifier.border`, which would draw all four.
            Row(modifier = Modifier.fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(conceptBorderSubtle())
                )
                Column(
                    modifier = Modifier
                        .width(339.dp)
                        .fillMaxHeight()
                        .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp)
                ) {
                    Text(
                        text = "Active Now",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = conceptHeaderPrimary(),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 20.sp,
                            letterSpacing = 0.sp
                        ),
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (activeNow.isEmpty()) {
                            Text(
                                text = "Nobody is around right now.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = conceptTextMuted(),
                                    fontSize = 13.sp
                                )
                            )
                        } else {
                            activeNow.forEach { friend ->
                                ActivityCard(
                                    friend = friend,
                                    onAction = { onOpenDm(friend) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * `.chat-header { height: var(--header-height); padding: 0 16px; display: flex;
 * align-items: center; justify-content: space-between; border-bottom: 1px solid
 * var(--border-subtle); background-color: var(--bg-chat) }` plus
 * `.chat-header-left { gap: 16px }` (the inline override of the CSS's 12px), a
 * `.chat-header-divider` (1px × 16px), and the four tabs.
 *
 * Tab rules, verbatim from the inline styles:
 *
 * | tab                    | background                      | colour                        | weight |
 * |------------------------|---------------------------------|-------------------------------|--------|
 * | Online / All / Pending | `--bg-modifier-selected` / none | `#fff` / `--interactive-normal` | 600 |
 * | Add Friend             | `--status-online`               | `#fff`                        | 700    |
 *
 * `Add Friend` is also padded `4px 10px` where the others are `4px 8px`, and it
 * is a solid fill rather than a wash, which is what separates it from the three
 * tabs that merely switch the list beneath it.
 */
@Composable
private fun FriendsHeader(
    activeTab: FriendsTab,
    onTabSelected: (FriendsTab) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(conceptChatSurface())) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(OrbitConceptMetrics.headerHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = null,
                        // `<Users size={22} color="var(--interactive-muted)" />`.
                        tint = SidebarConcept.interactiveMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Friends",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = conceptHeaderPrimary(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            letterSpacing = 0.sp
                        )
                    )
                }

                // `.chat-header-divider { width: 1px; height: 16px }`.
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(16.dp)
                        .background(conceptBorderSubtle())
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FriendsTabButton(
                        label = "Online",
                        selected = activeTab == FriendsTab.Online,
                        onClick = { onTabSelected(FriendsTab.Online) }
                    )
                    FriendsTabButton(
                        label = "All",
                        selected = activeTab == FriendsTab.All,
                        onClick = { onTabSelected(FriendsTab.All) }
                    )
                    FriendsTabButton(
                        label = "Pending",
                        selected = activeTab == FriendsTab.Pending,
                        onClick = { onTabSelected(FriendsTab.Pending) }
                    )
                    FriendsTabButton(
                        label = "Add Friend",
                        selected = activeTab == FriendsTab.Add,
                        solid = true,
                        onClick = { onTabSelected(FriendsTab.Add) }
                    )
                }
            }
        }

        // `.chat-header { border-bottom: 1px solid var(--border-subtle) }`.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(conceptBorderSubtle())
        )
    }
}

@Composable
private fun FriendsTabButton(
    label: String,
    selected: Boolean,
    /** `Add Friend` — the one tab that is a filled `--status-online` pill. */
    solid: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val fill by animateColorAsStateCompat(
        when {
            solid -> OrbitConceptStatus.online
            selected -> conceptSelectedOverlay()
            // The concept's three tabs carry no hover rule of their own. The
            // `--bg-modifier-hover` wash is the token set's closest and it is
            // what makes an unselected tab feel pressable at all; without it a
            // tab looks identical whether or not the pointer is on it.
            hovered -> conceptHoverOverlay()
            else -> Color.Transparent
        }
    )
    // `color: active ? '#fff' : 'var(--interactive-normal)'`.
    val fg by animateColorAsStateCompat(
        when {
            solid || selected -> Color.White
            hovered -> conceptInteractiveHover()
            else -> conceptInteractiveNormal()
        }
    )

    Box(
        modifier = Modifier
            // `padding: 4px 8px`, and `4px 10px` on the solid one.
            .clip(OrbitRadius.xsShape)
            .background(fill)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClickLabel = label,
                onClick = onClick
            )
            .padding(
                horizontal = if (solid) 10.dp else 8.dp,
                vertical = 4.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                // 600 on the three tabs, 700 on the solid one.
                fontWeight = if (solid) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 14.sp
            ),
            maxLines = 1
        )
    }
}

/** The search bar, the section label and the list — everything left of "Active Now". */
@Composable
private fun FriendsListPane(
    friends: List<OrbitFriend>,
    activeTab: FriendsTab,
    searchFilter: String,
    onSearchFilterChange: (String) -> Unit,
    onOpenDm: (OrbitFriend) -> Unit,
    onStartCall: (OrbitFriend, video: Boolean) -> Unit,
    onOpenProfile: ((OrbitUser) -> Unit)?
) {
    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // `backgroundColor: var(--bg-servers); border-radius: 6px;
        // padding: 8px 12px; margin-bottom: 20px; max-width: 560px`.
        Row(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(SidebarConcept.friendsSearchShape)
                .background(conceptServersSurface())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searchFilter.isEmpty()) {
                    Text(
                        text = "Search friends",
                        style = MaterialTheme.typography.bodyMedium.copy(color = conceptTextMuted()),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = searchFilter,
                    onValueChange = onSearchFilterChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = conceptTextNormal(),
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(conceptBrand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = conceptTextMuted(),
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        // `font-size: 12px; font-weight: 700; color: var(--text-muted);
        // margin-bottom: 12px; letter-spacing: 0.02em`.
        Text(
            text = if (activeTab == FriendsTab.Online) {
                "ONLINE — ${friends.size}"
            } else if (activeTab == FriendsTab.Pending) {
                "PENDING REQUESTS — 0"
            } else {
                "ALL FRIENDS — ${friends.size}"
            },
            style = MaterialTheme.typography.labelSmall.copy(
                color = conceptTextMuted(),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.24.sp
            ),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (friends.isEmpty()) {
            // An `if`, not an early `return`: returning above the list would
            // change the number of composed children when the list goes from
            // empty to non-empty, which re-binds the remembered list state.
            Text(
                text = when (activeTab) {
                    FriendsTab.Pending ->
                        "Matrix has no friend-request inbox, so there is nothing pending to show."
                    FriendsTab.Online ->
                        "Nobody you have a conversation with is online."
                    else ->
                        "No conversations yet."
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = conceptTextMuted(),
                    fontSize = 13.sp
                )
            )
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(friends, key = { it.user.id }) { friend ->
                        FriendRow(
                            friend = friend,
                            onOpenDm = { onOpenDm(friend) },
                            onStartVoiceCall = { onStartCall(friend, false) },
                            onStartVideoCall = { onStartCall(friend, true) },
                            onOpenProfile = onOpenProfile
                        )
                    }
                }
                OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * One friend: `.member-item-row` with the concept's inline overrides.
 *
 * The inline style sets `padding: 10px 12px` and `border-radius: 8px` over the
 * class's own `6px 8px` / `4px`, and adds `border-top: 1px solid
 * var(--border-subtle)` — a top rule rather than the member list's bottom one,
 * which is why the first row carries a line above it with nothing above it.
 * Hover is the class's: `--bg-modifier-hover`.
 *
 * The three quick actions are `.user-control-btn` at a forced `--bg-servers`
 * fill and 36px square, which is why they read as filled buttons here and as
 * quiet icons in the sidebar's user bar.
 */
@Composable
private fun FriendRow(
    friend: OrbitFriend,
    onOpenDm: () -> Unit,
    onStartVoiceCall: () -> Unit,
    onStartVideoCall: () -> Unit,
    onOpenProfile: ((OrbitUser) -> Unit)?
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        // `border-top: 1px solid var(--border-subtle)`.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(conceptBorderSubtle())
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitRadius.mdShape)
                .background(if (hovered) conceptHoverOverlay() else Color.Transparent)
                .hoverable(interaction)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(OrbitRadius.xsShape)
                    .then(
                        // The whole identity block is the profile link in the
                        // concept. Orbit has no route to a bare profile outside a
                        // room, so when no opener is supplied the block is a label
                        // rather than a button that opens nothing.
                        if (onOpenProfile == null) {
                            Modifier
                        } else {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClickLabel = "Open ${friend.user.displayName}'s profile",
                                onClick = { onOpenProfile(friend.user) }
                            )
                        }
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DmAvatar(
                    displayName = friend.user.displayName,
                    avatarColor = friend.user.avatarColor,
                    status = friend.user.status,
                    avatarUrl = friend.user.avatarUrl,
                    size = 40.dp
                )
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = friend.user.displayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = conceptHeaderPrimary(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isOrbitVerifiedUser(friend.user.username, friend.user.displayName)) {
                            Spacer(Modifier.width(6.dp))
                            OrbitVerifiedBadge(modifier = Modifier.size(12.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        // `@name`, not the concept's `name#tag`: Matrix has no
                        // discriminator, and printing a tag Orbit does not have
                        // would be inventing one.
                        Text(
                            text = "@${friend.user.username}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = conceptTextMuted(),
                                fontSize = 12.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = friend.activity
                            ?: if (friend.inCall) "In a call" else statusLabel(friend.user.status),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = conceptTextMuted(),
                            fontSize = 12.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // `display: flex; align-items: center; gap: 8px`.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FriendActionButton(
                    icon = Icons.Default.Chat,
                    contentDescription = "Message ${friend.user.displayName}",
                    onClick = onOpenDm
                )
                FriendActionButton(
                    icon = OrbitIcons.Call,
                    contentDescription = "Start a voice call with ${friend.user.displayName}",
                    onClick = onStartVoiceCall,
                    enabled = friend.roomId != null
                )
                FriendActionButton(
                    icon = OrbitIcons.Video,
                    contentDescription = "Start a video call with ${friend.user.displayName}",
                    onClick = onStartVideoCall,
                    enabled = friend.roomId != null
                )
            }
        }
    }
}

/**
 * `.user-control-btn` at a forced 36px square with
 * `backgroundColor: 'var(--bg-servers)'`.
 *
 * The forced fill is the concept's: these three read as buttons sitting on the
 * chat surface, where the same class in the sidebar's user bar is transparent.
 */
@Composable
private fun FriendActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val fill by animateColorAsStateCompat(
        when {
            !enabled -> conceptServersSurface().copy(alpha = 0.5f)
            hovered -> conceptHoverOverlay()
            else -> conceptServersSurface()
        }
    )
    val fg by animateColorAsStateCompat(
        when {
            !enabled -> conceptTextMuted()
            hovered -> conceptInteractiveHover()
            else -> conceptInteractiveNormal()
        }
    )

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(OrbitRadius.xsShape)
            .background(fill)
            .hoverable(interaction, enabled)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * The "Add Friend" tab: heading, one line of copy, and the form.
 *
 * `max-width: 520px` on the column, a `16px / weight 700` heading, a
 * `13px / --text-muted` paragraph, then a row with
 * `backgroundColor: var(--bg-servers); border-radius: 8px; padding: 8px 14px;
 * border: 1px solid var(--border-subtle)` holding a 15px field and a
 * `--brand-blurple` submit button at `6px 14px`.
 *
 * ## What the button actually does
 *
 * The concept's form fakes it: it sets a `friendRequestSent` flag, prints
 * "Success!" and clears itself after 2.5 seconds, having contacted nothing.
 * Matrix has no friend-request endpoint, so a button that reported success would
 * be reporting a send that never happened — the same class of bug this codebase
 * has spent a long time removing (a `Result` discarded, a UI that claims an
 * operation it never performed).
 *
 * So the submit resolves the typed handle against the conversations this
 * account already has, opens the one it finds, and says plainly when there is
 * none. That is a real action with a real outcome in either direction, and the
 * layout above it is still the concept's, line for line.
 */
@Composable
private fun AddFriendPane(
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    result: String?
) {
    Column(modifier = Modifier.widthIn(max = 520.dp)) {
        Text(
            text = "ADD FRIEND",
            style = MaterialTheme.typography.titleSmall.copy(
                color = conceptHeaderPrimary(),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                letterSpacing = 0.sp
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "You can add friends with their Matrix ID, for example @someone:matrix.org.",
            style = MaterialTheme.typography.bodySmall.copy(
                color = conceptTextMuted(),
                fontSize = 13.sp
            ),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SidebarConcept.friendsFormShape)
                .background(conceptServersSurface())
                .border(1.dp, conceptBorderSubtle(), SidebarConcept.friendsFormShape)
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (input.isEmpty()) {
                    Text(
                        text = "Enter a Matrix ID",
                        style = MaterialTheme.typography.bodyMedium.copy(color = conceptTextMuted()),
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = conceptTextNormal(),
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(conceptBrand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.width(10.dp))
            // `backgroundColor: var(--brand-blurple); color: #fff; padding: 6px
            // 14px; border-radius: 4px; font-weight: 600` with
            // `opacity: input ? 1 : 0.5`.
            val canSend = input.isNotBlank()
            Box(
                modifier = Modifier
                    .clip(OrbitRadius.xsShape)
                    .background(if (canSend) conceptBrand else conceptBrand.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = canSend,
                        role = Role.Button,
                        onClickLabel = "Find this person",
                        onClick = onSubmit
                    )
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Send Friend Request",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    maxLines = 1
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (result != null) {
            // `margin-top: 12px; color: var(--status-online); font-size: 14px;
            // font-weight: 600`.
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = OrbitConceptStatus.online,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            )
        }
    }
}

/**
 * One card in the "Active Now" column.
 *
 * `backgroundColor: var(--bg-secondary); border-radius: 10px; padding: 14px;
 * border: 1px solid var(--border-subtle); display: flex; flex-direction: column;
 * gap: 12px` — the geometry is the concept's in full.
 *
 * The accent comes from what Orbit knows about the person rather than from the
 * third-party brand the concept mocks up:
 *
 * | state             | accent            | glyph           | action    |
 * |-------------------|-------------------|-----------------|-----------|
 * | in a live call    | `--status-online` | `Monitor`       | Join call |
 * | present otherwise | `--brand-blurple` | `SportsEsports` | Message   |
 *
 * Both are the concept's own values. The two third-party accents it uses for
 * Spotify and Twitch are kept in [SidebarConcept] for a future rich-presence
 * source rather than being printed on cards about real people.
 */
@Composable
private fun ActivityCard(
    friend: OrbitFriend,
    onAction: () -> Unit
) {
    val accent = if (friend.inCall) OrbitConceptStatus.online else conceptBrand
    val icon = if (friend.inCall) Icons.Default.Monitor else Icons.Default.SportsEsports
    val actionLabel = if (friend.inCall) "Join call" else "Message"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SidebarConcept.activityCardShape)
            .background(conceptSecondarySurface())
            .border(1.dp, conceptBorderSubtle(), SidebarConcept.activityCardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // `display: flex; align-items: center; gap: 10px` with a 36px round
        // avatar.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DmAvatar(
                displayName = friend.user.displayName,
                avatarColor = friend.user.avatarColor,
                status = friend.user.status,
                avatarUrl = friend.user.avatarUrl,
                size = 36.dp
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = friend.user.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = conceptHeaderPrimary(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = friend.activity
                        ?: if (friend.inCall) "In a call" else statusLabel(friend.user.status),
                    style = MaterialTheme.typography.labelSmall.copy(
                        // `font-size: 11px; font-weight: 600`, in the card's own
                        // accent, which the concept sets per service.
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // `font-size: 13px; color: var(--text-normal)`.
        Text(
            text = if (friend.inCall) {
                "A call is running in your conversation."
            } else {
                "Online now."
            },
            style = MaterialTheme.typography.bodySmall.copy(
                color = conceptTextNormal(),
                fontSize = 13.sp
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        // `.voice-tool-btn` with the concept's inline override:
        // `backgroundColor` + `color` + `justifyContent: 'center'`, so it is a
        // full-width centred pill rather than the `flex: 1` the class gives it
        // beside other buttons. `.voice-tool-btn:hover` raises the fill to
        // `rgba(255,255,255,0.1)`, which is invisible on a saturated fill, so
        // the hover step here is a lighten of the accent instead.
        val interaction = remember { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(OrbitRadius.xsShape)
                .background(if (hovered) accent.copy(alpha = 0.85f) else accent)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = actionLabel,
                    onClick = onAction
                )
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

/**
 * Whether a typed handle refers to this friend.
 *
 * Accepts the two shapes a Matrix ID can arrive in — `@localpart:server` and
 * `localpart` — because that is what somebody copying one out of the app will
 * have. Case-insensitive, since Matrix localparts are.
 */
private fun OrbitFriend.matchesHandle(handle: String): Boolean {
    val trimmed = handle.trim().removePrefix("@")
    return user.id.equals(handle.trim(), ignoreCase = true) ||
        user.username.equals(trimmed, ignoreCase = true) ||
        user.id.substringBefore(':').equals(trimmed, ignoreCase = true)
}

/**
 * Resolves the friends list from the DM rooms, and owns the friends screen's own
 * wiring.
 *
 * This is the container the layouts call: [FriendsView] stays a pure view over a
 * list it is handed, and everything with a `repository` or a `CallManager` in it
 * is here.
 *
 * The population is the one honest mapping Orbit has: the other member of every
 * 1:1 you actually have. Matrix has no friend list, so "friends" here means
 * "people you have a conversation with" — the same set the DM drawer lists,
 * resolved through the same cached call, so the two can never disagree about who
 * somebody is.
 */
@Composable
fun FriendsPanel(
    repository: repository.OrbitRepository?,
    dmChannels: List<OrbitChannel>,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val callManager = webrtc.LocalCallManager.current
    val t = rememberCosmicTokens()

    val roomIds = remember(dmChannels) {
        dmChannels.map { it.matrixRoomId.ifBlank { it.id } }.distinct()
    }
    val roomKey = remember(roomIds) { roomIds.joinToString("|") }

    var contacts by remember { mutableStateOf<Map<String, OrbitUser>>(emptyMap()) }
    LaunchedEffect(roomKey, repository) {
        val repo = repository ?: return@LaunchedEffect
        contacts = repo.resolveDmContacts(roomIds)
    }

    // A call is "live in this conversation" when the room the call is running in
    // is one of the DM rooms. Reading the call state is what lets the Active Now
    // column say something true.
    val callStateFlow = remember(callManager) { callManager?.callState ?: flowOf(webrtc.CallState.IDLE) }
    val callState by callStateFlow.collectAsState(initial = webrtc.CallState.IDLE)
    val activeRoomFlow = remember(callManager) { callManager?.activeRoomIdFlow ?: flowOf<String?>(null) }
    val activeRoomId by activeRoomFlow.collectAsState(initial = null)
    val callIsLive = callState != webrtc.CallState.IDLE && callState != webrtc.CallState.ENDED

    // Deduplicated BY CONTACT ID, not by room id.
    //
    // The list renders as `items(friends, key = { it.user.id })`, so the user id
    // is the LazyColumn key and it must be unique. Two DM channels can resolve to
    // one and the same person -- Matrix happily hands out a second invite-only
    // room for a pair that already has one (a recreated DM, an abandoned room
    // still sitting in the list), and `resolveDmContacts` maps each room to the
    // OTHER participant, so both rooms land on the same `OrbitUser`. Two rows,
    // one mxid, and Compose throws `Key "@user:server" was already used` out of
    // the lazy layout.
    //
    // That throw is not a one-off: `FriendsPanel` recomposes on every call-state
    // and roster change, so the dialog returns until the duplicate goes away.
    //
    // `roomIds` above is distinct, but it only dedupes what we ASK about. This
    // dedupes what we RENDER, which is the list whose ids become keys. First room
    // wins, which matches the display order of the channel list the user is
    // already looking at.
    val friends = remember(contacts, dmChannels, callIsLive, activeRoomId) {
        val byUserId = LinkedHashMap<String, OrbitFriend>()
        for (ch in dmChannels) {
            val roomId = ch.matrixRoomId.ifBlank { ch.id }
            val contact = contacts[roomId] ?: continue
            // `putIfAbsent` rather than a null check + put: one lookup, and the
            // intent ("keep the first room for this person") is the statement.
            byUserId.putIfAbsent(contact.id, OrbitFriend(
                user = contact,
                roomId = roomId,
                inCall = callIsLive && activeRoomId == roomId
            ))
        }
        byUserId.values.toList()
    }

    FriendsView(
        friends = friends,
        onOpenDm = { friend -> friend.roomId?.let(onOpenChannel) },
        onStartCall = { friend, video ->
            friend.roomId?.let { roomId -> callManager?.startCall(roomId, video) }
        },
        // No route to a bare user profile exists outside a room: `ChatScreen` owns
        // its own `memberProfile` state. Passing null makes the identity block a
        // label rather than a button that opens nothing.
        onOpenProfile = null,
        modifier = modifier.background(t.Bg)
    )
}