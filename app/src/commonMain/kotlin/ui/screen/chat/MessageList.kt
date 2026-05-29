package ui.screen.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ui.assets.OrbitChatDoodleBackground
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
    onSeen: (String) -> Unit = {}
) {
    val listState = rememberLazyListState()
    val grouped   = groupMessages(messages)
    val coroutineScope = rememberCoroutineScope()
    val reduceMotion = ui.theme.LocalReduceMotion.current

    LaunchedEffect(messages.size) {
        if (grouped.isNotEmpty()) {
            if (reduceMotion) {
                listState.scrollToItem(grouped.size - 1)
            } else {
                listState.animateScrollToItem(grouped.size - 1)
            }
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
            items(grouped, key = { it.key }) { group ->
                if (ui.theme.LocalCompactMode.current) {
                    when (group) {
                        is MessageGroup.Single -> DenseMessageBody(group.message)
                        is MessageGroup.Dense -> {
                            for (msg in group.messages) DenseMessageBody(msg)
                        }
                    }
                } else {
                    when (group) {
                        is MessageGroup.Single -> MessageBubbleFull(group.message, currentUser)
                        is MessageGroup.Dense  -> {
                            if (ui.theme.LocalIsLiteMode.current) {
                                for (msg in group.messages) {
                                    MessageBubbleFull(msg, currentUser)
                                }
                            } else {
                                MessageBubbleFull(group.messages[0], currentUser)
                                for (i in 1 until group.messages.size) {
                                    DenseMessageBody(group.messages[i])
                                }
                            }
                        }
                    }
                }
            }
        }

        val showScrollToBottom by remember {
            derivedStateOf { listState.canScrollForward }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollToBottom,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
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
