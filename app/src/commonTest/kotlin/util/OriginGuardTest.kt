package util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the credential-attachment origin check.
 *
 * The two tests that matter most are [rejectsTheAttackersHostThatStartedThis] and
 * [rejectsSubstringLookalikeHosts]: both are attacks that the previous
 * `url.contains("/_matrix/") || host.contains(hsHost)` implementation accepted.
 * Everything else documents the normalization decisions so they are not silently
 * changed into something permissive later.
 */
class OriginGuardTest {

    private val homeserver = "https://matrix.example.org"

    // ---- the attack that motivated this file --------------------------------

    @Test
    fun rejectsTheAttackersHostThatStartedThis() {
        // `m.room.banner` is room state any member can write. A banner pointing at
        // `/_matrix/` satisfied the old substring test and got the viewer's access
        // token appended in the Authorization header.
        assertFalse(
            OriginGuard.mayAttachCredential(
                "https://attacker.example/_matrix/logo.png",
                homeserver
            )
        )
        // Same attack with the path buried deeper in the URL.
        assertFalse(
            OriginGuard.mayAttachCredential(
                "https://attacker.example/anything/_matrix/media/v3/download/x",
                homeserver
            )
        )
        // And with the real homeserver's hostname in the attacker URL's query string.
        assertFalse(
            OriginGuard.mayAttachCredential(
                "https://attacker.example/img.png?next=https://matrix.example.org/_matrix/",
                homeserver
            )
        )
    }

    @Test
    fun rejectsSubstringLookalikeHosts() {
        // `matrix.example.org` is the homeserver; these all *contain* it as text.
        val lookalikes = listOf(
            "https://evil-matrix.example.org/_matrix/client/v3/account/whoami",
            "https://matrix.example.org.attacker.com/_matrix/client/v3/account/whoami",
            "https://matrix.example.org.co/_matrix/client/v3/account/whoami",
            "https://notmatrix.example.org/_matrix/client/v3/account/whoami",
            "https://matrix.example.org@attacker.com/_matrix/client/v3/account/whoami",
        )
        for (url in lookalikes) {
            assertFalse(
                OriginGuard.mayAttachCredential(url, homeserver),
                "must not attach credentials to lookalike host: $url"
            )
        }
    }

    // ---- legitimate traffic must keep working -------------------------------

    @Test
    fun acceptsTheHomeserverSownMatrixApiPaths() {
        val allowed = listOf(
            "https://matrix.example.org/_matrix/media/v3/download/server/abc",
            "https://matrix.example.org/_matrix/client/v3/profile/displayname",
            "https://matrix.example.org/_matrix/client/v1/media/download/x",
            "https://matrix.example.org/_matrix",
        )
        for (url in allowed) {
            assertTrue(
                OriginGuard.mayAttachCredential(url, homeserver),
                "must attach credentials to own homeserver: $url"
            )
        }
    }

    @Test
    fun acceptsExplicitDefaultPortAndTrailingDot() {
        // All of these are the same origin to DNS as `https://matrix.example.org`.
        assertTrue(OriginGuard.isSameOrigin("https://matrix.example.org:443/_matrix/x", homeserver))
        assertTrue(OriginGuard.isSameOrigin("https://MATRIX.EXAMPLE.ORG/_matrix/x", homeserver))
        assertTrue(OriginGuard.isSameOrigin("https://matrix.example.org./_matrix/x", homeserver))
    }

    @Test
    fun acceptsReverseProxiedHomeserverPath() {
        // Synapse behind a reverse proxy is commonly at https://example.org/matrix,
        // so the origin still matches and the token must still be attached.
        assertTrue(
            OriginGuard.mayAttachCredential(
                "https://example.org/matrix/_matrix/client/v3/sync",
                "https://example.org/matrix"
            )
        )
    }

    @Test
    fun honoursNonDefaultPort() {
        val lan = "https://matrix.lan:8448"
        assertTrue(OriginGuard.isSameOrigin("https://matrix.lan:8448/_matrix/x", lan))
        // Same host, default port is a different origin.
        assertFalse(OriginGuard.isSameOrigin("https://matrix.lan/_matrix/x", lan))
    }

    // ---- scheme and port must match, not just the host ----------------------

