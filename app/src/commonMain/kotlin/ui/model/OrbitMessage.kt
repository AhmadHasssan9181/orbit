package ui.model

import androidx.compose.runtime.Immutable
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

/**
 * Sealed hierarchy of all Matrix message content types.
 * Used to drive rich rendering in the UI without stringly-typed branching.
 */
// Every leaf is a data class of vals of stable types and the hierarchy is
// closed, so the sealed base is stable as far as Compose is concerned.
@Immutable
sealed class MessageContent {
    /** m.text — regular plain or formatted text */
    data class Text(
        val body: String,
        val formattedBody: String? = null  // HTML-formatted body if format == org.matrix.custom.html
    ) : MessageContent()

    /** m.image — an image attachment */
    data class Image(
        val url: String,
        val thumbnailUrl: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val body: String,
        val mimeType: String? = null,
        val size: Long? = null
    ) : MessageContent()

    /** m.audio — a voice or audio clip */
    data class Audio(
        val url: String,
        val body: String,
        val durationMs: Int? = null,  // milliseconds
        val mimeType: String? = null,
        val size: Long? = null,
        val waveform: List<Int>? = null  // MSC3245 amplitude waveform (0–1024)
    ) : MessageContent()

    /** m.video — a video clip */
    data class Video(
        val url: String,
        val thumbnailUrl: String? = null,
        val body: String,
        val durationMs: Int? = null,
        val width: Int? = null,
        val height: Int? = null,
        val mimeType: String? = null,
        val size: Long? = null
    ) : MessageContent()

    /** m.file — a generic file attachment */
    data class File(
        val url: String,
        val filename: String,
        val mimeType: String? = null,
        val size: Long? = null
    ) : MessageContent()

    /** m.notice — system/bot notice, rendered in muted italic style */
    data class Notice(val body: String) : MessageContent()

    /** m.emote — /me emote action */
    data class Emote(val body: String) : MessageContent()

    /** m.sticker — sticker image */
    data class Sticker(
        val url: String,
        val body: String,
        val width: Int? = null,
        val height: Int? = null
    ) : MessageContent()

    /** m.reaction — emoji reaction to another event (rendered as chip on parent) */
    data class Reaction(
        val reactsToEventId: String,
        val key: String  // the emoji or text key
    ) : MessageContent()

    /**
     * Message that has been edited (m.room.message with m.relates_to.rel_type == "m.replace").
     * The newContent is the effective displayed content after the edit.
     */
    data class Edit(
        val replacesEventId: String,
        val newContent: MessageContent
    ) : MessageContent()

    /**
     * Reply — m.text (or other) that contains m.relates_to with rel_type m.in_reply_to.
     */
    data class Reply(
        val inReplyToEventId: String,
        val quotedSenderId: String,
        val quotedBody: String,  // fallback text from the quoted event
        val actualContent: MessageContent  // the reply's own content
    ) : MessageContent()

    /** Redacted / deleted message */
    data class Redacted(val reason: String? = null) : MessageContent()

    /**
     * An encrypted message we could not read.
     *
     * Its own type rather than a `Text` body carrying a placeholder string,
     * because a placeholder rendered as an ordinary message with the sender's
     * name and avatar is indistinguishable from real content -- it looks
     * something you could read and act on. This lets the renderer draw it
     * muted and say something true.
     *
     * @param pending true when the megolm session is still expected to arrive,
     *   so more messages may decrypt. False when we tried and failed, which for
     *   a message from before this device joined the room means it never will.
     */
    data class Undecryptable(val pending: Boolean) : MessageContent()

    /** Any message type we don't yet handle */
    data class Unsupported(val type: String, val fallbackBody: String = "") : MessageContent()
}

// ─── Extension to get display text for any content ──────────────

val MessageContent.displayText: String
    get() = when (this) {
        is MessageContent.Text -> body
        is MessageContent.Image -> "📷 ${body.ifBlank { "Image" }}"
        is MessageContent.Audio -> "🎵 ${body.ifBlank { "Audio" }}"
        is MessageContent.Video -> "🎬 ${body.ifBlank { "Video" }}"
        is MessageContent.File -> "📎 $filename"
        is MessageContent.Notice -> body
        is MessageContent.Emote -> "* $body"
        is MessageContent.Sticker -> "🖼️ ${body.ifBlank { "Sticker" }}"
        is MessageContent.Reaction -> key
        is MessageContent.Edit -> newContent.displayText
        is MessageContent.Reply -> actualContent.displayText
        is MessageContent.Redacted -> reason?.let { "Message deleted: $it" } ?: "Message deleted"
        is MessageContent.Undecryptable ->
            // Honest about which of the two situations this is. "Waiting for
            // keys" on a message from months ago is a promise nothing is going
            // to keep.
            if (pending) "Encrypted — waiting for keys" else "This message couldn't be decrypted"
        is MessageContent.Unsupported -> fallbackBody.ifBlank { "[$type]" }
    }

/**
 * Represents a message in a channel conversation.
 * Extends the original model with rich content type support.
 */
// Stable: vals of String/Boolean/Instant/@Immutable MessageContent/List/Map.
@Immutable
data class OrbitMessage(
    val id: String,
    val userId: String,
    val username: String,
    val body: String,           // plain text fallback for all content types
    val timestamp: Instant,
    val serverId: String? = null,
    val channelId: String? = null,
    val content: MessageContent = MessageContent.Text(body),
    val isEdited: Boolean = false,
    // Aggregated reactions: emoji key -> list of sender userIds
    val reactions: Map<String, List<String>> = emptyMap(),
    val replyToEventId: String? = null,
    val isPinned: Boolean = false,
    val readReceipts: List<String> = emptyList(),
    val transactionId: String? = null
) {
    fun formatTime(): String {
        val dt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val amPm = if (dt.hour >= 12) "PM" else "AM"
        val hour12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val minStr = dt.minute.toString().padStart(2, '0')
        return "${dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${dt.dayOfMonth}, ${dt.year} $hour12:$minStr $amPm"
    }

    fun formatShortTime(): String {
        val dt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val amPm = if (dt.hour >= 12) "PM" else "AM"
        val hour12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val minStr = dt.minute.toString().padStart(2, '0')
        return "$hour12:$minStr $amPm"
    }
}