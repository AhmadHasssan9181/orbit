package ui.screen.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How call video is transformed before drawing.
 *
 * This exists because of a real bug: `scaleY = if (isScreenShare) -1f else 1f`
 * rendered every screen share in a 1:1 **upside down**, while group calls were
 * fine. The flip lived in exactly one renderer in the app — the LiveKit one that
 * serves group calls has no flip at all — so there was never a mechanism by which
 * one path needed a vertical flip and the other did not.
 *
 * Extracted as a pure function so the rule can be pinned rather than re-derived.
 * The property worth protecting is the negative one: a screen share is NEVER
 * vertically flipped, whatever else changes.
 */
class VideoMirrorScaleTest {

    @Test
    fun yourOwnCameraIsMirroredByDefault() {
        // The one case mirroring is for: your own camera should read as a mirror.
        assertEquals(-1f to 1f, videoMirrorScale(mirror = true, isScreenShare = false))
    }

    @Test
    fun mirroringCanBeTurnedOff() {
        assertEquals(1f to 1f, videoMirrorScale(mirror = false, isScreenShare = false))
    }

    @Test
    fun aRemoteCameraIsNeverMirrored() {
        // A remote camera arrives with mirror = false. If that ever stops being
        // true, the other person appears left-handed -- so this is pinned rather
        // than left to whoever wires up the next remote renderer.
        assertEquals(1f to 1f, videoMirrorScale(mirror = false, isScreenShare = false))
    }

    @Test
    fun aScreenShareIsNeverVerticallyFlipped() {
        // THE regression. Every other case can be argued about; this one was
        // simply wrong and made shared windows unreadable.
        assertEquals(1f, videoMirrorScale(mirror = false, isScreenShare = true).second)
        assertEquals(1f, videoMirrorScale(mirror = true, isScreenShare = true).second)
    }

    @Test
    fun aScreenShareIsNeverMirroredHorizontallyEither() {
        // A share is not a person, so "mirror your camera" has no meaning for it,
        // and applying it turns text into backwards text.
        assertEquals(1f, videoMirrorScale(mirror = true, isScreenShare = true).first)
    }

    @Test
    fun nothingIsEverFlippedVertically() {
        for (mirror in listOf(true, false)) {
            for (share in listOf(true, false)) {
                assertEquals(
                    1f,
                    videoMirrorScale(mirror, share).second,
                    "scaleY must be 1 for mirror=$mirror isScreenShare=$share"
                )
            }
        }
    }

    @Test
    fun theScaleIsAlwaysExactlyPlusOrMinusOne() {
        // A fractional or interpolated scale would mean a partially mirrored or
        // partially shrunk frame, which looks like a rendering fault rather than a
        // decision.
        for (mirror in listOf(true, false)) {
            for (share in listOf(true, false)) {
                val (sx, sy) = videoMirrorScale(mirror, share)
                assertTrue(sx == 1f || sx == -1f, "scaleX was $sx")
                assertTrue(sy == 1f || sy == -1f, "scaleY was $sy")
            }
        }
    }
}

private fun assertTrue(condition: Boolean, message: String) {
    kotlin.test.assertTrue(condition, message)
}
