package ui.screen

import ui.screen.chat.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitMessage
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.theme.Theme
import ui.theme.rememberCosmicTokens

@Composable
fun ChatScreen(
    repository: repository.OrbitRepository? = null,
    channel: OrbitChannel,
    messages: List<OrbitMessage>,
    members: List<OrbitUser>,
    currentUser: OrbitUser?,
    directProfile: OrbitUser? = null,
    typingUsers: List<String> = emptyList(),
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
    val windowSize = LocalWindowSizeClass.current
    val callManager = webrtc.LocalCallManager.current
    val scope = rememberCoroutineScope()

    val callState by callManager?.callState?.collectAsState(initial = webrtc.CallState.IDLE) ?: remember { mutableStateOf(webrtc.CallState.IDLE) }
    val isGroupCall by callManager?.isGroupCall?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    val activeRoomId by callManager?.activeRoomIdFlow?.collectAsState(initial = null) ?: remember { mutableStateOf<String?>(null) }
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
        messages.filter {
            it.body.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true)
        }
    } else messages

    LaunchedEffect(callState, activeRoomId) {
        if (callState != webrtc.CallState.CONNECTING) {
            pendingGroupJoinCallId = null
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Chat_Bg)
    ) {
        CompositionLocalProvider(ui.theme.LocalRoomMembers provides members) {
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
            HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)
            if (searchActive) {
                RoomSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search in #${channel.name}\u2026",
                    resultCount = displayMessages.size,
                    onClose = {
                        searchActive = false
                        searchQuery = ""
                    }
                )
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
                                        // Room state is authoritative: never fall back to our default SFU while
                                        // an active call definition points elsewhere (split-room bug).
                                        val resolved = matrixRepository.resolveGroupCallJoinTarget(channel.matrixRoomId)
                                        val parsed = parseGroupCallJoinTarget(
                                            url = url,
                                            fallbackLiveKitUrl = matrixRepository.context.effectiveLiveKitServiceUrl()
                                        )
                                        val callId = resolved.callId ?: parsed.callId
                                        val livekitUrl = resolved.serviceUrl ?: parsed.livekitUrl
                                        println(
                                            "DEBUG [ChatScreen] JoinFocus callId=$callId sfu=$livekitUrl source=${resolved.source ?: "banner"}"
                                        )
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
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        enabled = pendingGroupJoinCallId == null && callState != webrtc.CallState.CONNECTING,
                        joining = pendingGroupJoinCallId != null || callState == webrtc.CallState.CONNECTING
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    MessageList(
                        messages = displayMessages,
                        currentUser = currentUser,
                        modifier = Modifier.fillMaxSize(),
                        firstUnreadId = firstUnreadId,
                        onSeen = onSeen,
                        onReact = onReact,
                        onEditMessage = onEditMessage,
                        onDeleteMessage = onDeleteMessage,
                        onTogglePin = onTogglePin,
                        onReply = { id -> replyingTo = messages.firstOrNull { it.id == id } }
                    )
                    
                    // Typing Indicator Overlay
                    if (typingUsers.isNotEmpty()) {
                        val typingNames = typingUsers.map { id ->
                            members.firstOrNull { it.id == id || id.startsWith(it.id) }?.displayName
                                ?.takeIf { it.isNotBlank() }
                                ?: id.substringBefore(':').ifBlank { id }
                        }
                        val typingText = when {
                            typingNames.size == 1 -> "${typingNames[0]} is typing..."
                            typingNames.size == 2 -> "${typingNames[0]} and ${typingNames[1]} are typing..."
                            else -> "Several people are typing..."
                        }
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 8.dp, start = 16.dp),
                            color = Chat_Bg.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = typingText,
                                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 11.sp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (replyingTo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(Input_Bg)
                            .padding(start = 0.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(28.dp)
                                .background(Accent)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${replyingTo?.username}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Accent,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            )
                            Text(
                                text = replyingTo?.body ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(24.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                tint = Text_Muted,
                                modifier = Modifier.size(14.dp)
                            )
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
                    onTyping = onTyping
                )
            }
        }
        
        // Panels that should only appear if the text chat is visible
        if (!showVoiceChannelUi) {
            when {
                directProfile != null && windowSize.isExpanded -> DirectProfilePanel(directProfile)
                pinsActive -> PinnedMessagesPanel(
                    pinned = messages.filter { it.isPinned },
                    canUnpin = onTogglePin != null,
                    onUnpin = { onTogglePin?.invoke(it) }
                )
                ui.theme.LocalShowMemberList.current && windowSize.isExpanded -> MemberListPanel(
                    members = members,
                    observePresence = repository?.let { it::observePresence },
                    memberRoles = memberRoles,
                    ownUserId = currentUser?.id,
                    onKickUser = onKickUser,
                    onBanUser = onBanUser,
                    onSetPowerLevel = onSetPowerLevel
                )
            }
        }
        }
    }
}

@Composable
private fun PinnedMessagesPanel(
    pinned: List<OrbitMessage>,
    canUnpin: Boolean,
    onUnpin: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .width(280.dp)
            .fillMaxHeight()
            .background(Members_Bg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.PushPin, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
            Text(
                text = "Pinned Messages",
                style = MaterialTheme.typography.titleSmall.copy(color = Text_White)
            )
        }
        HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)

        if (pinned.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No pinned messages.\nUse the \u2026 menu on a message to pin it.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Text_Muted, fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(pinned.size) { index ->
                    val message = pinned[index]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = message.username,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                            )
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = message.body,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Text_Secondary, fontSize = 12.sp
                            ),
                            maxLines = 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = message.formatShortTime(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Text_Muted, fontSize = 10.sp
                                )
                            )
                            if (canUnpin) {
                                Spacer(Modifier.weight(1f))
                                androidx.compose.material3.TextButton(
                                    onClick = { onUnpin(message.id) },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("Unpin", color = Text_Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = Separator.copy(alpha = 0.15f), thickness = 1.dp)
                }
            }
        }
    }
}

private data class GroupCallJoinTarget(
    val callId: String,
    val livekitUrl: String?
)

@Composable
private fun RoomSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    resultCount: Int,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Input_Bg)
            .border(1.dp, Separator.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = Text_Muted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = Text_White,
                fontSize = 13.sp
            ),
            cursorBrush = SolidColor(Text_White),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Text_Muted, fontSize = 13.sp
                            )
                        )
                    }
                    inner()
                }
            }
        )
        if (query.isNotBlank()) {
            Text(
                text = "$resultCount result${if (resultCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall.copy(color = Text_Muted)
            )
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close search",
                tint = Text_Muted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

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

