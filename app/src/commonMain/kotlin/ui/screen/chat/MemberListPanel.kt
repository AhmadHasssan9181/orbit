package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import repository.MemberRoleInfo
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitDialog
import ui.components.OrbitIconButton
import ui.components.OrbitListRow
import ui.components.OrbitMenu
import ui.components.OrbitMenuDivider
import ui.components.OrbitMenuItem
import ui.components.OrbitRowLeading
import ui.components.OrbitScrollbar
import ui.model.OrbitUser
import ui.screen.SidePanelHeader
import ui.model.UserStatus
import ui.theme.OrbitMetrics
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Member list.
 *
 * Rebuilt on `OrbitListRow` so it matches the channel and DM lists row for
 * row — they used to differ by 6dp in height, had different hover colours and
 * put a Material `DropdownMenu` in one and a hand-rolled one in the other. The
 * moderation actions also moved into the shared menu, which finally themes with
 * the rest of the app instead of staying Material-purple in the light theme.
 */
@Composable
internal fun MemberListPanel(
    members: List<OrbitUser>,
    observePresence: (suspend (String) -> kotlinx.coroutines.flow.Flow<UserStatus>)? = null,
    memberRoles: Map<String, MemberRoleInfo> = emptyMap(),
    ownUserId: String? = null,
    onKickUser: ((String) -> Unit)? = null,
    onBanUser: ((String) -> Unit)? = null,
    onSetPowerLevel: ((String, Long) -> Unit)? = null,
    onSelectUser: ((OrbitUser) -> Unit)? = null,
    /**
     * Non-null while a call is LIVE in this room. The panel then answers "who is
     * in the call", not "who is in the room": the room roster is a static
     * membership list that is empty in DMs and unchanged for the whole call, so
     * a 2-person call rendered `Members 0` next to a live call surface. This
     * list is already reconciled against the LiveKit room, so it cannot show a
     * ghost. The header count below reads from the SAME list the rows render,
     * which is what keeps the badge and the list from disagreeing.
     */
    liveCallMembers: List<OrbitUser>? = null
) {
    val t = rememberCosmicTokens()
    val allUsers = liveCallMembers ?: members
    val listState = rememberLazyListState()

    // derivedStateOf, NOT remember(members, memberRoles) { ... }: callers hand us
    // collections that are frequently SnapshotStateMap / SnapshotStateList
    // instances, and those implement Map/List WITHOUT overriding equals, so
    // `remember` keys on REFERENCE IDENTITY — a fresh-but-equal map re-ran the
    // block on every parent recomposition, and a map mutated IN PLACE never
    // re-ran it at all. rememberUpdatedState parks the current inputs in a real
    // MutableState, so reading them inside the derivedStateOf block is a genuine
    // snapshot read: it re-evaluates on content change and notifies downstream
    // only when the resulting List actually differs (which also removes the
    // per-recomposition allocation the three filter/sortedBy passes made).
    val currentUsers by rememberUpdatedState(allUsers)
    val currentRoles by rememberUpdatedState(memberRoles)
    val showRoleSectionsState = remember {
        derivedStateOf { currentRoles.values.any { it.powerLevel > 0 } }
    }
    val sectionsState = remember {
        derivedStateOf {
            buildMemberSections(currentUsers, currentRoles, showRoleSectionsState.value)
        }
    }
    val showRoleSections by showRoleSectionsState
    val sections by sectionsState

    // Counted from the DEDUPED rows, not from `allUsers`.
    //
    // The badge and the rows used to come from different lists, so a duplicate
    // inflated the badge while the extra row was invisible. Reading the count off
    // the sections means it cannot disagree with what is on screen even when the
    // input list is malformed.
    val rowCount = sections.sumOf { it.users.size }

    Row(
        modifier = Modifier
            .width(OrbitMetrics.sidePanelWidth)
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
                .background(t.Members_Bg)
        ) {
        SidePanelHeader(
            icon = ui.icons.OrbitIcons.Members,
            // "In call" while a call is live, because the list below is the call
            // roster and not the room membership. A "Members" header over a
            // participant list is how a 2-person call ended up reading "Members 0".
            title = if (liveCallMembers != null) "In call" else "Members",
            // Counted from the rows that actually render, post-dedupe. It used to
            // read `allUsers.size`, which disagreed with the list the moment the
            // input carried a duplicate id.
            count = rowCount
        )
        androidx.compose.material3.HorizontalDivider(color = t.Separator, thickness = 1.dp)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = OrbitSpace.sm,
                    bottom = OrbitSpace.xxl,
                    start = OrbitSpace.sm,
                    end = OrbitSpace.sm
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                sections.forEach { section ->
                    item(key = "section-${section.key}", contentType = "header") {
                        MemberSectionLabel(section.label, section.users.size)
                    }
                    items(section.users, key = { it.id }, contentType = { "member" }) { user ->
                        MemberItem(
                            user,
                            observePresence,
                            if (showRoleSections) currentRoles[user.id] else null,
                            ownUserId,
                            onKickUser,
                            onBanUser,
                            onSetPowerLevel,
                            onSelectUser
                        )
                    }
                }
            }
            OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
        }
    }
}

