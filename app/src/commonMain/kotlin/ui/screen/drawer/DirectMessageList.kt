package ui.screen.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitDefaultAvatar
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.icons.OrbitIcons
import ui.model.OrbitChannel
import ui.model.UserStatus

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun DirectMessageSearchBar() {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0xFF15161A) else Color(0xFF111214),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmSearchBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) Text_White.copy(alpha = 0.12f) else Separator.copy(alpha = 0.34f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmSearchBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isHovered) Text_White.copy(alpha = 0.82f) else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmSearchText"
    )
    val textOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmSearchTextOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "Find or start a conversation",
            style = MaterialTheme.typography.bodySmall.copy(
                color = textColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset(x = textOffset)
        )
    }
}

@Composable
internal fun DirectMessageNav() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
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
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) ChannelHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmNavBg"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmNavIcon"
    )
    val textColor by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmNavText"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmNavOffset"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset(x = rowOffset)
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor,
                fontSize = 16.sp,
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
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 5.dp, horizontal = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (!isLiteMode) {
            item {
                Text(
                    text = "DIRECT MESSAGES",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = Text_Muted,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp)
                )
            }
        }
        items(channels, key = { it.id }) { ch ->
            DmItem(
                displayName = ch.name,
                avatarColor = 0xFF7C6AF0.toInt(), // Replace with hashToColor or actual if you have it
                status = UserStatus.ONLINE, // Placeholder
                isSelected = ch.id == selectedChannelId,
                unreadCount = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0),
                onClick = { onChannelClick(ch.id) },
                onVoiceCall = { onVoiceCall?.invoke(ch.id) },
                onVideoCall = { onVideoCall?.invoke(ch.id) },
                lastMessage = ch.topic.ifBlank { "Tap to chat with ${ch.name}" },
                time = ""
            )
        }
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
    lastMessage: String = "Tap to chat with $displayName",
    time: String = "12:00 PM",
    unreadCount: Int = 0
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val cardShape = RoundedCornerShape(if (isLiteMode) 14.dp else 10.dp)

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> Accent.copy(alpha = 0.16f)
            isHovered  -> ChannelHover_Bg
            else       -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> Accent.copy(alpha = 0.42f)
            isHovered -> Text_White.copy(alpha = 0.10f)
            else -> Text_White.copy(alpha = 0.04f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmBorder"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isHovered) Text_White else Text_Secondary,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmText"
    )
    val indicatorAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            isHovered -> 0.45f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmIndicator"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isSelected || isHovered) 3.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmRowOffset"
    )
    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isSelected -> if (isLiteMode) 34.dp else 24.dp
            isHovered -> if (isLiteMode) 22.dp else 16.dp
            else -> 0.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "dmIndicatorHeight"
    )

    if (isLiteMode) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = rowOffset)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .clip(cardShape)
                    .background(bgColor)
                    .border(1.dp, borderColor, cardShape)
                    .hoverable(interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(indicatorHeight)
                        .clip(RoundedCornerShape(99.dp))
                        .background(Accent.copy(alpha = indicatorAlpha))
                )
                Spacer(Modifier.width(10.dp))
                DmAvatar(displayName, avatarColor, status, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Text_White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isOrbitVerifiedUser(displayName)) {
                                Spacer(Modifier.width(5.dp))
                                OrbitVerifiedBadge(modifier = Modifier.size(15.dp))
                            }
                        }
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Text_Muted,
                                fontSize = 12.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = lastMessage,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Text_Muted,
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(start = 76.dp).height(0.5.dp).background(Separator.copy(alpha = 0.55f)))
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .padding(horizontal = 1.dp, vertical = 1.dp)
                .clip(cardShape)
                .background(bgColor)
                .border(1.dp, borderColor, cardShape)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(indicatorHeight)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Accent.copy(alpha = indicatorAlpha))
            )
            Spacer(Modifier.width(7.dp))
            DmAvatar(displayName, avatarColor, status, size = 30.dp)
            Spacer(Modifier.width(9.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = textColor,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isOrbitVerifiedUser(displayName)) {
                    Spacer(Modifier.width(5.dp))
                    OrbitVerifiedBadge(modifier = Modifier.size(13.dp))
                }
            }
            if (unreadCount > 0 && !isSelected) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFFF23F42))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )
                }
            }
            // Action icons on hover like Discord
            AnimatedVisibility(
                visible = isHovered,
                enter = fadeIn() + scaleIn(initialScale = 0.92f),
                exit = fadeOut() + scaleOut(targetScale = 0.92f)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onVoiceCall?.invoke() }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = OrbitIcons.Call,
                            contentDescription = "Voice Call",
                            tint = Text_White,
                            modifier = Modifier.size(16.dp).alpha(0.8f)
                        )
                    }
                    IconButton(onClick = { onVideoCall?.invoke() }, modifier = Modifier.size(20.dp)) {
                        Icon(
                            imageVector = OrbitIcons.Video,
                            contentDescription = "Video Call",
                            tint = Text_White,
                            modifier = Modifier.size(16.dp).alpha(0.8f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Text_Muted,
                        modifier = Modifier.size(14.dp).alpha(0.6f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun DmAvatar(displayName: String, avatarColor: Int, status: UserStatus, size: androidx.compose.ui.unit.Dp = 32.dp) {
    Box(modifier = Modifier.size(size)) {
        OrbitDefaultAvatar(
            label = displayName,
            seed = displayName,
            color = Color(avatarColor),
            modifier = Modifier.fillMaxSize()
        )
        val dotColor = statusColor(status)
        Box(
            modifier = Modifier
                .size(if (size > 40.dp) 14.dp else 10.dp)
                .align(Alignment.BottomEnd)
                .border(2.dp, Drawer_Bg, CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
    }
}
