package ui.screen.drawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.components.OrbitCountBadge
import ui.components.OrbitIconButton
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.OrbitChannel
import ui.model.UserStatus
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

@Composable
internal fun DmListContent(
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onVoiceCall: ((String) -> Unit)? = null,
    onVideoCall: ((String) -> Unit)? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    repository: repository.OrbitRepository? = null,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
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
                top = OrbitSpace.xs,
                bottom = OrbitSpace.lg,
                start = OrbitSpace.sm,
                end = OrbitSpace.sm
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            item(key = "dm_header") {
                Text(
                    text = "DIRECT MESSAGES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = t.Text_Muted,
                        letterSpacing = 0.7.sp,
                        fontSize = 10.5.sp
                    ),
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp)
                )
            }
            items(
            channels,
            // Composite key, defensive: a duplicate LazyColumn key throws out of
            // the content lambda and takes the process down.
            key = { "${it.id}#${it.type}#${it.categoryId}" },
            contentType = { "dm" }
        ) { ch ->
                val contact = dmContacts[ch.matrixRoomId.ifBlank { ch.id }]
                DmItem(
                    // The resolved member's display name is authoritative; the
                    // channel name is a stale denormalised copy of it.
                    displayName = contact?.displayName?.takeIf { it.isNotBlank() } ?: ch.name,
                    avatarColor = ui.theme.avatarSeedColor(ch.id).value.toInt(),
                    // Null (unknown) rather than a fabricated ONLINE when the
                    // other member could not be resolved.
                    status = contact?.status,
                    avatarUrl = contact?.avatarUrl ?: ch.avatarUrl,
                    isSelected = ch.id == selectedChannelId,
                    unreadCount = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0),
                    onClick = { onChannelClick(ch.id) },
                    onVoiceCall = { onVoiceCall?.invoke(ch.id) },
                    onVideoCall = { onVideoCall?.invoke(ch.id) }
                )
            }
        }
        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

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
    unreadCount: Int = 0
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current

    val bg by animateColorAsState(
        targetValue = when {
            isSelected -> t.ChannelSelected_Bg
            hovered -> t.ChannelHover_Bg
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "dmBg"
    )
    val fg by animateColorAsState(
        targetValue = when {
            isSelected -> t.Text_White
            hovered -> t.Text_Secondary
            else -> t.Text_Muted
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "dmFg"
    )
    // Hover actions slide in from the trailing edge instead of popping in place.
    val actionsAlpha by animateFloatAsState(
        targetValue = if (hovered && !isSelected) 1f else 0f,
        animationSpec = OrbitMotion.Snappy,
        label = "dmActions"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 1.dp)
            .clip(OrbitRadius.smShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(start = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DmAvatar(displayName, avatarColor, status, avatarUrl = avatarUrl, size = 30.dp)
        Spacer(Modifier.width(OrbitSpace.sm))
        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.5.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (isOrbitVerifiedUser(displayName)) {
            Spacer(Modifier.width(4.dp))
            OrbitVerifiedBadge(modifier = Modifier.size(12.dp))
        }

        Box(contentAlignment = Alignment.Center) {
            // Unread pill fades out as the call actions fade in.
            if (unreadCount > 0 && actionsAlpha < 0.5f) {
                OrbitCountBadge(
                    count = unreadCount,
                    color = t.Danger,
                    modifier = Modifier.alpha(1f - actionsAlpha * 2f)
                )
            }
            if (actionsAlpha > 0.01f) {
                // Gating on `!reduce` used to hide the buttons entirely, so the
                // accessibility toggle removed the only way to call from the DM
                // list. Only the animation is conditional now.
                Row(
                    modifier = if (reduce) Modifier else Modifier.alpha(actionsAlpha),
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
}

@Composable
internal fun DmAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus?,
    avatarUrl: String? = null,
    size: androidx.compose.ui.unit.Dp = 32.dp
) {
    val t = rememberCosmicTokens()
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
            OrbitDefaultAvatar(
                label = displayName,
                seed = displayName,
                color = Color(avatarColor),
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
                    .border(2.dp, t.Drawer_Bg, CircleShape)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(presenceColor(t, status))
            )
        }
    }
}
