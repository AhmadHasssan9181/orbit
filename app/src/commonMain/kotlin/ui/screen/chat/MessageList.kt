package ui.screen.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.assets.OrbitChatDoodleBackground
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitIconButtonRound
import ui.components.OrbitMessageSkeleton
import ui.model.MessageContent
import ui.model.OrbitMessage
import ui.model.OrbitUser
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.minus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The message list.
 *
 * Added here: **date separators**, which a chat without them makes you do
 * mental arithmetic to answer "when did they say that". The scroll affordances
 * also became real controls — the previous "jump to unread" was a bright
 * accent pill with a drop shadow sitting permanently in the corner of every
 * room, competing with the message input; and the "scroll to bottom" button
 * was a 48dp Material FAB in flat Discord blue.
 */
@Composable
internal fun MessageList(
    messages: List<OrbitMessage>,
    currentUser: OrbitUser?,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    firstUnreadId: String? = null,
    onSeen: (String) -> Unit = {},
    onReact: ((String, String) -> Unit)? = null,
    onEditMessage: ((String, String) -> Unit)? = null,
    onDeleteMessage: ((String) -> Unit)? = null,
    onTogglePin: ((String) -> Unit)? = null,
    onReply: ((String) -> Unit)? = null,
    onMentionUser: ((String) -> Unit)? = null,
    /**
     * Request one more page of history, older than what is loaded.
     *
     * Null disables scrollback. Called when the list reaches its top; the caller
     * is expected to guard against repeat fires (in-flight flag, has-more flag)
     * rather than relying on this to throttle, because a snapshotFlow on the
     * first visible index really does fire repeatedly while the cursor sits at
     * the top.
     */
    onLoadOlder: (suspend () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()
    // groupMessages is O(n) and produced a fresh list on every recomposition,
    // which invalidated every memo keyed on `grouped` downstream.
    val grouped = remember(messages) { groupMessages(messages) }
    val scope = rememberCoroutineScope()
    val reduceMotion = ui.theme.LocalReduceMotion.current

    // A separator is inserted wherever the calendar day changes, so the row
    // indices used by the unread marker have to account for the extra items.
    val rows = remember(grouped) { buildMessageRows(grouped) }

    val unreadSeparatorIndex = remember(rows, firstUnreadId) {
        firstUnreadId?.let { id ->
            rows.indexOfFirst { row ->
                when (row) {
                    is MessageRow.Date -> false
                    is MessageRow.Content -> row.contains(id)
                }
            }.takeIf { it >= 0 }
        }
    }

    // Only auto-scroll when already at (or near) the bottom, or on first load /
    // channel switch — never yank the viewport while someone reads history.
    var scrolledForChannelKey by remember { mutableStateOf<String?>(null) }
    val channelKey = messages.firstOrNull()?.id
    val isNearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 2
        }
    }
    val lastContentIndex = remember(rows) { rows.indexOfLast { it is MessageRow.Content } }

    LaunchedEffect(messages.size, channelKey) {
        if (lastContentIndex < 0) return@LaunchedEffect
        val isFirstLoad = scrolledForChannelKey != channelKey
        if (isFirstLoad || isNearBottom) {
            if (reduceMotion || isFirstLoad) {
                listState.scrollToItem(lastContentIndex)
            } else {
                listState.animateScrollToItem(lastContentIndex)
            }
            scrolledForChannelKey = channelKey
        }
    }

    // Read receipts: only the newest message, and only when the user is
    // genuinely at the bottom of the room. The scroll index is observed inside
    // the effect rather than read during composition — reading it here made
    // every item boundary crossed restart the effect *and* invalidate the whole
    // list's composition scope.
    val currentOnSeen by rememberUpdatedState(onSeen)
    LaunchedEffect(messages.size, rows.size) {
        // Throttle on the id actually sent. This collector fires on every
        // firstVisibleItemIndex change, and while the cursor sits at the bottom
        // that is once per item boundary crossed -- so scrolling sent one
        // read-receipt network request per row scrolled past.
        var lastSentId: String? = null
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect {
                if (messages.isNotEmpty() && rows.isNotEmpty()) {
                    val lastVisibleItemIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisibleItemIndex >= rows.size - 1) {
                        val newestId = messages.last().id
                        if (newestId != lastSentId) {
                            lastSentId = newestId
                            currentOnSeen(newestId)
                        }
                    }
                }
            }
    }

    // Scrollback trigger.
    //
    // Reaching the top requests one more page. `onLoadOlder` is null wherever
    // history is not paged, and the caller's own guards (loading flag, has-more
    // flag) make repeat fires free, so firing on every index change at the top
    // is safe and needs no debounce here.
    //
    // The `firstVisibleItemIndex <= LOAD_OLDER_TRIGGER` window is not a scroll
    // position but an item COUNT, and rows include day separators, so a
    // threshold of 2 is "within the first couple of rows". Small on purpose: a
    // user who scrolls to the top expects more, and one wasted request that
    // returns nothing is cheaper than a spinner that never appears.
    val currentOnLoadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState, rows.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index in 0..LOAD_OLDER_TRIGGER_ROWS && rows.isNotEmpty()) {
                    currentOnLoadOlder?.invoke()
                }
            }
    }

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
            contentPadding = PaddingValues(vertical = OrbitSpace.md),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (isLoading) {
                items(6, contentType = { "skeleton" }) { OrbitMessageSkeleton() }
            }

            itemsIndexed(
                items = rows,
                key = { _, row: MessageRow -> row.key }
            ) { index: Int, row: MessageRow ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    when (row) {
                        is MessageRow.Date -> DateSeparator(row.label)
                        is MessageRow.Content -> {
                            if (index == unreadSeparatorIndex) UnreadSeparatorRow()
                            val group = row.group
                            if (ui.theme.LocalCompactMode.current) {
                                when (group) {
                                    is MessageGroup.Single -> DenseMessageBody(
                                        message = group.message,
                                        onReact = onReact,
                                        onReply = onReply,
                                        onTogglePin = onTogglePin,
                                        onDeleteMessage = onDeleteMessage,
                                        onMentionUser = onMentionUser
                                    )
                                    is MessageGroup.Dense -> group.messages.forEach {
                                        DenseMessageBody(
                                            message = it,
                                            onReact = onReact,
                                            onReply = onReply,
                                            onTogglePin = onTogglePin,
                                            onDeleteMessage = onDeleteMessage,
                                            onMentionUser = onMentionUser
                                        )
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
                                        onReply = onReply,
                                        onMentionUser = onMentionUser
                                    )
                                    is MessageGroup.Dense -> {
                                        MessageBubbleFull(
                                            group.messages[0],
                                            currentUser,
                                            onReact = onReact,
                                            onEditMessage = onEditMessage,
                                            onDeleteMessage = onDeleteMessage,
                                            onTogglePin = onTogglePin,
                                            onReply = onReply,
                                            onMentionUser = onMentionUser
                                        )
                                        for (i in 1 until group.messages.size) {
                                            DenseMessageBody(
                                                message = group.messages[i],
                                                onReact = onReact,
                                                onReply = onReply,
                                                onTogglePin = onTogglePin,
                                                onDeleteMessage = onDeleteMessage,
                                                onMentionUser = onMentionUser
                                            )
                                        }
                                    }
                                }
                            }
                            if (row.ids.contains(lastSeenOwnMessageId)) {
                                SeenIndicator()
                            }
                        }
                    }
                }
            }
        }

        // ── Floating affordances ──────────────────────────────────
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
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = OrbitSpace.lg, bottom = OrbitSpace.lg),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            AnimatedVisibility(
                visible = showJumpToUnread,
                enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                    animationSpec = OrbitMotion.tweenFast(),
                    initialScale = 0.92f
                ),
                exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                    animationSpec = OrbitMotion.tweenFast(),
                    targetScale = 0.94f
                )
            ) {
                OrbitButton(
                    text = "Jump to new messages",
                    icon = Icons.Default.KeyboardArrowDown,
                    onClick = {
                        scope.launch {
                            unreadSeparatorIndex?.let { target ->
                                if (reduceMotion) listState.scrollToItem(target)
                                else listState.animateScrollToItem(target)
                            }
                        }
                    },
                    tone = OrbitButtonTone.Primary
                )
            }

            AnimatedVisibility(
                visible = showScrollToBottom,
                enter = fadeIn(OrbitMotion.tweenFast()) + scaleIn(
                    animationSpec = OrbitMotion.tweenFast(),
                    initialScale = 0.9f
                ),
                exit = fadeOut(OrbitMotion.tweenFast()) + scaleOut(
                    animationSpec = OrbitMotion.tweenFast(),
                    targetScale = 0.92f
                )
            ) {
                Box(
                    modifier = Modifier
                        .shadow(12.dp, CircleShape, clip = false)
                        .clip(CircleShape)
                ) {
                    OrbitIconButtonRound(
                        icon = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to the latest message",
                        onClick = {
                            scope.launch {
                                if (lastContentIndex >= 0) {
                                    if (reduceMotion) listState.scrollToItem(lastContentIndex)
                                    else listState.animateScrollToItem(lastContentIndex)
                                }
                            }
                        },
                        size = 34.dp,
                        iconSize = 18.dp,
                        filled = true
                    )
                }
            }
        }
    }
}

