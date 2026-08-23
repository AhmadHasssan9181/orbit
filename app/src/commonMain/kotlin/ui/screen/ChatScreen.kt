package ui.screen

import ui.screen.chat.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                onVoiceCall = { callManager?.startCall(channel.id, false) }
            )
            HorizontalDivider(color = Separator.copy(alpha = 0.2f), thickness = 1.dp)
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
                    modifier = Modifier.weight(1f).fillMaxWidth()
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
                        messages = messages,
                        currentUser = currentUser,
                        modifier = Modifier.fillMaxSize(),
                        onSeen = onSeen
                    )
                    
                    // Typing Indicator Overlay
                    if (typingUsers.isNotEmpty()) {
                        val typingText = when {
                            typingUsers.size == 1 -> "${typingUsers[0]} is typing..."
                            typingUsers.size == 2 -> "${typingUsers[0]} and ${typingUsers[1]} are typing..."
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
                MessageInputBar(
                    placeholder = directProfile?.let { "Message @${it.displayName}" } ?: "Message #${channel.name}",
                    onSend = onSend,
                    onSendAttachment = onSendAttachment,
                    onTyping = onTyping
                )
            }
        }
        
        // Panels that should only appear if the text chat is visible
        if (!showVoiceChannelUi) {
            when {
                directProfile != null && windowSize.isExpanded -> DirectProfilePanel(directProfile)
                ui.theme.LocalShowMemberList.current && windowSize.isExpanded -> MemberListPanel(members = members)
            }
        }
        }
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