    @Test
    fun rejectsDifferentSchemeOnSameHost() {
        // https homeserver, http request: a cleartext request must never carry a
        // token minted for the TLS origin.
        assertFalse(
            OriginGuard.mayAttachCredential(
                "http://matrix.example.org/_matrix/client/v3/sync",
                homeserver
            )
        )
    }

    @Test
    fun rejectsNonMatrixPathOnTheHomeserverOrigin() {
        // Origin matches, but this is not a Matrix API path. Attachment is refused
        // because there is no reason for us to authenticate a non-Matrix request to
        // our own homeserver.
        assertFalse(
            OriginGuard.mayAttachCredential(
                "https://matrix.example.org/anything-else",
                homeserver
            )
        )
    }

    // ---- refuse to guess ----------------------------------------------------

    @Test
    fun refusesUnparseableAndRelativeUrls() {
        assertFalse(OriginGuard.mayAttachCredential("", homeserver))
        assertFalse(OriginGuard.mayAttachCredential("/_matrix/client/v3/sync", homeserver))
        assertFalse(OriginGuard.mayAttachCredential("not a url at all", homeserver))
        assertFalse(OriginGuard.mayAttachCredential("https://", homeserver))
        assertNull(OriginGuard.originOf("/relative/path"))
        assertNull(OriginGuard.originOf("   "))
    }

    @Test
    fun refusesWhenHomeserverIsUnknown() {
        // No configured homeserver means no basis for an origin comparison, so no
        // credential goes out. Failing closed here is the point.
        assertFalse(OriginGuard.mayAttachCredential("https://matrix.example.org/_matrix/x", null))
        assertFalse(OriginGuard.mayAttachCredential("https://matrix.example.org/_matrix/x", ""))
        assertFalse(OriginGuard.mayAttachCredential("https://matrix.example.org/_matrix/x", "  "))
    }

    // ---- normalization details, pinned so they cannot loosen ---------------

    @Test
    fun comparesEffectivePorts() {
        assertEquals(
            OriginGuard.Origin("https", "example.org", 443),
            OriginGuard.originOf("https://example.org")
        )
        assertEquals(
            OriginGuard.Origin("https", "example.org", 443),
            OriginGuard.originOf("https://example.org:443")
        )
        // http:80 and https:443 are different origins despite the same host.
        assertFalse(
            OriginGuard.isSameOrigin("http://example.org/_matrix/x", "https://example.org")
        )
    }

    @Test
    fun originToStringIsStableForLogging() {
        assertEquals(
            "https://example.org:443",
            OriginGuard.originOf("https://example.org")?.toString()
        )
    }

    /**
     * Documents that [OriginGuard.isMatrixPath] is a *loose* secondary filter, and
     * that the security boundary is the origin check.
     *
     * The path test intentionally matches `/_matrix/` anywhere in the path, because
     * Orbit supports Synapse behind a reverse proxy at `https://example.org/matrix`,
     * where the real API path is `/matrix/_matrix/...`. An anchored test would
     * break that supported deployment.
     *
     * That looseness is safe only because [isMatrixOrigin] requires the origin to
     * match first, so `https://attacker.example/evil/_matrix/x` still gets no
     * credential. These two tests pin that relationship, so the path test cannot
     * later be tightened or loosened without someone noticing which half matters.
     */
    @Test
    fun pathTestIsLooseButOriginTestIsTheBoundary() {
        // Loose: a nested /_matrix/ is accepted as Matrix traffic.
        assertTrue(OriginGuard.isMatrixPath("https://example.org/matrix/_matrix/x"))
        assertTrue(OriginGuard.isMatrixPath("https://example.org/_matrix/x"))
        assertFalse(OriginGuard.isMatrixPath("https://example.org/not-matrix-api/x"))

        // ...and that is harmless, because the origin check still refuses it.
        assertFalse(
            OriginGuard.mayAttachCredential("https://example.org/evil/_matrix/x", homeserver)
        )
        assertFalse(
            OriginGuard.mayAttachCredential("https://attacker.example/matrix/_matrix/x", homeserver)
        )
        // Same host, genuine Matrix path: allowed.
        assertTrue(
            OriginGuard.mayAttachCredential("https://example.org/matrix/_matrix/x", "https://example.org")
        )
    }
}