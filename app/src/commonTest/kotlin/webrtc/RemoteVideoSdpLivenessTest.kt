package webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The peer's screen-share tile has to disappear when they stop sharing.
 *
 * Reported as: Element stopped streaming and Orbit's share tile stayed on screen for
 * the rest of the call, frozen on the last frame and holding the spotlight.
 *
 * The old answer listened for the track to *end*. Element stops by renegotiating,
 * so the track object never ends and the listener never fires. Run 58 shows exactly
 * that: `remote_screen` stayed the featured node from attach until `endCall`, and
 * the only clear in the whole log came from hangup.
 *
 * The replacement reads the SDP, which is the one place that states which tracks
 * exist right now. Most of these tests are about the *other* direction: the check
 * must not delete a working tile over an SDP it failed to understand.
 */
class RemoteVideoSdpLivenessTest {

    // A remote description with a camera track, as Element sends it.
    private val cameraOffer = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=msid:stream-a audio-track-a
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=sendrecv
        a=msid:stream-b camera-track-b
    """.trimIndent()

    // The same peer, second video m-line, which is how a share arrives in 1:1.
    private val shareOffer = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=msid:stream-a audio-track-a
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=sendrecv
        a=msid:stream-b camera-track-b
        m=video 9 UDP/TLS/RTP/SAVPF 98
        a=sendrecv
        a=msid:stream-c share-track-c
    """.trimIndent()

    @Test
    fun findsVideoTrackIds() {
        assertEquals(
            setOf("camera-track-b", "share-track-c"),
            RemoteVideoSdpLiveness.videoTrackIdsIn(shareOffer)
        )
    }

    @Test
    fun aTrackStillInTheSdpSurvives() {
        // The share is announced and still there. Nothing to do.
        assertTrue(
            RemoteVideoSdpLiveness.survives(
                sdp = shareOffer,
                trackId = "share-track-c",
                sdpHasVideoSection = true
            )
        )
    }

    @Test
    fun aTrackThePeerWithdrewIsDropped() {
        // This is the actual fix. Element stopped sharing and renegotiated without
        // the share m-line. The tile must go.
        assertFalse(
            RemoteVideoSdpLiveness.survives(
                sdp = cameraOffer,
                trackId = "share-track-c",
                sdpHasVideoSection = true
            ),
            "a share the peer withdrew stayed on screen"
        )
    }

    @Test
    fun theCameraIsHeldToTheSameRuleAsTheShare() {
        // The share was fixed by adding a liveness check. A check that only covered
        // the share leaves the identical bug sitting next to it, so the camera goes
        // through the same function.
        assertTrue(
            RemoteVideoSdpLiveness.survives(cameraOffer, "camera-track-b", true),
            "a live camera was dropped"
        )
        assertFalse(
            RemoteVideoSdpLiveness.survives(cameraOffer, "camera-track-gone", true),
            "a camera the peer withdrew stayed on screen"
        )
    }

    // --- the direction that matters: never destroy working video ----------------

    @Test
    fun aBlankSdpKeepsTheTile() {
        // No opinion, so no action. Guessing here deletes a working share.
        assertTrue(RemoteVideoSdpLiveness.survives("", "share-track-c", true))
        assertTrue(RemoteVideoSdpLiveness.survives("   ", "share-track-c", true))
    }

    @Test
    fun anUnparseableDumpKeepsTheTile() {
        // `sdpHasVideoSection` false means the dump was not shaped as we expect.
        // Whatever is wrong with our reader, it is not proof the peer stopped.
        assertTrue(
            RemoteVideoSdpLiveness.survives(
                sdp = "this is not an SDP at all",
                trackId = "share-track-c",
                sdpHasVideoSection = false
            )
        )
    }

    @Test
    fun anSdpWithVideoButNoTrackIdsKeepsTheTile() {
        // The msid attribute is absent -- some peers omit it. No ids means no
        // contradiction was established, so the tile stays.
        val noMsid = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=sendrecv
        """.trimIndent()
        assertTrue(RemoteVideoSdpLiveness.survives(noMsid, "share-track-c", true))
    }

    @Test
    fun aBlankTrackIdKeepsTheTile() {
        // Nothing to look for, so nothing to contradict.
        assertTrue(RemoteVideoSdpLiveness.survives(cameraOffer, null, true))
        assertTrue(RemoteVideoSdpLiveness.survives(cameraOffer, "", true))
        assertTrue(RemoteVideoSdpLiveness.survives(cameraOffer, "   ", true))
    }

    @Test
    fun anAudioTrackIdIsNeverMatchedAgainstTheVideoList() {
        // `a=msid` appears in audio sections too and a track id is only unique
        // within its kind. An unscoped scan would find `audio-track-a` and could
        // match a video track against it.
        val ids = RemoteVideoSdpLiveness.videoTrackIdsIn(cameraOffer)
        assertFalse("audio-track-a" in ids, "an audio track id leaked into the video list")
    }

    @Test
    fun sessionLevelMsidWithNoTrackIsSkipped() {
        // `a=msid:<stream>` alone carries no track id. Treating it as one would put
        // a stream id in the list, which can never match a track.
        val sessionLevel = """
            v=0
            a=msid:session-stream
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:stream-b camera-track-b
        """.trimIndent()
        assertEquals(
            setOf("camera-track-b"),
            RemoteVideoSdpLiveness.videoTrackIdsIn(sessionLevel),
            "a session-level stream id was treated as a track id"
        )
    }

    @Test
    fun theVideoSectionCheckIsIndependentOfTheMsidScan() {
        // They answer different questions and the caller passes both, so they have
        // to agree on where a video section starts and ends.
        assertTrue(RemoteVideoSdpLiveness.hasVideoSection(cameraOffer))
        assertTrue(RemoteVideoSdpLiveness.hasVideoSection(shareOffer))
        assertFalse(RemoteVideoSdpLiveness.hasVideoSection("m=audio 9 UDP/TLS/RTP/SAVPF 111"))
        assertFalse(RemoteVideoSdpLiveness.hasVideoSection(""))
    }

    @Test
    fun mLinesAfterTheAudioSectionAreStillSeenAsVideo() {
        // The scanner flips inVideoSection on every `m=`, so an audio section after
        // a video one must not leave it stuck true.
        val videoThenAudio = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:stream-b camera-track-b
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=msid:stream-a audio-track-a
        """.trimIndent()
        assertEquals(
            setOf("camera-track-b"),
            RemoteVideoSdpLiveness.videoTrackIdsIn(videoThenAudio)
        )
    }
}
