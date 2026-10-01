package livekit

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Makes the participant-lifecycle imbalance a number instead of something a human
 * has to grep and count.
 *
 * Run 62 produced 16 `remote participant connected` callbacks and 1 disconnect, with
 * audio-track removal never firing. Both sides were already logged, so nothing said
 * "these should balance" -- which is why the imbalance had to be discovered by hand
 * rather than being reported by the thing that noticed it.
 *
 * Most of these pin the arithmetic rather than the diagnosis, because the arithmetic
 * is the part that can be wrong quietly: a counter that clamps, floors, or resets
 * wrongly would make a real imbalance look balanced, which is the one failure this
 * cannot have.
 */
class NativeParticipantLedgerTest {

    @AfterTest
    fun clean() = NativeParticipantLedger.reset()

    @Test
    fun balancedLifecycleReportsNothingPending() {
        NativeParticipantLedger.onConnect("@a")
        NativeParticipantLedger.onDisconnect("@a")
        assertEquals(0, NativeParticipantLedger.pending)
    }

    @Test
    fun anUnclosedParticipantIsPending() {
        // The run-62 shape: a connect with no disconnect behind it.
        NativeParticipantLedger.onConnect("@a")
        assertEquals(1, NativeParticipantLedger.pending)
    }

    @Test
    fun pendingNeverGoesNegative() {
        // A disconnect for an identity that was never recorded. Clamped at zero on
        // purpose: a negative "pending" reads as "we cleaned up more than we made",
        // which is a different bug and must not be hidden behind a floor that also
        // hides the bug this exists to show.
        NativeParticipantLedger.onDisconnect("@ghost")
        assertEquals(0, NativeParticipantLedger.pending)
    }

    @Test
    fun repeatedRejoinsAccumulate() {
        // The actual scenario: a peer rejoining several times over a call.
        repeat(5) {
            NativeParticipantLedger.onConnect("@talnz")
            NativeParticipantLedger.onDisconnect("@talnz")
        }
        assertEquals(0, NativeParticipantLedger.pending, "every rejoin was matched and cleaned up")
    }

    @Test
    fun reconnectsWithoutDisconnectsAccumulate() {
        // A peer who rejoins and whose disconnect never arrives. This is the leak
        // shape, and it must be visible as a growing count rather than as a hunch.
        repeat(5) { NativeParticipantLedger.onConnect("@talnz") }
        assertEquals(5, NativeParticipantLedger.pending)
    }

    @Test
    fun videoTracksAreCountedSeparatelyFromParticipants() {
        // Deliberately independent. In run 62 the participant-connected callback and
        // the video-track-added callback both called participantConnected, so the
        // connect total could have been inflated by track adds rather than by native
        // re-announcing participants. Keeping the counters apart is what makes those
        // two hypotheses distinguishable instead of a matter of opinion.
        NativeParticipantLedger.onConnect("@a")
        repeat(3) { NativeParticipantLedger.onVideoTrackAdded() }
        assertEquals(1, NativeParticipantLedger.pending, "a track add must not read as a participant")
        assertTrue(
            NativeParticipantLedger.line().contains("video +3/-0"),
            "track adds were not visible: ${NativeParticipantLedger.line()}"
        )
    }

    @Test
    fun removedTracksAreVisibleToo() {
        NativeParticipantLedger.onVideoTrackAdded()
        NativeParticipantLedger.onVideoTrackRemoved()
        assertTrue(
            NativeParticipantLedger.line().contains("video +1/-1"),
            "a removal was not visible: ${NativeParticipantLedger.line()}"
        )
    }

    @Test
    fun theLineNamesWhoRejoinedHowOften() {
        // "16 connects" is a number. "@talnz x5" is the name, so the tally points at
        // a person instead of requiring a diff against the rest of the log.
        repeat(3) { NativeParticipantLedger.onConnect("@talnz") }
        NativeParticipantLedger.onConnect("@other")
        assertTrue(
            NativeParticipantLedger.line().contains("@talnzx3"),
            "repeat joins not attributed: ${NativeParticipantLedger.line()}"
        )
        assertTrue(
            NativeParticipantLedger.line().contains("@otherx1"),
            "single join not attributed: ${NativeParticipantLedger.line()}"
        )
    }

    @Test
    fun aCleanSessionLeavesNothingBehind() {
        repeat(3) {
            NativeParticipantLedger.onConnect("@a")
            NativeParticipantLedger.onVideoTrackAdded()
            NativeParticipantLedger.onDisconnect("@a")
            NativeParticipantLedger.onVideoTrackRemoved()
        }
        val line = NativeParticipantLedger.line()
        assertTrue(line.contains("connects=3 disconnects=3 pending=0"), "unbalanced: $line")
        assertTrue(line.contains("video +3/-3"), "video unbalanced: $line")
    }
}
