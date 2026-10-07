package repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trust boundary on room-supplied SFU URLs.
 *
 * ## What is being defended
 *
 * `m.call` room state carries `livekit_service_url`, and **any member of a room can
 * write room state**. That URL ends up as the host the user's OpenID token is
 * POSTed to (`LiveKitTokenProvider` -> `<url>/get_token`). The OpenID token is a
 * single-use bearer credential the SFU exchanges for a LiveKit JWT, so a hostile
 * definition pointing at an attacker-controlled host gets:
 *
 *  - the victim's MXID, device id and homeserver;
 *  - a token replayable at the real `lk-jwt-service` to mint a JWT *as the
 *    victim* -- call impersonation.
 *
 * The rule is that a room may only select among SFUs the homeserver itself
 * advertised (Settings override or `.well-known` `rtc_foci`). It cannot invent one.
 */
class SfuUrlPolicyTest {

    private fun ctx(
        override: String? = null,
        wellKnown: String? = null
    ): MatrixContext {
        // MatrixContext's constructor normalises and validates the URL, which is the
        // behaviour under test elsewhere; here a real https URL is all that is needed.
        val c = MatrixContext("https://matrix.example.org")
        c.liveKitServiceUrlOverride = override
        c.setWellKnownRtcFocusUrlForTest(wellKnown)
        return c
    }

    @AfterTest
    fun reset() = Unit

    // ---- the room-supplied URL is only used when the homeserver named it ----

    @Test
    fun acceptsAnSfuTheWellKnownAdvertised() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        assertTrue(SfuUrlPolicy.isAdmissible("https://livekit.example.org", c))
    }

    @Test
    fun acceptsAnSfuTheUserConfiguredInSettings() {
        val c = ctx(override = "https://my-sfu.example.org")
        assertTrue(SfuUrlPolicy.isAdmissible("https://my-sfu.example.org", c))
    }

    @Test
    fun refusesAnSfuNobodyAdvertised() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        // The attack: a room member publishes an m.call definition naming their host.
        assertFalse(SfuUrlPolicy.isAdmissible("https://attacker.example", c))
        assertNull(SfuUrlPolicy.resolve("https://attacker.example", c))
    }

    // ---- substring lookalikes must not inherit consent ------------------

    @Test
    fun refusesSubstringLookalikesOfTheAdvertisedSfu() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        val lookalikes = listOf(
            "https://evil-livekit.example.org",
            "https://livekit.example.org.attacker.com",
            "https://livekit.example.org@attacker.com",
            "https://notlivekit.example.org",
            "https://livekit.example.org.co",
        )
        for (url in lookalikes) {
            assertFalse(
                SfuUrlPolicy.isAdmissible(url, c),
                "a lookalike host must not inherit the advertised Sfu's consent: $url"
            )
        }
    }

    // ---- scheme and port are part of the origin --------------------------

    @Test
    fun refusesAPlaintextVersionOfTheAdvertisedHost() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        // Same host, http: the OpenID token would go out in cleartext.
        assertFalse(SfuUrlPolicy.isAdmissible("http://livekit.example.org", c))
    }

    @Test
    fun refusesADifferentPortOnTheAdvertisedHost() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        assertFalse(SfuUrlPolicy.isAdmissible("https://livekit.example.org:8448", c))
    }

    @Test
    fun acceptsTheAdvertisedHostWithItsExplicitDefaultPort() {
        val c = ctx(wellKnown = "https://livekit.example.org:443")
        assertTrue(SfuUrlPolicy.isAdmissible("https://livekit.example.org", c))
        assertTrue(SfuUrlPolicy.isAdmissible("https://livekit.example.org:443", c))
    }

    // ---- "we have not checked yet" must not mean "allow everything" ------

    @Test
    fun refusesEverythingWhenNothingHasBeenAdvertised() {
        val c = ctx()
        // Before refreshWellKnownRtcFoci completes there is no allow-list. Failing
        // closed sends the caller to its configured fallback, which is right;
        // treating "unknown" as "allow" is what the vulnerability was.
        assertFalse(SfuUrlPolicy.isAdmissible("https://livekit.example.org", c))
        assertTrue(SfuUrlPolicy.allowedOrigins(c).isEmpty())
    }

    // ---- a blank hint is "no hint", not a rejection ----------------------

    @Test
    fun aBlankHintIsPassedThroughForTheCallersOwnFallback() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        assertTrue(SfuUrlPolicy.isAdmissible(null, c))
        assertTrue(SfuUrlPolicy.isAdmissible("", c))
        assertTrue(SfuUrlPolicy.isAdmissible("   ", c))
        // ...but resolve() still returns null, so the caller applies its fallback.
        assertNull(SfuUrlPolicy.resolve(null, c))
        assertNull(SfuUrlPolicy.resolve("", c))
    }

    // ---- unparseable input is refused, not guessed ----------------------

    @Test
    fun refusesUnparseableCandidates() {
        val c = ctx(wellKnown = "https://livekit.example.org")
        for (bad in listOf("not a url", "//livekit.example.org", "livekit.example.org")) {
            assertFalse(SfuUrlPolicy.isAdmissible(bad, c), "must refuse: $bad")
        }
    }

    // ---- both sources are honoured --------------------------------------

    @Test
    fun honoursBothTheOverrideAndTheWellKnown() {
        val c = ctx(
            override = "https://override.example.org",
            wellKnown = "https://wellknown.example.org"
        )
        assertEquals(
            setOf("https://override.example.org:443", "https://wellknown.example.org:443"),
            SfuUrlPolicy.allowedOrigins(c)
        )
        assertTrue(SfuUrlPolicy.isAdmissible("https://override.example.org", c))
        assertTrue(SfuUrlPolicy.isAdmissible("https://wellknown.example.org", c))
    }

    @Test
    fun ignoresBlankAllowances() {
        val c = ctx(override = "   ", wellKnown = "")
        assertTrue(SfuUrlPolicy.allowedOrigins(c).isEmpty())
    }
}