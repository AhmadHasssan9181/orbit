package webrtc

import livekit.NativeError
import livekit.NativeErrorCode
import livekit.NativeMediaReadiness
import livekit.NativeMediaState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupCallConnectionTrackerTest {

    private var clockMs = 1_000_000L
    private val tracker = GroupCallConnectionTracker(nowMs = { clockMs })

    private fun readiness(
        state: NativeMediaState,
        audioPublished: Boolean = true,
        videoPublished: Boolean = false,
        acceptingAudioFrames: Boolean = true,
        audioOnlyFallback: Boolean = false,
        errorMessage: String? = null
    ) = NativeMediaReadiness(
        sessionId = 1L,
        epoch = 1L,
        state = state,
        connected = state != NativeMediaState.Idle && state != NativeMediaState.Failed,
        audioPublished = audioPublished,
        videoPublished = videoPublished,
        acceptingAudioFrames = acceptingAudioFrames,
        acceptingVideoFrames = videoPublished,
        cameraRequested = videoPublished,
        cameraRequired = false,
        cameraState = livekit.CameraPublishState.NotRequested,
        joinedAsAudioOnlyFallback = audioOnlyFallback,
        tokenRefreshScheduled = false,
        lastError = errorMessage?.let {
            NativeError(NativeErrorCode.CONNECT_FAILED, it, "test", recoverable = false)
        }
    )

    @Test
    fun voiceCallLadderReachesInCall() {
        tracker.begin()
        assertEquals(GroupCallStage.Joining, tracker.connection.value.stage)

        tracker.onMembershipPublished(true)
        assertEquals(GroupCallStage.Joined, tracker.connection.value.stage)

        tracker.onNativeReadiness(readiness(NativeMediaState.LiveKitConnected))
        assertEquals(GroupCallStage.Connected, tracker.connection.value.stage)

        tracker.onNativeReadiness(readiness(NativeMediaState.AudioPublished))
        assertEquals(GroupCallStage.AudioPublished, tracker.connection.value.stage)

        val stable = tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)
        assertTrue(stable)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
    }

    @Test
    fun videoCallReachesInCallOnlyOnRemoteFrame() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = true)

        assertEquals(GroupCallStage.VideoPublished, tracker.connection.value.stage)

        tracker.onFirstRemoteFrame()
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
    }

    @Test
    fun invisibleMembershipFailsVisibly() {
        tracker.begin()
        tracker.onMembershipPublished(false)

        val connection = tracker.connection.value
        assertEquals(GroupCallStage.Failed, connection.stage)
        assertEquals(GroupCallStage.Joining, connection.failedStage)
        assertTrue(connection.message?.contains("Membership") == true)
    }

    @Test
    fun failedFinalizeSurfacesStageAndMessage() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.onNativeReadiness(readiness(NativeMediaState.LiveKitConnected))

        val stable = tracker.finalizeJoin(
            readiness(NativeMediaState.Failed, audioPublished = false, acceptingAudioFrames = false, errorMessage = "room closed"),
            videoRequested = false
        )

        assertFalse(stable)
        val connection = tracker.connection.value
        assertEquals(GroupCallStage.Failed, connection.stage)
        assertEquals("room closed", connection.message)
    }

    @Test
    fun watchdogGivesUpWhenConnectStalls() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        clockMs += GroupCallConnectionTracker.CONNECT_TIMEOUT_MS + 1

        assertEquals(TrackerDecision.GiveUp, tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = true))
    }

    @Test
    fun watchdogGivesUpWhenMembershipStalls() {
        tracker.begin()
        clockMs += GroupCallConnectionTracker.MEMBERSHIP_TIMEOUT_MS + 1

        assertEquals(TrackerDecision.GiveUp, tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = false))
    }

    @Test
    fun joiningProgressStampExtendsMembershipDeadline() {
        tracker.begin()
        clockMs += GroupCallConnectionTracker.MEMBERSHIP_TIMEOUT_MS + 1

        // Without a stamp the breached Joining deadline gives up…
        assertEquals(
            TrackerDecision.GiveUp,
            tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = false)
        )

        // …but stamping progress opens a fresh window instead.
        tracker.noteJoiningProgress()
        assertEquals(
            TrackerDecision.None,
            tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = false)
        )

        clockMs += GroupCallConnectionTracker.MEMBERSHIP_TIMEOUT_MS + 1
        assertEquals(
            TrackerDecision.GiveUp,
            tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = false)
        )
    }

    @Test
    fun joiningProgressStampIsNoOpAfterJoining() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        clockMs += 5_000

        tracker.noteJoiningProgress()
        // Joined deadline must NOT have been reset by the stamp.
        clockMs += GroupCallConnectionTracker.CONNECT_TIMEOUT_MS + 1
        assertEquals(
            TrackerDecision.GiveUp,
            tracker.evaluateDeadlines(clockMs, videoRequested = false, remoteMediaExpected = true)
        )
    }

    @Test
    fun soloVideoCallSettlesToInCall() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = true)
        assertEquals(GroupCallStage.VideoPublished, tracker.connection.value.stage)

        clockMs += GroupCallConnectionTracker.SOLO_ROOM_SETTLE_MS + 1
        val decision = tracker.evaluateDeadlines(clockMs, videoRequested = true, remoteMediaExpected = false)

        assertEquals(TrackerDecision.None, decision)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
    }

    @Test
    fun missingRemoteMediaIsASoftWarningNotAFailure() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = true)

        clockMs += GroupCallConnectionTracker.REMOTE_MEDIA_SOFT_WARN_MS + 1
        val decision = tracker.evaluateDeadlines(clockMs, videoRequested = true, remoteMediaExpected = true)

        assertEquals(TrackerDecision.None, decision)
        assertTrue(tracker.connection.value.waitingForRemoteMedia)
    }

    @Test
    fun reconnectingIsSurfacedAndLadderResumesAfter() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)

        tracker.onNativeReadiness(readiness(NativeMediaState.Reconnecting, audioPublished = false, acceptingAudioFrames = false))
        assertEquals(GroupCallStage.Reconnecting, tracker.connection.value.stage)

        tracker.onNativeReadiness(readiness(NativeMediaState.AudioPublished))
        assertEquals(GroupCallStage.AudioPublished, tracker.connection.value.stage)
    }

    @Test
    fun audioOnlyFallbackIsSurfaced() {
        tracker.begin()
        tracker.onNativeReadiness(
            readiness(NativeMediaState.MediaReady, videoPublished = false, audioOnlyFallback = true)
        )
        assertTrue(tracker.connection.value.audioOnlyFallback)
    }

    @Test
    fun retryBumpsAttemptAndReturnsToJoined() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.onNativeReadiness(readiness(NativeMediaState.LiveKitConnected))

        assertTrue(tracker.noteRetryStarted())
        val connection = tracker.connection.value
        assertEquals(GroupCallStage.Joined, connection.stage)
        assertEquals(2, connection.attempt)
    }

    @Test
    fun resetReturnsToIdle() {
        tracker.begin()
        tracker.onMembershipPublished(false)
        assertEquals(GroupCallStage.Failed, tracker.connection.value.stage)

        tracker.reset()
        assertEquals(GroupCallStage.Idle, tracker.connection.value.stage)
        assertNull(tracker.connection.value.failedStage)
        assertFalse(tracker.connection.value.audioOnlyFallback)
    }

    @Test
    fun failedTrackerIgnoresFurtherAdvances() {
        tracker.begin()
        tracker.onMembershipPublished(false)

        tracker.onMembershipPublished(true)
        tracker.onNativeReadiness(readiness(NativeMediaState.MediaReady))
        tracker.onFirstRemoteFrame()

        assertEquals(GroupCallStage.Failed, tracker.connection.value.stage)
    }

    // ── H1: mid-call reconnect completion ──

    @Test
    fun reconnectCompleteRestoresInCallFromReconnecting() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)

        tracker.onNativeReadiness(readiness(NativeMediaState.Reconnecting, audioPublished = false, acceptingAudioFrames = false))
        assertEquals(GroupCallStage.Reconnecting, tracker.connection.value.stage)

        tracker.onReconnectComplete()
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
        assertFalse(tracker.connection.value.waitingForRemoteMedia)
        assertNull(tracker.connection.value.message)
    }

    @Test
    fun reconnectCompleteIsNoOpOutsideReconnecting() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)

        tracker.onReconnectComplete()
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
    }

    // ── Reconnecting must be bounded, like every other stage ──
    //
    // Reconnecting used to fall through to the `else` branch of
    // evaluateDeadlines, which returns None unconditionally. Every other stage on
    // the ladder has a deadline; this one had none, so a socket that died and never
    // came back left the call advertising "Reconnecting…" forever while the
    // reconnect loop kept firing and the token kept refreshing.

    @Test
    fun reconnectingIsNotGivenUpOnImmediately() {
        givenReconnecting()

        // Well inside the window: a transient drop must not kill a live call.
        assertEquals(
            TrackerDecision.None,
            tracker.evaluateDeadlines(
                now = nowPlus(GroupCallConnectionTracker.RECONNECT_TIMEOUT_MS - 5_000),
                videoRequested = false,
                remoteMediaExpected = true
            )
        )
        assertEquals(GroupCallStage.Reconnecting, tracker.connection.value.stage)
    }

    @Test
    fun reconnectingIsGivenUpOnceItsDeadlinePasses() {
        givenReconnecting()

        assertEquals(
            TrackerDecision.GiveUp,
            tracker.evaluateDeadlines(
                now = nowPlus(GroupCallConnectionTracker.RECONNECT_TIMEOUT_MS + 5_000),
                videoRequested = false,
                remoteMediaExpected = true
            )
        )
    }

    @Test
    fun aReconnectThatKeepsSucceedingIsNeverGivenUpOn() {
        // The deadline must measure the CURRENT attempt, not total time in the
        // stage. A call that drops, recovers, drops again, recovers again is
        // healthy and must not be failed by the sum of its outages -- onReconnectComplete
        // re-stamps the stage-entry time, and this pins that it still does.
        repeat(3) {
            givenReconnecting()
            assertEquals(
                TrackerDecision.None,
                tracker.evaluateDeadlines(
                    now = nowPlus(GroupCallConnectionTracker.RECONNECT_TIMEOUT_MS - 5_000),
                    videoRequested = false,
                    remoteMediaExpected = true
                )
            )
            tracker.onReconnectComplete()
            assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
        }
    }

    /**
     * `evaluateDeadlines` derives elapsed time as `now - stageEnteredAtMs`, and
     * `stageEnteredAtMs` is private. Offsetting `now` forward on the INJECTED
     * clock is therefore the way to ask "what would you decide in N milliseconds",
     * which is exactly the question these tests are asking. Using the wall clock
     * here instead would silently compare against a `stageEnteredAtMs` stamped
     * from `clockMs`, and every assertion would be measuring nothing.
     */
    private fun nowPlus(offsetMs: Long): Long = clockMs + offsetMs

    private fun givenReconnecting() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)
        tracker.onNativeReadiness(
            readiness(NativeMediaState.Reconnecting, audioPublished = false, acceptingAudioFrames = false)
        )
        assertEquals(GroupCallStage.Reconnecting, tracker.connection.value.stage)
    }

    @Test
    fun midCallFailureFromInCallFailsWithMessage() {
        tracker.begin()
        tracker.onMembershipPublished(true)
        tracker.finalizeJoin(readiness(NativeMediaState.MediaReady), videoRequested = false)
        assertEquals(GroupCallStage.InCall, tracker.connection.value.stage)

        tracker.onNativeReadiness(
            readiness(NativeMediaState.Failed, audioPublished = false, acceptingAudioFrames = false, errorMessage = "reconnect budget exhausted")
        )
        assertEquals(GroupCallStage.Failed, tracker.connection.value.stage)
        assertEquals("reconnect budget exhausted", tracker.connection.value.message)
    }
}
