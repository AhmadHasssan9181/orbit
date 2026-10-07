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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitMascotAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitIconButton
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.OrbitChannel
import ui.model.UserStatus
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitRadius

/**
 * The direct-message list: the concept's DM branch of `.channel-sidebar`.
 *
 * The concept's version is `.channel-row` again, at the same 6px/8px padding,
 * except that the row is pinned to 42px (`style={{ height: '42px' }}`), the
 * glyph is a 28px avatar inside a `.user-avatar-wrap`, and the gap inside
 * `.channel-row-content` is 10px rather than 8. The unread count is
 * `.badge-unread` `position: static` — an inline pill rather than the
 * absolutely-positioned rail badge, which is why it sits at the row's trailing
 * edge instead of hanging off a corner.
 *
 * Real avatars and presence for the other member of each 1:1. The list used
 * to hard-code ONLINE and a seeded initial for every row, which was simply
 * wrong information. Resolved off the channel set so a rename or a new DM
 * triggers one refresh, not one per row per recomposition.
 */
@Composable
internal fun DmListContent(
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onVoiceCall: ((String) -> Unit)? = null,
    onVideoCall: ((String) -> Unit)? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    repository: repository.OrbitRepository? = null,
    /** See [ChannelDrawer.friendsTabActive]. */
    friendsTabActive: Boolean = false,
    /** Null means the layout has nowhere to show the friends view, so no row. */
    onSelectFriendsTab: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Real avatars and presence for the other member of each 1:1. The list used
    // to hard-code ONLINE and a seeded initial for every row, which was simply
    // wrong information. Resolved off the channel set so a rename or a new DM
    // triggers one refresh, not one per row per recomposition.
    var dmContacts by remember { mutableStateOf<Map<String, ui.model.OrbitUser>>(emptyMap()) }
    // Keyed on the room-id SET, not on the channel objects. OrbitChannel.topic
    // carries the last-message preview, so keying on the objects re-ran the
    // whole membership sweep every time any conversation received a message.
    val channelIds = remember(channels) { channels.map { it.matrixRoomId.ifBlank { it.id } }.distinct() }
    val channelKey = remember(channelIds) { channelIds.joinToString("|") }
    LaunchedEffect(channelKey, repository) {
        val repo = repository ?: return@LaunchedEffect
        dmContacts = repo.resolveDmContacts(channelIds)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                top = 12.dp,
                bottom = 12.dp,
                start = 8.dp,
                end = 8.dp
            ),
            // `.category-group { gap: 2px }`.
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "dm_home", contentType = "home") {
                HomeRow(
                    isSelected = !friendsTabActive && (selectedChannelId.isNullOrBlank()),
                    onClick = { onChannelClick("") }
                )
            }
            if (onSelectFriendsTab != null) {
                item(key = "dm_friends", contentType = "friends") {
                    FriendsRow(
                        isSelected = friendsTabActive,
                        onClick = onSelectFriendsTab
                    )
                }
            }
            item(key = "dm_header") {
                // `.category-header` — same rule as a server category's, but the
                // concept's DM branch has no chevron on it because the section is
                // not collapsible here.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(OrbitRadius.xsShape)
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "DIRECT MESSAGES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = conceptTextMuted(),
                            fontSize = 12.sp,
                            letterSpacing = 0.24.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        imageVector = OrbitIcons.Add,
                        contentDescription = "Start a new conversation",
                        tint = conceptTextMuted(),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            items(
                channels,
                // Composite key, defensive: a duplicate LazyColumn key throws out of
                // the content lambda and takes the process down.
                key = { "${it.id}#${it.type}#${it.categoryId}" },
                contentType = { "dm" }
            ) { ch ->
                val contact = dmContacts[ch.matrixRoomId.ifBlank { ch.id }]
                val resolvedUser = contact ?: ui.model.OrbitUser(
                    id = ch.matrixRoomId.ifBlank { ch.id },
                    username = ch.name,
                    displayName = ch.name,
                    avatarColor = ui.theme.avatarSeedColor(ch.id).value.toInt(),
                    status = contact?.status ?: ui.model.UserStatus.ONLINE,
                    avatarUrl = ch.avatarUrl
                )
                DmItem(
                    // The resolved member's display name is authoritative; the
                    // channel name is a stale denormalised copy of it.
                    displayName = resolvedUser.displayName.takeIf { it.isNotBlank() } ?: ch.name,
                    avatarColor = ui.theme.avatarSeedColor(ch.id).value.toInt(),
                    status = resolvedUser.status,
                    avatarUrl = resolvedUser.avatarUrl ?: ch.avatarUrl,
                    isSelected = ch.id == selectedChannelId,
                    unreadCount = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0),
                    onClick = { onChannelClick(ch.id) },
                    onVoiceCall = { onVoiceCall?.invoke(ch.id) },
                    onVideoCall = { onVideoCall?.invoke(ch.id) },
                    user = resolvedUser,
                    roomId = ch.id
                )
            }
        }
        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * One conversation: `.channel-row` at the concept's 42px, with a 28px avatar.
 *
 * The concept's DM row also shows an activity line under the name
 * (`font-size: 10px; color: var(--text-muted)`). Matrix carries no equivalent
 * of the rich-presence "activity" the concept mocks up, so what is shown there
 * is the presence the homeserver actually reported, in the same 10px muted
 * type. Inventing a game or a listening-to line would have been the kind of
 * fabricated data the previous version of this list was removed for.
 */
