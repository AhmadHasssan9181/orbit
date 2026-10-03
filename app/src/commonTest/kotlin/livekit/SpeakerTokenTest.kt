package livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One person, two names, in one call.
 *
 * Two real group-call logs (from two different participants) contained the same
 * human reported twice in a single speaking event:
 *
 * ```
 * [@marioreo:matrix.org:djpYnE6QIj, TR_AMpAsYgfYnJ4z7]
 * [@marioreo:matrix.org:djpYnE6QIj]
 * [TR_AMpAsYgfYnJ4z7]
 * ```
 *
 * Consequences that follow directly from a duplicate: the speaking ring lights
 * the wrong tile or counts one person as two; per-participant volume and mute
 * are applied through an identity matcher, so a track-SID key never matches a
 * real identity and that track stays at unity gain; and the call surface grew a
 * workaround to avoid rendering one human as two tiles.
 *
 * These pin the Kotlin-side guard. The C++ no longer produces the `TR_` token,
 * but the UI must not be able to lie if an older native DLL is ever in flight.
 */
class SpeakerTokenTest {

    /** The exact token pairs observed in the two logs. */
    @Test
    fun aDeviceSuffixedIdentityAndATrackSidAreOnePerson() {
        val raw = listOf("@marioreo:matrix.org:djpYnE6QIj", "TR_AMpAsYgfYnJ4z7")
        val normalized = raw.map { normalizeSpeakerToken(it) }.filter { it.isNotBlank() }

        assertEquals(
            listOf("@marioreo:matrix.org"),
            normalized,
            "a device-suffixed identity and a track SID are the SAME human and must collapse"
        )
    }

    @Test
    fun aTrackSidIsNeverAPerson() {
        assertEquals("", normalizeSpeakerToken("TR_AMpAsYgfYnJ4z7"))
        assertEquals("", normalizeSpeakerToken("TR_AM5hCuCBRHmiNR"))
        // Participant SIDs too -- same class of identifier, same reason.
        assertEquals("", normalizeSpeakerToken("PA_NoTSngNzpkyh"))
    }

    @Test
    fun theDeviceSuffixIsStripped() {
        assertEquals(
            "@ahmad9812:matrix.org",
            normalizeSpeakerToken("@ahmad9812:matrix.org:rkpOhK4TpM")
        )
        assertEquals(
            "@hamzamasudbutt:matrix.org",
            normalizeSpeakerToken("@hamzamasudbutt:matrix.org:BzPLGdcHdw")
        )
    }

    /**
     * The case that makes a naive `substringBeforeLast(':')` wrong. Stripping
     * unconditionally turns `@user:server` into `@user`, which matches nobody and
     * would merge two different people on the same homeserver-adjacent id.
     */
    @Test
    fun aBareIdentityIsNotMangled() {
        assertEquals("@marioreo:matrix.org", normalizeSpeakerToken("@marioreo:matrix.org"))
        assertEquals("@a:b", normalizeSpeakerToken("@a:b"))
    }

    @Test
    fun twoPeopleOnOneServerStayDistinct() {
        val raw = listOf("@ali:matrix.org:X1", "@ahmad:matrix.org:X2")
        val normalized = raw.map { normalizeSpeakerToken(it) }.toSet()

        assertEquals(setOf("@ali:matrix.org", "@ahmad:matrix.org"), normalized)
    }

    /** The exact reduction the session performs, including the set collapse. */
    @Test
    fun theObservedEventCollapsesToOneEntry() {
        val event = listOf("@marioreo:matrix.org:djpYnE6QIj", "TR_AMpAsYgfYnJ4z7")
        val speakers = event.map { normalizeSpeakerToken(it) }.filter { it.isNotBlank() }.toSet()

        assertEquals(1, speakers.size, "one human must occupy one speaking slot")
        assertEquals(setOf("@marioreo:matrix.org"), speakers)
    }

    @Test
    fun blankAndNullAreDropped() {
        assertEquals("", normalizeSpeakerToken(null))
        assertEquals("", normalizeSpeakerToken(""))
        assertEquals("", normalizeSpeakerToken("   "))
    }

    /**
     * A malformed Matrix id is passed through rather than dropped, because the
     * blank check is the only gate and silently swallowing a token we merely failed
     * to parse would hide a future format change -- which is how the original bug
     * got in.
     *
     * `@a:b:c:d` is four segments, so one device suffix IS stripped and the result
     * is `@a:b:c`, a legitimate three-segment shape rather than a mangling.
     */
    @Test
    fun aMalformedMatrixIdIsPassedThroughNotSwallowed() {
        assertEquals("@weird", normalizeSpeakerToken("@weird"))
        assertEquals("@a:b:c", normalizeSpeakerToken("@a:b:c:d"))
    }

    /**
     * Anything that is not a Matrix id is NOT a person and is dropped. This is the
     * opposite policy to a malformed `@` id on purpose: `nonsense` cannot be a
     * person under any reading, so keeping it would put a nameless entry in a set
     * the UI renders as humans.
     */
    @Test
    fun aNonMatrixTokenIsDropped() {
        assertEquals("", normalizeSpeakerToken("nonsense"))
        assertEquals("", normalizeSpeakerToken("participant"))
        assertEquals("", normalizeSpeakerToken("livekit"))
    }

    /** Idempotent: normalizing an already-normal token must not keep eating it. */
    @Test
    fun normalizationIsIdempotent() {
        val once = normalizeSpeakerToken("@marioreo:matrix.org:djpYnE6QIj")
        assertEquals(once, normalizeSpeakerToken(once))
    }

    /**
     * Domain dots are the reason the arity check exists. A matrix.org id has no
     * dot in the domain, so prove a dotted domain survives intact rather than
     * being truncated at the wrong separator.
     */
    @Test
    fun aDottedDomainIsNotTruncated() {
        assertEquals(
            "@user:example.co.uk",
            normalizeSpeakerToken("@user:example.co.uk:DEVICE1")
        )
        assertEquals("@user:example.co.uk", normalizeSpeakerToken("@user:example.co.uk"))
    }
}
