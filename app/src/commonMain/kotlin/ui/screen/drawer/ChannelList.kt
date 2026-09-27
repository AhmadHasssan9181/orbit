package ui.screen.drawer

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.assets.OrbitDefaultAvatar
import ui.components.OrbitCountBadge
import ui.components.OrbitIconButton
import ui.components.OrbitListRow
import ui.components.OrbitPill
import ui.components.OrbitRowLeading
import ui.components.OrbitRowTrailing
import ui.components.OrbitScrollbar
import ui.icons.OrbitIcons
import ui.model.ChannelCategory
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * The channel list.
 *
 * Reworked around `OrbitListRow` so it shares geometry, hover behaviour and
 * the selection treatment with the DM list, the member list and the settings
 * sidebar. Beyond unifying, this fixes three things that made the drawer feel
 * unfinished: the section header had no hit area of its own, the "add room"
 * affordance was permanently visible and never announced itself, and the live
 * indicator on a voice channel pulsed a whole pill up and down instead of
 * pulsing a dot.
 */
@Composable
internal fun ChannelListContent(
    categories: List<ChannelCategory>,
    channels: List<OrbitChannel>,
    selectedChannelId: String?,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    onChannelClick: (String) -> Unit,
    onCreateRoomClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var roomsExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val t = rememberCosmicTokens()

    Box(modifier = modifier.fillMaxWidth()) {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                top = OrbitSpace.sm,
                bottom = OrbitSpace.lg,
                start = OrbitSpace.sm,
                end = OrbitSpace.sm
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            item(key = "rooms_header") {
                ChannelSectionHeader(
                    name = "Rooms",
                    isExpanded = roomsExpanded,
                    onToggle = { roomsExpanded = !roomsExpanded },
                    onAddClick = onCreateRoomClick
                )
            }
            if (roomsExpanded) {
                items(channels, key = { it.id }) { ch ->
                    val chUnread = maxOf(unreadCounts[ch.id] ?: 0, unreadCounts[ch.matrixRoomId] ?: 0)
                    ChannelItem(
                        channel = ch,
                        isSelected = ch.id == selectedChannelId,
                        currentUser = currentUser,
                        repository = repository,
                        unreadCount = chUnread,
                        onClick = { onChannelClick(ch.id) }
                    )
                }
                if (channels.isEmpty()) {
                    item(key = "empty_rooms") {
                        Text(
                            text = "No rooms here yet",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = t.Text_Subtle,
                                fontSize = 12.sp
                            ),
                            modifier = Modifier.padding(start = 26.dp, top = OrbitSpace.sm, bottom = OrbitSpace.sm)
                        )
                    }
                }
            }
        }
        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** Collapsible section header. The add affordance only appears on hover. */
@Composable
internal fun ChannelSectionHeader(
    name: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onAddClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 0f else -90f,
        animationSpec = OrbitMotion.Snappy,
        label = "sectionChevron"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(start = OrbitSpace.xs, end = OrbitSpace.xxs)
            .hoverable(interaction),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(OrbitRadius.xsShape)
                .hoverable(interaction)
                .clickableNoRipple(onToggle)
                .padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = if (hovered) t.Text_Secondary else t.Text_Subtle,
                modifier = Modifier.size(14.dp).rotate(rotation)
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (hovered) t.Text_Secondary else t.Text_Muted,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (onAddClick != null) {
            OrbitIconButton(
                icon = OrbitIcons.Add,
                contentDescription = "Create a room in $name",
                onClick = onAddClick,
                size = 24.dp,
                iconSize = 14.dp,
                shape = CircleShape,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )

@Composable
internal fun ChannelItem(
    channel: OrbitChannel,
    isSelected: Boolean,
    currentUser: OrbitUser?,
    repository: repository.OrbitRepository? = null,
    unreadCount: Int = 0,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val callManager = webrtc.LocalCallManager.current
    val callState by callManager?.callState?.collectAsState(initial = webrtc.CallState.IDLE)
        ?: remember { mutableStateOf(webrtc.CallState.IDLE) }
    val isActiveCallInChannel =
        callManager?.activeRoomId == channel.id && callState != webrtc.CallState.IDLE && callState != webrtc.CallState.ENDED
    val isLive = channel.activeCallUrl != null || isActiveCallInChannel
    val displayName = remember(channel.name, channel.type) { channelDisplayName(channel) }

    // Live members of a voice channel, straight from room state.
    val members by repository?.observeCallMembers(channel.id)?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList()) }

    Column {
        OrbitListRow(
            title = displayName,
            onClick = onClick,
            selected = isSelected,
            height = 32.dp,
            horizontalPadding = 8.dp,
            accentColor = if (channel.type == ChannelType.VOICE) t.Live else t.Accent,
            leading = OrbitRowLeading(icon = if (channel.type == ChannelType.TEXT) OrbitIcons.TextChannel else OrbitIcons.VoiceChannel),
            trailing = {
                if (channel.type == ChannelType.VOICE && isLive) {
                    LiveDot(label = "Live", color = t.Live)
                }
                if (unreadCount > 0 && !isSelected) {
                    OrbitCountBadge(count = unreadCount, color = t.Accent)
                }
            }
        )

        if (members.isNotEmpty() || isActiveCallInChannel) {
            VoiceMemberStack(
                members = members,
                currentUser = currentUser,
                showLocalFallback = isActiveCallInChannel,
                callManager = callManager
            )
        }
    }
}

