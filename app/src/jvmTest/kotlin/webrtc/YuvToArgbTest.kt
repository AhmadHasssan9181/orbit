package webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 1:1 renderer's colour conversion, pinned against known values.
 *
 * A 1:1 call looked like a washed-out green haze. Group calls -- which go through
 * LiveKit and never touch this code -- looked correct, which is what localised it.
 *
 * The cause was one line folded into the luma base of every pixel:
 *
 *     val c = ((yValue * 0.85) + 35)
 *
 * A screen share is mostly dark interface. That line moves video black (16) to 48
 * and mid grey (128) to 144, so every dark pixel became pale grey-green before the
 * colour channels were even applied. No adjustment downstream could recover it.
 */
class YuvToArgbTest {

    private fun rgb(y: Int, u: Int, v: Int): Triple<Int, Int, Int> {
        val argb = yuvToArgb(y, u, v)
        return Triple(
            (argb shr 16) and 0xFF,
            (argb shr 8) and 0xFF,
            argb and 0xFF
        )
    }

    @Test
    fun videoBlackIsBlack() {
        // Limited range: 16 is the floor, not 0. The old code turned this into 48.
        assertEquals(Triple(0, 0, 0), rgb(16, 128, 128))
    }

    @Test
    fun videoWhiteIsWhite() {
        // Limited range ceiling is 235, not 255.
        val (r, g, b) = rgb(235, 128, 128)
        assertTrue(r >= 254 && g >= 254 && b >= 254, "got $r,$g,$b")
    }

    @Test
    fun primaryRed() {
        val (r, g, b) = rgb(81, 90, 240)
        assertTrue(r >= 250, "red too low: $r")
        assertTrue(g <= 5, "green should be near zero, was $g")
        assertTrue(b <= 5, "blue should be near zero, was $b")
    }

    @Test
    fun primaryGreen() {
        val (r, g, b) = rgb(145, 54, 34)
        assertTrue(g >= 250, "green too low: $g")
        assertTrue(r <= 5, "red should be near zero, was $r")
        assertTrue(b <= 5, "blue should be near zero, was $b")
    }

    @Test
    fun primaryBlue() {
        val (r, g, b) = rgb(41, 240, 110)
        assertTrue(b >= 250, "blue too low: $b")
        assertTrue(r <= 5, "red should be near zero, was $r")
        assertTrue(g <= 5, "green should be near zero, was $g")
    }

    @Test
    fun neutralGreysStayNeutral() {
        // The green cast lived here. Equal luma with neutral chroma must give
        // r == g == b at every level, or every surface is tinted.
        for (luma in 16..235 step 7) {
            val (r, g, b) = rgb(luma, 128, 128)
            assertEquals(r, g, "red/green disagree at luma $luma: $r vs $g")
            assertEquals(g, b, "green/blue disagree at luma $luma: $g vs $b")
        }
    }

    @Test
    fun midGreySitsNearMidGrey() {
        // The regression that made this visible: 128 used to become 144.
        val (r, g, b) = rgb(128, 128, 128)
        listOf(r, g, b).forEach {
            assertTrue(it in 120..136, "mid grey drifted to $it")
        }
    }

    @Test
    fun darknessStaysDark() {
        // The single clearest statement of what the brightness hack destroyed.
        val (r, g, b) = rgb(20, 128, 128)
        listOf(r, g, b).forEach {
            assertTrue(it <= 8, "near-black became $it -- the haze is back")
        }
    }

    @Test
    fun everythingIsFullyOpaque() {
        // Compose treats a non-opaque frame as transparent, which reads as a
        // washed-out panel rather than as a missing alpha channel.
        for (luma in 0..255 step 15) {
            val argb = yuvToArgb(luma, 128, 128)
            assertEquals(0xFF, (argb shr 24) and 0xFF, "alpha wrong at luma $luma")
        }
    }

    @Test
    fun outOfRangeInputClampsRatherThanWrapping() {
        // Frames arrive from a decoder and can be a little out of range. Wrapping
        // would turn a slightly hot pixel into a violently wrong one.
        val over = yuvToArgb(255, 255, 255)
        assertEquals(255, (over shr 16) and 0xFF)
        val under = yuvToArgb(0, 0, 0)
        assertEquals(0, (under shr 16) and 0xFF)
    }

    @Test
    fun moreVMeansMoreRed() {
        // V drives red. Moving U at the same time is what made the first version
        // of this test wrong: it moved both chroma channels together and then
        // asserted they pulled in opposite directions, which is not what BT.601
        // does. Each channel has to be moved on its own to test its sign.
        val (rLow, _, _) = rgb(128, 128, 100)
        val (rHigh, _, _) = rgb(128, 128, 200)
        assertTrue(rHigh > rLow, "raising V must raise red: $rLow -> $rHigh")
    }

    @Test
    fun moreUMeansMoreBlue() {
        val (_, _, bLow) = rgb(128, 100, 128)
        val (_, _, bHigh) = rgb(128, 200, 128)
        assertTrue(bHigh > bLow, "raising U must raise blue: $bLow -> $bHigh")
    }

    @Test
    fun changingULeavesRedAlone() {
        // The actual structure of the common integer BT.601 shortcut: U feeds
        // green and blue, V feeds red, and they do not cross-couple. Two earlier
        // versions of these sign tests asserted a cross-coupling that this matrix
        // does not have, which is why they are written this way now.
        val (rNeutral, _, _) = rgb(128, 128, 128)
        val (rShifted, _, _) = rgb(128, 200, 128)
        assertEquals(rNeutral, rShifted, "U must not move red")
    }

    @Test
    fun changingVLeavesBlueAlone() {
        val (_, _, bNeutral) = rgb(128, 128, 128)
        val (_, _, bShifted) = rgb(128, 128, 200)
        assertEquals(bNeutral, bShifted, "V must not move blue")
    }
}