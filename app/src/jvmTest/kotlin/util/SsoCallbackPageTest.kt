package util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SSO callback page and the callback validator.
 *
 * Two things are pinned here:
 *
 *  1. **The page renders for failures too.** Every rejection used to throw with no
 *     HTTP response written, so the browser showed `ERR_CONNECTION_RESET` while the
 *     app window quietly explained what had happened. The screen the user is
 *     guaranteed to be looking at was the one saying nothing.
 *  2. **An absent `state` is a rejection.** The attack shape for the login-CSRF is a
 *     callback with *no* state at all, so treating absence as "compatible" left the
 *     original vulnerability open.
 */
class SsoCallbackPageTest {

    // ---- validation ---------------------------------------------------

    private val state = "expected-state-value"

    private fun params(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun acceptsACallbackWithMatchingStateAndAToken() {
        val outcome = resolveCallback(
            params("state" to state, "loginToken" to "syt_abc123"),
            expectedState = state
        )
        assertEquals(SsoOutcome.LoginToken("syt_abc123"), outcome)
    }

    @Test
    fun rejectsAnAbsentState() {
        // The original regression: a callback with no state at all used to skip
        // verification entirely, which is the login-CSRF shape. It is now its own
        // outcome so the login screen can offer consent rather than just failing.
        val outcome = resolveCallback(params("loginToken" to "attacker-token"), expectedState = state)
        assertEquals(SsoOutcome.StateMissing, outcome)
    }

    @Test
    fun rejectsAMismatchedState() {
        val outcome = resolveCallback(
            params("state" to "some-other-state", "loginToken" to "attacker-token"),
            expectedState = state
        )
        assertTrue(outcome is SsoOutcome.Rejected, "got $outcome")
        assertTrue((outcome as SsoOutcome.Rejected).detail.contains("mismatch"))
    }

    @Test
    fun rejectsAMissingLoginToken() {
        val outcome = resolveCallback(params("state" to state), expectedState = state)
        assertTrue(outcome is SsoOutcome.Rejected, "got $outcome")
    }

    @Test
    fun rejectsABlankLoginToken() {
        val outcome = resolveCallback(
            params("state" to state, "loginToken" to "   "),
            expectedState = state
        )
        assertTrue(outcome is SsoOutcome.Rejected, "got $outcome")
    }

    @Test
    fun anEmptyCallbackHasNoStateAndSoAsksTheUser() {
        assertEquals(SsoOutcome.StateMissing, resolveCallback(emptyMap(), expectedState = state))
    }

    // ---- the missing-state case, which is NOT a plain rejection ----------
    //
    // Measured against a real homeserver that does not implement the `state` echo:
    // every callback arrived with no state and SSO was refused outright. Absent is
    // therefore its own outcome, so the login screen can explain and offer consent
    // instead of the user just being told "failed".

    @Test
    fun anAbsentStateIsItsOwnOutcomeNotARejection() {
        val outcome = resolveCallback(params("loginToken" to "syt_x"), expectedState = state)
        assertEquals(SsoOutcome.StateMissing, outcome)
    }

    @Test
    fun consentLetsAnAbsentStateThrough() {
        val outcome = resolveCallback(
            params("loginToken" to "syt_x"),
            expectedState = state,
            allowMissingState = true
        )
        assertEquals(SsoOutcome.LoginToken("syt_x"), outcome)
    }

    /**
     * The invariant that matters most: consent weakens the *missing* case only. A
     * wrong state is either a stale tab or an attack and has no legitimate reading,
     * so it must stay refused even for a homeserver the user has opted in for.
     */
    @Test
    fun consentNeverAcceptsAMismatchedState() {
        val outcome = resolveCallback(
            params("state" to "wrong", "loginToken" to "attacker-token"),
            expectedState = state,
            allowMissingState = true
        )
        assertTrue(outcome is SsoOutcome.Rejected, "got $outcome")
        assertTrue((outcome as SsoOutcome.Rejected).detail.contains("mismatch"))
    }

    @Test
    fun consentStillRequiresALoginToken() {
        val outcome = resolveCallback(params("state" to state), expectedState = state, allowMissingState = true)
        assertTrue(outcome is SsoOutcome.Rejected, "got $outcome")
    }

    // ---- rendering ----------------------------------------------------

    @Test
    fun successPageIsSelfContainedAndOnBrand() {
        val html = SsoCallbackPage.success("matrix.example.org")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("matrix.example.org"))
        // No outbound requests of any kind: no web font, no CDN, no favicon, no script.
        for (forbidden in listOf("<script", "http://", "https://", "@import", "//fonts.")) {
            assertTrue(
                !html.contains(forbidden),
                "the login page must not reference anything external: found '$forbidden'"
            )
        }
        // The CSP sent in the response header has to match what the markup actually
        // needs, or the browser blocks the page and the user sees nothing. Inline
        // `<style>` is the only privilege it uses, so `style-src 'unsafe-inline'` has
        // to be there -- and `default-src 'none'` only works while there is no script
        // or external resource, which the assertion above enforces.
        assertTrue(SsoCallbackPage.CSP.contains("style-src 'unsafe-inline'"))
        assertTrue(SsoCallbackPage.CSP.contains("default-src 'none'"))
        assertTrue(html.contains("<style"), "the page's styling is inline, which the CSP permits")
    }

