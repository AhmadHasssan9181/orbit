package ui.screen.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.assets.OrbitChatDoodleBackground
import ui.model.MessageContent
import ui.model.OrbitMessage
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun MessageList(
    messages: List<OrbitMessage>,
    currentUser: OrbitUser?,
    modifier: Modifier = Modifier,
    firstUnreadId: String? = null,
    onSeen: (String) -> Unit = {},
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    val grouped   = groupMessages(messages)
    val coroutineScope = rememberCoroutineScope()
    val reduceMotion = ui.theme.LocalReduceMotion.current

    // Group holding the first message that arrived while the user was away this session.
    val unreadSeparatorIndex = remember(grouped, firstUnreadId) {
        firstUnreadId?.let { id ->
            grouped.indexOfFirst { group ->
                when (group) {
                    is MessageGroup.Single -> group.message.id == id
                    is MessageGroup.Dense  -> group.messages.any { it.id == id }
                }
            }.takeIf { it >= 0 }
        }
    }

    // Only auto-scroll when the user is already at (or near) the bottom, or on first load /
    // channel switch — never yank the viewport while they're reading history.
    var scrolledForChannelKey by remember { mutableStateOf<String?>(null) }
    val channelKey = messages.firstOrNull()?.id
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(messages.size, channelKey) {
        if (grouped.isEmpty()) return@LaunchedEffect
        val isFirstLoad = scrolledForChannelKey != channelKey
        if (isFirstLoad || isNearBottom) {
            if (reduceMotion) {
                listState.scrollToItem(grouped.size - 1)
            } else {
                listState.animateScrollToItem(grouped.size - 1)
            }
            scrolledForChannelKey = channelKey
        }
    }
    
    // Read Receipt Logic
    LaunchedEffect(listState.firstVisibleItemIndex, messages.size) {
        if (messages.isNotEmpty() && grouped.isNotEmpty()) {
            val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (lastVisibleItemIndex >= grouped.size - 1) {
                onSeen(messages.last().id)
            }
        }
    }

    // Latest own message someone has read — renders the "Seen" row under it.
    val lastSeenOwnMessageId = remember(messages, currentUser) {
        currentUser ?: return@remember null
        messages.lastOrNull {
            it.userId == currentUser.id &&
                it.readReceipts.isNotEmpty() &&
                it.content !is MessageContent.Reaction
        }?.id
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OrbitChatDoodleBackground(
            selectedId = ui.theme.LocalChatWallpaper.current,
            modifier = Modifier.matchParentSize()
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(grouped, key = { _, group -> group.key }) { index, group ->
                val groupMessageIds = when (group) {
                    is MessageGroup.Single -> listOf(group.message.id)
                    is MessageGroup.Dense -> group.messages.map { it.id }
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (index == unreadSeparatorIndex) {
                        UnreadSeparatorRow()
                    }
                    if (ui.theme.LocalCompactMode.current) {
                        when (group) {
                            is MessageGroup.Single -> DenseMessageBody(group.message)
                            is MessageGroup.Dense -> {
                                for (msg in group.messages) DenseMessageBody(msg)
                            }
                        }
                    } else {
                        when (group) {
                            is MessageGroup.Single -> MessageBubbleFull(
                                group.message,
                                currentUser,
                                onReact = onReact,
                                onEditMessage = onEditMessage,
                                onDeleteMessage = onDeleteMessage,
                                onTogglePin = onTogglePin,
                                onReply = onReply
                            )
                            is MessageGroup.Dense  -> {
                                if (ui.theme.LocalIsLiteMode.current) {
                                    for (msg in group.messages) {
                                        MessageBubbleFull(
                                            msg,
                                            currentUser,
                                            onReact = onReact,
                                            onEditMessage = onEditMessage,
                                            onDeleteMessage = onDeleteMessage,
                                            onTogglePin = onTogglePin
                                        )
                                    }
                                } else {
                                    MessageBubbleFull(
                                        group.messages[0],
                                        currentUser,
                                        onReact = onReact,
                                        onEditMessage = onEditMessage,
                                        onDeleteMessage = onDeleteMessage,
                                        onTogglePin = onTogglePin,
                                        onReply = onReply
                                    )
                                    for (i in 1 until group.messages.size) {
                                        DenseMessageBody(group.messages[i])
                                    }
                                }
                            }
                        }
                    }
                    if (groupMessageIds.contains(lastSeenOwnMessageId)) {
                        SeenIndicator()
                    }
                }
            }
        }

        val showScrollToBottom by remember {
            derivedStateOf { listState.canScrollForward }
        }

        val showJumpToUnread by remember(unreadSeparatorIndex) {
            derivedStateOf {
                val target = unreadSeparatorIndex ?: return@derivedStateOf false
                listState.layoutInfo.visibleItemsInfo.none { it.index == target }
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showJumpToUnread,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
            ) {
                Surface(
                    onClick = {
                        coroutineScope.launch {
                            unreadSeparatorIndex?.let { target ->
                                if (reduceMotion) listState.scrollToItem(target) else listState.animateScrollToItem(target)
                            }
                        }
                    },
                    shape = CircleShape,
                    color = Accent,
                    contentColor = Color.White,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Jump to unread",
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.width(2.dp))
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to unread", modifier = Modifier.size(18.dp))
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToBottom,
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            if (grouped.isNotEmpty()) {
                                if (reduceMotion) {
                                    listState.scrollToItem(grouped.size - 1)
                                } else {
                                    listState.animateScrollToItem(grouped.size - 1)
                                }
                            }
                        }
                    },
                    containerColor = Accent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom", modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

internal sealed interface MessageGroup {
    val key: String
    data class Single(val message: OrbitMessage) : MessageGroup {
        override val key = "s_${message.id}"
    }
    data class Dense(val messages: List<OrbitMessage>) : MessageGroup {
        override val key = "d_${messages.firstOrNull()?.id}"
    }
}

internal fun groupMessages(messages: List<OrbitMessage>): List<MessageGroup> {
    if (messages.isEmpty()) return emptyList()
    val result = mutableListOf<MessageGroup>()
    var curUserId = messages[0].userId
    var curGroup  = mutableListOf(messages[0])

    for (i in 1 until messages.size) {
        val msg = messages[i]
        val newGroup = msg.userId != curUserId || curGroup.size >= 5
        if (newGroup) {
            result.add(if (curGroup.size == 1) MessageGroup.Single(curGroup[0]) else MessageGroup.Dense(curGroup.toList()))
            curGroup = mutableListOf()
            curUserId = msg.userId
        }
        curGroup.add(msg)
    }
    if (curGroup.isNotEmpty())
        result.add(if (curGroup.size == 1) MessageGroup.Single(curGroup[0]) else MessageGroup.Dense(curGroup.toList()))
    return result
}

@Composable
private fun UnreadSeparatorRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Accent.copy(alpha = 0.55f)))
        Text(
            text = "NEW",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            ),
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(Accent.copy(alpha = 0.55f)))
    }
}

@Composable
private fun SeenIndicator() {
    Row(
        modifier = Modifier.padding(start = 62.dp, top = 1.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.DoneAll,
            contentDescription = "Seen",
            tint = Accent,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = "Seen",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(
                color = Accent.copy(alpha = 0.85f),
                fontSize = 10.sp
            )
        )
    }
}
