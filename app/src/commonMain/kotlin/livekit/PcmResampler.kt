package livekit

/**
 * Incremental PCM converter: signed 16-bit input at any sample rate / channel count /
 * byte order → mono little-endian 16-bit at [dstSampleRate] (48 kHz pipeline rate).
 *
 * Feeds chunk-by-chunk from a capture line whose native format differs from the
 * pipeline format; keeps fractional resample phase and partially consumed source
 * samples across chunks so output is gapless.
 */
class PcmResampler(
    srcSampleRate: Int,
    private val srcChannels: Int,
    private val bigEndian: Boolean,
    private val dstSampleRate: Int
) {
    init {
        require(srcSampleRate > 0) { "srcSampleRate must be positive" }
        require(dstSampleRate > 0) { "dstSampleRate must be positive" }
        require(srcChannels > 0) { "srcChannels must be positive" }
    }

    private val step = srcSampleRate.toDouble() / dstSampleRate
    private val srcFrameBytes = srcChannels * BYTES_PER_SAMPLE

    private var buf = ShortArray(SRC_BUFFER_FRAMES)
    private var filled = 0
    private var readPos = 0
    private var frac = 0.0

    /** Append raw PCM bytes ([length] valid bytes) from the capture line. */
    fun push(chunk: ByteArray, length: Int) {
        if (length <= 0) return
        val frames = length / srcFrameBytes
        if (frames == 0) return
        ensureCapacity(filled + frames)
        var offset = 0
        repeat(frames) {
            buf[filled] = readMonoFrame(chunk, offset)
            filled += 1
            offset += srcFrameBytes
        }
    }

    /**
     * Try to produce exactly one destination frame of [samplesPerChannel] mono samples
     * into [out] at [outByteOffset]. Returns false (and leaves [out] untouched) when the
     * buffered source cannot cover a whole destination frame yet — caller keeps reading.
     */
    fun pullFrame(out: ByteArray, outByteOffset: Int, samplesPerChannel: Int): Boolean {
        if (readPos >= CONSUMED_PREFIX_LIMIT) {
            val remaining = filled - readPos
            System.arraycopy(buf, readPos, buf, 0, remaining)
            readPos = 0
            filled = remaining
        }
        // Linear interpolation needs one lookahead sample past the last touched one.
        val neededSourceFrames = (samplesPerChannel * step).toInt() + 3
        if (filled - readPos < neededSourceFrames) return false

        for (produced in 0 until samplesPerChannel) {
            val a = buf[readPos].toInt()
            val b = if (readPos + 1 < filled) buf[readPos + 1].toInt() else a
            val sample = a + (b - a) * frac
            writeSampleLe(
                out,
                outByteOffset + produced * BYTES_PER_SAMPLE,
                sample.toInt().coerceIn(-32768, 32767)
            )
            frac += step
            while (frac >= 1.0) {
                frac -= 1.0
                readPos += 1
            }
        }
        return true
    }

    /** Buffered source frames not yet consumed. */
    fun bufferedFrames(): Int = filled - readPos

    private fun ensureCapacity(needed: Int) {
        if (needed <= buf.size) return
        buf = buf.copyOf(maxOf(buf.size * 2, needed))
    }

    private fun readMonoFrame(chunk: ByteArray, offset: Int): Short {
        var sum = 0
        for (ch in 0 until srcChannels) {
            val low = chunk[offset + ch * BYTES_PER_SAMPLE].toInt() and 0xff
            val high = chunk[offset + ch * BYTES_PER_SAMPLE + 1].toInt()
            sum += if (bigEndian) {
                (((low shl 8) or (high and 0xff)).toShort()).toInt()
            } else {
                (((high shl 8) or low).toShort()).toInt()
            }
        }
        return (sum / srcChannels).coerceIn(-32768, 32767).toShort()
    }

    private fun writeSampleLe(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xff).toByte()
        out[offset + 1] = ((value shr 8) and 0xff).toByte()
    }

    companion object {
        private const val BYTES_PER_SAMPLE = 2
        private const val SRC_BUFFER_FRAMES = 8192

        // Compact the internal buffer once this many consumed frames pile up.
        private const val CONSUMED_PREFIX_LIMIT = 4096

        /**
         * Pick the best usable PCM_SIGNED 16-bit format from a device's advertised list:
         * sample rate nearest to [targetSampleRate], mono preferred over multichannel.
         * Returns null when no compatible format exists (caller fails loudly).
         */
        fun pickNativeFormat(
            formats: List<Pair<Int, Int>>, // (sampleRateHz, channels)
            targetSampleRate: Int
        ): Pair<Int, Int>? {
            val usable = formats.filter { (rate, channels) -> rate in 8000..192000 && channels in 1..2 }
            if (usable.isEmpty()) return null
            return usable.minWithOrNull(
                compareBy(
                    { (rate, _) -> kotlin.math.abs(rate - targetSampleRate) },
                    { (_, channels) -> channels }
                )
            )
        }

        fun needsConversion(
            lineSampleRate: Int,
            lineChannels: Int,
            lineBigEndian: Boolean,
            targetSampleRate: Int,
            targetChannels: Int
        ): Boolean =
            lineSampleRate != targetSampleRate ||
                lineChannels != targetChannels ||
                lineBigEndian
    }
}