    @Test
    fun failurePageIsAlsoSelfContained() {
        val html = SsoCallbackPage.failure("something went wrong")
        for (forbidden in listOf("<script", "http://", "https://")) {
            assertTrue(!html.contains(forbidden), "must not reference anything external: $forbidden")
        }
    }

    /** The state-missing page must tell the user there is a way forward, not just that it failed. */
    @Test
    fun stateMissingPagePointsAtTheLoginScreen() {
        val html = SsoCallbackPage.failure(
            "This homeserver did not return the one-time value Orbit uses to confirm the " +
                "response belongs to this sign-in. You can allow it for this homeserver from " +
                "the Orbit login screen."
        )
        assertTrue(html.contains("login screen"), "should say where the option lives")
        assertTrue(html.contains("homeserver"))
    }

    @Test
    fun failurePageRendersTheReason() {
        val html = SsoCallbackPage.failure("The sign-in response did not match this login attempt.")
        assertTrue(html.contains("did not match"))
        assertTrue(html.contains("failed") || html.contains("didn&#39;t") || html.contains("didn't"))
    }

    /**
     * The detail string is built from an exception message, and a Ktor exception
     * message can carry a homeserver's response body. Reflecting that unescaped into
     * a page the user is looking at is a reflected-XSS vector.
     */
    @Test
    fun escapesUntrustedDetailText() {
        val nasty = "<script>alert('xss')</script> & \"quoted\""
        val html = SsoCallbackPage.failure(nasty)
        assertTrue(!html.contains("<script>"), "must not emit a raw script tag")
        assertTrue(html.contains("&lt;script&gt;"), "should be entity-escaped")
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&quot;"))
    }

    @Test
    fun escapesTheHomeserverHostOnTheSuccessPage() {
        val html = SsoCallbackPage.success("matrix.example.org\"><script>x</script>")
        assertTrue(!html.contains("<script>x"), "host must be escaped")
    }

    @Test
    fun escapeHtmlHandlesTheFiveSignificantCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", SsoCallbackPage.escapeHtml("&<>\"'"))
        assertEquals("nothing to do", SsoCallbackPage.escapeHtml("nothing to do"))
    }

    @Test
    fun setsNoReferrerAndNoIndexOnTheSuccessPage() {
        val html = SsoCallbackPage.success(null)
        assertTrue(html.contains("""name="referrer" content="no-referrer""""))
        assertTrue(html.contains("noindex"))
    }

    @Test
    fun successPageOmitsTheHomeserverRowWhenThereIsNoHost() {
        val html = SsoCallbackPage.success(null)
        assertTrue(!html.contains("Homeserver"), "should not render an empty homeserver row")
    }

    // ---- display host -------------------------------------------------

    @Test
    fun hostOfExtractsTheHostAndSurvivesGarbage() {
        assertEquals("matrix.example.org", hostOf("https://matrix.example.org"))
        assertEquals("matrix.example.org", hostOf("https://matrix.example.org:8448/matrix"))
        assertEquals(null, hostOf("not a url"))
        assertEquals(null, hostOf(""))
    }
}