/** One heading plus its rows. Built once per roster/role change, not per frame. */
internal class MemberSection(val key: String, val label: String, val users: List<OrbitUser>)

/**
 * Drop repeated user ids, and say so.
 *
 * Every row below is keyed by `it.id`, so a repeated id is not a cosmetic bug:
 * Compose throws `Key "@user:server" was already used` out of the lazy layout,
 * and because the call roster is re-emitted every 3 seconds by the poller in
 * `MatrixCallsRepository`, the throw came back every 3 seconds for the length of
 * the call. The user heard Windows' error-dialog beep as a tick and had no way to
 * get a log out of the app -- stdout is discarded by the GUI-subsystem launcher.
 *
 * ## Why dedupe here rather than at the sources
 *
 * Both feeds are supposed to be unique already -- the room roster is
 * `getJoinedMembers().joined.keys`, a Map, and the call roster is built with
 * `currentMembers.none { it.id == sender }`. Yet the duplicate reached the screen
 * from a real group call, which means at least one path we did not read hands us
 * a repeated id. Guaranteeing the invariant at the ONE point every row funnels
 * through closes that off for every current and future source, instead of moving
 * the same bug to whichever list is added next.
 *
 * First occurrence wins, so display order (and therefore the section a person
 * lands in) is unchanged.
 *
 * WARN, not silent: the ids are logged so a report from a user's machine names
 * the duplicate instead of leaving us to re-derive it. This is a data-shape bug
 * upstream, not something this panel can fix, so it must stay visible.
 */
internal fun dedupeByUserId(users: List<OrbitUser>): List<OrbitUser> {
    if (users.size < 2) return users
    val seen = HashSet<String>(users.size)
    val duplicates = ArrayList<String>()
    val out = ArrayList<OrbitUser>(users.size)
    for (user in users) {
        if (seen.add(user.id)) out += user else duplicates += user.id
    }
    if (duplicates.isNotEmpty()) {
        util.OrbitLog.w(
            "MemberListPanel",
            "dropped ${duplicates.size} duplicate roster entr(ies) sharing a user id: " +
                duplicates.distinct().joinToString().take(500)
        )
    }
    return out
}