internal fun OrbitMotion.tweenFast(): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
    androidx.compose.animation.core.tween(120)

// ─── Row model with date separators ──────────────────────────────────────

internal sealed interface MessageRow {
    val key: String

    data class Date(val label: String, val epochDay: String) : MessageRow {
        override val key = "date_$epochDay"
    }

    data class Content(val group: MessageGroup) : MessageRow {
        override val key = "c_${group.key}"

        // Was a `get()`: it allocated a fresh list on every access, and
        // `row.ids.contains(...)` runs once per item per recomposition.
        val ids: List<String> = when (group) {
            is MessageGroup.Single -> listOf(group.message.id)
            is MessageGroup.Dense -> group.messages.map { it.id }
        }

        fun contains(id: String?): Boolean = id != null && ids.contains(id)
    }
}

private fun buildMessageRows(grouped: List<MessageGroup>): List<MessageRow> {
    if (grouped.isEmpty()) return emptyList()
    val out = ArrayList<MessageRow>(grouped.size + 8)
    var lastDay: String? = null
    grouped.forEach { group ->
        val ts = when (group) {
            is MessageGroup.Single -> group.message.timestamp
            is MessageGroup.Dense -> group.messages.first().timestamp
        }
        val day = ts.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()
        if (day != lastDay) {
            out.add(MessageRow.Date(dayLabel(ts), day))
            lastDay = day
        }
        out.add(MessageRow.Content(group))
    }
    return out
}

