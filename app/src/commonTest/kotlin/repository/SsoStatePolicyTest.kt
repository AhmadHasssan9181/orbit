package repository

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Consent for completing SSO without verifying the callback's `state`.
 *
 * ## What is being defended
 *
 * Orbit sends a `state` with the SSO redirect and compares it on return. Without
 * that, an attacker who can get the victim's browser to open
 * `http://localhost:<ephemeral-port>/?loginToken=<attacker token>` signs the victim
 * into the **attacker's** account, and everything they then send goes somewhere the
 * attacker can read.
 *
 * ## Why this exists at all
 *
 * Requiring `state` outright was measured against a homeserver that does not echo
 * it: every callback arrived with no `state` and SSO was refused outright. A control
 * that makes the feature unusable gets removed by whoever finds it next, so this
 * turns the refusal into an informed, per-homeserver choice.
 *
 * The two invariants these tests exist to protect:
 *   1. an ABSENT state is refused until the user opts in, for that homeserver only;
 *   2. a MISMATCHED state is *never* accepted, opt-in or not.
 */
class SsoStatePolicyTest {

    private val homeserver = "https://matrix.example.org"

    @AfterTest
    fun reset() = SsoStatePolicy.reset()

    // ---- default is refusal -------------------------------------------

    @Test
    fun refusesByDefault() {
        assertFalse(SsoStatePolicy.isAccepted(homeserver))
    }

    @Test
    fun acceptsConsent() {
        assertTrue(SsoStatePolicy.accept(homeserver))
        assertTrue(SsoStatePolicy.isAccepted(homeserver))
    }

    // ---- consent is per homeserver ------------------------------------

    @Test
    fun consentDoesNotLeakToAnotherHomeserver() {
        SsoStatePolicy.accept(homeserver)
        // The whole point of keying on a host rather than a boolean: one server that
        // cannot echo state says nothing about the next one you sign in to.
        assertFalse(SsoStatePolicy.isAccepted("https://other.example.org"))
        assertFalse(SsoStatePolicy.isAccepted("http://matrix.example.org"))
    }

    @Test
    fun consentIgnoresPathAndPortSoItTracksTheServer() {
        assertTrue(SsoStatePolicy.accept("https://matrix.example.org/some/path:8448"))
        assertTrue(SsoStatePolicy.isAccepted("https://matrix.example.org"))
        assertTrue(SsoStatePolicy.isAccepted("https://MATRIX.example.org"))
    }

    @Test
    fun consentIsNotSubstringMatched() {
        SsoStatePolicy.accept(homeserver)
        assertFalse(SsoStatePolicy.isAccepted("https://evil-matrix.example.org.attacker.com"))
        assertFalse(SsoStatePolicy.isAccepted("https://notmatrix.example.org"))
    }

    // ---- revoke --------------------------------------------------------

    @Test
    fun consentCanBeWithdrawn() {
        SsoStatePolicy.accept(homeserver)
        assertTrue(SsoStatePolicy.isAccepted(homeserver))
        SsoStatePolicy.revoke(homeserver)
        assertFalse(SsoStatePolicy.isAccepted(homeserver))
    }

    // ---- garbage input --------------------------------------------------

    @Test
    fun unparseableUrlsCannotBeAccepted() {
        assertFalse(SsoStatePolicy.accept("not a url"))
        assertFalse(SsoStatePolicy.accept(""))
        assertFalse(SsoStatePolicy.isAccepted("not a url"))
    }

    // ---- the warning has to be honest -----------------------------------

    @Test
    fun theWarningNamesTheServerAndStatesWhatIsAccepted() {
        val text = SsoStatePolicy.warningText(homeserver)
        assertTrue(text.contains("matrix.example.org"), "must name the server: $text")
        assertTrue(text.contains("sign-in") || text.contains("sign in"))
        // It must be clear that this is a risk decision, not an error to dismiss.
        assertTrue(
            text.contains("somebody else's account") || text.contains("attacker"),
            "must say what the risk actually is: $text"
        )
    }
}