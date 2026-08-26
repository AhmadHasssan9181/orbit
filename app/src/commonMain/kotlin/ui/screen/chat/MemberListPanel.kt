package ui.screen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.model.OrbitUser
import ui.model.UserStatus

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun MemberListPanel(
    members: List<OrbitUser>,
    observePresence: (suspend (String) -> kotlinx.coroutines.flow.Flow<UserStatus>)? = null
) {
    val allUsers  = members
    val online    = allUsers.filter { it.status == UserStatus.ONLINE }
    val idle      = allUsers.filter { it.status == UserStatus.IDLE }
    val dnd       = allUsers.filter { it.status == UserStatus.DO_NOT_DISTURB }
    val offline   = allUsers.filter { it.status == UserStatus.OFFLINE }

    Column(
        modifier = Modifier
            .width(224.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            if (online.isNotEmpty()) {
                item { MemberSectionLabel("ONLINE — ${online.size}") }
                items(online, key = { it.id }) { MemberItem(it, observePresence) }
            }
            if (idle.isNotEmpty()) {
                item { MemberSectionLabel("IDLE — ${idle.size}") }
                items(idle, key = { it.id }) { MemberItem(it, observePresence) }
            }
            if (dnd.isNotEmpty()) {
                item { MemberSectionLabel("DO NOT DISTURB") }
                items(dnd, key = { it.id }) { MemberItem(it, observePresence) }
            }
            if (offline.isNotEmpty()) {
                item { MemberSectionLabel("OFFLINE — ${offline.size}") }
                items(offline, key = { it.id }) { MemberItem(it, observePresence) }
            }
        }
    }
}

// Live presence per visible row: section headers regroup on the next member
// refresh, but dots/labels track homeserver presence while composed.
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
private fun MemberSectionLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            color = Text_Muted,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp
        ),
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

@Composable
private fun MemberItem(
    user: OrbitUser,
    observePresence: (suspend (String) -> kotlinx.coroutines.flow.Flow<UserStatus>)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) MessageHover_Bg else Color.Transparent,
        label = "memberHover"
    )
    val liveStatus = rememberLiveStatus(user, observePresence)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar + status dot
        Box(modifier = Modifier.size(32.dp)) {
            if (user.avatarUrl != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                        .padding(1.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    coil3.compose.AsyncImage(
                        model = user.avatarUrl,
                        contentDescription = user.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                OrbitDefaultAvatar(
                    label = user.displayName,
                    seed = user.id,
                    color = Color(user.avatarColor),
                    modifier = Modifier.fillMaxSize()
                )
            }
            val dotColor = memberStatusColor(liveStatus)
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .align(Alignment.BottomEnd)
                    .border(2.dp, Members_Bg, CircleShape)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (liveStatus == UserStatus.OFFLINE)
                            Text_Muted.copy(alpha = 0.6f) else Text_Secondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(user.username, user.displayName, user.id)) {
                    Spacer(Modifier.width(4.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            MemberRolePill(label = "Member", color = Color(user.avatarColor))
        }
    }
}

@Composable
private fun MemberRolePill(label: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1
        )
    }
}
