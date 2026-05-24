package webrtc

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.awt.image.BufferedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DesktopVideoRenderer(
    val mirror: Boolean = false
) : VideoTrackSink {
    private val _frameImage = MutableStateFlow<BufferedImage?>(null)
    val frameImage: StateFlow<BufferedImage?> = _frameImage
    private var lastFrameNanos = 0L

    override fun onVideoFrame(frame: VideoFrame) {
        val now = System.nanoTime()
        if (now - lastFrameNanos < 66_000_000L) {
            frame.release()
            return
        }
        lastFrameNanos = now

        var i420: I420Buffer? = null
        try {
            i420 = frame.buffer.toI420()
            _frameImage.value = i420.toBufferedImage()
        } catch (error: Throwable) {
            println("DEBUG: [WebRTC] Desktop frame render failed: ${error.message}")
        } finally {
            if (i420 != null && i420 !== frame.buffer) {
                i420.release()
            }
            frame.release()
        }
    }

    private fun I420Buffer.toBufferedImage(): BufferedImage {
        val frameWidth = this.width
        val frameHeight = this.height
        val image = BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_RGB)
        val yPlane = getDataY()
        val uPlane = getDataU()
        val vPlane = getDataV()

        for (y in 0 until frameHeight) {
            for (x in 0 until frameWidth) {
                val yValue = yPlane.get(y * getStrideY() + x).toInt() and 0xFF
                val uValue = uPlane.get((y / 2) * getStrideU() + (x / 2)).toInt() and 0xFF
                val vValue = vPlane.get((y / 2) * getStrideV() + (x / 2)).toInt() and 0xFF

                val c = yValue - 16
                val d = uValue - 128
                val e = vValue - 128

                val red = clamp((298 * c + 409 * e + 128) shr 8)
                val green = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
                val blue = clamp((298 * c + 516 * d + 128) shr 8)

                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
        }

        return image
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
