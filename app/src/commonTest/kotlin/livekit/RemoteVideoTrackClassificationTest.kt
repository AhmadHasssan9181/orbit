package livekit

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A remote screen share must be filed as a screen share.
 *
 * `LiveKitGroupCallSurface` builds one tile per entry in `activeTracks` and
 * `CallGrid` keys its entire layout off `RenderNode.isScreenShare`: a share gets
 * the 2.5-weight spotlight slot, a camera gets an equal grid cell. So a share
 * recorded as a camera is not "a slightly smaller video" — it is the wrong UI
 * shape entirely, on the layout path that decides what is on screen at all.
 *
 * The bug being pinned here is the frame-before-trackAdded race. Native starts
 * the reader thread immediately after queueing `onRemoteVideoTrackAdded`, and
 * that queue is only run inline when the session lock is free, so the first
 * frames genuinely can land first. `updateFrame` handled that case by calling
 * `trackStarted(participantIdentity, trackSid)` — with `trackSource` defaulted
 * to `""`. An empty source makes `isScreenShare` false, so the track was
 * permanently misfiled: the later, correctly-sourced `trackAdded` overwrites the
 * entry, but by then the tile had already been built as a camera and the
 * spotlight had already been skipped.
 *
 * The fix passes the source in with every frame, because the native reader
 * already knows it. These tests drive `updateFrame` with no prior `trackStarted`
 * at all — that is exactly the raced ordering — and assert on the classification.
 */
class RemoteVideoTrackClassificationTest {

    private val sessionId = 7L
    private val epoch = 3L
    private val identity = "@peer:example.org:DEV"

    // Deliberately neutral. `isScreenShare` also matches on the track SID and
    // name containing "screen"/"share"/"desktop"/"display", so a SID like
    // "TR_SHARE" would make every camera assertion in here pass for the wrong
    // reason — these tests are about the SOURCE, and the SID must not leak the
    // answer into them.
    private val shareTrackSid = "TR_ALPHA"
    private val cameraTrackSid = "TR_BETA"

    @BeforeTest
    fun arm() {
        LiveKitRemoteVideoFrameStore.clear("test-setup")
        LiveKitRemoteVideoFrameStore.beginSession(sessionId, epoch)
    }

    @AfterTest
    fun disarm() {
        LiveKitRemoteVideoFrameStore.clear("test-teardown")
    }

    private fun pushFrame(trackSource: String, track: String, width: Int = 1280, height: Int = 720) {
        LiveKitRemoteVideoFrameStore.updateFrame(
            sessionId = sessionId,
            epoch = epoch,
            participantIdentity = identity,
            trackSid = track,
            trackSource = trackSource,
            // The store does not inspect pixel contents, so a one-byte-per-pixel
            // buffer is enough to make the frame non-empty and keep this test about
            // classification rather than about pixel data.
            buffer = ByteArray(maxOf(1, width * height)),
            width = width,
            height = height,
            format = 0,
            strideY = width * 4,
            strideU = 0,
            strideV = 0,
            timestampNs = 1_000L,
            rotation = 0
        )
    }

    @Test
    fun `a share frame arriving before trackAdded is still classified as a share`() {
        pushFrame(trackSource = "screenshare", track = shareTrackSid)

        val state = LiveKitRemoteVideoFrameStore.activeTracks.value[shareTrackSid]
        assertTrue(
            state != null && state.isScreenShare,
            "share frame with no prior trackAdded produced state=$state; " +
                "an empty trackSource makes isScreenShare false and the share is " +
                "built as a camera tile"
        )
    }

    @Test
    fun `a share frame arriving before trackAdded lands in the screenshare slot`() {
        pushFrame(trackSource = "screenshare", track = shareTrackSid)

        assertNull(
            LiveKitRemoteVideoFrameStore.latestCameraFrame.value,
            "a screen share must not land in the camera slot"
        )
        assertEquals(
            shareTrackSid,
            LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value?.trackSid
        )
    }

    @Test
    fun `a camera frame arriving before trackAdded is still classified as a camera`() {
        // The mirror of the case above, and the reason the source cannot simply be
        // dropped: without it, every frame would look like a share and take the
        // spotlight slot instead.
        pushFrame(trackSource = "camera", track = cameraTrackSid)

        val state = LiveKitRemoteVideoFrameStore.activeTracks.value[cameraTrackSid]
        assertTrue(state != null && !state.isScreenShare, "camera misfiled as a share: $state")
        assertNull(LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value)
        assertEquals(cameraTrackSid, LiveKitRemoteVideoFrameStore.latestCameraFrame.value?.trackSid)
    }

    @Test
    fun `the raced frame is addressable by track sid so trackStopped can clean it up`() {
        // The original reason updateFrame registered the track itself. If this is
        // ever "fixed" by dropping the registration, the map entry has nothing to
        // key it and trackStopped's "clear the slot showing THIS track" checks miss.
        pushFrame(trackSource = "screenshare", track = shareTrackSid)

        assertSame(
            shareTrackSid,
            LiveKitRemoteVideoFrameStore.framesByTrack.value[shareTrackSid]?.trackSid
        )

        LiveKitRemoteVideoFrameStore.trackStopped(identity, shareTrackSid)
        assertNull(LiveKitRemoteVideoFrameStore.framesByTrack.value[shareTrackSid])
        assertNull(LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value)
    }

    @Test
    fun `a later correctly sourced trackAdded corrects a raced empty-source entry`() {
        // Belt and braces: even if the raced registration somehow arrives blank,
        // the authoritative trackAdded must still win, because that is the event
        // that carries the negotiated source.
        pushFrame(trackSource = "", track = cameraTrackSid)
        LiveKitRemoteVideoFrameStore.trackStarted(identity, cameraTrackSid, trackSource = "camera", trackName = "")

        val state = LiveKitRemoteVideoFrameStore.activeTracks.value[cameraTrackSid]
        assertTrue(state != null && !state.isScreenShare, "trackAdded did not take over: $state")
    }
}