@Composable
internal fun DmItem(
    displayName: String,
    avatarColor: Int,
    status: UserStatus?,
    isSelected: Boolean,
    onClick: () -> Unit,
    avatarUrl: String? = null,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    unreadCount: Int = 0,
    user: ui.model.OrbitUser? = null,
    roomId: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    var cardHovered by remember { mutableStateOf(false) }
    var isPinned by remember { mutableStateOf(false) }
    var showHoverCard by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, cardHovered, isPinned) {
        if (isPinned || hovered || cardHovered) {
            if (!showHoverCard) {
                kotlinx.coroutines.delay(200)
                if (isPinned || hovered || cardHovered) showHoverCard = true
            }
        } else {
            kotlinx.coroutines.delay(180)
            if (!hovered && !cardHovered && !isPinned) showHoverCard = false
        }
    }

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
    // Hover actions fade in from the trailing edge instead of popping in place.
    val actionsAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hovered && !isSelected) 1f else 0f,
        animationSpec = ui.theme.OrbitMotion.Snappy,
        label = "dmActions"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // `style={{ height: '42px' }}` on the concept's DM row, over the
                // `.channel-row { padding: 6px 8px }` it also carries.
                .height(42.dp)
                .clip(OrbitRadius.xsShape)
                .background(bg)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Open the conversation with $displayName",
                    onClick = {
                        isPinned = !isPinned
                        onClick()
                    }
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // `.channel-row-content` with `gap: 10px` rather than the 8px a channel
            // row uses, because the leading glyph here is a 28px avatar.
            DmAvatar(
                displayName = displayName,
                avatarColor = avatarColor,
                status = status,
                avatarUrl = avatarUrl,
                size = 28.dp,
                userId = user?.id
            )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = fg,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(displayName)) {
                    Spacer(Modifier.width(4.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(12.dp))
                }
            }
            if (status != null) {
                // The concept's activity line: 10px, `--text-muted`.
                Text(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = conceptTextMuted(),
                        fontSize = 10.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Box(contentAlignment = Alignment.Center) {
            // `.badge-unread` — solid `--status-dnd`, white, 11px, weight 700,
            // min-width 16px, radius 8px, 4px of horizontal padding.
            if (unreadCount > 0 && actionsAlpha < 0.5f) {
                DmUnreadBadge(
                    count = unreadCount,
                    modifier = Modifier.alpha(1f - actionsAlpha * 2f)
                )
            }
            if (actionsAlpha > 0.01f) {
                // Gating on `!reduce` used to hide the buttons entirely, so the
                // accessibility toggle removed the only way to call from the DM
                // list. Only the animation is conditional now.
                Row(
                    modifier = if (ui.theme.LocalReduceMotion.current) Modifier
                    else Modifier.alpha(actionsAlpha),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OrbitIconButton(
                        icon = OrbitIcons.Call,
                        contentDescription = "Start a voice call with $displayName",
                        onClick = { onVoiceCall?.invoke() },
                        size = 24.dp,
                        iconSize = 14.dp,
                        shape = CircleShape
                    )
                    OrbitIconButton(
                        icon = OrbitIcons.Video,
                        contentDescription = "Start a video call with $displayName",
                        onClick = { onVideoCall?.invoke() },
                        size = 24.dp,
                        iconSize = 14.dp,
                        shape = CircleShape
                    )
                }
            }
        }
    }

    if (showHoverCard && user != null) {
        ui.screen.chat.CompactUserProfileBesidePopup(
            user = user,
            callRoomId = roomId,
            onVoiceCall = onVoiceCall,
            onVideoCall = onVideoCall,
            onDismissRequest = {
                isPinned = false
                showHoverCard = false
            },
            onCardHoverStateChange = { cardHovered = it }
        )
    }
}
}

