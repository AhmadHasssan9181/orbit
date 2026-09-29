package repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server discovery is how a self-hoster actually finds their own homeserver: the
 * "continue in browser" SSO flow hands back a URL, not a Matrix ID, so users
 * routinely have a domain rather than a base URL.
 */
class ServerDiscoveryTest {

    @Test
    fun `a full mxid yields its server name`() {
        assertEquals("example.org", matrixServerNameFrom("@alice:example.org"))
        assertEquals("example.org:8448", matrixServerNameFrom("@alice:example.org:8448"))
        assertEquals("matrix.example.org", matrixServerNameFrom("@bob:matrix.example.org"))
    }

    @Test
    fun `a url yields its host`() {
        assertEquals("matrix.example.org", matrixServerNameFrom("https://matrix.example.org"))
        assertEquals("matrix.example.org", matrixServerNameFrom("https://matrix.example.org/"))
        assertEquals("example.org", matrixServerNameFrom("https://example.org:8448"))
    }

    @Test
    fun `a bare domain is accepted`() {
        assertEquals("example.org", matrixServerNameFrom("example.org"))
        assertEquals("matrix.example.org", matrixServerNameFrom("matrix.example.org/"))
    }

    @Test
    fun `a tailscale magicdns name is an ordinary server name`() {
        // This is the recommended self-hosting shape, and it needs no special
        // handling anywhere in the client.
        assertEquals("matrix.yak-bebop.ts.net", matrixServerNameFrom("matrix.yak-bebop.ts.net"))
        assertEquals(
            "matrix.yak-bebop.ts.net",
            matrixServerNameFrom("@alice:matrix.yak-bebop.ts.net")
        )
    }

    @Test
    fun `nonsense yields null rather than a bogus host`() {
        assertNull(matrixServerNameFrom(""))
        assertNull(matrixServerNameFrom("   "))
    }

    @Test
    fun `a well-known body yields its base url`() {
        val raw = """
            {
              "m.homeserver": { "base_url": "https://matrix.example.org" },
              "org.matrix.msc4143.rtc_foci": [
                { "type": "livekit", "livekit_service_url": "https://lk.example.org" }
              ]
            }
        """.trimIndent()
        assertEquals("https://matrix.example.org", parseWellKnownClient(raw))
    }

    @Test
    fun `a trailing slash on base_url is stripped`() {
        val raw = """{"m.homeserver":{"base_url":"https://matrix.example.org/"}}"""
        assertEquals("https://matrix.example.org", parseWellKnownClient(raw))
    }

    @Test
    fun `a subpath base url is preserved`() {
        val raw = """{"m.homeserver":{"base_url":"https://example.org/matrix"}}"""
        assertEquals("https://example.org/matrix", parseWellKnownClient(raw))
    }

    @Test
    fun `a relative or missing base url is rejected, not guessed at`() {
        assertNull(parseWellKnownClient("""{"m.homeserver":{"base_url":"/_matrix"}}"""))
        assertNull(parseWellKnownClient("""{"m.homeserver":{}}"""))
        assertNull(parseWellKnownClient("""{}"""))
        assertNull(parseWellKnownClient("not json at all"))
    }

    @Test
    fun `a real versions response is recognised`() {
        assertTrue(looksLikeMatrixVersions("""{"versions":["r0.0.1","v1.1","v1.11"]}"""))
    }

    @Test
    fun `something that is not a matrix server is rejected`() {
        // The point of validating is to catch a well-known pointing at a
        // captive portal or a parking page, which otherwise fails much later
        // with a confusing error.
        assertTrue(!looksLikeMatrixVersions("""{"error":"not found"}"""))
        assertTrue(!looksLikeMatrixVersions("<html>hello</html>"))
        assertTrue(!looksLikeMatrixVersions(""))
    }
}
