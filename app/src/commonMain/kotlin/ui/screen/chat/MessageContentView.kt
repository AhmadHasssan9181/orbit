package ui.screen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.NativeVideoPlayer
import ui.components.OrbitTooltip
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
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
            // A body that is nothing but a fenced block renders as a `.code-block`
            // card rather than as a paragraph with backticks in it. Split here
            // rather than inside [TextMessageBody] so the whole body is one
            // structural decision: a body that *is* a code block must not also
            // render the fence characters as text.
            is ui.model.MessageContent.Text -> {
                val fenced = fencedCodeIn(content.body)
                if (fenced != null) {
                    MessageCodeBlockCard(code = fenced.second, language = fenced.first)
                } else {
                    TextMessageBody(content, isCompact, onMentionUser)
                }
            }
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
                // `.message-text strong { color: var(--header-primary) }`
                strong = if (isLightChatSurface(tokens)) ChatConcept.headerPrimaryLight else tokens.Text_White,
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
            // `.message-text { font-size: 15px; line-height: 1.45; color: var(--text-normal) }`.
            // 15 * 1.45 = 21.75, written as the product rather than rounded to 22 so
            // the number in the stylesheet is the number on screen. The
            // `isCompact` branch it replaced returned 14sp/20sp either way, so it
            // was a conditional that could not branch.
            color = Text_Secondary,
            fontSize = 15.sp,
            lineHeight = (15f * 1.45f).sp
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
            // 15sp to match `.message-text`; an emote is body text with a prefix,
            // and at 14sp it was the one line in a message that did not line up
            // with the lines above and below it.
            color = Text_Secondary,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontSize = 15.sp,
            lineHeight = (15f * 1.45f).sp
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

/**
 * The inline "Replying to …" line above a message body.
 *
 * The concept draws it as one muted line, not as a quoted block:
 *
 * ```html
 * <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;
 *             display: flex; align-items: center; gap: 6px">
 *   <CornerUpLeft size={12} />
 *   <span>Replying to <strong>@name</strong>: preview…</span>
 * </div>
 * ```
 *
 * So: a 12dp reply arrow, a 6dp gap, 12sp muted, and the quoted *name* in
 * `--header-primary` because `.message-text strong` paints bold text in the
 * header colour.
 *
 * The previous version was a 3dp accent bar beside a two-line quote with its own
 * accent-coloured name. The bar is gone: nothing in the concept's reply preview
 * has one, and a 3dp accent stripe on every reply in a busy thread turns the
 * left edge of the text column into a picket fence.
 */
@Composable
private fun ReplyQuoteBlock(content: ui.model.MessageContent.Reply) {
    val tokens = rememberCosmicTokens()
    val strong = if (isLightChatSurface(tokens)) ChatConcept.headerPrimaryLight else tokens.Text_White
    // Display name, not the raw localpart: the rest of the app shows names, and
    // "@alice:matrix.org" in a quote read like a bug.
    val members = ui.theme.LocalRoomMembers.current
    val senderDisplay = if (content.quotedSenderId.isNotBlank()) {
        members.firstOrNull {
            it.id == content.quotedSenderId ||
                it.id.endsWith(content.quotedSenderId) ||
                content.quotedSenderId.endsWith(it.id)
        }?.displayName ?: content.quotedSenderId.split(":").first().removePrefix("@")
    } else "Unknown"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = ui.icons.OrbitIcons.Reply,
            contentDescription = null,
            tint = tokens.Text_Muted,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = buildAnnotatedString {
                append("Replying to ")
                // `.message-text strong { color: var(--header-primary) }`
                withStyle(androidx.compose.ui.text.SpanStyle(color = strong, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                    append("@$senderDisplay")
                }
                val preview = content.quotedBody.ifBlank { "(no preview)" }
                // The concept truncates the quoted text at 30 characters and adds
                // an ellipsis; a full quote inside a one-line reply preview is a
                // second paragraph, not a preview.
                append(": " + if (preview.length > 30) preview.take(30) + "…" else preview)
            },
            style = MaterialTheme.typography.bodySmall.copy(
                color = tokens.Text_Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * `.code-block` — a fenced snippet as a card with a language label and a Copy
 * button, rather than as inline monospace text with a background span.
 *
 * ```
 * .code-block {
 *   background-color: var(--bg-secondary); border: 1px solid var(--border-subtle);
 *   border-radius: 6px; padding: 12px; margin-top: 8px;
 *   font-family: var(--font-code); font-size: 13px; line-height: 1.5; color: #e0e0e0;
 *   overflow-x: auto;
 * }
 * .code-block-header {
 *   display: flex; align-items: center; justify-content: space-between;
 *   margin-bottom: 6px; font-size: 11px; color: var(--text-muted);
 *   font-family: var(--font-main); text-transform: uppercase; font-weight: 600;
 * }
 * ```
 *
 * The Copy button is the concept's: a `Copy` glyph plus the word, swapping to a
 * `Check` in `--text-positive` and the word "Copied!" for two seconds.
 *
 * ## What this cannot see
 *
 * The concept's code is a separate `codeSnippet` field on the message, separate
 * from `content`. Matrix has no such field, so this reads a fenced block out of
 * the plain body — see [fencedCodeIn]. A sender whose client emits `<pre>` in
 * `formatted_body` and a correspondingly *fenced* plain body is covered; one
 * that emits `<pre>` and a bare plain body still renders as inline code, because
 * the HTML parser is not going to be taught to lift a subtree out of a body it
 * flattens to spans.
 */
@Composable
private fun MessageCodeBlockCard(
    code: String,
    language: String,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)
    val clipboard = LocalClipboardManager.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    val surface = if (light) ChatConcept.codeSurfaceLight else ChatConcept.secondary
    val edge = if (light) ChatConcept.codeBorderLight else OrbitConceptBorder.subtle()
    val codeColor = if (light) ChatConcept.codeTextLight else ChatConcept.codeText
    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = modifier
            .padding(top = 8.dp)
            .clip(shape)
            .background(surface)
            .border(1.dp, edge, shape)
            .padding(12.dp)
    ) {
        // .code-block-header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = t.Text_Muted,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 0.sp
                )
            )
            CodeCopyButton(
                copied = copied,
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }
            )
        }
        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                color = codeColor,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 13.sp,
                // 13 * 1.5
                lineHeight = 19.5.sp
            )
        )
    }
}

