package webrtc

import ui.screen.call.INBOUND_VIDEO_MAX_LONG_EDGE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The inbound 1:1 downscale step.
 *
 * The 1:1 path had no resolution limit at all, while a comment in
 * WebRtcClientImpl claimed it was capped at 1280. A peer sharing a 4K screen
 * therefore made the renderer allocate a 3840x2160 ARGB image and run 8.3 million
 * pixel conversions fifteen times a second on a WebRTC sink thread.
 *
 * The limit is 1920 and not the group path's 1280. 1280 was tried first and
 * a1080pShareIsNotScaled below is the test that caught it: 1920x1080 is the most
 * common screen share there is, and halving it to 960x540 would have manufactured
 * the very blurriness this was meant to fix.
 */
class IntegerDownscaleStepTest {

    @Test
    fun a1080pShareIsNotScaled() {
        // The most common screen-share resolution must survive untouched. This is
        // the test that rejected a 1280 limit.
        assertEquals(1, integerDownscaleStep(1920, 1080, INBOUND_VIDEO_MAX_LONG_EDGE))
    }

    @Test
    fun aFrameInsideTheLimitIsUntouched() {
        assertEquals(1, integerDownscaleStep(1280, 720, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertEquals(1, integerDownscaleStep(640, 480, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertEquals(1, integerDownscaleStep(1600, 900, INBOUND_VIDEO_MAX_LONG_EDGE))
    }

    @Test
    fun a4kShareIsHalvedTo1080p() {
        assertEquals(2, integerDownscaleStep(3840, 2160, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertEquals(1920, 3840 / 2)
    }

    @Test
    fun an8kShareComesDownTo1080p() {
        assertEquals(4, integerDownscaleStep(7680, 4320, INBOUND_VIDEO_MAX_LONG_EDGE))
    }

    @Test
    fun theResultIsAlwaysWithinTheLimit() {
        val samples = listOf(
            1921 to 1080, 2560 to 1440, 3840 to 2160, 3841 to 2160,
            5120 to 2880, 7680 to 4320, 7681 to 4320, 10000 to 5000
        )
        for ((w, h) in samples) {
            val step = integerDownscaleStep(w, h, INBOUND_VIDEO_MAX_LONG_EDGE)
            val longest = maxOf(w / step, h / step)
            assertTrue(
                longest <= INBOUND_VIDEO_MAX_LONG_EDGE,
                "${w}x$h step=$step still yields a ${longest}px edge"
            )
        }
    }

    @Test
    fun portraitUsesTheLongEdgeNotTheWidth() {
        // A portrait frame: height is the long edge, so height is what must come
        // down. Keying off width instead would leave portrait 4K untouched.
        assertEquals(2, integerDownscaleStep(1080, 2160, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertTrue(2160 / 2 <= INBOUND_VIDEO_MAX_LONG_EDGE)
    }

    @Test
    fun degenerateInputNeverProducesZeroOrNegative() {
        // A zero step would divide by zero and take the renderer down mid-call.
        assertEquals(1, integerDownscaleStep(0, 0, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertEquals(1, integerDownscaleStep(-10, 100, INBOUND_VIDEO_MAX_LONG_EDGE))
        assertEquals(1, integerDownscaleStep(1920, 1080, 0))
        assertEquals(1, integerDownscaleStep(1920, 1080, -5))
    }

    @Test
    fun theStepIsAlwaysAtLeastOne() {
        for (w in listOf(1, 100, 1280, 1921, 4000, 9000)) {
            for (h in listOf(1, 100, 720, 1080, 2160)) {
                assertTrue(
                    integerDownscaleStep(w, h, INBOUND_VIDEO_MAX_LONG_EDGE) >= 1,
                    "w=$w h=$h produced a non-positive step"
                )
            }
        }
    }
}