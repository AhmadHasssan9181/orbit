package livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The "I can hear everyone but I can see nobody" bug.
 *
 * A user in a group call reported that the grid collapsed to just their own tile
 * while audio stayed perfect in both directions, and that hanging up and rejoining
 * was the only thing that brought the tiles back.
 *
 * ## What actually happened
 *
 * A LiveKit token refresh calls `refreshAndReconnect`, which runs
 * `configureRemoteVideoSink("reconnect")`. That calls
 * `LiveKitRemoteVideoFrameStore.clear("reconnect-start")`, which empties
 * `_activeTracks` and `_remoteParticipants` -- and every tile the call surface
 * draws is built from exactly those two collections. So the grid drops to the
 * hardcoded local "You" node. Audio never touches this store, which is why the
 * call still sounded normal.
 *
 * The roster was then never refilled, and that was the real defect. Native
 * deduplicates participant announcements in `g_announcedParticipantSids`, and
 * `resetRoomLocked()` cleared `g_announcedVideoPublications` but NOT the
 * participant set. So on the new room the rescan re-announced video and silently
 * skipped every participant, `participantConnected` was never called again, and
 * the store stayed empty. Only the two full-disconnect paths cleared that set,
 * which is exactly why a manual reconnect was the only cure.
 *
 * These pin the Kotlin-visible half of that contract: that `clear()` empties the
 * roster (so the fix has to happen where it is refilled), and that the store
 * reports what it dropped so the window is visible in a user's log.
 */
class RemoteRosterClearTest {

    private val sessionId = 11L
    private val epoch = 5L

    private fun arm() {
        LiveKitRemoteVideoFrameStore.clear("test-setup")
        LiveKitRemoteVideoFrameStore.beginSession(sessionId, epoch)
    }

    private fun disarm() {
        LiveKitRemoteVideoFrameStore.clear("test-teardown")
    }

