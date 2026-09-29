package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.NativeVideoPlayer
import ui.components.openUrlInBrowser
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun MessageContentView(
    content: ui.model.MessageContent,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
    onMentionUser: ((String) -> Unit)? = null
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (content) {
            is ui.model.MessageContent.Text -> TextMessageBody(content, isCompact, onMentionUser)
            is ui.model.MessageContent.Image -> ImageMessageCard(content)
            is ui.model.MessageContent.Audio -> AudioMessageCard(content)
            is ui.model.MessageContent.Video -> VideoMessageCard(content)
            is ui.model.MessageContent.File -> FileMessageCard(content)
            is ui.model.MessageContent.Notice -> NoticeBody(content)
            is ui.model.MessageContent.Emote -> EmoteBody(content)
            is ui.model.MessageContent.Sticker -> StickerCard(content)
            is ui.model.MessageContent.Reply -> {
                ReplyQuoteBlock(content)
                MessageContentView(content.actualContent, isCompact, onMentionUser = onMentionUser)
            }
            is ui.model.MessageContent.Edit -> {
                MessageContentView(content.newContent, isCompact, onMentionUser = onMentionUser)
            }
            is ui.model.MessageContent.Reaction -> { /* Reactions are aggregated, not rendered as bubbles */ }
            is ui.model.MessageContent.Redacted -> RedactedBody()
            is ui.model.MessageContent.Undecryptable -> UndecryptableBody(content)
            is ui.model.MessageContent.Unsupported -> UnsupportedBody(content)
        }
    }
}

@Composable
private fun TextMessageBody(
    content: ui.model.MessageContent.Text,
    isCompact: Boolean,
    onMentionUser: ((String) -> Unit)?
) {
    val members = ui.theme.LocalRoomMembers.current
    val tokens = rememberCosmicTokens()
    val currentUserId = repository.CredentialManager.userId
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    // The mention callback identity changes as the panel state changes; reading
    // it through a State keeps the remembered AnnotatedString valid.
    val mentionTarget = rememberUpdatedState(onMentionUser)
    val annotated = remember(content.body, content.formattedBody, members, tokens, currentUserId) {
        buildRichMessageText(
            plainBody = content.body,
            formattedBody = content.formattedBody,
            members = members.map { it.displayName to it.id },
            currentUserId = currentUserId,
            palette = RichTextPalette(
                text = tokens.Text_Secondary,
                muted = tokens.Text_Muted,
                accent = tokens.Accent,
                codeBg = tokens.Input_Bg,
                // 0.45 matches the mention pill's background alpha in
                // RichText.mentionStyle; the foreground has to be chosen from the
                // composited wash, not the raw accent, or the light theme gets
                // white text on light lavender again.
                onAccent = ui.components.onColorForWash(tokens.Accent, 0.45f, tokens.Bg),
                onMention = { userId -> mentionTarget.value?.invoke(userId) }
            )
        )
    }
    // Compose Desktop 1.7 does not give `Text` any link hit-testing: a
    // LinkAnnotation.Url is stored and styled but never dispatched. So the tap is
    // resolved here — offset via the layout result, annotation via the string.
    val layoutResult = remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text = annotated,
        onTextLayout = { layoutResult.value = it },
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Secondary,
            fontSize = if (isCompact) 14.sp else 14.sp,
            lineHeight = 20.sp
        ),
        modifier = Modifier.pointerInput(annotated) {
            // Named argument: detectTapGestures' last parameter is
            // onPressDelayMillis, so a trailing lambda would not be onTap.
            detectTapGestures(onTap = { position ->
                val result = layoutResult.value ?: return@detectTapGestures
                val offset = result.getOffsetForPosition(position)
                val link = annotated.getLinkAnnotations(offset, offset).firstOrNull()?.item
                when (link) {
                    // Second allowlist, at the point of use: the annotation can
                    // also arrive from a path that did not go through
                    // safeExternalUrl, and this is the last gate before the OS.
                    is LinkAnnotation.Url -> safeExternalUrl(link.url)?.let { safe ->
                        runCatching { uriHandler.openUri(safe) }
                    }
                    is LinkAnnotation.Clickable ->
                        link.linkInteractionListener?.onClick(link)
                    else -> Unit
                }
            })
        }
    )
}

