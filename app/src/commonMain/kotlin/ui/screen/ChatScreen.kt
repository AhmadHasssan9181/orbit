package ui.screen

import ui.screen.chat.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.components.OrbitEmptyState
import ui.components.OrbitIconButton
import ui.components.OrbitScrollbar
import ui.components.OrbitSearchField
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMetrics
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import ui.screen.call.LiveKitGroupCallSurface
import ui.screen.call.VoiceChannelScreen
import ui.screen.chat.DirectProfilePanel
import ui.screen.chat.MessageInputBar
import ui.screen.chat.ReplyPreview
import ui.screen.chat.formatTimestampCompact

/**
 * The room surface.
 *
 * Two changes worth calling out. The right-hand panel used to be a bare `when`
 * that swapped between profile, pins and members with no transition at all, so
 * clicking "members" made the entire pane blink; it is now an `AnimatedContent`
 * that slides horizontally in the direction of travel. And the room search bar
 * was a `BasicTextField` in a hand-drawn bordered box with its own focus
 * handling; it is now the shared field, so it focuses, clears and themes like
 * every other input in the app.
 */
@Composable
fun ChatScreen(
    repository: repository.OrbitRepository? = null,
    channel: OrbitChannel,
    messages: List<OrbitMessage>,
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    directProfile: OrbitUser? = null,
    typingUsers: List<String> = emptyList(),
    isLoadingMessages: Boolean = false,
    activeCall: repository.CallEvent? = null,
    activeGroupCallUrl: String? = null,
    onSend: (String) -> Unit,
    onSendAttachment: ((ByteArray, String, String) -> Unit)? = null,
    onTyping: (Boolean) -> Unit = {},
    onSeen: (String) -> Unit = {},
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onSendReply: ((String, String) -> Unit)? = null,
    memberRoles: Map<String, repository.MemberRoleInfo> = emptyMap(),
    onKickUser: ((String) -> Unit)? = null,
    onBanUser: ((String) -> Unit)? = null,
    onSetPowerLevel: ((String, Long) -> Unit)? = null,
    notifMode: String = "ALL",
    onCycleNotificationMode: (() -> Unit)? = null,
    firstUnreadId: String? = null,
    showMemberList: Boolean = true,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val windowSize = LocalWindowSizeClass.current
    val callManager = webrtc.LocalCallManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val reduceMotion = ui.theme.LocalReduceMotion.current

    val callState by (callManager?.callState?.collectAsState(initial = webrtc.CallState.IDLE)
        ?: remember { mutableStateOf(webrtc.CallState.IDLE) })
    val isGroupCall by (callManager?.isGroupCall?.collectAsState(initial = false)
        ?: remember { mutableStateOf(false) })
    val activeRoomId by (callManager?.activeRoomIdFlow?.collectAsState(initial = null)
        ?: remember { mutableStateOf<String?>(null) })
    val isCallActiveInRoom = (activeRoomId == channel.id || activeRoomId == channel.matrixRoomId) &&
        callState != webrtc.CallState.IDLE &&
        callState != webrtc.CallState.ENDED
    val showVoiceChannelUi = channel.type == ChannelType.VOICE && windowSize.isExpanded

    var pendingGroupJoinCallId by remember(channel.id) { mutableStateOf<String?>(null) }
    var searchActive by remember(channel.id) { mutableStateOf(false) }
    var searchQuery by remember(channel.id) { mutableStateOf("") }
    var pinsActive by remember(channel.id) { mutableStateOf(false) }
    var replyingTo by remember(channel.id) { mutableStateOf<OrbitMessage?>(null) }

    val displayMessages = if (searchActive && searchQuery.isNotBlank()) {
        val q = searchQuery.trim()
        messages.filter { it.body.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true) }
    } else {
        messages
    }

    androidx.compose.runtime.LaunchedEffect(callState, activeRoomId) {
        if (callState != webrtc.CallState.CONNECTING) pendingGroupJoinCallId = null
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(t.Bg)
    ) {
        androidx.compose.runtime.CompositionLocalProvider(ui.theme.LocalRoomMembers provides members) {
            Column(modifier = Modifier.weight(1f)) {
                ChatHeader(
                    channel = channel,
                    directProfile = directProfile,
                    onBack = onBack,
                    onVideoCall = { callManager?.startCall(channel.id, true) },
                    onVoiceCall = { callManager?.startCall(channel.id, false) },
                    searchActive = searchActive,
                    onToggleSearch = { searchActive = !searchActive },
                    pinsActive = pinsActive,
                    onTogglePins = { pinsActive = !pinsActive },
                    notifMode = notifMode,
                    onCycleNotificationMode = onCycleNotificationMode
                )
                HorizontalDivider(color = t.Separator, thickness = 1.dp)

                if (searchActive) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OrbitSearchField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = "Search in ${channel.name}",
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(Modifier.width(OrbitSpace.sm))
                            Text(
                                text = "${displayMessages.size} result${if (displayMessages.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 11.sp),
                                maxLines = 1
                            )
                        }
                    }
                }

                if (showVoiceChannelUi) {
                    VoiceChannelScreen(
                        callManager = callManager!!,
                        matrixRepository = repository as repository.MatrixOrbitRepository,
                        channel = channel,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                } else if (isCallActiveInRoom && isGroupCall && callManager != null) {
                    LiveKitGroupCallSurface(
                        callManager = callManager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        remoteFallbackName = channel.name
                    )
                } else {
                    if (activeGroupCallUrl != null) {
                        GroupCallBanner(
                            url = activeGroupCallUrl,
                            roomId = channel.id,
                            onJoinCall = { url ->
                                val matrixRepository = repository as? repository.MatrixOrbitRepository
                                if (matrixRepository == null) {
                                    println("ERROR [ChatScreen] Cannot join MatrixRTC call inside Orbit; missing Matrix repository for $url")
                                } else if (pendingGroupJoinCallId != null || callState == webrtc.CallState.CONNECTING) {
                                    println("DEBUG [ChatScreen] Ignoring duplicate MatrixRTC join click")
                                } else {
                                    pendingGroupJoinCallId = "resolving"
                                    scope.launch {
                                        try {
                                            val resolved = matrixRepository.resolveGroupCallJoinTarget(channel.matrixRoomId)
                                            val parsed = parseGroupCallJoinTarget(
                                                url = url,
                                                fallbackLiveKitUrl = matrixRepository.context.effectiveLiveKitServiceUrl()
                                            )
                                            val callId = resolved.callId ?: parsed.callId
                                            val livekitUrl = resolved.serviceUrl ?: parsed.livekitUrl
                                            if (livekitUrl.isNullOrBlank()) {
                                                println("ERROR [ChatScreen] No LiveKit URL available for $url")
                                                return@launch
                                            }
                                            pendingGroupJoinCallId = callId
                                            callManager?.joinGroupCall(channel.matrixRoomId, callId, false, livekitUrl) {
                                                matrixRepository.fetchLiveKitToken(channel.matrixRoomId, callId, livekitUrl)
                                            }
                                        } finally {
                                            if (pendingGroupJoinCallId == "resolving") pendingGroupJoinCallId = null
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(OrbitSpace.lg),
                            enabled = pendingGroupJoinCallId == null && callState != webrtc.CallState.CONNECTING,
                            joining = pendingGroupJoinCallId != null || callState == webrtc.CallState.CONNECTING
                        )
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        MessageList(
                            messages = displayMessages,
                            currentUser = currentUser,
                            isLoading = isLoadingMessages,
                            modifier = Modifier.fillMaxSize(),
                            firstUnreadId = firstUnreadId,
                            onSeen = onSeen,
                            onReact = onReact,
                            onEditMessage = onEditMessage,
                            onDeleteMessage = onDeleteMessage,
                            onTogglePin = onTogglePin,
                            onReply = { id -> replyingTo = messages.firstOrNull { it.id == id } }
                        )

                        // Typing indicator: a small, quiet, self-clearing pill.
                        if (typingUsers.isNotEmpty()) {
                            val typingNames = typingUsers.map { id ->
                                members.firstOrNull { it.id == id || id.startsWith(it.id) }?.displayName
                                    ?.takeIf { it.isNotBlank() }
                                    ?: id.substringBefore(':').ifBlank { id }
                            }
                            val typingText = when {
                                typingNames.size == 1 -> "${typingNames[0]} is typing…"
                                typingNames.size == 2 -> "${typingNames[0]} and ${typingNames[1]} are typing…"
                                else -> "Several people are typing…"
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(start = OrbitSpace.lg, bottom = OrbitSpace.sm)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(OrbitRadius.pillShape)
                                        .background(t.Elevated)
                                        .padding(horizontal = OrbitSpace.md, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
                                ) {
                                    TypingDots()
                                    Text(
                                        text = typingText,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = t.Text_Muted,
                                            fontSize = 11.sp
                                        ),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    MessageInputBar(
                        placeholder = directProfile?.let { "Message @${it.displayName}" } ?: "Message #${channel.name}",
                        onSend = { body ->
                            val target = replyingTo
                            if (target != null && onSendReply != null) {
                                onSendReply(target.id, body)
                                replyingTo = null
                            } else {
                                onSend(body)
                            }
                        },
                        onSendAttachment = onSendAttachment,
                        onTyping = onTyping,
                        onCancelReply = { replyingTo = null },
                        replyPreview = replyingTo?.let { ReplyPreview(it.username, it.body) }
                    )
                }
            }

            // ── Right-hand panel ───────────────────────────────────
            if (!showVoiceChannelUi) {
                AnimatedContent(
                    targetState = when {
                        directProfile != null && windowSize.isExpanded -> 0
                        pinsActive -> 1
                        ui.theme.LocalShowMemberList.current && windowSize.isExpanded -> 2
                        else -> -1
                    },
                    transitionSpec = {
                        val forward = targetState > initialState
                        val ms = if (reduceMotion) 0 else 220
                        val spec = tween<Float>(ms)
                        val specOffset = tween<androidx.compose.ui.unit.IntOffset>(ms)
                        if (initialState == -1) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { it / 6 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { -it / 6 }
                        } else if (targetState == -1) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { -it / 6 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { it / 6 }
                        } else if (forward) {
                            fadeIn(spec) + slideInHorizontally(specOffset) { it / 5 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { -it / 5 }
                        } else {
                            fadeIn(spec) + slideInHorizontally(specOffset) { -it / 5 } togetherWith
                                fadeOut(spec) + slideOutHorizontally(specOffset) { it / 5 }
                        }
                    },
                    label = "sidePanel"
                ) { panel ->
                    when (panel) {
                        0 -> directProfile?.let { DirectProfilePanel(it) }
                        1 -> PinnedMessagesPanel(
                            pinned = messages.filter { it.isPinned },
                            canUnpin = onTogglePin != null,
                            onUnpin = { onTogglePin?.invoke(it) }
                        )
                        2 -> MemberListPanel(
                            members = members,
                            observePresence = repository?.let { it::observePresence },
                            memberRoles = memberRoles,
                            ownUserId = currentUser?.id,
                            onKickUser = onKickUser,
                            onBanUser = onBanUser,
                            onSetPowerLevel = onSetPowerLevel
                        )
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Three dots that rise in sequence — the universally understood "typing". */
@Composable
private fun TypingDots() {
    val t = rememberCosmicTokens()
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = tween(760, delayMillis = index * 150, easing = androidx.compose.animation.core.LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                ),
                label = "typingDot$index"
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(OrbitRadius.pillShape)
                    .background(t.Text_Muted.copy(alpha = alpha))
            )
        }
    }
}

@Composable
private fun PinnedMessagesPanel(
    pinned: List<OrbitMessage>,
    canUnpin: Boolean,
    onUnpin: (String) -> Unit
) {
    val t = rememberCosmicTokens()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    Column(
        modifier = Modifier
            .width(OrbitMetrics.sidePanelWidth)
            .fillMaxHeight()
            .background(t.Members_Bg)
            .border(1.dp, t.Separator)
    ) {
        SidePanelHeader(
            icon = Icons.Default.PushPin,
            title = "Pinned",
            count = pinned.size
        )
        HorizontalDivider(color = t.Separator, thickness = 1.dp)

        if (pinned.isEmpty()) {
            OrbitEmptyState(
                title = "Nothing pinned yet",
                body = "Open a message's ··· menu and choose Pin to keep it here for everyone in the room.",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.weight(1f)) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = OrbitSpace.lg)
                ) {
                    items(pinned.size, key = { pinned[it].id }) { index ->
                        val message = pinned[index]
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = message.username,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = t.Accent,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (canUnpin) {
                                    OrbitIconButton(
                                        icon = Icons.Default.Close,
                                        contentDescription = "Unpin this message",
                                        onClick = { onUnpin(message.id) },
                                        size = 22.dp,
                                        iconSize = 12.dp,
                                        shape = OrbitRadius.xsShape
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = message.body,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = t.Text_Secondary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp
                                ),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = formatTimestampCompact(message.timestamp),
                                style = MaterialTheme.typography.labelSmall.copy(color = t.Text_Subtle, fontSize = 10.sp)
                            )
                        }
                        HorizontalDivider(color = t.Separator, thickness = 1.dp)
                    }
                }
                OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/** Shared chrome for the right-hand panel headers. */
@Composable
internal fun SidePanelHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    count: Int? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = OrbitSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = t.Text_Muted, modifier = Modifier.size(15.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = t.Text_White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 0.sp
            ),
            maxLines = 1
        )
        if (count != null) {
            Box(
                modifier = Modifier
                    .clip(OrbitRadius.pillShape)
                    .background(t.Sunken)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = t.Text_Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

private data class GroupCallJoinTarget(
    val callId: String,
    val livekitUrl: String?
)

private fun parseGroupCallJoinTarget(url: String, fallbackLiveKitUrl: String?): GroupCallJoinTarget {
    if (url.startsWith("matrix-call://")) {
        val urlContent = url.substringAfter("://")
        val callId = urlContent.substringAfterLast("/", urlContent).ifBlank { "m.call" }
        return GroupCallJoinTarget(callId = callId, livekitUrl = fallbackLiveKitUrl)
    }

    val callId = url.substringAfter("?callId=", missingDelimiterValue = "m.call")
        .substringBefore("&")
        .ifBlank { "m.call" }
    val livekitUrl = url.substringBefore("?").ifBlank { fallbackLiveKitUrl }
    return GroupCallJoinTarget(callId = callId, livekitUrl = livekitUrl)
}
