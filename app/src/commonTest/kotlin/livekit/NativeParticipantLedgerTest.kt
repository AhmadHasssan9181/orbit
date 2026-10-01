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
        // The actual scenario: a peer rejoining several times over a call. Each
        // cycle is connect-then-disconnect under a fresh sid, so every rejoin is a
        // real arrival that was then really cleaned up.
        repeat(5) { i ->
            NativeParticipantLedger.onConnect("@talnz", "PA_$i")
            NativeParticipantLedger.onDisconnect("@talnz", "PA_$i")
        }
        assertEquals(0, NativeParticipantLedger.pending, "every rejoin was matched and cleaned up")
        assertEquals(5, NativeParticipantLedger.connectsTotalForTest, "every rejoin is still counted as an arrival")
    }

    @Test
    fun reconnectsWithoutDisconnectsAccumulate() {
        // A peer who rejoins and whose disconnect never arrives. This is the leak
        // shape, and it must be visible as a growing count rather than as a hunch.
        //
        // Each join gets a distinct sid, which is what a real rejoin looks like:
        // same identity, new sid. Passing the same sid five times would be five
        // callbacks about ONE arrival, which is the other bug and is tested
        // separately below.
        repeat(5) { i -> NativeParticipantLedger.onConnect("@talnz", "PA_$i") }
        assertEquals(5, NativeParticipantLedger.pending)
    }

    @Test
    fun repeatedCallbacksAboutOneArrivalAreNotFiveParticipants() {
        // The run-82 shape, and the reason `pending` used to read 8 for a room
        // holding one person. Native announces a participant from five delegate
        // sites -- onParticipantConnected, onTrackPublished, onTrackSubscribed,
        // the connect-time roster walk and the rescan -- so one peer with three
        // tracks is announced eight times. Every one of those carries the SAME sid,
        // because it is the same arrival.
        //
        // This is the distinction the whole ledger turns on: a count of callbacks
        // cannot tell "eight announcements" apart from "eight participants", so an
        // imbalance could never be told apart from a busy call.
        repeat(8) { NativeParticipantLedger.onConnect("@talnz", "PA_one") }
        assertEquals(1, NativeParticipantLedger.pending, "eight announcements of one arrival are one participant")
        assertTrue(
            NativeParticipantLedger.line().contains("dupConnects=7"),
            "the suppressed announcements were not visible: ${NativeParticipantLedger.line()}"
        )
    }

    @Test
    fun aRejoinAfterADepartureIsANewArrivalNotADuplicate() {
        // The case that makes the sid keying load-bearing rather than fussy.
        // A peer leaves and comes back: the identity is unchanged, the sid is not.
        // Keyed on identity, the second arrival would be swallowed as a duplicate
        // and pending would stay 0 -- a diagnostic that reports a healthy call as
        // balanced, which is worse than one that is merely wrong.
        NativeParticipantLedger.onConnect("@talnz", "PA_first")
        NativeParticipantLedger.onDisconnect("@talnz", "PA_first")
        assertEquals(0, NativeParticipantLedger.pending)
        NativeParticipantLedger.onConnect("@talnz", "PA_second")
        assertEquals(1, NativeParticipantLedger.pending, "the rejoin is a real arrival and must be counted")
        assertEquals(
            0,
            NativeParticipantLedger.line().substringAfter("dupConnects=").substringBefore(' ').toInt(),
            "a rejoin must not be counted as a duplicate callback"
        )
    }

    @Test
    fun oneArrivalAndOneDepartureBalanceEvenWhenTheCallbackRepeats() {
        // The combination that must hold: the rescan re-announces a live peer on
        // every tick, so connects genuinely arrive after the peer is already
        // present. A repeat announcement followed by the real departure has to end
        // balanced, or a normal call reports UNBALANCED at teardown forever.
        repeat(4) { NativeParticipantLedger.onConnect("@talnz", "PA_one") }
        NativeParticipantLedger.onDisconnect("@talnz", "PA_one")
        assertEquals(0, NativeParticipantLedger.pending, "the departure still cleaned up the one arrival")
    }

    @Test
    fun aDepartureAfterAPeerRejoinedDoesNotClearTheNewArrival() {
        // A late disconnect for the OLD sid, arriving after the peer has already
        // rejoined under a new one. If the keys did not agree this would cancel the
        // live participant and report pending=0 while someone is still in the room.
        NativeParticipantLedger.onConnect("@talnz", "PA_first")
        NativeParticipantLedger.onConnect("@talnz", "PA_second")
        NativeParticipantLedger.onDisconnect("@talnz", "PA_first")
        assertEquals(1, NativeParticipantLedger.pending, "the rejoin must survive a late disconnect for the old sid")
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
        //
        // Five arrivals for @talnz: each a fresh sid with no disconnect between
        // them, which is the run-62 leak shape. Counting them is the point -- if
        // these were the same sid the count would stay at 1, and the growth this
        // test exists to make visible would be silently swallowed.
        repeat(5) { i -> NativeParticipantLedger.onConnect("@talnz", "PA_$i") }
        NativeParticipantLedger.onConnect("@other", "PA_other")
        assertTrue(
            NativeParticipantLedger.line().contains("@talnzx5"),
            "repeat joins not attributed: ${NativeParticipantLedger.line()}"
        )
        assertTrue(
            NativeParticipantLedger.line().contains("@otherx1"),
            "single join not attributed: ${NativeParticipantLedger.line()}"
        )
    }

    @Test
    fun aCleanSessionLeavesNothingBehind() {
        repeat(3) { i ->
            NativeParticipantLedger.onConnect("@a", "PA_$i")
            NativeParticipantLedger.onVideoTrackAdded()
            NativeParticipantLedger.onDisconnect("@a", "PA_$i")
            NativeParticipantLedger.onVideoTrackRemoved()
        }
        val line = NativeParticipantLedger.line()
        assertTrue(line.contains("connects=3 disconnects=3 pending=0"), "unbalanced: $line")
        assertTrue(line.contains("video +3/-3"), "video unbalanced: $line")
    }
}
