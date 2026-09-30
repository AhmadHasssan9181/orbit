package ui.screen.call

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The share panel has to be the same shape as the thing in it.
 *
 * This was the "shitty box". The panel was `weight(2.5f).fillMaxWidth()`, which in
 * a 1920x1080 window is 1896x653 -- a 2.9:1 box. A 16:9 screen drawn inside a 2.9:1
 * box comes out 1161px wide with a 367px black bar down each side, so two thirds
 * of the panel was empty, and the faces underneath had been handed a third of the
 * window for the privilege.
 *
 * These tests exist because the wrong version of this could not be expressed as a
 * test at all -- it was a weight, and a weight is only ever correct for one window
 * shape. Once it is a size, it can be pinned.
 */
class SharePanelSizeTest {

    private fun near(actual: Dp, expected: Dp, tolerance: Float = 0.5f) {
        assertTrue(
            kotlin.math.abs((actual - expected).value) <= tolerance,
            "expected ~$expected but was $actual"
        )
    }

    @Test
    fun exactSixteenNineContainerIsTakenWhole() {
        val panel = sharePanelSizeIn(1600.dp, 900.dp)
        near(panel.width, 1600.dp)
        near(panel.height, 900.dp)
    }

    @Test
    fun aWideShortSpaceIsLimitedByItsHeight() {
        // The shape that produced the reported bug: plenty of width, not much
        // height. The answer must be a narrower 16:9 box, not a full-width strip.
        val panel = sharePanelSizeIn(1896.dp, 653.dp)
        near(panel.height, 653.dp)
        near(panel.width, 653.dp * 16f / 9f)
        assertTrue(panel.width < 1896.dp, "must not fill the full width when height runs out")
    }

    @Test
    fun aTallNarrowSpaceIsLimitedByItsWidth() {
        // A narrow window: the full height is available but the width is not, so
        // the panel uses the width and leaves the surplus height empty.
        val panel = sharePanelSizeIn(1000.dp, 1200.dp)
        near(panel.width, 1000.dp)
        near(panel.height, 1000.dp * 9f / 16f)
    }

    @Test
    fun theResultAlwaysKeepsTheRequestedAspect() {
        val spaces = listOf(
            1920.dp to 1080.dp,
            1896.dp to 653.dp,
            1000.dp to 1200.dp,
            800.dp to 1000.dp,
            3840.dp to 400.dp,
            400.dp to 400.dp
        )
        for ((w, h) in spaces) {
            val panel = sharePanelSizeIn(w, h)
            val measured = panel.width.value / panel.height.value
            assertTrue(
                kotlin.math.abs(measured - CALL_SHARE_ASPECT) < 0.01f,
                "aspect drifted for ${w}x${h}: got $measured, wanted $CALL_SHARE_ASPECT"
            )
        }
    }

    @Test
    fun theResultNeverOverflowsTheSpaceGiven() {
        val spaces = listOf(
            1920.dp to 1080.dp,
            1896.dp to 653.dp,
            1000.dp to 1200.dp,
            3840.dp to 400.dp
        )
        for ((w, h) in spaces) {
            val panel = sharePanelSizeIn(w, h)
            assertTrue(panel.width <= w, "overflowed width in ${w}x${h}")
            assertTrue(panel.height <= h, "overflowed height in ${w}x${h}")
        }
    }

    @Test
    fun itTakesAsMuchRoomAsTheAspectAllows() {
        // Guards the other direction: a "safe" fix that shrank the panel to a
        // fixed height would pass every test above and still waste the window.
        val panel = sharePanelSizeIn(1920.dp, 1080.dp)
        assertTrue(
            panel.width == 1920.dp || panel.height == 1080.dp,
            "must be limited by width or by height, never by both at once"
        )
    }

    @Test
    fun degenerateSpacesCollapseToNothingRatherThanGoingNegative() {
        // A window dragged to zero height must not produce a negative Dp and take
        // the layout down with it.
        for ((w, h) in listOf(0.dp to 0.dp, 1000.dp to 0.dp, 0.dp to 1000.dp, (-50).dp to 100.dp)) {
            val panel = sharePanelSizeIn(w, h)
            assertEquals(0.dp, panel.width)
            assertEquals(0.dp, panel.height)
        }
    }

    @Test
    fun aNonSixteenNineAspectIsHonouredIfEverAskedFor() {
        // The aspect is a parameter rather than a constant so the same arithmetic
        // serves a portrait phone screen or an ultrawide without a second code path.
        val panel = sharePanelSizeIn(1000.dp, 1000.dp, aspect = 1f)
        near(panel.width, 1000.dp)
        near(panel.height, 1000.dp)

        val portrait = sharePanelSizeIn(1000.dp, 1000.dp, aspect = 0.5f)
        near(portrait.height, 1000.dp)
        near(portrait.width, 500.dp)
    }
}