@Composable
private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    val url = content.thumbnailUrl?.ifBlank { null } ?: content.url
    if (url.isBlank()) {
        // Pending upload — show placeholder until the real mxc:// URL arrives via sync
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.CardBg)
                .widthIn(max = 360.dp)
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = tokens.Accent,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.height(6.dp))
                Text("Uploading\u2026", style = MaterialTheme.typography.labelSmall.copy(color = tokens.Text_Muted))
            }
        }
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.CardBg)
                .widthIn(max = 360.dp)
        ) {
            coil3.compose.AsyncImage(
                model = url,
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Error && util.OrbitLog.debugEnabled) {
                        util.OrbitLog.d("Coil", "error loading image: ${state.result.throwable.message}")
                        util.OrbitLog.d("Coil", "url was: $url")
                    }
                },
                contentDescription = content.body.ifBlank { "Image" },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            // Download button overlay
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { scope.launch { util.openAuthenticatedMedia(content.url) } }
                    .padding(6.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (content.body.isNotBlank()) {
        Text(
            text = content.body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
    }
}

@Composable
private fun AudioMessageCard(content: ui.model.MessageContent.Audio) {
    val tokens = rememberCosmicTokens()
    // Keyed on the audio URL and never released on dispose: this composable
    // lives in a LazyColumn item, so `remember { }` + `release()` meant playing a
    // voice message died as soon as the row was recycled.
    val player = remember(content.url) { util.AudioPlayer() }
    val isPlaying by player.isPlaying.collectAsState()
    val progress by player.progress.collectAsState()

    val durationText = remember(content.durationMs) {
        if (content.durationMs != null) {
            val secs = content.durationMs / 1000
            "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        } else "—:——"
    }
    val waveform = remember(content.waveform) {
        content.waveform?.take(32) ?: (1..32).map { (30 + (it * 13) % 80) }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.CardBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(min = 200.dp, max = 300.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IconButton(
            onClick = {
                if (isPlaying) player.pause() else player.play(content.url)
            },
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(tokens.Accent)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // One Canvas instead of 32 Boxes: the old version rebuilt the whole set
        // of composables on every progress tick.
        AudioWaveform(
            waveform = waveform,
            progress = progress,
            playedColor = tokens.Accent,
            pendingColor = tokens.Text_Muted.copy(alpha = 0.6f),
            modifier = Modifier
                .weight(1f)
                .height(24.dp)
        )
        Text(
            text = durationText,
            style = MaterialTheme.typography.labelSmall.copy(
                color = tokens.Text_Muted, fontSize = 11.sp
            )
        )
    }
    if (content.body.isNotBlank()) {
        Text(
            text = content.body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted, fontSize = 11.sp
            )
        )
    }
}

@Composable
private fun AudioWaveform(
    waveform: List<Int>,
    progress: Float,
    playedColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        if (waveform.isEmpty()) return@Canvas
        val maxAmplitude = waveform.maxOrNull()?.toFloat()?.takeIf { it > 0f } ?: 100f
        val barWidth = 2.dp.toPx()
        val gap = 2.dp.toPx()
        val slot = barWidth + gap
        val count = maxOf(1, minOf(waveform.size, (size.width / slot).toInt()))
        val barWidthPx = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        for (index in 0 until count) {
            val amp = waveform[(index * waveform.size / count).coerceIn(0, waveform.lastIndex)]
            val fraction = (amp / maxAmplitude).coerceIn(0.1f, 1f)
            val barHeight = (fraction * size.height).coerceAtLeast(2f)
            val isPlayed = (index.toFloat() / count) <= progress
            drawRoundRect(
                color = if (isPlayed) playedColor else pendingColor,
                topLeft = androidx.compose.ui.geometry.Offset(index * (barWidthPx + gap), (size.height - barHeight) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidthPx, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidthPx / 2f)
            )
        }
    }
}

@Composable
private fun VideoMessageCard(content: ui.model.MessageContent.Video) {
    val tokens = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }

    val durationText = remember(content.durationMs) {
        if (content.durationMs != null) {
            val secs = content.durationMs / 1000
            "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
        } else null
    }

    if (content.url.isBlank()) {
        // Pending upload placeholder
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.CardBg)
                .widthIn(max = 360.dp)
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = tokens.Accent,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.height(6.dp))
                Text("Uploading\u2026", style = MaterialTheme.typography.labelSmall.copy(color = tokens.Text_Muted))
            }
        }
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(tokens.CardBg)
                .widthIn(max = 360.dp)
                .heightIn(min = 220.dp)
        ) {
            if (isPlaying) {
                NativeVideoPlayer(url = content.url, modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(8.dp)))
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { isPlaying = false }
                        .padding(6.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            } else {
                if (!content.thumbnailUrl.isNullOrBlank()) {
                    coil3.compose.AsyncImage(
                        model = content.thumbnailUrl,
                        contentDescription = content.body,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(220.dp).background(tokens.CardBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.VideoFile, contentDescription = null, tint = tokens.Text_Muted,
                            modifier = Modifier.size(48.dp))
                    }
                }
                Box(
                    modifier = Modifier.matchParentSize().clickable { isPlaying = true },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play video",
                            tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable { scope.launch { util.openAuthenticatedMedia(content.url) } }
                        .padding(6.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                if (durationText != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(durationText, style = MaterialTheme.typography.labelSmall.copy(
                            color = Color.White, fontSize = 10.sp))
                    }
                }
            }
        }
    }
    if (content.body.isNotBlank()) {
        Text(content.body, style = MaterialTheme.typography.bodySmall.copy(
            color = Text_Muted, fontSize = 11.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
    }
}

@Composable
private fun FileMessageCard(content: ui.model.MessageContent.File) {
    val tokens = rememberCosmicTokens()
    val scope = rememberCoroutineScope()
    val sizeText = remember(content.size) {
        content.size?.let { sz ->
            when {
                // One decimal: integer division rendered a 1.9 MB file as "1 MB".
                sz >= 1_048_576 -> String.format("%.1f MB", sz / 1_048_576.0)
                sz >= 1_024 -> "${sz / 1_024} KB"
                else -> "$sz B"
            }
        }
    }
    val icon = when {
        content.mimeType?.startsWith("image/") == true -> Icons.Default.Image
        content.mimeType?.startsWith("video/") == true -> Icons.Default.VideoFile
        content.mimeType?.startsWith("audio/") == true -> Icons.Default.AudioFile
        content.mimeType?.contains("pdf") == true -> Icons.Default.PictureAsPdf
        content.mimeType?.contains("zip") == true ||
                content.mimeType?.contains("archive") == true -> Icons.Default.FolderZip
        else -> Icons.Default.AttachFile
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .border(1.dp, tokens.Separator.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable { scope.launch { util.openAuthenticatedMedia(content.url) } }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .widthIn(min = 180.dp, max = 300.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = tokens.Accent, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = content.filename,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_White,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                ),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (sizeText != null || content.mimeType != null) {
                Text(
                    text = listOfNotNull(sizeText, content.mimeType?.split("/")?.lastOrNull()).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Text_Muted, fontSize = 10.sp
                    )
                )
            }
        }
        Icon(Icons.Default.Download, contentDescription = "Download", tint = tokens.Text_Muted,
            modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NoticeBody(content: ui.model.MessageContent.Notice) {
    Text(
        text = content.body,
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Muted,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 13.sp
        )
    )
}

@Composable
private fun EmoteBody(content: ui.model.MessageContent.Emote) {
    Text(
        text = "* ${content.body}",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Secondary,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 14.sp
        )
    )
}

@Composable
private fun StickerCard(content: ui.model.MessageContent.Sticker) {
    coil3.compose.AsyncImage(
        model = content.url,
        contentDescription = content.body.ifBlank { "Sticker" },
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .size(128.dp)
            .clip(RoundedCornerShape(8.dp))
    )
}

@Composable
private fun ReplyQuoteBlock(content: ui.model.MessageContent.Reply) {
    val tokens = rememberCosmicTokens()
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(tokens.Accent.copy(alpha = 0.7f))
        )
        Spacer(Modifier.width(8.dp))
        Column {
            // Display name, not the raw localpart: the rest of the app shows
            // names, and "@alice:matrix.org" in a quote read like a bug.
            val members = ui.theme.LocalRoomMembers.current
            val senderDisplay = if (content.quotedSenderId.isNotBlank()) {
                members.firstOrNull {
                    it.id == content.quotedSenderId ||
                        it.id.endsWith(content.quotedSenderId) ||
                        content.quotedSenderId.endsWith(it.id)
                }?.displayName ?: content.quotedSenderId.split(":").first().removePrefix("@")
            } else "Unknown"
            Text(
                text = senderDisplay,
                style = MaterialTheme.typography.labelMedium.copy(
                    color = tokens.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
            )
            Text(
                text = content.quotedBody.ifBlank { "(no preview)" },
                style = MaterialTheme.typography.bodySmall.copy(
                    color = tokens.Text_Muted, fontSize = 12.sp
                ),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ReactionChips(reactions: Map<String, List<String>>) {
    val tokens = rememberCosmicTokens()
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        reactions.forEach { (emoji, senderIds) ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.Accent.copy(alpha = 0.12f))
                    .border(1.dp, tokens.Accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                Text(
                    text = "${senderIds.size}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = tokens.Accent,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun UndecryptableBody(content: ui.model.MessageContent.Undecryptable) {
    // Muted and italic on purpose. A placeholder drawn like an ordinary message
    // -- same weight, same colour, sender's name above it -- reads as something
    // you could act on. This is a gap in the conversation, not content.
    val tokens = rememberCosmicTokens()
    Text(
        text = if (content.pending) "🔒 Encrypted — waiting for keys" else "🔒 This message couldn't be decrypted",
        color = tokens.Text_Subtle,
        style = MaterialTheme.typography.bodySmall.copy(
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    )
}

@Composable
private fun RedactedBody() {
    Text(
        text = "🗑 This message was deleted",
        style = MaterialTheme.typography.bodyMedium.copy(
            color = Text_Muted.copy(alpha = 0.6f),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 13.sp
        )
    )
}

@Composable
private fun UnsupportedBody(content: ui.model.MessageContent.Unsupported) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Text_Muted.copy(alpha = 0.08f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Block, contentDescription = null, tint = Text_Muted,
            modifier = Modifier.size(12.dp))
        Text(
            text = if (content.fallbackBody.isNotBlank()) content.fallbackBody
            else "[Unsupported: ${content.type}]",
            style = MaterialTheme.typography.bodySmall.copy(
                color = Text_Muted.copy(alpha = 0.7f), fontSize = 12.sp,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
        )
    }
}
