package ui.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageAmendmentTest {

    private fun msg(
        id: String,
        userId: String = "@alice:example.org",
        text: String = "hello",
        ts: Long = 0L,
        reactions: Map<String, List<String>> = emptyMap()
    ) = OrbitMessage(
        id = id,
        userId = userId,
        username = userId,
        body = text,
        timestamp = Instant.fromEpochMilliseconds(ts),
        content = MessageContent.Text(text),
        reactions = reactions
    )

    private fun reaction(parentId: String, key: String, sender: String, ts: Long = 1L) =
        msg("$parentId-r$key-$sender", userId = sender, text = key, ts = ts).copy(
            content = MessageContent.Reaction(reactsToEventId = parentId, key = key)
        )

    private fun edit(parentId: String, replacement: String, sender: String = "@alice:example.org", ts: Long = 1L) =
        msg("$parentId-e", userId = sender, text = replacement, ts = ts).copy(
            content = MessageContent.Edit(
                replacesEventId = parentId,
                newContent = MessageContent.Text(replacement)
            )
        )

    @Test
    fun `a live reaction updates the parent and is not appended as a bubble`() {
        val parent = msg("$1")
        val result = applyLiveAmendments(listOf(parent), listOf(reaction("$1", "👍", "@bob:example.org")))

        // The pseudo-message must NOT be rendered on its own.
        assertEquals(1, result.size, "reaction pseudo-message leaked into the message list")
        assertEquals(listOf("@bob:example.org"), result[0].reactions["👍"])
    }

    @Test
    fun `an un-annotate removes the sender`() {
        val parent = msg("$1", reactions = mapOf("👍" to listOf("@bob:example.org", "@carol:example.org")))
        val result = applyLiveAmendments(
            listOf(parent),
            listOf(reaction("$1", "", "@bob:example.org"))
        )

        assertEquals(listOf("@carol:example.org"), result[0].reactions["👍"])
    }

    @Test
    fun `a live edit rewrites the parent rather than adding a second bubble`() {
        val parent = msg("$1", text = "old")
        val result = applyLiveAmendments(listOf(parent), listOf(edit("$1", "new")))

        assertEquals(1, result.size, "edit pseudo-message leaked into the message list")
        assertEquals("new", result[0].body)
        assertTrue(result[0].isEdited)
    }

    @Test
    fun `an edit never resurrects a deleted message`() {
        val deleted = msg("$1", text = "secret").copy(content = MessageContent.Redacted())
        val result = applyLiveAmendments(listOf(deleted), listOf(edit("$1", "leaked")))

        assertEquals(1, result.size)
        assertTrue(
            result[0].content is MessageContent.Redacted,
            "a redaction was undone by a later edit event: deleted text came back"
        )
    }

    @Test
    fun `a later edit wins over an earlier one`() {
        val parent = msg("$1", text = "v1")
        val result = applyLiveAmendments(
            listOf(parent),
            listOf(edit("$1", "v2", ts = 1), edit("$1", "v3", ts = 2))
        )
        assertEquals("v3", result[0].body)
    }

    @Test
    fun `an ordinary message is appended and sorted`() {
        val first = msg("$1", text = "one", ts = 100)
        val second = msg("$2", text = "two", ts = 200)
        val result = applyLiveAmendments(listOf(first), listOf(second))
        assertEquals(listOf("$1", "$2"), result.map { it.id })
    }

    @Test
    fun `a duplicate of a message already on screen is not appended twice`() {
        val first = msg("$1", ts = 100)
        val result = applyLiveAmendments(listOf(first), listOf(msg("$1", ts = 100)))
        assertEquals(1, result.size)
    }

    @Test
    fun `a transaction-id match replaces the optimistic copy`() {
        val optimistic = msg("local-1", text = "sending…", ts = 100).copy(transactionId = "txn-1")
        val confirmed = msg("evt-1", text = "sending…", ts = 150).copy(transactionId = "txn-1")
        val result = applyLiveAmendments(listOf(optimistic), listOf(confirmed))
        assertEquals(1, result.size)
        assertEquals("evt-1", result[0].id)
    }

    @Test
    fun `an empty incoming list returns the same instance`() {
        val list = listOf(msg("$1"))
        assertTrue(applyLiveAmendments(list, emptyList()) === list)
    }

    @Test
    fun `a reaction for a message not on screen is dropped, not rendered`() {
        val result = applyLiveAmendments(
            listOf(msg("$1")),
            listOf(reaction("\$unknown", "👍", "@bob:example.org"))
        )
        assertEquals(1, result.size)
        assertFalse(result[0].reactions.containsKey("👍"))
    }
}