internal fun buildMemberSections(
    members: List<OrbitUser>,
    memberRoles: Map<String, MemberRoleInfo>,
    showRoleSections: Boolean
): List<MemberSection> {
    val members = dedupeByUserId(members)
    if (showRoleSections) {
        val admins = members.filter { (memberRoles[it.id]?.powerLevel ?: 0L) >= 100L }
            .sortedBy { presenceRank(it.status) }
        val mods = members.filter {
            val pl = memberRoles[it.id]?.powerLevel ?: 0L
            pl in 50..99
        }.sortedBy { presenceRank(it.status) }
        val plainMembers = members.filter { (memberRoles[it.id]?.powerLevel ?: 0L) < 50L }
            .sortedBy { presenceRank(it.status) }
        return buildList {
            if (admins.isNotEmpty()) add(MemberSection("admin", "Admins", admins))
            if (mods.isNotEmpty()) add(MemberSection("mods", "Moderators", mods))
            if (plainMembers.isNotEmpty()) add(MemberSection("members", "Members", plainMembers))
        }
    }

    val online = members.filter { it.status == UserStatus.ONLINE }
    val idle = members.filter { it.status == UserStatus.IDLE }
    val dnd = members.filter { it.status == UserStatus.DO_NOT_DISTURB }
    val offline = members.filter { it.status == UserStatus.OFFLINE }
    return buildList {
        if (online.isNotEmpty()) add(MemberSection("s-online", "Online", online))
        if (idle.isNotEmpty()) add(MemberSection("s-idle", "Idle", idle))
        if (dnd.isNotEmpty()) add(MemberSection("s-dnd", "Do not disturb", dnd))
        if (offline.isNotEmpty()) add(MemberSection("s-offline", "Offline", offline))
    }
}

private fun presenceRank(status: UserStatus): Int = when (status) {
    UserStatus.ONLINE -> 0
    UserStatus.IDLE -> 1
    UserStatus.DO_NOT_DISTURB -> 2
    UserStatus.OFFLINE -> 3
}

private fun roleLabel(powerLevel: Long): String = when {
    powerLevel >= 100L -> "Admin"
    powerLevel >= 50L -> "Moderator"
    else -> "Member"
}

@Composable
private fun roleColor(powerLevel: Long): Color {
    val t = rememberCosmicTokens()
    return when {
        powerLevel >= 100L -> t.Danger
        powerLevel >= 50L -> t.Warning
        else -> t.Text_Muted
    }
}

/** Live presence per visible row: sections regroup on the next member refresh,
 *  but dots and labels track homeserver presence while composed. */
@Composable
private fun rememberLiveStatus(
    user: OrbitUser,
    observePresence: (suspend (String) -> kotlinx.coroutines.flow.Flow<UserStatus>)?
): UserStatus {
    var status by remember(user.id) { mutableStateOf(user.status) }
    LaunchedEffect(user.id) {
        val flow = runCatching { observePresence?.invoke(user.id) }.getOrNull() ?: return@LaunchedEffect
        runCatching {
            flow.collect {
                if (it != status) status = it
            }
        }
    }
    return status
}

@Composable
private fun MemberSectionLabel(label: String, count: Int) {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // `.member-group-header` — 12px, weight 700, `--text-muted`, uppercase,
        // 0.02em tracking, 4px/8px of padding. It was 10px at SemiBold with
        // 0.6em tracking, which is a different heading: smaller, lighter and
        // tracked out so wide it read as a caption rather than a group name.
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                color = t.Text_Muted,
                fontSize = 12.sp,
                letterSpacing = 0.24.sp
            )
        )
        // The count is Orbit's addition and is deliberately kept: the header
        // above the list says how many there are, and having the same number
        // again here is how a roster can be checked at a glance.
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.Text_Subtle,
                fontSize = 10.sp
            )
        )
    }
}