/**
 * The `.code-block-header` copy control.
 *
 * `background: transparent; border: none; color: var(--text-muted)` at rest, and
 * `--interactive-hover` on hover, with a 4dp gap between the glyph and the word.
 * The checked state paints the glyph `--text-positive` (#23a55a), which is why
 * there is a `Check` as well as a `Check` colour.
 */
@Composable
private fun CodeCopyButton(copied: Boolean, onClick: () -> Unit) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        hovered -> t.Text_Secondary              // --interactive-hover
        else -> t.Text_Muted
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (copied) "Copied" else "Copy code",
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            // `contentDescription = null` on the glyph is deliberate and it is the
            // whole accessible name of this control: the word beside it already
            // says "Copy" or "Copied!", so announcing "Check, Copied!" over it is
            // two labels for one button.
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = null,
            tint = if (copied) OrbitConceptStatus.online else tint,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (copied) "Copied!" else "Copy",
            style = MaterialTheme.typography.labelSmall.copy(
                color = tint,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        )
    }
}

/**
 * A fenced code block lifted out of a plain message body, if that is what it is.
 *
 * Returns the language (or "code" when the fence did not name one) and the code
 * itself, or null when the body is not a single fenced block. Only the *first*
 * fence is taken, and only when the body is nothing but prose plus that fence:
 * a message that is one code block is a code block, and a message that quotes
 * one in the middle of a sentence is not.
 */
internal fun fencedCodeIn(body: String): Pair<String, String>? {
    val lines = body.lines()
    fun isFence(line: String) = line.trimStart().startsWith("```")

    val open = lines.indexOfFirst(::isFence)
    if (open < 0) return null
    // Absolute indices throughout. `indexOfFirst` over a *range* returns a
    // range-relative index, which is the classic way to write an off-by-N here.
    var close = -1
    for (i in open + 1 until lines.size) {
        if (isFence(lines[i])) {
            close = i
            break
        }
    }
    if (close < 0) return null
    // Anything after the closing fence is prose, so this is not a lone code block.
    if (lines.drop(close + 1).any { it.isNotBlank() }) return null

    val language = lines[open].trim().removePrefix("```").trim()
        .ifBlank { "code" }
        .take(24)
    return language to lines.subList(open + 1, close).joinToString("\n").trimEnd()
}

