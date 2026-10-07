package repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The plaintext-homeserver gate.
 *
 * The regression these guard: `normalize` used to accept `http://` and only print a
 * warning. That warning was invisible in the packaged app (a jpackage
 * GUI-subsystem launcher has no console), while the consequences were not: the
 * password went out in the clear in the `/login` body, and the long-lived access
 * token followed in a cleartext `Authorization: Bearer` header on every request.
 *
 * The consent model is per-host rather than a global toggle, which is itself
 * asserted below: approving `http://192.168.1.50` must not authorise
 * `http://matrix.example.com`.
 */
class HomeserverUrlNormalizerTest {

    @AfterTest
    fun clearAllowances() = HomeserverUrlNormalizer.resetInsecureAllowances()

    // ---- the gate -----------------------------------------------------------

    @Test
    fun refusesPlaintextHomeserverByDefault() {
        val error = assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://matrix.example.org")
        }
        // The message has to say what to do, since it is shown to a user.
        assertTrue(
            error.message!!.contains("cleartext"),
            "the error must explain the consequence: ${error.message}"
        )
        assertTrue(
            error.message!!.contains("https"),
            "the error must offer the fix: ${error.message}"
        )
    }

    @Test
    fun refusesPlaintextEvenWhenTypoLooking() {
        // Explicitly typed http, not a missing scheme.
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://matrix.example.org:8008")
        }
    }

    // ---- loopback stays usable ---------------------------------------------

    @Test
    fun loopbackIsExemptWithoutConsent() {
        // A local Synapse on plain http is the legitimate case and must not need
        // ceremony, because requiring consent here would just train people to
        // blanket-approve the warning everywhere else.
        assertEquals("http://localhost:8008", HomeserverUrlNormalizer.normalize("http://localhost:8008"))
        assertEquals("http://127.0.0.1:8008", HomeserverUrlNormalizer.normalize("http://127.0.0.1:8008"))
        assertEquals("http://127.0.0.53", HomeserverUrlNormalizer.normalize("http://127.0.0.53"))
        assertEquals("http://[::1]:8008", HomeserverUrlNormalizer.normalize("http://[::1]:8008"))
    }

    // ---- consent is per host -----------------------------------------------

    @Test
    fun consentAppliesOnlyToTheConfirmedHost() {
        assertTrue(HomeserverUrlNormalizer.allowInsecure("192.168.1.50"))
        // The approved host works.
        assertEquals(
            "http://192.168.1.50:8008",
            HomeserverUrlNormalizer.normalize("http://192.168.1.50:8008")
        )
        // A different host on the same scheme is still refused. This is the whole
        // reason consent is a set of hosts and not a boolean.
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://matrix.example.com")
        }
    }

    @Test
    fun consentIsCaseInsensitiveOnHost() {
        assertTrue(HomeserverUrlNormalizer.allowInsecure("Matrix.Example.ORG"))
        assertEquals(
            "http://matrix.example.org",
            HomeserverUrlNormalizer.normalize("http://MATRIX.example.org")
        )
    }

    @Test
    fun consentCanBeWithdrawn() {
        assertTrue(HomeserverUrlNormalizer.allowInsecure("lan.example"))
        assertTrue(HomeserverUrlNormalizer.isInsecureAllowed("LAN.example"))
        HomeserverUrlNormalizer.disallowInsecure("lan.example")
        assertTrue(!HomeserverUrlNormalizer.isInsecureAllowed("lan.example"))
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://lan.example")
        }
    }

    @Test
    fun blankHostCannotBeApproved() {
        assertTrue(!HomeserverUrlNormalizer.allowInsecure(""))
        assertTrue(!HomeserverUrlNormalizer.allowInsecure("   "))
    }

    // ---- https is unaffected ----------------------------------------------

    @Test
    fun httpsStillNormalizesExactlyAsBefore() {
        assertEquals("https://matrix.example.org", HomeserverUrlNormalizer.normalize("https://matrix.example.org"))
        assertEquals("https://matrix.example.org", HomeserverUrlNormalizer.normalize("matrix.example.org"))
        assertEquals("https://matrix.example.org", HomeserverUrlNormalizer.normalize("https://matrix.example.org/"))
        // Non-default port survives.
        assertEquals("https://matrix.example.org:8448", HomeserverUrlNormalizer.normalize("https://matrix.example.org:8448"))
        // Reverse-proxied Synapse path is preserved.
        assertEquals(
            "https://example.org/matrix",
            HomeserverUrlNormalizer.normalize("https://example.org/matrix/")
        )
    }

    @Test
    fun nonHttpSchemesAreStillRejected() {
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("ftp://matrix.example.org")
        }
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("file:///etc/passwd")
        }
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("")
        }
    }

    /**
     * Consent must not leak across hosts via substring, which is the same class of
     * bug as the origin check in OriginGuard.
     */
    @Test
    fun consentIsNotSubstringMatched() {
        assertTrue(HomeserverUrlNormalizer.allowInsecure("matrix.example.org"))
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://evil-matrix.example.org.attacker.com")
        }
        assertFailsWith<IllegalArgumentException> {
            HomeserverUrlNormalizer.normalize("http://notmatrix.example.org")
        }
    }
}