package ui.screen.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverServerTest {

    private val testServers = listOf(
        DiscoverServer(
            id = "#orbit:matrix.org",
            name = "Orbit Community",
            description = "Official hub for Orbit voice and chat.",
            category = "Official",
            tags = listOf("orbit", "voice", "calls", "matrix"),
            memberCount = "24.5k",
            onlineCount = "3.4k"
        ),
        DiscoverServer(
            id = "#gaming:matrix.org",
            name = "Gaming & Esports Lounge",
            description = "Multiplayer matchmaking and game nights.",
            category = "Gaming",
            tags = listOf("gaming", "esports", "steam", "fps"),
            memberCount = "38.4k",
            onlineCount = "5.1k"
        ),
        DiscoverServer(
            id = "#kotlin:matrix.org",
            name = "Kotlin Developers",
            description = "Multiplatform apps and Compose.",
            category = "Tech",
            tags = listOf("kotlin", "compose", "programming"),
            memberCount = "18.9k",
            onlineCount = "2.3k"
        )
    )

    private fun filterServers(servers: List<DiscoverServer>, query: String, category: String): List<DiscoverServer> {
        val trimmed = query.trim()
        return servers.filter { server ->
            val matchesCategory = category == "All" || server.category.equals(category, ignoreCase = true)
            val matchesQuery = trimmed.isEmpty() ||
                server.name.contains(trimmed, ignoreCase = true) ||
                server.description.contains(trimmed, ignoreCase = true) ||
                server.category.contains(trimmed, ignoreCase = true) ||
                server.id.contains(trimmed, ignoreCase = true) ||
                server.tags.any { it.contains(trimmed, ignoreCase = true) }
            matchesCategory && matchesQuery
        }
    }

    @Test
    fun emptySearchReturnsAllServers() {
        val results = filterServers(testServers, "", "All")
        assertEquals(3, results.size)
    }

    @Test
    fun searchByNameFiltersCorrectly() {
        val results = filterServers(testServers, "gaming", "All")
        assertEquals(1, results.size)
        assertEquals("Gaming & Esports Lounge", results.first().name)
    }

    @Test
    fun searchByCategoryFiltersCorrectly() {
        val results = filterServers(testServers, "", "Tech")
        assertEquals(1, results.size)
        assertEquals("Kotlin Developers", results.first().name)
    }

    @Test
    fun searchByTagFiltersCorrectly() {
        val results = filterServers(testServers, "compose", "All")
        assertEquals(1, results.size)
        assertEquals("Kotlin Developers", results.first().name)
    }

    @Test
    fun searchWithNoMatchesReturnsEmpty() {
        val results = filterServers(testServers, "nonexistent-server-xyz", "All")
        assertTrue(results.isEmpty())
    }
}
