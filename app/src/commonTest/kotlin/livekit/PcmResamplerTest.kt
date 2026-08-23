package livekit

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C1: capture-line SRC. Devices that refuse the 48 kHz pipeline format get opened at
 * their native rate and converted by PcmResampler — these tests freeze the conversion
 * contract (gapless across chunks, correct framing, format selection).
 */
class PcmResamplerTest {

    private fun leBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            out[i * 2] = (s.toInt() and 0xff).toByte()
            out[i * 2 + 1] = ((s.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun pullFrames(resampler: PcmResampler, frames: Int): ShortArray {
        val buffer = ByteArray(960)
        val out = ShortArray(frames * 480)
        for (frame in 0 until frames) {
            assertTrue(resampler.pullFrame(buffer, 0, 480), "frame $frame should be producible")
            for (i in 0 until 480) {
                val low = buffer[i * 2].toInt() and 0xff
                val high = buffer[i * 2 + 1].toInt()
                out[frame * 480 + i] = ((high shl 8) or low).toShort()
            }
        }
        return out
    }

    @Test
    fun passthroughWhenRatesMatch() {
        val resampler = PcmResampler(srcSampleRate = 48000, srcChannels = 1, bigEndian = false, dstSampleRate = 48000)
        // Feed a ramp; output must reproduce it sample-for-sample.
        resampler.push(leBytes(ShortArray(1000) { (it * 20).coerceAtMost(32000).toShort() }), 2000)
        val out = pullFrames(resampler, 2)
        assertEquals(20.toShort(), out[1])
        assertEquals((479 * 20).toShort(), out[479])
        assertEquals((480 * 20).toShort(), out[480])
    }

    @Test
    fun downsampleKeepsFrequencyAndIsGaplessAcrossChunks() {
        val srcRate = 44100
        val dstRate = 48000
        val toneHz = 440.0
        // Slightly more than one second of source so the lookahead held back at the
        // tail doesn't starve the first full second of output.
        val totalSrcSamples = srcRate + srcRate / 10
        val resampler = PcmResampler(srcRate, 1, bigEndian = false, dstRate)

        // Push in odd-sized chunks to prove phase continuity across chunk boundaries.
        val totalSrcBytes = totalSrcSamples * 2
        var produced = 0
        var pushed = 0
        var lastPeakIndex = -1
        var peaks = 0
        var prev = 0
        while (produced < dstRate) {
            val chunkSize = 777.coerceAtMost(totalSrcBytes - pushed)
            if (chunkSize <= 0) break
            val samples = ShortArray(chunkSize / 2) { j ->
                val t = (pushed / 2 + j).toDouble() / srcRate
                (sin(2.0 * PI * toneHz * t) * 12000).toInt().toShort()
            }
            resampler.push(leBytes(samples), chunkSize)
            pushed += chunkSize
            while (resampler.bufferedFrames() > 480 * 3 && produced < dstRate) {
                val frameOut = pullFrames(resampler, 1)
                for (s in frameOut) {
                    if (prev < 0 && s >= 0) {
                        peaks += 1
                        lastPeakIndex = produced
                    }
                    prev = s.toInt()
                    produced += 1
                    if (produced >= dstRate) break
                }
            }
        }
        // ~440 zero crossings per second of 440 Hz sine (±5 for resample smoothing).
        assertTrue(peaks in 435..445, "expected ~440 rising crossings, got $peaks")
        // Last rising crossing lands near second end: frequency held to the end.
        assertTrue(lastPeakIndex > dstRate - dstRate / 200, "tone stopped early at $lastPeakIndex")
    }

    @Test
    fun stereoDownmixesToMono() {
        val resampler = PcmResampler(srcSampleRate = 16000, srcChannels = 2, bigEndian = false, dstSampleRate = 48000)
        // L=1000, R=-500 on every frame → mono ≈ 250 constant.
        val samples = ShortArray(1600) { if (it % 2 == 0) 1000 else -500 }
        resampler.push(leBytes(samples), samples.size * 2)
        val out = pullFrames(resampler, 1)
        assertTrue(out.all { it in 240..260 }, "mono downmix should average channels, got ${out.take(8)}")
    }

    @Test
    fun bigEndianInputIsByteSwapped() {
        val resampler = PcmResampler(srcSampleRate = 48000, srcChannels = 1, bigEndian = true, dstSampleRate = 48000)
        val value = 12345
        val be = ByteArray(1000) { i ->
            if (i % 2 == 0) ((value shr 8) and 0xff).toByte() else (value and 0xff).toByte()
        }
        resampler.push(be, be.size)
        val out = pullFrames(resampler, 1)
        assertEquals(value.toShort(), out[0])
    }

    @Test
    fun pullFrameReturnsFalseUntilEnoughSourceBuffered() {
        val resampler = PcmResampler(srcSampleRate = 8000, srcChannels = 1, bigEndian = false, dstSampleRate = 48000)
        val buffer = ByteArray(960)
        // One destination frame needs ≥ 80 source samples (8000/48000 upsampling needs MORE:
        // 480 outputs × 8000/48000 = 80 source samples minimum — feed less than that).
        resampler.push(leBytes(ShortArray(60) { 100 }), 120)
        assertFalse(resampler.pullFrame(buffer, 0, 480))
        resampler.push(leBytes(ShortArray(60) { 100 }), 120)
        assertTrue(resampler.pullFrame(buffer, 0, 480))
    }

    @Test
    fun pickNativeFormatPrefersNearestRateThenMono() {
        val picked = PcmResampler.pickNativeFormat(
            listOf(96000 to 2, 44100 to 2, 44100 to 1, 16000 to 1),
            targetSampleRate = 48000
        )
        assertNotNull(picked)
        // |44100-48000| == 3900 beats 16000 (32000 away); mono wins over stereo at same rate.
        assertEquals(44100 to 1, picked)
        assertNull(PcmResampler.pickNativeFormat(emptyList(), 48000))
        assertNull(PcmResampler.pickNativeFormat(listOf(4000 to 3), 48000))
    }

    @Test
    fun needsConversionFlagsEveryMismatchAxis() {
        assertTrue(PcmResampler.needsConversion(44100, 1, false, 48000, 1))
        assertTrue(PcmResampler.needsConversion(48000, 2, false, 48000, 1))
        assertTrue(PcmResampler.needsConversion(48000, 1, true, 48000, 1))
        assertFalse(PcmResampler.needsConversion(48000, 1, false, 48000, 1))
    }
}
