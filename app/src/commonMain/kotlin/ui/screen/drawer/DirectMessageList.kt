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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * Direct messages.
 *
 * The DM row is the densest list in the app and it was carrying the most
 * inconsistency: a `#F23F42` unread pill, a 20dp call/close button pair that
 * only existed on hover, and a "close" icon that did nothing at all. It now
 * matches `OrbitListRow` exactly, and the hover actions are real call buttons
 * that fade in from the right instead of popping.
 */
@Composable
internal fun DirectMessageSearchBar() {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = if (hovered) t.CardBgHover else t.Input_Bg,
        animationSpec = OrbitMotion.SnappyColor,
        label = "dmSearchBg"
    )
    val fg by animateColorAsState(
        targetValue = if (hovered) t.Text_White else t.Text_Muted,
        animationSpec = OrbitMotion.SnappyColor,
        label = "dmSearchText"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.sm)
            .height(32.dp)
            .clip(OrbitRadius.mdShape)
            .background(bg)
            .border(1.dp, t.Separator, OrbitRadius.mdShape)
            .hoverable(interaction)
            .padding(horizontal = OrbitSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        androidx.compose.material3.Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = t.Text_Subtle,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Find or start a conversation",
            style = MaterialTheme.typography.bodySmall.copy(
                color = fg,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DirectMessageNav() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.xxs),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        DirectMessageNavItem(Icons.Default.People, "Friends")
        DirectMessageNavItem(Icons.Default.Star, "Nitro")
        DirectMessageNavItem(Icons.Default.Storefront, "Shop")
        DirectMessageNavItem(Icons.Default.Explore, "Quests")
    }
}

@Composable
internal fun DirectMessageNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    OrbitNavStub(icon = icon, label = label)
}

@Composable
private fun OrbitNavStub(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = if (hovered) t.ChannelHover_Bg else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "navBg"
    )
    val fg by animateColorAsState(
        targetValue = if (hovered) t.Text_White else t.Text_Muted,
        animationSpec = OrbitMotion.SnappyColor,
        label = "navFg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(OrbitRadius.smShape)
            .background(bg)
            .hoverable(interaction)
            .padding(horizontal = OrbitSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(17.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DmListContent(
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    onChannelClick: (String) -> Unit,
    onVoiceCall: ((String) -> Unit)? = null,
    onVideoCall: ((String) -> Unit)? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()

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
            if (!isLiteMode) {
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
            }
            items(channels, key = { it.id }) { ch ->
                DmItem(
                    displayName = ch.name,
                    avatarColor = ui.theme.avatarSeedColor(ch.id).value.toInt(),
                    status = UserStatus.ONLINE,
                    isSelected = ch.id == selectedChannelId,
                    unreadCount = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0),
                    onClick = { onChannelClick(ch.id) },
                    onVoiceCall = { onVoiceCall?.invoke(ch.id) },
                    onVideoCall = { onVideoCall?.invoke(ch.id) },
                    lastMessage = ch.topic.ifBlank { "Start a conversation" }
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
    status: UserStatus,
    isSelected: Boolean,
    onClick: () -> Unit,
    onVoiceCall: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    lastMessage: String = "Start a conversation",
    time: String = "",
    unreadCount: Int = 0
) {
    val t = rememberCosmicTokens()
    val isLiteMode = ui.theme.LocalIsLiteMode.current
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

    if (isLiteMode) {
        LiteDmItem(
            displayName = displayName,
            avatarColor = avatarColor,
            status = status,
            isSelected = isSelected,
            unreadCount = unreadCount,
            lastMessage = lastMessage,
            time = time,
            onClick = onClick
        )
        return
    }

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
        DmAvatar(displayName, avatarColor, status, size = 30.dp)
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
            if (!reduce && actionsAlpha > 0.01f) {
                Row(
                    modifier = Modifier.alpha(actionsAlpha),
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
private fun LiteDmItem(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    isSelected: Boolean,
    unreadCount: Int,
    lastMessage: String,
    time: String,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = when {
            isSelected -> t.AccentGhost
            hovered -> t.Hover
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "liteDmBg"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clip(OrbitRadius.mdShape)
            .background(bg)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DmAvatar(displayName, avatarColor, status, size = 44.dp)
            Spacer(Modifier.width(OrbitSpace.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = t.Text_White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOrbitVerifiedUser(displayName)) {
                        Spacer(Modifier.width(4.dp))
                        OrbitVerifiedBadge(modifier = Modifier.size(14.dp))
                    }
                    if (time.isNotBlank()) {
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 11.sp),
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(1.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = lastMessage,
                        style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (unreadCount > 0) {
                        OrbitCountBadge(count = unreadCount, color = t.Accent)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DmAvatar(
    displayName: String,
    avatarColor: Int,
    status: UserStatus,
    size: androidx.compose.ui.unit.Dp = 32.dp
) {
    val t = rememberCosmicTokens()
    Box(modifier = Modifier.size(size)) {
        OrbitDefaultAvatar(
            label = displayName,
            seed = displayName,
            color = Color(avatarColor),
            modifier = Modifier.size(size)
        )
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 13.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = 1.dp)
                .border(2.dp, t.Drawer_Bg, CircleShape)
                .padding(1.5.dp)
                .clip(CircleShape)
                .background(statusColor(status))
        )
    }
}