/**
 * `.reactions-tray` and `.reaction-pill`.
 *
 * ```
 * .reactions-tray { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-top: 6px }
 * .reaction-pill {
 *   display: inline-flex; align-items: center; gap: 4px;
 *   background-color: var(--bg-secondary); border: 1px solid transparent;
 *   border-radius: 8px; padding: 3px 8px; font-size: 13px;
 *   cursor: pointer; color: var(--interactive-normal);
 * }
 * .reaction-pill:hover   { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.15) }
 * .reaction-pill.reacted { background: rgba(88,101,242,.2); border-color: var(--brand-blurple); color: #fff }
 * .reaction-count        { font-size: 12px; font-weight: 600 }
 * ```
 *
 * Three things changed, and the first is the one that matters.
 *
 * **"Reacted" is now a real state.** The pill carries the brand wash only when
 * the *current user* is in the reaction's sender list; every other pill is the
 * recessed surface. The previous version painted every pill in a brand wash
 * unconditionally, so "you reacted" and "somebody reacted" were the same picture
 * — which is the whole reason the concept has the modifier, and the whole reason
 * a tap could not be read as a toggle.
 *
 * **The pills are buttons.** They were a `Row` with no click handler at all, so
 * `.reaction-pill { cursor: pointer }` had nothing behind it. Tapping now toggles
 * your own reaction, which is the one thing a reaction count invites.
 *
 * **The trailing "add" pill** is the concept's `PlusCircle` button, so an emoji
 * that is not on the message yet is still one click away.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ReactionChips(
    reactions: Map<String, List<String>>,
    currentUserId: String? = null,
    onToggle: ((emoji: String) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val light = isLightChatSurface(t)

    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 6.dp)
    ) {
        reactions.forEach { (emoji, senderIds) ->
            ReactionPill(
                emoji = emoji,
                count = senderIds.size,
                reacted = currentUserId != null && senderIds.contains(currentUserId),
                light = light,
                label = "React with $emoji",
                onClick = onToggle?.let { toggle -> { toggle(emoji) } }
            )
        }
        if (onToggle != null) {
            ReactionPill(
                emoji = null,
                count = 0,
                reacted = false,
                light = light,
                label = "Add reaction",
                onClick = { onToggle(ChipAddReactionEmoji) }
            )
        }
    }
}

/** The emoji the trailing "add reaction" pill sends, per the concept's `👍`. */
internal const val ChipAddReactionEmoji = "👍"

@Composable
private fun ReactionPill(
    emoji: String?,
    count: Int,
    reacted: Boolean,
    light: Boolean,
    /**
     * Tooltip and click label.
     *
     * Named `label` rather than `contentDescription` on purpose: the call site
     * below assigns to `contentDescription` *inside* a `semantics { }` block, where
     * the receiver has a property of that name. A parameter called
     * `contentDescription` would be shadowed by the receiver there and the
     * assignment would silently write the semantics property to itself.
     */
    label: String,
    onClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val border by animateColorAsState(
        targetValue = when {
            reacted -> t.Accent
            hovered && light -> ChatConcept.pillBorderLight
            hovered -> OrbitConceptBorder.strong()
            // `.reaction-pill { border: 1px solid transparent }` — the border is
            // always laid out so a hovered pill does not grow a pixel.
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "reactionPillBorder"
    )
    val fill by animateColorAsState(
        targetValue = when {
            reacted && light -> t.Accent.copy(alpha = ChatConcept.PILL_REACTED_ALPHA_LIGHT)
            reacted -> t.Accent.copy(alpha = ChatConcept.PILL_REACTED_ALPHA_DARK)
            hovered && light -> ChatConcept.pillHoverLight
            hovered -> Color.White.copy(alpha = ChatConcept.PILL_HOVER_ALPHA)
            else -> if (light) ChatConcept.pillSurfaceLight else ChatConcept.secondary
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "reactionPillFill"
    )
    // `--interactive-normal` at rest, `--interactive-hover` on hover. The reacted
    // pill is white in the dark theme and the brand in the light one, which is
    // what the two `[data-theme='light']` overrides actually say.
    //
    // Named `foreground` rather than `label`: the `label` parameter is a String
    // and a local `val label` would shadow it for the rest of the function, which
    // is the kind of collision that compiles and then renders the wrong thing.
    val foreground = when {
        reacted && light -> t.Accent
        reacted -> Color.White
        hovered -> t.Text_Secondary
        else -> if (light) ChatConcept.interactiveNormalLight else ChatConcept.interactiveNormal
    }

    val shape = RoundedCornerShape(8.dp)
    val core: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(fill)
                .border(1.dp, border, shape)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .then(
                    if (onClick == null) Modifier else Modifier
                        .hoverable(interaction)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = label,
                            onClick = onClick
                        )
                )
                // The whole pill is one thing to a screen reader, so the emoji and
                // the count need a single name between them: "🚀, 14 reactions" and
                // not two separate runs. The trailing "add" pill takes its name
                // from its own label instead, because there is no count to read.
                .semantics {
                    contentDescription = if (emoji != null) {
                        "$emoji, $count reaction${if (count == 1) "" else "s"}" +
                            if (reacted) ", you reacted" else ""
                    } else {
                        label
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (emoji != null) {
                // The pill's own `font-size: 13px`.
                Text(emoji, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp))
                // .reaction-count { font-size: 12px; font-weight: 600 }
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = foreground,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AddCircle,
                    // The name of this one is on the Row above, from `label`; a
                    // second one here would be announced twice.
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    OrbitTooltip(label, core)
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