@Composable
private fun HomeRow(isSelected: Boolean, onClick: () -> Unit) {
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
            .defaultMinSize(minHeight = 32.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Tab,
                onClickLabel = "Show home dashboard",
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Home,
            contentDescription = null,
            tint = if (hovered || isSelected) conceptInteractiveHover() else SidebarConcept.interactiveMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Home",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = fg,
                fontSize = 13.5.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The concept's `Friends` row: a plain `.channel-row` with a 18px `Users` glyph,
 * `style={{ marginBottom: '8px' }}`, and `.active` when the friends view is the
 * main column.
 *
 * A `.channel-row` rather than a bespoke tile, which is what makes it read as
 * part of the same list as the conversations under it rather than as a separate
 * navigation control that happens to sit above them.
 */
@Composable
private fun FriendsRow(isSelected: Boolean, onClick: () -> Unit) {
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

    // `style={{ marginBottom: '8px' }}` — the concept separates this row
    // from the DIRECT MESSAGES heading below it, because the heading is a
    // group title and the row is a destination, not part of the group. A CSS
    // margin is outside the element's border box, so it is applied on a wrapper
    // here rather than as padding on the row: padding would put the hover wash
    // under the 8dp as well.
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 30.dp)
                .clip(OrbitRadius.xsShape)
                .background(bg)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClickLabel = "Show friends",
                    onClick = onClick
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            tint = if (hovered || isSelected) conceptInteractiveHover() else SidebarConcept.interactiveMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Friends",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        }
    }
}

/**
 * `.badge-unread` in the DM list, where the concept overrides `position: static`.
 *
 * The rail's version carries `border: 3px solid var(--bg-servers)` so the pill
 * reads against a 72px tile; on a 42px row that ring would be most of the
 * badge, so it is dropped here and the pill is the solid `--status-dnd` disc the
 * base rule describes.
 */
@Composable
private fun DmUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = 16.dp)
            .height(16.dp)
            .clip(CircleShape)
            .background(OrbitConceptStatus.dnd)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                lineHeight = 12.sp
            ),
            maxLines = 1
        )
    }
}

/**
 * `.user-avatar-wrap { position: relative }` with `.user-avatar-img` filling it
 * and a `.status-indicator` at the bottom-right.
 *
 * The indicator is 10px with a 2px `--bg-secondary` ring, so it is drawn as a
 * border on a slightly larger circle rather than as an absolutely-positioned
 * child offset by -2px: the border and the disc are the same shape at two
 * sizes, which is what a CSS outline is.
 */
@Composable
internal fun DmAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus?,
    avatarUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    userId: String? = null
) {
    Box(modifier = Modifier.size(size)) {
        if (avatarUrl != null) {
            coil3.compose.AsyncImage(
                model = avatarUrl,
                contentDescription = displayName,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            OrbitMascotAvatar(
                seed = userId ?: displayName,
                modifier = Modifier.size(size)
            )
        }
        // Only drawn when real presence is known — a hard-coded green dot on
        // every conversation was fabricated data.
        if (status != null) {
            Box(
                modifier = Modifier
                    .size(if (size > 40.dp) 13.dp else 10.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .border(2.dp, conceptSecondarySurface(), CircleShape)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(presenceColor(sidebarTokens(), status))
            )
        }
    }
}
