package ui.model

/**
 * One collapsible group in the channel drawer: a header label and the channels
 * under it.
 *
 * This is the shape Discord's sidebar is made of, and it is the reason the drawer
 * used to look nothing like it: every room sat under a single flat "Rooms"
 * header. The server's own category assignment had been thrown away (every room
 * carried a hard-coded `categoryId = "general"`), so there was nothing to group by
 * even though the plumbing to do so was already in place and unused.
 */
data class ChannelGroup(
    /** Stable identity for the LazyColumn key. Never shown. */
    val key: String,
    /** Header text, already human-facing ("Text Channels", "Lounge", ...). */
    val label: String,
    val channels: List<OrbitChannel>,
)

/**
 * Turns a flat room list into drawer groups.
 *
 * ## Why there is no Text/Voice split any more
 *
 * This used to fall back to two groups — "Text Channels" and "Voice Channels" —
 * copied from Discord. **Discord's data model genuinely has two kinds of channel.
 * Matrix does not.** A Matrix room is one object that has a message timeline and,
 * at the same time, may have MatrixRTC call state. Element shows every room in a
 * single flat list for exactly this reason.
 *
 * So the split was not a cosmetic difference. It was a category error, and it
 * produced visible, repeated damage:
 *
 * - A room that was both — `General` on matrix.org — could only be in ONE of the
 *   two groups, so putting it under "Voice Channels" made its **chat
 *   unreachable**, and putting it under "Text Channels" hid its call.
 * - Which group it landed in was decided by `roomName.contains("voice")`. A text
 *   room called "voice-chat-announcements" became a voice channel; a genuine
 *   voice room called "general" became a text channel.
 * - When a space had no room whose name matched, the list **invented** a
 *   "General Voice" channel pointing at the first text room.
 *
 * Removing the split fixes all three at once, because the question "is this a
 * text channel or a voice channel?" no longer has a wrong answer available.
 *
 * ## What replaces it
 *
 * Voice capability is a **per-room attribute shown on the row** — a live dot when
 * a call is running, a speaker button to join — not a section the room is filed
 * under. Clicking a room opens its chat; joining a call is a deliberate act on
 * that room.
 *
 * ## What is kept
 *
 * Server categories still group the list, because those are what the person who
 * built the space intended and they are orthogonal to text-vs-voice. On a
 * homeserver with no `/categories` support the result is one flat group.
 *
 * Pure and total by design: no I/O, no ordering surprises, and every input maps
 * to exactly one output with every room appearing exactly once. It is the kind
 * of function that is far cheaper to be certain about in a unit test than through
 * a screenshot.
 */
object ChannelGrouping {

    /**
     * Header for a space whose server reported no categories.
     *
     * The name the user gave the space, so it is not a type the app invented.
     */
    fun uncategorisedLabel(spaceName: String?): String =
        spaceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Channels"

    fun group(channels: List<OrbitChannel>, spaceName: String? = null): List<ChannelGroup> {
        if (channels.isEmpty()) return emptyList()

        val categorised = channels.filter { !it.categoryId.isNullOrBlank() }
        if (categorised.isEmpty()) {
            val textChannels = channels.filter { it.type == ChannelType.TEXT }.sortedBy { it.name.lowercase() }
            val voiceChannels = channels.filter { it.type == ChannelType.VOICE }.sortedBy { it.name.lowercase() }
            val groups = mutableListOf<ChannelGroup>()
            if (textChannels.isNotEmpty()) {
                groups.add(ChannelGroup(key = "text_channels", label = "TEXT CHANNELS", channels = textChannels))
            }
            if (voiceChannels.isNotEmpty()) {
                groups.add(ChannelGroup(key = "voice_channels", label = "VOICE CHANNELS", channels = voiceChannels))
            }
            return if (groups.isNotEmpty()) groups else listOf(
                ChannelGroup(
                    key = "flat",
                    label = uncategorisedLabel(spaceName),
                    channels = channels.sortedBy { it.name.lowercase() }
                )
            )
        }

        val byCategory = LinkedHashMap<String, MutableList<OrbitChannel>>()
        for (channel in channels) {
            val id = channel.categoryId
            if (id.isNullOrBlank()) continue
            byCategory.getOrPut(id) { mutableListOf() }.add(channel)
        }

        val groups = byCategory.entries
            // Server order first, label as the tiebreak so a room server that
            // sent two categories the same position still renders in a
            // predictable order instead of whatever the map iteration did.
            .sortedWith(
                compareBy(
                    { (it.value.minOfOrNull { c -> c.categoryPosition } ?: 0.0) },
                    { labelFor(it.key, it.value) },
                )
            )
            .map { (id, members) ->
                // Alphabetical within a category. Rooms with a live call are NOT
                // floated to the top: a room's position in the list should not
                // change because someone started talking in it.
                val sorted = members.sortedBy { it.name.lowercase() }
                ChannelGroup(key = "cat:$id", label = labelFor(id, members), channels = sorted)
            }
            .toMutableList()

        val orphans = channels.filter { it.categoryId.isNullOrBlank() }
        if (orphans.isNotEmpty()) {
            val orphanText = orphans.filter { it.type == ChannelType.TEXT }.sortedBy { it.name.lowercase() }
            val orphanVoice = orphans.filter { it.type == ChannelType.VOICE }.sortedBy { it.name.lowercase() }
            if (orphanText.isNotEmpty()) {
                groups += ChannelGroup(key = "orphan:text", label = "TEXT CHANNELS", channels = orphanText)
            }
            if (orphanVoice.isNotEmpty()) {
                groups += ChannelGroup(key = "orphan:voice", label = "VOICE CHANNELS", channels = orphanVoice)
            }
            if (orphanText.isEmpty() && orphanVoice.isEmpty()) {
                groups += ChannelGroup(
                    key = "orphan:flat",
                    label = uncategorisedLabel(spaceName),
                    channels = orphans.sortedBy { it.name.lowercase() }
                )
            }
        }
        return groups
    }

    /**
     * Header text for a category id.
     *
     * Prefers the name the server gave us. The id itself is a room id
     * ("!kAbCdEf:matrix.org"), which is not a heading any human wrote, so when no
     * name was resolved the group falls back to a neutral label rather than
     * printing the id at the user — and never to a type label, because there is
     * no longer a type split.
     */
    private fun labelFor(categoryId: String, members: List<OrbitChannel>): String {
        val declared = members.firstNotNullOfOrNull { it.categoryName?.trim()?.takeIf(String::isNotEmpty) }
        return declared ?: "Channels"
    }
}