/**
 * A calm "someone is in this voice channel" dot. The previous version scaled a
 * whole "LIVE" pill between 0.97× and 1.04× forever, which drew the eye to
 * every voice room on the screen at all times.
 */
@Composable
private fun LiveDot(label: String, color: Color) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "liveDot")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveDotPulse"
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(14.dp)) {
        if (!reduce) {
            Box(
                modifier = Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.18f * pulse))
            )
        }
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/** Indented roster shown under a voice channel that has participants. */
@Composable
private fun VoiceMemberStack(
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    showLocalFallback: Boolean,
    callManager: webrtc.CallManager?
) {
    val t = rememberCosmicTokens()
    val isAudioEnabled by callManager?.isLocalAudioEnabled?.collectAsState() ?: remember { mutableStateOf(true) }
    val speakers = callManager?.activeSpeakers?.collectAsState()?.value ?: emptySet()
    val remoteMuted = callManager?.remoteMicMutedIds?.collectAsState()?.value ?: emptySet()

    val showSelf = showLocalFallback && currentUser != null && members.none { it.id == currentUser.id }

    Column(modifier = Modifier.padding(start = 30.dp, bottom = OrbitSpace.xs)) {
        members.forEach { member ->
            val speaking = speakers.any { it.substringBeforeLast(':') == member.id }
            val selfMuted = member.id == currentUser?.id && !isAudioEnabled
            val muted = selfMuted || ui.screen.call.identityIsMicMuted(member.id, remoteMuted)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OrbitRadius.xsShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(18.dp)) {
                    if (member.avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = member.avatarUrl,
                            contentDescription = member.displayName,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().size(18.dp).clip(CircleShape)
                        )
                    } else {
                        OrbitDefaultAvatar(
                            label = member.displayName,
                            seed = member.id,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (speaking) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(CircleShape)
                                .border(1.5.dp, t.Success, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.width(OrbitSpace.sm))
                Text(
                    text = member.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (speaking) t.Text_White else t.Text_Muted,
                        fontSize = 12.5.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (muted) {
                    OrbitIcons.MicOff.let {
                        androidx.compose.material3.Icon(
                            imageVector = it,
                            contentDescription = "Muted",
                            tint = t.Danger,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        if (showSelf && currentUser != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(OrbitRadius.xsShape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OrbitDefaultAvatar(
                    label = currentUser.displayName,
                    seed = currentUser.id,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(OrbitSpace.sm))
                Text(
                    text = currentUser.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Muted, fontSize = 12.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (!isAudioEnabled) {
                    androidx.compose.material3.Icon(
                        imageVector = OrbitIcons.MicOff,
                        contentDescription = "Muted",
                        tint = t.Danger,
                        modifier = Modifier.size(13.dp)
                    )
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
