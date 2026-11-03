package webrtc

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenShareBitrateTest {

    @Test
    fun bitrateScalesWithResolution() {
        assertEquals(2_500_000, screenShareBitrateBps("720p", 30))
        assertEquals(5_000_000, screenShareBitrateBps("1080p", 30))
        assertEquals(8_000_000, screenShareBitrateBps("1440p", 30))
        assertEquals(12_000_000, screenShareBitrateBps("Source", 30))
    }

    @Test
    fun bitrateScalesWithFrameRate() {
        assertEquals(1_500_000, screenShareBitrateBps("720p", 15))
        assertEquals(2_500_000, screenShareBitrateBps("720p", 30))
        assertEquals(4_000_000, screenShareBitrateBps("720p", 60))

        assertEquals(5_000_000, screenShareBitrateBps("1080p", 30))
        assertEquals(8_000_000, screenShareBitrateBps("1080p", 60))
        assertEquals(19_200_000, screenShareBitrateBps("Source", 60))
    }

    @Test
    fun unknownQualityUsesThe1080pBudget() {
        assertEquals(5_000_000, screenShareBitrateBps("", 30))
        assertEquals(5_000_000, screenShareBitrateBps("garbage", 30))
    }
}
