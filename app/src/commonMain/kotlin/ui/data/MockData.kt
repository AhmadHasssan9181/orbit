package ui.data
import androidx.compose.ui.graphics.toArgb
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import ui.model.*
import ui.theme.*
import kotlin.random.Random

/**
 * Centralised mock data for the Orbit UI.
 * Every composable reads from here during development.
 */
object MockData {

    // ──────────────────────────────────────────────
    // USERS
    // ───────────────────────── Color(0xFF…) = Material Int colour
    // ──────────────────────────────────────────────

    val CurrentUser = OrbitUser(
        id = "user_current",
        username = "you",
        displayName = "You",
        avatarColor = Color_GradientStart.toArgb(),
        status = UserStatus.ONLINE
    )

    val MockUsers = listOf(
        OrbitUser(
            id = "user_1",
            username = "nova",
            displayName = "Nova ✨",
            avatarColor = Color_SuccessGreen.toArgb(),
            status = UserStatus.ONLINE
        ),
        OrbitUser(
            id = "user_2",
            username = "phoenix",
            displayName = "Phoenix",
            avatarColor = Color_WarningAmber.toArgb(),
            status = UserStatus.IDLE
        ),
        OrbitUser(
            id = "user_3",
            username = "raven",
            displayName = "Raven 🖤",
            avatarColor = Color_DNDRed.toArgb(),
            status = UserStatus.DO_NOT_DISTURB
        ),
        OrbitUser(
            id = "user_4",
            username = "stella",
            displayName = "Stella",
            avatarColor = Color_GradientMid.toArgb(),
            status = UserStatus.ONLINE
        ),
        OrbitUser(
            id = "bot_1",
            username = "orbit-bot",
            displayName = "Orbit Bot 🤖",
            avatarColor = Color_GradientStart.toArgb(),
            status = UserStatus.ONLINE,
            isBot = true
        )
    )

    // ──────────────────────────────────────────────
    // SERVERS  (each maps to the ServerRail)
    // ──────────────────────────────────────────────

    val MockServers = listOf(
        // Direct Messages server (special)
        OrbitServer(
            id = "dm",
            name = "Direct Messages",
            iconColor = Color_GradientStart.toArgb(),
            description = "Your private conversations",
            isDM = true
        ),
        OrbitServer(
            id = "srv_1",
            name = "Orbit Hub",
            iconColor = Color_GradientStart.toArgb(),
            description = "The official Orbit community server"
        ),
        OrbitServer(
            id = "srv_2",
            name = "Kotlin Devs",
            iconColor = Color_SuccessGreen.toArgb(),
            description = "Kotlin / KMP developers lounge"
        ),
        OrbitServer(
            id = "srv_3",
            name = "Creative Studio",
            iconColor = Color_WarningAmber.toArgb(),
            description = "Design, art & creative work"
        ),
        OrbitServer(
            id = "srv_4",
            name = "Chill Zone 🎵",
            iconColor = Color_DNDRed.toArgb(),
            description = "Music, memes & relaxation"
        )
    )

    // ──────────────────────────────────────────────
    // CHANNELS  (grouped per server)
    // ──────────────────────────────────────────────

    /** Categories + channels for *Orbit Hub* */
    fun getChannelsForServer(serverId: String): List<OrbitChannel> {
        return when (serverId) {
            "srv_1" -> listOf(
                // -- Text channels --
                OrbitChannel(id = "ch_welcome", name = "welcome", type = ChannelType.TEXT,
                    categoryId = "cat_general", topic = "Welcome to Orbit Hub! Read the rules."),
                OrbitChannel(id = "ch-general", name = "general", type = ChannelType.TEXT,
                    categoryId = "cat_general", topic = "Off-topic conversation"),
                OrbitChannel(id = "ch-announcements", name = "announcements", type = ChannelType.TEXT,
                    categoryId = "cat_general", topic = "Latest updates from the team"),
                // -- Category: Development --
                OrbitChannel(id = "ch-kotlin", name = "kotlin-help", type = ChannelType.TEXT,
                    categoryId = "cat_dev", topic = "Ask Kotlin / KMP questions here"),
                OrbitChannel(id = "ch-showcase", name = "showcase", type = ChannelType.TEXT,
                    categoryId = "cat_dev", topic = "Show off your projects!"),
                OrbitChannel(id = "ch-voice-dev", name = "Dev Room (Voice)", type = ChannelType.VOICE,
                    categoryId = "cat_dev"),
                // -- Category: Community --
                OrbitChannel(id = "ch-memes", name = "memes", type = ChannelType.TEXT,
                    categoryId = "cat_community", topic = "Top-tier memes only 🐸"),
                OrbitChannel(id = "ch-voice-chat", name = "Lounge (Voice)", type = ChannelType.VOICE,
                    categoryId = "cat_community"),
                // -- Category: Media --
                OrbitChannel(id = "ch-media-share", name = "media-share", type = ChannelType.TEXT,
                    categoryId = "cat_media", topic = "Share images, videos & more")
            )

            "srv_2" -> listOf(
                OrbitChannel(id = "ch-kotlin-start", name = "start-here", type = ChannelType.TEXT,
                    categoryId = "cat-root", topic = "New to Kotlin? Start here!"),
                OrbitChannel(id = "ch-kotlin-help", name = "help", type = ChannelType.TEXT,
                    categoryId = "cat-root", topic = "Get help with your code"),
                OrbitChannel(id = "ch-kotlin-voice", name = "Code & Coffee (Voice)", type = ChannelType.VOICE,
                    categoryId = "cat-root")
            )

            "srv_3" -> listOf(
                OrbitChannel(id = "ch-critique", name = "critique", type = ChannelType.TEXT,
                    categoryId = "cat-creative", topic = "Get feedback on your work"),
                OrbitChannel(id = "ch-resources", name = "resources", type = ChannelType.TEXT,
                    categoryId = "cat-creative", topic = "Tools, brushes & presets"),
                OrbitChannel(id = "ch-voice-art", name = "Art Stream (Voice)", type = ChannelType.VOICE,
                    categoryId = "cat-creative")
            )

            "srv_4" -> listOf(
                OrbitChannel(id = "ch-music-share", name = "music-share", type = ChannelType.TEXT,
                    categoryId = "cat-lounge", topic = "Share your favourite tracks"),
                OrbitChannel(id = "ch-voice-game", name = "Gaming (Voice)", type = ChannelType.VOICE,
                    categoryId = "cat-lounge")
            )

            // Direct Messages channels (private conversations)
            "dm" -> listOf(
                OrbitChannel(id = "dm-nova", name = "Nova ✨", type = ChannelType.TEXT,
                    topic = "Direct message with Nova"),
                OrbitChannel(id = "dm-phoenix", name = "Phoenix", type = ChannelType.TEXT,
                    topic = "Direct message with Phoenix"),
                OrbitChannel(id = "dm-raven", name = "Raven 🖤", type = ChannelType.TEXT,
                    topic = "Direct message with Raven"),
                OrbitChannel(id = "dm-stella", name = "Stella", type = ChannelType.TEXT,
                    topic = "Direct message with Stella"),
                OrbitChannel(id = "dm-bot", name = "Orbit Bot 🤖", type = ChannelType.TEXT,
                    topic = "Direct message with the bot")
            )

            else -> emptyList()
        }
    }