@Composable
private fun MemberItem(
    user: OrbitUser,
    observePresence: (suspend (String) -> kotlinx.coroutines.flow.Flow<UserStatus>)? = null,
    roleInfo: MemberRoleInfo? = null,
    ownUserId: String? = null,
    onKickUser: ((String) -> Unit)? = null,
    onBanUser: ((String) -> Unit)? = null,
    onSetPowerLevel: ((String, Long) -> Unit)? = null,
    onSelectUser: ((OrbitUser) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val liveStatus = rememberLiveStatus(user, observePresence)

    val pl = roleInfo?.powerLevel ?: 0L
    val isSelf = ownUserId != null &&
        (user.id == ownUserId || user.id.endsWith(ownUserId) || ownUserId.endsWith(user.id))
    val kickVisible = !isSelf && onKickUser != null && roleInfo?.canKick == true
    val banVisible = !isSelf && onBanUser != null && roleInfo?.canBan == true
    val promoteVisible = !isSelf && onSetPowerLevel != null && pl < 50L &&
        (roleInfo?.maxSettablePowerLevel ?: 0L) >= 50L
    val demoteVisible = !isSelf && onSetPowerLevel != null && pl >= 50L &&
        (roleInfo?.maxSettablePowerLevel ?: 0L) >= 50L
    val anyAction = kickVisible || banVisible || promoteVisible || demoteVisible

    var moderationMenuOpen by remember(user.id) { mutableStateOf(false) }
    var banConfirmOpen by remember(user.id) { mutableStateOf(false) }

    if (banConfirmOpen) {
        OrbitDialog(
            title = "Ban ${user.displayName}?",
            subtitle = "They will be removed from this room and unable to rejoin without an invite.",
            icon = Icons.Default.Block,
            onDismissRequest = { banConfirmOpen = false },
            confirmLabel = "Ban",
            confirmTone = OrbitButtonTone.Danger,
            onConfirm = {
                banConfirmOpen = false
                onBanUser?.invoke(user.id)
            },
            width = 400.dp
        ) { }
    }

    OrbitListRow(
        title = user.displayName,
        // Was `onClick = {}`: every row looked strongly interactive and did
        // nothing. Now the row is only clickable when there is a profile to open.
        onClick = onSelectUser?.let { open -> { open(user) } },
        // `.member-item-row { padding: 6px 8px; gap: 10px }` around a 32px
        // avatar, which is 44px of row. It was 40px with 6px of side padding, so
        // the roster was 4px shorter per row and its names sat 2px further in than
        // the channel list's.
        height = 44.dp,
        showSelectionBar = false,
        role = null,
        horizontalPadding = 8.dp,
        titleStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (liveStatus == UserStatus.OFFLINE) t.Text_Subtle else t.Text_Secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        ),
        leading = OrbitRowLeading(
            // `.member-avatar-wrap` — 32px, with a 10px presence dot ringed in 2px
            // of the surface the list is painted on. Was 28px with a 9px unringed
            // dot, so the dot touched the avatar and the roster read a size smaller
            // than the concept's.
            avatar = {
                MemberAvatarWithStatus(
                    user = user,
                    status = liveStatus,
                    ringColor = t.Members_Bg
                )
            }
        ),
        trailing = {
            if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                OrbitVerifiedBadge(modifier = Modifier.size(12.dp))
            }
            if (pl > 0L) {
                MemberRolePill(label = roleLabel(pl), color = roleColor(pl))
            }
            if (anyAction) {
                OrbitIconButton(
                    icon = Icons.Default.MoreVert,
                    contentDescription = "Moderate ${user.displayName}",
                    onClick = { moderationMenuOpen = true },
                    size = 24.dp,
                    iconSize = 14.dp,
                    shape = OrbitRadius.xsShape
                )
                OrbitMenu(
                    expanded = moderationMenuOpen,
                    onDismissRequest = { moderationMenuOpen = false },
                    width = 208.dp
                ) {
                    if (promoteVisible) {
                        OrbitMenuItem(
                            label = "Promote to Moderator",
                            onClick = {
                                moderationMenuOpen = false
                                onSetPowerLevel?.invoke(user.id, 50L)
                            }
                        )
                    }
                    if (demoteVisible) {
                        OrbitMenuItem(
                            label = "Remove Moderator",
                            onClick = {
                                moderationMenuOpen = false
                                onSetPowerLevel?.invoke(user.id, 0L)
                            }
                        )
                    }
                    if (kickVisible || banVisible) OrbitMenuDivider()
                    if (kickVisible) {
                        OrbitMenuItem(
                            label = "Kick",
                            icon = Icons.Default.PersonRemove,
                            danger = true,
                            onClick = {
                                moderationMenuOpen = false
                                onKickUser?.invoke(user.id)
                            }
                        )
                    }
                    if (banVisible) {
                        OrbitMenuItem(
                            label = "Ban",
                            icon = Icons.Default.Block,
                            danger = true,
                            onClick = {
                                moderationMenuOpen = false
                                banConfirmOpen = true
                            }
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun MemberRolePill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(OrbitRadius.xsShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp
            ),
            maxLines = 1
        )
    }
}
