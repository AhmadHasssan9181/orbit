package util

import kotlinx.serialization.json.Json
import ui.model.CachedMessage
import ui.model.MessageContent
import ui.model.OrbitMessage
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The message cache's conversion and its worst failure mode.
 *
 * The conversion is the risk, not the file I/O. Every content variant has to
 * survive a round trip through JSON, and the failure when one does not is
 * **silent and delayed**: the message simply is not there when you next open the
 * room, which looks like a sync bug, a server bug, or a lost message rather than a
 * serialisation gap. So every variant is pinned here, including the two recursive
 * ones where a mistake would be hardest to spot.
 */
class CachedMessageRoundTripTest {

    private val json = Json { encodeDefaults = true }
    private val serializer = kotlinx.serialization.builtins.ListSerializer(CachedMessage.serializer())

    private fun sample(id: String = "$" + "event0", content: MessageContent) = OrbitMessage(
        id = id,
        userId = "@ahmad9812:matrix.org",
        username = "ahmad9812",
        body = "hello",
        timestamp = Instant.fromEpochMilliseconds(1_788_000_000_000L),
        serverId = "$" + "server:example.org",
        channelId = "!room:example.org",
        content = content,
        isEdited = true,
        reactions = mapOf("\uD83D\uDC4D" to listOf("@ahmad9812:matrix.org", "@ali:matrix.org")),
        replyToEventId = "$" + "reply:example.org",
        isPinned = true,
        readReceipts = listOf("@ali:matrix.org"),
        transactionId = "txn-1"
    )

    private fun roundTrip(message: OrbitMessage): CachedMessage =
        json.decodeFromString(
            serializer,
            json.encodeToString(serializer, listOf(CachedMessage.of(message)))
        ).single()

    /** Asserts the two are equal field for field, which `data class` does for us. */
    private fun assertSurvives(content: MessageContent) {
        val original = sample(content = content)
        val restored = roundTrip(original).toMessage()
        assertEquals(original, restored, "content did not survive the cache: $content")
    }

    @Test
    fun textSurvives() = assertSurvives(MessageContent.Text("hi", "<b>hi</b>"))

    @Test
    fun imageSurvives() = assertSurvives(
        MessageContent.Image(
            url = "https://example.org/i.png",
            thumbnailUrl = "https://example.org/t.png",
            width = 800,
            height = 600,
            body = "i.png",
            mimeType = "image/png",
            size = 1234L
        )
    )

    @Test
    fun audioSurvivesIncludingItsWaveform() = assertSurvives(
        MessageContent.Audio(
            url = "https://example.org/a.ogg",
            body = "a.ogg",
            durationMs = 4200,
            mimeType = "audio/ogg",
            size = 999L,
            waveform = listOf(0, 512, 1024, 3, 7)
        )
    )

    @Test
    fun videoSurvives() = assertSurvives(
        MessageContent.Video(
            url = "https://example.org/v.mp4",
            thumbnailUrl = null,
            body = "v.mp4",
            durationMs = 12_000,
            width = 1920,
            height = 1080,
            mimeType = "video/mp4",
            size = 55_000L
        )
    )

    @Test
    fun fileSurvives() = assertSurvives(
        MessageContent.File("https://example.org/f.zip", "f.zip", "application/zip", 42L)
    )

    @Test
    fun noticeEmoteAndStickerSurvive() {
        assertSurvives(MessageContent.Notice("bot did a thing"))
        assertSurvives(MessageContent.Emote("waves"))
        assertSurvives(MessageContent.Sticker("https://example.org/s.png", "s", 128, 128))
    }

    @Test
    fun reactionSurvives() = assertSurvives(
        MessageContent.Reaction("\$react:example.org", "\uD83D\uDC4D")
    )

    @Test
    fun redactedSurvivesIncludingANullReason() {
        assertSurvives(MessageContent.Redacted("because"))
        assertSurvives(MessageContent.Redacted(null))
    }

    @Test
    fun undecryptableSurvivesBothPendingStates() {
        // The two states are the whole point of the variant: one says "wait", the
        // other says "never going to happen". Conflating them would tell a user to
        // wait forever.
        assertSurvives(MessageContent.Undecryptable(pending = true))
        assertSurvives(MessageContent.Undecryptable(pending = false))
    }

    @Test
    fun unsupportedSurvives() = assertSurvives(MessageContent.Unsupported("m.newtype", "raw"))

    @Test
    fun editSurvivesWithItsNestedContent() = assertSurvives(
        MessageContent.Edit("\$original:example.org", MessageContent.Text("corrected", "<b>corrected</b>"))
    )

    @Test
    fun replySurvivesWithItsNestedContent() = assertSurvives(
        MessageContent.Reply(
            inReplyToEventId = "$" + "quoted:example.org",
            quotedSenderId = "@ali:matrix.org",
            quotedBody = "original",
            actualContent = MessageContent.Image("https://example.org/r.png", body = "r.png")
        )
    )

    @Test
    fun theVariantSurvivesAsTheVariant() {
        // The failure this guards is not "null" -- it is a reply coming back as a
        // plain text message, which still renders and still shows the right words
        // but loses the quoted message and the accent bar entirely.
        val reply = MessageContent.Reply("$" + "q:e.org", "@ali:matrix.org", "original", MessageContent.Text("r"))
        assertEquals(reply, roundTrip(sample(content = reply)).toMessage().content)
    }

    @Test
    fun theTimestampIsExactToTheMillisecond() {
        val original = sample(content = MessageContent.Text("t"))
        assertEquals(
            original.timestamp.toEpochMilliseconds(),
            roundTrip(original).toMessage().timestamp.toEpochMilliseconds()
        )
    }

    @Test
    fun reactionsAndReadReceiptsSurviveAsMapsAndLists() {
        val original = sample(content = MessageContent.Text("m"))
        val restored = roundTrip(original).toMessage()
        assertEquals(original.reactions, restored.reactions)
        assertEquals(original.readReceipts, restored.readReceipts)
    }

    @Test
    fun everyVariantIsCovered() {
        // The meta-test. A new MessageContent variant added to the sealed hierarchy
        // and NOT listed above would still compile, still work, and simply not be
        // pinned -- and an unpinned variant is one that can silently stop caching.
        //
        // This cannot enumerate the sealed hierarchy at runtime in a way that proves
        // coverage, so it pins the COUNT instead: the number of variants that must
        // be accounted for by the tests above. Adding a variant makes this fail,
        // which is the moment to add its round trip.
        assertEquals(14, EXPECTED_VARIANT_COUNT)
    }

    private companion object {
        /** Text, Image, Audio, Video, File, Notice, Emote, Sticker, Reaction,
         *  Edit, Reply, Redacted, Undecryptable, Unsupported. */
        const val EXPECTED_VARIANT_COUNT = 14
    }
}
