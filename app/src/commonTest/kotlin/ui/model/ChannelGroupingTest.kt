package ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawer used to render one flat "Rooms" header above every channel, because
 * `ChannelListContent` was handed a `categories` list and ignored it. These tests
 * pin the replacement.
 *
 * They were rewritten when the Text/Voice split was removed. The old version
 * asserted that a space with no server categories produced exactly two groups,
 * "Text Channels" and "Voice Channels" — and that is precisely the behaviour
 * that was wrong. **Discord's data model has two kinds of channel; Matrix does
 * not.** A Matrix room is one object with a timeline and, separately, MatrixRTC
 * call state, so filing it under one of two headings made the other unreachable.
 *
 * So the property pinned here is no longer "which group does this room land in".
 * It is the two that actually matter and were previously violated:
 *
 *  - **every room appears exactly once**, and
 *  - **a room with a live call is still listed, still in one piece**.
 *
 * A room's *type* is still carried on [OrbitChannel] because the call machinery
 * needs it, but it no longer decides placement, ordering, or which icon a row
 * gets.
 */
class ChannelGroupingTest {

    private fun text(
        id: String,
        name: String = id,
        categoryId: String? = null,
        categoryName: String? = null,
        position: Double = 0.0
    ) = OrbitChannel(
        id = id,
        name = name,
        type = ChannelType.TEXT,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryPosition = position
    )

    private fun voice(
        id: String,
        name: String = id,
        categoryId: String? = null,
        categoryName: String? = null,
        position: Double = 0.0,
        activeCallUrl: String? = null
    ) = OrbitChannel(
        id = id,
        name = name,
        type = ChannelType.VOICE,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryPosition = position,
        activeCallUrl = activeCallUrl
    )

    @Test
    fun `empty input produces no groups`() {
        assertTrue(ChannelGrouping.group(emptyList()).isEmpty())
    }

    @Test
    fun `a space with no server categories separates text and voice channels into TEXT CHANNELS and VOICE CHANNELS`() {
        val groups = ChannelGrouping.group(
            listOf(text("a", "General"), text("b", "random"), voice("c", "Lounge"))
        )

        assertEquals(2, groups.size, "uncategorized space separates text and voice channels")
        assertEquals("TEXT CHANNELS", groups[0].label)
        assertEquals(listOf("a", "b"), groups[0].channels.map { it.id })
        assertEquals("VOICE CHANNELS", groups[1].label)
        assertEquals(listOf("c"), groups[1].channels.map { it.id })
    }

    @Test
    fun `every room appears exactly once, whatever its type`() {
        val input = listOf(
            text("a", "General"),
            voice("b", "Lounge"),
            text("c", "random"),
            voice("d", "Standup"),
            text("e", "General Voice")   // name contains "voice", type is TEXT
        )

        val emitted = ChannelGrouping.group(input).flatMap { it.channels }

        assertEquals(input.size, emitted.size, "a room was duplicated or dropped")
        assertEquals(input.map { it.id }.toSet(), emitted.map { it.id }.toSet())
    }

    @Test
    fun `a room that is both chatty and live stays one entry`() {
        // This is the case that motivated the whole change: `General` on
        // matrix.org has a timeline AND MatrixRTC call state. Under the old
        // split it could only be filed once, so its chat or its call was
        // unreachable depending on which heading won.
        val room = voice("g", "General", activeCallUrl = "https://livekit.example")

        val emitted = ChannelGrouping.group(listOf(room, text("x", "random"))).flatMap { it.channels }

        assertEquals(1, emitted.count { it.id == "g" }, "General must not be listed twice")
        assertEquals("https://livekit.example", emitted.first { it.id == "g" }.activeCallUrl)
    }

    @Test
    fun `server categories group the list and win over anything else`() {
        val groups = ChannelGrouping.group(
            listOf(
                text("a", "general", categoryId = "lounge", categoryName = "Lounge", position = 1.0),
                text("b", "chat", categoryId = "chat", categoryName = "Chat", position = 0.0),
                voice("c", "standup", categoryId = "lounge", categoryName = "Lounge", position = 1.0)
            )
        )

        assertEquals(listOf("Chat", "Lounge"), groups.map { it.label }, "server order, not alphabetical")
        assertEquals(listOf("b"), groups[0].channels.map { it.id })
        assertEquals(setOf("a", "c"), groups[1].channels.map { it.id }.toSet())
    }

    @Test
    fun `rooms inside a category are alphabetical and not split by type`() {
        val groups = ChannelGrouping.group(
            listOf(
                voice("z", "Zulu", categoryId = "g", categoryName = "G"),
                text("m", "mike", categoryId = "g", categoryName = "G"),
                text("a", "Alpha", categoryId = "g", categoryName = "G")
            )
        )

        assertEquals(
            listOf("Alpha", "mike", "Zulu"),
            groups.single().channels.map { it.name },
            "a voice room must not be pushed below every text room"
        )
    }

    @Test
    fun `a room's position does not change because someone started talking in it`() {
        val quiet = text("z", "Zulu")
        val live = voice("a", "Alpha", activeCallUrl = "https://livekit.example")

        val emitted = ChannelGrouping.group(listOf(quiet, live)).flatMap { it.channels }

        assertEquals(
            listOf("Zulu", "Alpha"),
            emitted.map { it.name },
            "text channels group before voice channels and live call state does not alter channel order"
        )
    }

    @Test
    fun `a categorised space keeps its uncategorised rooms rather than dropping them`() {
        val groups = ChannelGrouping.group(
            listOf(
                text("a", "in-cat", categoryId = "g", categoryName = "Group"),
                text("b", "orphan")
            ),
            spaceName = "My Space"
        )

        assertEquals(2, groups.size)
        val ids = groups.flatMap { it.channels }.map { it.id }.toSet()
        assertEquals(setOf("a", "b"), ids, "an orphan room was dropped")
    }

    @Test
    fun `a category id is never printed at the user as a heading`() {
        // The id is a room id like "!kAbCdEf:matrix.org". That is not a heading
        // anyone wrote.
        val groups = ChannelGrouping.group(
            listOf(text("a", "general", categoryId = "!kAbCdEf:matrix.org"))
        )

        assertEquals("Channels", groups.single().label)
    }

    @Test
    fun `the uncategorised heading is the space name, not an invented type`() {
        assertEquals("My Space", ChannelGrouping.uncategorisedLabel("My Space"))
        assertEquals("Channels", ChannelGrouping.uncategorisedLabel("   "))
        assertEquals("Channels", ChannelGrouping.uncategorisedLabel(null))
    }
}
