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
    val isCallActiveInRoom = callManager?.activeRoomId == channel.id && callState != webrtc.CallState.IDLE && callState != webrtc.CallState.ENDED
    val showVoiceChannelUi = channel.type == ChannelType.VOICE && windowSize.isExpanded

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(Chat_Bg)
    ) {
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
            } else {

                if (activeGroupCallUrl != null) {
                    GroupCallBanner(
                        url = activeGroupCallUrl,
                        roomId = channel.id,
                        onJoinCall = { url ->
                            val callId = if (url.startsWith("matrix-call://")) {
                                val urlContent = url.substringAfter("://")
                                if (urlContent.contains("/")) urlContent.substringAfterLast("/") else urlContent
                            } else url
                            scope.launch {
                                (repository as? repository.MatrixOrbitRepository)?.joinGroupCall(channel.matrixRoomId, callId)
                                callManager?.joinGroupCall(channel.matrixRoomId, callId, false)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
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