    /** Categories for display in the drawer */
    fun getCategoriesForServer(serverId: String): List<ChannelCategory> {
        return when (serverId) {
            // DMs don't have categories
            "dm" -> emptyList()
            "srv_1" -> listOf(
                ChannelCategory("cat_general", "General", "ch-welcome"),
                ChannelCategory("cat_dev", "Development", "ch-kotlin"),
                ChannelCategory("cat_community", "Community", "ch-memes"),
                ChannelCategory("cat_media", "Media", "ch-media-share")
            )
            "srv_2" -> listOf(
                ChannelCategory("cat-root", "Information", "ch-kotlin-start")
            )
            "srv_3" -> listOf(
                ChannelCategory("cat-creative", "Creative", "ch-critique")
            )
            "srv_4" -> listOf(
                ChannelCategory("cat-lounge", "Lounge", "ch-music-share")
            )
            else -> emptyList()
        }
    }

    // ──────────────────────────────────────────────
    // MESSAGES  (per channel)
    // ──────────────────────────────────────────────

    private val SampleBodies = listOf(
        "Hey everyone! Welcome to Orbit 🚀",
        "This UI looks absolutely incredible — love the dark theme.",
        "Has anyone tried Jetpack Compose Multiplatform yet?",
        "The gradient accents on the server rail are *chef's kiss* 👨‍🍳",
        "I'm working on a KMP app using this template. So far so good!",
        "Can someone share the link to the design tokens doc?",
        "Just pushed my first commit — feeling pumped! 🔥",
        "The dense message mode is such a smart UX choice.",
        "Does Orbit plan to support threads? That would be amazing.",
        "Who else is here because of Kotlin Slack? 😂",
        "Let's build something cool together!",
        "The Lite Mode gives serious WhatsApp vibes — in a good way.",
        "I think the voice channel icons need a little more pop though 🤔",
        "Agreed! Maybe a subtle glow animation?",
        "That's a great idea — I'll throw together a prototype.",
        "Can't wait to see it!",
        "Matrix backend here we come 🎯"
    )

    /** Generate mock messages for a given channel */
    fun getMessagesForChannel(channelId: String): List<OrbitMessage> {
        val baseTime = Clock.System.now()
        val msgs = mutableListOf<OrbitMessage>()

        // Pick a subset of bodies so each channel gets different content
        val shuffled = SampleBodies.shuffled()
        val count = 8 + Random.nextInt(5) // 8–12 messages

        for (i in 0..minOf(count - 1, shuffled.size - 1)) {
            val user = if (i % 4 == 0) CurrentUser else MockUsers[i % MockUsers.size]

            // manually add minutes by manipulating as duration or doing it roughly. Since mock data:
            val mockInstant = Instant.fromEpochMilliseconds(baseTime.toEpochMilliseconds() + (i * 3L * 60 * 1000))

            msgs.add(
                OrbitMessage(
                    id = "msg_${channelId}_$i",
                    userId = user.id,
                    username = user.displayName,
                    body = shuffled[i],
                    timestamp = mockInstant,
                    channelId = channelId
                )
            )
        }

        // Sort ascending so reverseLayout renders newest at bottom
        return msgs.sortedBy { it.timestamp }
    }
}
