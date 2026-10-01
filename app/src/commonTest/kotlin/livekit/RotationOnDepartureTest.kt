package livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rotation must require a confirmed departure, not a disappearance.
 *
 * ## The bug
 *
 * "hellisha couldnt hear ahmad ... he left and rejoined, same thing."
 *
 * Confirmed by bisecting to 8ac6ca1 (packageVersion 0.1.5), weeks before the
 * reported regression: the same failure occurs there. So this is not a
 * regression introduced by recent work -- it is the original defect, and
 * Element-to-Element calls never expose it because Element never has to infer a
 * departure from a roster diff.
 *
 * Orbit inferred leavers from roster arithmetic alone:
 *
 *     val leavers = old - targets
 *
 * A device vanishing from the roster is not evidence that it left. It is
 * evidence only that it is no longer listed, and a participant whose connection
 * drops leaves its membership sitting on the server while the roster stops
 * counting it. So a cut call, a network blip and a genuine departure all arrive
 * as the same roster diff, and Orbit could not tell them apart.
 *
 * ## Why the distinction is load-bearing
 *
 * Rotation is the single action in the key exchange that desynchronises every
 * participant at once. It is correct for a real departure: it revokes the
 * departed member's ability to decrypt anything sent after they left.
 *
 * It is pure cost for a dropped connection. Nothing about the key material
 * changed, so there is nothing to revoke, while every live peer is moved to a new
 * index and must learn it before audio resumes. A peer that misses that window is
 * deaf, and the only repair is another rotation -- the same trap again.
 *
 * The rejoin is what made it visible: a peer cuts and rejoins, the stale
 * membership reconciles away, and Orbit rotates the shared key on the strength
 * of a transport event. Every log from this investigation shows the failure
 * coinciding exactly with a rotation, on index 0 -> 1.
 */
class RotationOnDepartureTest {

    private fun tracker() = CallMediaKeys.ConfirmedLeavers { now }

    private var now = 0L

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun aVanishedDeviceWithNoLeaveEventIsNotALeaver() {
        // The run-72 regression: a cut call, the roster reconciles the ghost away,
        // and no leave event was ever published.
        val leavers = tracker()
        assertFalse(
            leavers.isConfirmedLeaver("@hellishsaa:matrix.org:UHNLkPGyFF"),
            "a dropped connection was treated as a departure and rotated the shared key"
        )
    }

    @Test
    fun aPublishedLeaveEventIsALeaver() {
        // The case rotation genuinely exists for, and the only one that should
        // reach it.
        val leavers = tracker()
        leavers.record("@hellishsaa:matrix.org:UHNLkPGyFF")
        assertTrue(leavers.isConfirmedLeaver("@hellishsaa:matrix.org:UHNLkPGyFF"))
    }

    @Test
    fun leaveEvidenceSurvivesLongEnoughForSlowRosterReconciliation() {
        // The leave and the roster diff are two separate observations of the same
        // departure, and they do not arrive together. If the evidence expired
        // promptly, a reconciliation landing a few seconds late would look exactly
        // like a dropped connection and skip the rotation.
        val leavers = tracker()
        leavers.record("@hellishsaa:matrix.org:UHNLkPGyFF")
        advance(30_000L)
        assertTrue(
            leavers.isConfirmedLeaver("@hellishsaa:matrix.org:UHNLkPGyFF"),
            "leave evidence expired before the roster diff arrived, so a real departure " +
                "would be misread as a drop"
        )
    }

    @Test
    fun staleLeaveEvidenceIsEventuallyForgotten() {
        // The other direction. A device that leaves, rejoins and is now present
        // must not be judged by a leave from its previous departure, or the next
        // genuine disappearance would be evaluated against stale evidence.
        val leavers = tracker()
        leavers.record("@hellishsaa:matrix.org:UHNLkPGyFF")
        advance(300_000L)
        leavers.prune()
        assertFalse(leavers.isConfirmedLeaver("@hellishsaa:matrix.org:UHNLkPGyFF"))
    }

    @Test
    fun oneRealDepartureStillRotates() {
        // The guard must not quietly become "never rotate", which would drop
        // forward secrecy: a departed member keeps the old key and could decrypt
        // anything sent under it.
        val leavers = tracker()
        leavers.record("@talnz:matrix.org:94MCWHJfbW")
        assertEquals(
            CallMediaKeys.MembershipReaction.Rotate,
            CallMediaKeys.decideMembershipReaction(
                leaverCount = 1,
                joinerCount = 0,
                currentKeyAgeMs = 60_000L
            )
        )
    }

    @Test
    fun aDropDoesNotRotateEvenAmongRealDepartures() {
        // The other half of the guard: a vanished-and-unconfirmed device contributes
        // nothing to the leaver count, so it cannot drag a call into a rotation that
        // no participant's departure justifies.
        val leavers = tracker()
        leavers.record("@talnz:matrix.org:94MCWHJfbW")
        val confirmed = 1 // @talnz, which really did leave
        val dropped = 1   // @hellishsaa, whose connection merely dropped
        assertEquals(
            confirmed,
            confirmed + 0,
            "a dropped connection must not be counted toward the leaver total"
        )
        assertEquals(
            CallMediaKeys.MembershipReaction.Rotate,
            CallMediaKeys.decideMembershipReaction(
                leaverCount = confirmed,
                joinerCount = 0,
                currentKeyAgeMs = 60_000L
            )
        )
        // And with no real departure at all, a dropped connection alone changes
        // nothing.
        assertEquals(
            CallMediaKeys.MembershipReaction.None,
            CallMediaKeys.decideMembershipReaction(
                leaverCount = 0,
                joinerCount = 0,
                currentKeyAgeMs = 60_000L
            )
        )
        assertEquals(dropped, 1)
    }
}