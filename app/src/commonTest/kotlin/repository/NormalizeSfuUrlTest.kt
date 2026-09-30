package repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The self-hosted SFU URL.
 *
 * This value is concatenated onto "/get_token" and "/sfu/get" without a scheme being
 * added, so a stored "livekit.example.com" produces a relative URL and the failure
 * lands much later as a call that silently never connects -- with nothing anywhere
 * pointing at the setting. The validator is the only thing standing between a bad
 * paste and that, so it gets pinned rather than eyeballed.
 */
class NormalizeSfuUrlTest {

    @Test
    fun blankIsTheAutoDetectPath() {
        // Null here means "store empty", which is the documented default: ask the
        // homeserver's .well-known, then fall back to the public dev SFU.
        assertNull(normalizeSfuUrl(""))
        assertNull(normalizeSfuUrl("   "))
    }

    @Test
    fun aSchemeLessValueIsRejected() {
        // The whole reason this function exists.
        assertNull(normalizeSfuUrl("livekit.example.com"))
        assertNull(normalizeSfuUrl("//livekit.example.com"))
        assertNull(normalizeSfuUrl("ftp://livekit.example.com"))
        assertNull(normalizeSfuUrl("javascript:alert(1)"))
    }

    @Test
    fun bothHttpAndHttpsAreAccepted() {
        assertEquals("https://livekit.example.com", normalizeSfuUrl("https://livekit.example.com"))
        assertEquals("http://192.168.1.10:7880", normalizeSfuUrl("http://192.168.1.10:7880"))
    }

    @Test
    fun schemeMatchingIsCaseInsensitiveButTheValueIsStoredAsTyped() {
        // "HTTPS://" is accepted -- the check lowercases -- but the stored value
        // keeps the case the user typed. HTTP schemes are case-insensitive, so the
        // URL works either way, and rewriting a value the user just entered is a
        // good way to make them wonder what else the app is changing.
        assertEquals("HTTPS://livekit.example.com", normalizeSfuUrl("HTTPS://livekit.example.com"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        // Nobody types a URL without a stray leading space, and rejecting one is
        // not a useful thing to do.
        assertEquals("https://livekit.example.com", normalizeSfuUrl("  https://livekit.example.com  "))
    }

    @Test
    fun trailingSlashesAreRemovedRatherThanRejected() {
        assertEquals("https://livekit.example.com", normalizeSfuUrl("https://livekit.example.com/"))
        assertEquals("https://livekit.example.com", normalizeSfuUrl("https://livekit.example.com///"))
    }

    @Test
    fun aPathPrefixIsKeptBecauseTheTokenEndpointsHangOffIt() {
        // A LiveKit deployment behind a reverse proxy is routinely mounted at a
        // sub-path, and "/rtc/get_token" is built by appending to this value. Trimming
        // the path off would silently point the client at the wrong place.
        assertEquals("https://example.com/livekit", normalizeSfuUrl("https://example.com/livekit/"))
    }

    @Test
    fun aSchemeWithNothingAfterItIsNotMangledIntoSomethingElse() {
        // "https://" is not a working SFU, but it is syntactically a URL and the
        // failure is then a clear connection error naming this host, rather than a
        // relative-path error that names nothing. What it must NOT become is
        // "https:" -- a naive trimEnd('/') does exactly that, which is a worse value
        // than the one the user typed and yields a token URL with no scheme at all.
        assertEquals("https://", normalizeSfuUrl("https://"))
        assertEquals("http://", normalizeSfuUrl("http:///"))
    }
}