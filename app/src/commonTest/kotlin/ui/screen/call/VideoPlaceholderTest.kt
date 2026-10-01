package ui.screen.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a call tile shows for a participant whose camera is off.
 *
 * Reported as: in group calls, an Element user with their camera off still reads
 * "starting video" for the whole call.
 *
 * The cause was structural rather than a wrong string. LiveKit announces a video
 * publication whether or not it will ever carry a frame, so the tile always had a
 * renderer, and the renderer draws a spinner because no frame arrives. "Not yet"
 * and "not coming" were drawn identically, with nothing to distinguish them.
 *
 * The rule is here as a pure function so it can be pinned, because a timer-driven
 * transition is exactly the kind of thing that quietly regresses.
 */
class VideoPlaceholderTest {

    @Test
    fun aFrameAlwaysWins() {
        // Even long past the grace window. A slow camera that finally delivers
        // must never be talked out of its own tile.
        assertEquals(VideoPlaceholder.Video, videoPlaceholder(hasFrame = true, waitedMs = 0))
        assertEquals(VideoPlaceholder.Video, videoPlaceholder(hasFrame = true, waitedMs = 60_000))
    }

    @Test
    fun nothingYetShowsASpinner() {
        // The honest middle state. A camera can take a moment to attach and a
        // spinner says so, which is true.
        assertEquals(VideoPlaceholder.Spinner, videoPlaceholder(hasFrame = false, waitedMs = 0))
        assertEquals(
            VideoPlaceholder.Spinner,
            videoPlaceholder(hasFrame = false, waitedMs = CAMERA_FRAME_GRACE_MS - 1)
        )
    }

    @Test
    fun nothingAfterTheGraceWindowShowsTheAvatar() {
        // The actual fix. This is the camera-off case, which previously spun
        // forever because nothing could ever arrive.
        assertEquals(
            VideoPlaceholder.Avatar,
            videoPlaceholder(hasFrame = false, waitedMs = CAMERA_FRAME_GRACE_MS)
        )
        assertEquals(
            VideoPlaceholder.Avatar,
            videoPlaceholder(hasFrame = false, waitedMs = CAMERA_FRAME_GRACE_MS + 1)
        )
    }

    @Test
    fun theGraceWindowIsLongEnoughToBeInvisibleAndShortEnoughToMatter() {
        // Below a second and a real camera attach looks like an error. Much above
        // ten and the fix does not feel like a fix.
        assertTrue(CAMERA_FRAME_GRACE_MS >= 3_000, "too eager: a normal attach would flash an avatar")
        assertTrue(CAMERA_FRAME_GRACE_MS <= 10_000, "too patient: the camera-off case would still look broken")
    }

    @Test
    fun theWindowIsNotSoShortThatSharesAreAffected() {
        // Shares have their own stuck-share path with a 5s auto-reconnect, and one
        // attached at 18s in run 27. They must not be caught by a camera grace
        // window, which is why the grace is applied only to camera tracks and
        // never to isScreenShare nodes.
        assertTrue(
            CAMERA_FRAME_GRACE_MS < 18_000,
            "if this ever reaches 18s it would start eating share tiles too"
        )
    }

    @Test
    fun everyStateIsReachableAndTheyAreDistinct() {
        val all = listOf(
            videoPlaceholder(true, 0),
            videoPlaceholder(false, 0),
            videoPlaceholder(false, CAMERA_FRAME_GRACE_MS)
        )
        assertEquals(3, all.toSet().size, "the three states collapsed into fewer")
    }

    @Test
    fun timeAloneNeverInventsAFrame() {
        // Time can promote Spinner -> Avatar, because that is a claim about what
        // Orbit knows. It can never promote to Video, because that is a claim
        // about a frame having arrived. Only a real frame may make it.
        for (waited in listOf(0L, 1_000L, 100_000L, 10_000_000L)) {
            assertTrue(
                videoPlaceholder(hasFrame = false, waitedMs = waited) != VideoPlaceholder.Video,
                "elapsed time invented a video frame at $waited ms"
            )
        }
    }
}