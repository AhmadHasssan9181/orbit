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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.model.ChannelCategory
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun ChannelListContent(
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    onChannelClick: (String) -> Unit,
    onCreateRoomClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var roomsExpanded by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp, start = 8.dp, end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        item(key = "rooms_header") {
            ChannelSectionHeader(
                name = "Rooms and spaces",
                isExpanded = roomsExpanded,
                onToggle = { roomsExpanded = !roomsExpanded },
                onAddClick = onCreateRoomClick
            )
        }
        if (roomsExpanded) {
            items(channels, key = { it.id }) { ch ->
                ChannelItem(ch, ch.id == selectedChannelId, currentUser, repository) { onChannelClick(ch.id) }
            }
            if (channels.isEmpty()) {
                item(key = "empty_rooms") {
                    Text(
                        text = "No rooms yet",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Text_Muted.copy(alpha = 0.72f),
                            fontSize = 12.sp
                        ),
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun CategoryHeader(name: String, isExpanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "chevronRot"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = if (isHovered) Text_Secondary else Text_Muted,
            modifier = Modifier.size(12.dp).rotate(rotation)
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text = name.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = if (isHovered) Text_Secondary else Text_Muted,
                letterSpacing = 0.5.sp,
                fontSize = 10.sp
            )
        )
    }
}

@Composable
internal fun ChannelSectionHeader(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddClick: (() -> Unit)? = null
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "sectionChevronRot"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .hoverable(interactionSource),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .clickable(interactionSource = interactionSource, indication = null, onClick = onToggle)
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = if (isHovered) Text_Secondary else Text_Muted,
                modifier = Modifier.size(13.dp).rotate(rotation)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (isHovered) Text_Secondary else Text_Muted,
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onAddClick != null) {
            IconButton(onClick = onAddClick, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = OrbitIcons.Add,
                    contentDescription = "Create Channel",
                    tint = if (isHovered) Text_Secondary else Text_Muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun ChannelItem(channel: OrbitChannel, isSelected: Boolean, currentUser: OrbitUser?, repository: repository.OrbitRepository? = null, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val callManager = webrtc.LocalCallManager.current
    val callState by callManager?.callState?.collectAsState(initial = webrtc.CallState.IDLE) ?: remember { mutableStateOf(webrtc.CallState.IDLE) }
    val isActiveCallInChannel = callManager?.activeRoomId == channel.id && callState != webrtc.CallState.IDLE && callState != webrtc.CallState.ENDED
    val isLive = channel.activeCallUrl != null || isActiveCallInChannel
    val liveGreen = Color(0xFF23A55A)
    val displayName = remember(channel.name, channel.type) { channelDisplayName(channel) }

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> if (channel.type == ChannelType.VOICE) liveGreen.copy(alpha = 0.16f) else ChannelSelected_Bg
            isHovered  -> ChannelHover_Bg
            else       -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chBg"
    )
    val textColor by animateColorAsState(
        targetValue = when {
            isSelected -> Text_White
            isHovered  -> Text_Secondary
            else       -> Text_Muted
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chText"
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            isSelected && channel.type == ChannelType.VOICE -> liveGreen
            isLive -> liveGreen
            isSelected -> Text_White
            isHovered  -> Text_Secondary
            else       -> Text_Muted
        },
        label = "chIcon"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isSelected || isHovered || isLive) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chRowOffset"
    )
    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isSelected -> 22.dp
            isLive -> 18.dp
            isHovered -> 12.dp
            else -> 0.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "chIndicatorHeight"
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .clip(RoundedCornerShape(4.dp))
                .background(bgColor)
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(indicatorHeight)
                .clip(RoundedCornerShape(3.dp))
                .background(
                    when {
                        isSelected && channel.type == ChannelType.VOICE -> liveGreen
                        isSelected -> Accent
                        isLive -> liveGreen.copy(alpha = 0.85f)
                        else -> Color.Transparent
                    }
                )
        )
        Spacer(Modifier.width(5.dp))
        when (channel.type) {
            ChannelType.TEXT -> Icon(
                imageVector = OrbitIcons.TextChannel,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(17.dp)
            )
            ChannelType.VOICE -> Icon(
                imageVector = OrbitIcons.VoiceChannel,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(17.dp)
            )
        }

        Spacer(Modifier.width(6.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textColor,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 14.sp
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (channel.type == ChannelType.VOICE && isLive) {
            val livePulse by rememberInfiniteTransition(label = "liveChipPulse").animateFloat(
                initialValue = 0.97f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "liveChipScale"
            )
            Box(
                modifier = Modifier
                    .scale(livePulse)
                    .clip(RoundedCornerShape(999.dp))
                    .background(liveGreen.copy(alpha = 0.16f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = liveGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                )
            }
        } else if (isHovered) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(
                    imageVector = Icons.Default.PersonAdd,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(14.dp).alpha(0.6f)
                )
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = Text_Muted,
                    modifier = Modifier.size(14.dp).alpha(0.6f)
                )
            }
        }
        }
        
        // Always observe live members from Matrix for voice channels
        val members by repository?.observeCallMembers(channel.id)?.collectAsState(initial = emptyList()) ?: mutableStateOf(emptyList())
        
        if (members.isNotEmpty() || isActiveCallInChannel) {
            val isAudioEnabled by callManager?.isLocalAudioEnabled?.collectAsState() ?: mutableStateOf(true)
            // LiveKit identities of whoever is talking right now (native mixer
            // metering). Identity is `${userId}:${deviceId}` — match members on
            // the userId part.
            val speakers = callManager?.activeSpeakers?.collectAsState()?.value ?: emptySet()

            Column(modifier = Modifier.padding(start = 24.dp, bottom = 4.dp)) {
                // If local user is in the call but not in Matrix state yet (e.g. just joined), we can artificially show them
                val showLocalUserFallback = isActiveCallInChannel && currentUser != null && members.none { it.id == currentUser.id }


                members.forEach { member ->
                    val speaking = speakers.any { it.substringBeforeLast(':') == member.id }
                    val speakRing by animateFloatAsState(
                        targetValue = if (speaking) 1f else 0f,
                        animationSpec = tween(durationMillis = 200),
                        label = "memberSpeakRing"
                    )
                    val memberNameColor by animateColorAsState(
                        targetValue = if (speaking) Color.White else Text_Secondary,
                        animationSpec = tween(durationMillis = 200),
                        label = "memberSpeakName"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(20.dp)) {
                            if (member.avatarUrl != null) {
                                coil3.compose.AsyncImage(
                                    model = member.avatarUrl,
                                    contentDescription = member.displayName,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                ui.assets.OrbitDefaultAvatar(
                                    label = member.displayName,
                                    seed = member.id,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (speakRing > 0f) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .alpha(speakRing)
                                        .border(2.dp, liveGreen, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = member.displayName,
                            style = MaterialTheme.typography.bodySmall.copy(color = memberNameColor, fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.weight(1f))
                        // Muted state would ideally come from m.call.member too, for now just local logic for the current user
                        if (member.id == currentUser?.id && !isAudioEnabled) {
                            Icon(OrbitIcons.MicOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(14.dp))
                        }
                    }
                }
                
                if (showLocalUserFallback && currentUser != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(20.dp)) {
                            if (currentUser.avatarUrl != null) {
                                coil3.compose.AsyncImage(
                                    model = currentUser.avatarUrl,
                                    contentDescription = currentUser.displayName,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                                )
                            } else {
                                ui.assets.OrbitDefaultAvatar(
                                    label = currentUser.displayName,
                                    seed = currentUser.id,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentUser.displayName,
                            style = MaterialTheme.typography.bodySmall.copy(color = Text_Secondary, fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.weight(1f))
                        if (!isAudioEnabled) {
                            Icon(OrbitIcons.MicOff, null, tint = Color(0xFFF23F42), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

internal fun channelDisplayName(channel: OrbitChannel): String {
    if (channel.type != ChannelType.VOICE) return channel.name
    val withoutCall = channel.name.removeSuffix(" (Call)")
    val cleaned = withoutCall.replace(
        Regex("(?i)([-_\\s]+voice(?:[-_\\s]+channel)?)$"),
        ""
    ).trim('-', '_', ' ')
    return cleaned.ifBlank { withoutCall }
}