private fun dayLabel(ts: Instant): String {
    val tz = TimeZone.currentSystemDefault()
    val date = ts.toLocalDateTime(tz).date
    val today = Instant.fromEpochMilliseconds(System.currentTimeMillis()).toLocalDateTime(tz).date
    return when (date) {
        today -> "Today"
        today.minus(1, DateTimeUnit.DAY) -> "Yesterday"
        else -> {
            val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
            if (date.year == today.year) {
                "${date.dayOfMonth} $month"
            } else {
                "${date.dayOfMonth} $month ${date.year}"
            }
        }
    }
}

@Composable
private fun DateSeparator(label: String) {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.Separator)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.Text_Subtle,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.5.sp,
                letterSpacing = 0.4.sp
            )
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.Separator)
        )
    }
}

@Composable
private fun UnreadSeparatorRow() {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.md)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.Accent.copy(alpha = 0.45f))
        )
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(t.AccentGhost)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = "NEW",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.6.sp
                )
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(t.Accent.copy(alpha = 0.45f))
        )
    }
}

@Composable
private fun SeenIndicator() {
    val t = rememberCosmicTokens()
    Row(
        modifier = Modifier.padding(start = 62.dp, top = 1.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = Icons.Default.DoneAll,
            contentDescription = "Seen",
            tint = t.Accent,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = "Seen",
            style = MaterialTheme.typography.labelSmall.copy(
                color = t.Accent.copy(alpha = 0.9f),
                fontSize = 10.sp
            )
        )
    }
}

// ─── Grouping ────────────────────────────────────────────────────────────

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
    var curGroup = mutableListOf(messages[0])

    for (i in 1 until messages.size) {
        val msg = messages[i]
        val newGroup = msg.userId != curUserId || curGroup.size >= 5
        if (newGroup) {
            result.add(
                if (curGroup.size == 1) MessageGroup.Single(curGroup[0])
                else MessageGroup.Dense(curGroup.toList())
            )
            curGroup = mutableListOf()
            curUserId = msg.userId
        }
        curGroup.add(msg)
    }
    if (curGroup.isNotEmpty()) {
        result.add(
            if (curGroup.size == 1) MessageGroup.Single(curGroup[0])
            else MessageGroup.Dense(curGroup.toList())
        )
    }
    return result
}

/**
 * How close to the top of the list a scroll must get before another page of
 * history is requested.
 *
 * A row COUNT, not a pixel offset, and `rows` includes day separators, so 2 means
 * "within the first couple of rows". Deliberately eager: a user who has scrolled
 * to the top is asking for more, and one request that returns nothing costs less
 * than a spinner that never appears.
 */
private const val LOAD_OLDER_TRIGGER_ROWS = 2