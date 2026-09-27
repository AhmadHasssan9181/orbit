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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonRemove
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import repository.MemberRoleInfo
import ui.assets.OrbitDefaultAvatar
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
    onSetPowerLevel: ((String, Long) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val allUsers = members
    val rolesActive = memberRoles.values.any { it.powerLevel > 0 }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .width(OrbitMetrics.sidePanelWidth)
            .fillMaxHeight()
            .background(t.Members_Bg)
            .border(1.dp, t.Separator)
    ) {
        SidePanelHeader(
            icon = ui.icons.OrbitIcons.Members,
            title = "Members",
            count = allUsers.size
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
                if (rolesActive) {
                    val admins = allUsers.filter { (memberRoles[it.id]?.powerLevel ?: 0L) >= 100L }
                        .sortedBy { presenceRank(it.status) }
                    val mods = allUsers.filter {
                        val pl = memberRoles[it.id]?.powerLevel ?: 0L
                        pl in 50..99
                    }.sortedBy { presenceRank(it.status) }
                    val plainMembers = allUsers.filter { (memberRoles[it.id]?.powerLevel ?: 0L) < 50L }
                        .sortedBy { presenceRank(it.status) }

                    if (admins.isNotEmpty()) {
                        item(key = "section-admin") { MemberSectionLabel("Admins", admins.size) }
                        items(admins, key = { it.id }) {
                            MemberItem(it, observePresence, memberRoles[it.id], ownUserId, onKickUser, onBanUser, onSetPowerLevel)
                        }
                    }
                    if (mods.isNotEmpty()) {
                        item(key = "section-mods") { MemberSectionLabel("Moderators", mods.size) }
                        items(mods, key = { it.id }) {
                            MemberItem(it, observePresence, memberRoles[it.id], ownUserId, onKickUser, onBanUser, onSetPowerLevel)
                        }
                    }
                    if (plainMembers.isNotEmpty()) {
                        item(key = "section-members") { MemberSectionLabel("Members", plainMembers.size) }
                        items(plainMembers, key = { it.id }) {
                            MemberItem(it, observePresence, memberRoles[it.id], ownUserId, onKickUser, onBanUser, onSetPowerLevel)
                        }
                    }
                } else {
                    val online = allUsers.filter { it.status == UserStatus.ONLINE }
                    val idle = allUsers.filter { it.status == UserStatus.IDLE }
                    val dnd = allUsers.filter { it.status == UserStatus.DO_NOT_DISTURB }
                    val offline = allUsers.filter { it.status == UserStatus.OFFLINE }

                    if (online.isNotEmpty()) {
                        item(key = "s-online") { MemberSectionLabel("Online", online.size) }
                        items(online, key = { it.id }) { MemberItem(it, observePresence) }
                    }
                    if (idle.isNotEmpty()) {
                        item(key = "s-idle") { MemberSectionLabel("Idle", idle.size) }
                        items(idle, key = { it.id }) { MemberItem(it, observePresence) }
                    }
                    if (dnd.isNotEmpty()) {
                        item(key = "s-dnd") { MemberSectionLabel("Do not disturb", dnd.size) }
                        items(dnd, key = { it.id }) { MemberItem(it, observePresence) }
                    }
                    if (offline.isNotEmpty()) {
                        item(key = "s-offline") { MemberSectionLabel("Offline", offline.size) }
                        items(offline, key = { it.id }) { MemberItem(it, observePresence) }
                    }
                }
            }
            OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
        }
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
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = t.Text_Muted,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp
            )
        )
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
    onSetPowerLevel: ((String, Long) -> Unit)? = null
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
        onClick = {},
        height = 40.dp,
        showSelectionBar = false,
        role = null,
        horizontalPadding = 6.dp,
        titleStyle = MaterialTheme.typography.bodyMedium.copy(
            color = if (liveStatus == UserStatus.OFFLINE) t.Text_Subtle else t.Text_Secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal
        ),
        leading = OrbitRowLeading(
            avatar = {
                Box(modifier = Modifier.size(28.dp)) {
                    if (user.avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = user.displayName,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(CircleShape)
                        )
                    } else {
                        OrbitDefaultAvatar(
                            label = user.displayName,
                            seed = user.id,
                            color = Color(user.avatarColor),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(memberStatusColor(liveStatus))
                    )
                }
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
