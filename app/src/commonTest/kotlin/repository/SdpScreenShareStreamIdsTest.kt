package repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Telling a shared screen apart from a camera, from the SDP alone.
 *
 * Run 45 is what these are written against. One remote video track arrived, its
 * id was `528d6310-a864-4e5a-ab1d-989b6c6d8c15`, and the classifier filed it as a
 * camera -- so the grid reported `shares=0` and the peer's screen was drawn as one
 * more participant tile while the banner said somebody was sharing.
 *
 * The classifier that did it:
 *
 *     track.getId().contains("screen") || contains("desktop") || remoteVideoTrack != null
 *
 * A bare UUID matches neither name test, and a share arriving FIRST means there
 * is no camera yet, so all three clauses are false. That case had no answer.
 */
class SdpScreenShareStreamIdsTest {

    /** Element's shape: a bare UUID for the track, no hint in the name. */
    private val shareOnlySdp = """
        v=0
        o=- 1 1 IN IP4 127.0.0.1
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        a=msid:audioStream audioTrack
        m=video 9 UDP/TLS/RTP/SAVPF 96
        a=msid:screenStream 528d6310-a864-4e5a-ab1d-989b6c6d8c15
        a=SCREEN_TRACK_ID:528d6310-a864-4e5a-ab1d-989b6c6d8c15
    """.trimIndent()

    @Test
    fun aSingleVideoStreamWithAScreenMarkerIsTheShare() {
        // The exact case that produced shares=0.
        assertEquals(setOf("screenStream"), sdpScreenShareStreamIds(shareOnlySdp))
    }

    @Test
    fun noMarkerAtAllMeansNoShare() {
        // An ordinary camera-only call. Nothing here should be promoted.
        val cameraOnly = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:cameraStream 11111111-2222-3333-4444-555555555555
        """.trimIndent()
        assertEquals(emptySet(), sdpScreenShareStreamIds(cameraOnly))
    }

    @Test
    fun aCameraIsNeverPromotedWhenARealShareIsPresent() {
        // The dangerous direction: a session-level marker plus two video streams
        // is ambiguous, and guessing "both" turns somebody's face into a
        // letterboxed panel on top of the call.
        val cameraAndShare = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=msid:audioStream audioTrack
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:cameraStream 11111111-2222-3333-4444-555555555555
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:screenStream 528d6310-a864-4e5a-ab1d-989b6c6d8c15
            a=SCREEN_TRACK_ID:528d6310-a864-4e5a-ab1d-989b6c6d8c15
        """.trimIndent()

        val shareIds = sdpScreenShareStreamIds(cameraAndShare)
        assertTrue("cameraStream" !in shareIds, "the camera must not be marked as the share")
        assertTrue(
            shareIds.isEmpty() || "screenStream" in shareIds,
            "the session-level marker alone must not promote an arbitrary stream, got $shareIds"
        )
    }

    @Test
    fun aMarkerInsideOneSectionPicksThatSectionAndNoOther() {
        // Where the marker sits in the section is the precise signal, so when one
        // section carries it that section is the share and the other is the face.
        val perSectionMarker = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:cameraStream 11111111-2222-3333-4444-555555555555
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:screenStream aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
            a=content:slides
            a=screenTrack
        """.trimIndent()

        assertEquals(setOf("screenStream"), sdpScreenShareStreamIds(perSectionMarker))
    }

    @Test
    fun blankAndEmptySdpAreHandledRatherThanThrowing() {
        assertEquals(emptySet(), sdpScreenShareStreamIds(""))
        assertEquals(emptySet(), sdpScreenShareStreamIds("   "))
    }

    @Test
    fun markerSpellingVariantsAllCount() {
        // Whatever the client spells it, the answer should be the same.
        val spellings = listOf(
            "a=SCREEN_TRACK_ID:abc",
            "a=screenTrack",
            "a=ScreenShare",
            "a=screen_share",
            "a=screenshare"
        )
        for (spelling in spellings) {
            val sdp = """
                v=0
                m=video 9 UDP/TLS/RTP/SAVPF 96
                a=msid:screenStream track-1
                $spelling
            """.trimIndent()
            assertEquals(
                setOf("screenStream"),
                sdpScreenShareStreamIds(sdp),
                "spelling '$spelling' was not recognised"
            )
        }
    }

    @Test
    fun theTrackIdIsRecoverableFromTheStreamWeIdentified() {
        // The share stream is only useful if the track id can be pulled out of it,
        // because that -- not the stream id -- is what the native layer reports
        // back from onTrack. Getting this pairing wrong reintroduces the original
        // bug one layer down.
        val trackIds = Regex("""(?m)^a=msid:([^\s]+)\s+([^\s]+)""")
            .findAll(shareOnlySdp)
            .filter { it.groupValues[1] in sdpScreenShareStreamIds(shareOnlySdp) }
            .map { it.groupValues[2] }
            .toSet()

        assertEquals(setOf("528d6310-a864-4e5a-ab1d-989b6c6d8c15"), trackIds)
    }

    @Test
    fun aSectionWithNoMsidContributesNothingRatherThanGuessing() {
        // Some SDPs put msid only at session level. An unidentified section must
        // not be guessed into being the share.
        val noMsid = """
            v=0
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=SCREEN_TRACK_ID:abc
        """.trimIndent()
        assertEquals(emptySet(), sdpScreenShareStreamIds(noMsid))
    }

    @Test
    fun audioOnlySectionsAreNeverConsideredShares() {
        // Audio sections can carry an msid too, and one of them mentioning a
        // screen marker must not drag the stream in as a video share.
        val audioMarker = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=msid:audioStream audioTrack
            a=screenTrack
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=msid:cameraStream 11111111-2222-3333-4444-555555555555
        """.trimIndent()

        val shareIds = sdpScreenShareStreamIds(audioMarker)
        assertTrue("audioStream" !in shareIds, "an audio stream is not a video share")
    }
}