    @Test
    fun clearEmptiesEverySourceTheTilesAreBuiltFrom() {
        arm()
        try {
            LiveKitRemoteVideoFrameStore.participantConnected("@peer:example.org:DEV")
            LiveKitRemoteVideoFrameStore.trackAnnounced("@peer:example.org:DEV", "TR_ALPHA", "camera")
            assertTrue(LiveKitRemoteVideoFrameStore.remoteParticipants.value.isNotEmpty())
            assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.isNotEmpty())

            LiveKitRemoteVideoFrameStore.clear("reconnect-start")

            // Both empty means the call surface can only produce its local node.
            // This is the collapse; the tests below pin that it is recoverable.
            assertTrue(
                LiveKitRemoteVideoFrameStore.remoteParticipants.value.isEmpty(),
                "clear() must empty the participant set -- that IS the bug"
            )
            assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.isEmpty())
        } finally {
            disarm()
        }
    }

    /**
     * The recovery the native dedup bug prevented. `participantConnected` is what
     * a re-announcement from `nativeRescanRemoteVideoTracks` ends up calling, so
     * the store must accept it and repopulate the roster after a clear. It is a
     * plain set insert with no "already announced" memory of its own -- the
     * dedup that broke this lived in C++, which is why fixing it there was the
     * whole fix and nothing here needed to change.
     */
    @Test
    fun theRosterRepopulatesAfterClearWhenNativeReAnnounces() {
        arm()
        try {
            LiveKitRemoteVideoFrameStore.participantConnected("@a:example.org:DEV")
            LiveKitRemoteVideoFrameStore.participantConnected("@b:example.org:DEV")
            LiveKitRemoteVideoFrameStore.clear("reconnect-start")
            assertTrue(LiveKitRemoteVideoFrameStore.remoteParticipants.value.isEmpty())

            // What the rescan does once the participant dedup set is cleared.
            LiveKitRemoteVideoFrameStore.participantConnected("@a:example.org:DEV")
            LiveKitRemoteVideoFrameStore.participantConnected("@b:example.org:DEV")

            assertEquals(
                setOf("@a:example.org:DEV", "@b:example.org:DEV"),
                LiveKitRemoteVideoFrameStore.remoteParticipants.value
            )
        } finally {
            disarm()
        }
    }

    /**
     * Re-announcement must be idempotent from the store's side too, since the
     * native rescan runs every 2 seconds and legitimately re-announces a
     * participant that is still connected. A set insert, not an append.
     */
    @Test
    fun repeatedReAnnouncementDoesNotDuplicateAParticipant() {
        arm()
        try {
            repeat(5) {
                LiveKitRemoteVideoFrameStore.participantConnected("@peer:example.org:DEV")
            }
            assertEquals(1, LiveKitRemoteVideoFrameStore.remoteParticipants.value.size)
        } finally {
            disarm()
        }
    }

    /**
     * `trackStarted` is the authoritative path and also registers the
     * participant. A tile that arrives via a real track must bring its owner
     * back with it, or a camera-only peer renders a tile with no roster entry.
     */
    @Test
    fun trackStartedRegistersItsParticipantToo() {
        arm()
        try {
            LiveKitRemoteVideoFrameStore.clear("reconnect-start")
            LiveKitRemoteVideoFrameStore.trackStarted("@peer:example.org:DEV", "TR_ALPHA", "camera")

            assertEquals(
                setOf("@peer:example.org:DEV"),
                LiveKitRemoteVideoFrameStore.remoteParticipants.value
            )
            assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey("TR_ALPHA"))
        } finally {
            disarm()
        }
    }

    /** A blank identity is not a participant; accepting it would key a tile on "". */
    @Test
    fun blankIdentitiesAreIgnored() {
        arm()
        try {
            LiveKitRemoteVideoFrameStore.participantConnected("")
            LiveKitRemoteVideoFrameStore.participantConnected("   ")
            assertTrue(LiveKitRemoteVideoFrameStore.remoteParticipants.value.isEmpty())
        } finally {
            disarm()
        }
    }

    @Test
    fun opaqueSubscriberConnectionStaysOutOfTheVisibleRoster() {
        arm()
        try {
            val ghost = "f".repeat(64)
            LiveKitRemoteVideoFrameStore.participantConnected(ghost)

            assertTrue(ghost in LiveKitRemoteVideoFrameStore.rawRemoteParticipants.value)
            assertFalse(ghost in LiveKitRemoteVideoFrameStore.remoteParticipants.value)

            LiveKitRemoteVideoFrameStore.participantDisconnected(ghost)
            assertTrue(LiveKitRemoteVideoFrameStore.rawRemoteParticipants.value.isEmpty())
        } finally {
            disarm()
        }
    }

    @Test
    fun opaqueIdentityBecomesVisibleOnceItPublishesMedia() {
        arm()
        try {
            val opaquePublisher = "opaque-publisher".repeat(4)
            LiveKitRemoteVideoFrameStore.participantConnected(opaquePublisher)
            assertFalse(opaquePublisher in LiveKitRemoteVideoFrameStore.remoteParticipants.value)

            LiveKitRemoteVideoFrameStore.trackAnnounced(opaquePublisher, "TR_OPAQUE", "camera")

            assertTrue(opaquePublisher in LiveKitRemoteVideoFrameStore.remoteParticipants.value)
            assertTrue(opaquePublisher in LiveKitRemoteVideoFrameStore.rawRemoteParticipants.value)
        } finally {
            disarm()
        }
    }

    /** Clearing with nothing present is the normal disconnect path and must be quiet. */
    @Test
    fun clearingAnEmptyStoreIsHarmless() {
        arm()
        try {
            LiveKitRemoteVideoFrameStore.clear("nothing-here")
            assertTrue(LiveKitRemoteVideoFrameStore.remoteParticipants.value.isEmpty())
            assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.isEmpty())
        } finally {
            disarm()
        }
    }
}
