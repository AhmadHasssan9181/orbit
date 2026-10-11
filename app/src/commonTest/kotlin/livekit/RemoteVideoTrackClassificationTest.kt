package livekit

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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

    /**
     * Pushes a frame with a buffer sized to match the stride, which is what the
     * store requires. The default stays one-byte-per-pixel so these tests remain
     * about classification rather than about pixel data; [bufferBytes] is the
     * override for the validation tests.
     */
    private fun pushFrame(
        trackSource: String,
        track: String,
        width: Int = 1280,
        height: Int = 720,
        strideY: Int = width * 4,
        bufferBytes: Int = maxOf(1, strideY * height)
    ) {
        LiveKitRemoteVideoFrameStore.updateFrame(
            sessionId = sessionId,
            epoch = epoch,
            participantIdentity = identity,
            trackSid = track,
            trackSource = trackSource,
            // The store does not inspect pixel contents, only that the buffer is
            // large enough to hold the picture, so the bytes are zeros either way.
            buffer = ByteArray(bufferBytes),
            width = width,
            height = height,
            format = 0,
            strideY = strideY,
            strideU = 0,
            strideV = 0,
            timestampNs = 1_000L,
            rotation = 0
        )
    }

    @Test
    fun `an 8x8 frame is rejected rather than published`() {
        // Shipped in 0.2.1: a run logged `width=8 height=8 bytes=256` and the frame
        // was accepted. Nothing downstream checks the arithmetic, so the renderer
        // allocated an 8x8 image and stretched it across the tile -- a frozen blocky
        // smear on a track that was otherwise live and delivering. To a user that
        // is "the video is stuck", and there is no way to tell that from a stalled
        // network.
        //
        // 256 bytes is 4 bytes per pixel for 8x8, so the buffer is not obviously
        // empty -- which is the whole point. The fault is the frame contradicting
        // itself, not a missing buffer, and the pre-0.2.2 guard only caught
        // width<=0 / height<=0 / empty buffer. This one cleared all three.
        pushFrame(
            trackSource = "screenshare",
            track = shareTrackSid,
            width = 8,
            height = 8,
            strideY = 32,
            bufferBytes = 256
        )

        assertNull(
            LiveKitRemoteVideoFrameStore.latestFrame.value,
            "an 8x8 frame was published as a real picture"
        )
        assertTrue(
            LiveKitRemoteVideoFrameStore.activeTracks.value[shareTrackSid] == null,
            "a rejected frame must not register the track either -- otherwise a " +
                "track that only ever sends degenerate frames shows a live tile " +
                "that never has a picture"
        )
    }

    @Test
    fun `a frame whose buffer is one byte short of its stride is rejected`() {
        // The arithmetic check rather than a magic pixel count: 1279*720*4 - 1
        // bytes for a frame claiming 1279x720. A caller that under-allocates by a
        // single byte would otherwise hand the renderer a buffer it reads past the
        // end of.
        val width = 1279
        val height = 720
        pushFrame(
            trackSource = "camera",
            track = cameraTrackSid,
            width = width,
            height = height,
            strideY = width * 4,
            bufferBytes = width * height * 4 - 1
        )

        assertNull(
            LiveKitRemoteVideoFrameStore.latestFrame.value,
            "a buffer one byte short of the luma plane was accepted"
        )
    }

    @Test
    fun `a frame exactly at the minimum edge is accepted`() {
        // The boundary that could actually hurt.
        //
        // The floor exists to reject degenerate frames, and the failure mode of a
        // floor that is too high is not a bad-looking tile -- it is a real video
        // stream that silently never displays, which is the bug this whole change
        // was made to fix. So the exact boundary is pinned rather than left
        // implied: at the floor it goes through, one pixel under it does not.
        pushFrame(
            trackSource = "camera",
            track = cameraTrackSid,
            width = 16,
            height = 16,
            strideY = 64,
            bufferBytes = 64 * 16
        )
        assertNotNull(
            LiveKitRemoteVideoFrameStore.latestFrame.value,
            "a frame exactly at the minimum edge was rejected; the floor is too high"
        )

        LiveKitRemoteVideoFrameStore.clear("test-boundary")
        pushFrame(
            trackSource = "camera",
            track = cameraTrackSid,
            width = 15,
            height = 15,
            strideY = 60,
            bufferBytes = 60 * 15
        )
        assertNull(
            LiveKitRemoteVideoFrameStore.latestFrame.value,
            "a frame one pixel under the minimum edge was accepted"
        )
    }

    @Test
    fun `a frame whose buffer exactly holds its stride is accepted`() {
        // The boundary case, pinned because a validation check is only correct if
        // the exact-fit frame still gets through. An off-by-one here would reject
        // every real frame, which looks exactly like "video never works".
        val width = 320
        val height = 240
        pushFrame(
            trackSource = "camera",
            track = cameraTrackSid,
            width = width,
            height = height,
            strideY = width * 4,
            bufferBytes = width * 4 * height
        )

        assertNotNull(
            LiveKitRemoteVideoFrameStore.latestFrame.value,
            "an exactly-sized frame was rejected"
        )
    }

    @Test
    fun `a real frame still lands after a degenerate one was rejected`() {
        // The ordering that matters. If the rejection is implemented by arming
        // state that the next frame has to clear, a rejected frame would leave the
        // store disarmed and the following good frame would vanish too. Rejecting
        // has to cost exactly one frame.
        pushFrame(trackSource = "screenshare", track = shareTrackSid, width = 8, height = 8, strideY = 32, bufferBytes = 256)
        pushFrame(trackSource = "screenshare", track = shareTrackSid)

        val frame = LiveKitRemoteVideoFrameStore.latestFrame.value
        assertNotNull(frame, "the frame after a rejected one did not get through")
        assertEquals(1280, frame!!.width, "the rejected frame's dimensions leaked into the good frame")
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

    @Test
    fun `stopping a screen share purges both publicationSid and trackSid and clears latestScreenShareFrame`() {
        val pubSid = "TR_pub_screenshare_123"
        val trackSid = "TR_track_screenshare_456"
        LiveKitRemoteVideoFrameStore.trackAnnounced(identity, pubSid, trackSource = "screen_share")
        LiveKitRemoteVideoFrameStore.trackStarted(identity, trackSid, trackSource = "screen_share")
        pushFrame(trackSource = "screenshare", track = trackSid)

        assertEquals(trackSid, LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value?.trackSid)
        assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(pubSid))
        assertTrue(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(trackSid))

        LiveKitRemoteVideoFrameStore.trackStopped(identity, trackSid)

        assertNull(LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value)
        assertFalse(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(trackSid))
        assertFalse(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(pubSid))
        assertNull(LiveKitRemoteVideoFrameStore.framesByTrack.value[trackSid])
    }

    @Test
    fun `participantDisconnected cleans up all tracks and frames for participant`() {
        val pubSid = "TR_pub_789"
        val trackSid = "TR_track_789"
        LiveKitRemoteVideoFrameStore.trackAnnounced(identity, pubSid, trackSource = "screen_share")
        LiveKitRemoteVideoFrameStore.trackStarted(identity, trackSid, trackSource = "screen_share")
        pushFrame(trackSource = "screenshare", track = trackSid)

        LiveKitRemoteVideoFrameStore.participantDisconnected(identity)

        assertNull(LiveKitRemoteVideoFrameStore.latestScreenShareFrame.value)
        assertFalse(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(pubSid))
        assertFalse(LiveKitRemoteVideoFrameStore.activeTracks.value.containsKey(trackSid))
        assertNull(LiveKitRemoteVideoFrameStore.framesByTrack.value[trackSid])
    }
}
