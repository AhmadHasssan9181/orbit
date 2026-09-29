package webrtc

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.awt.image.BufferedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DesktopVideoRenderer(
    var mirror: Boolean = false
) : VideoTrackSink {
    var isScreenShare: Boolean = false
    private val _frameImage = MutableStateFlow<BufferedImage?>(null)
    val frameImage: StateFlow<BufferedImage?> = _frameImage
    private var lastFrameNanos = 0L

    private var frameCount = 0

    override fun onVideoFrame(frame: VideoFrame) {
        frameCount++
        // Per-frame-adjacent logging, so it is gated on the debug flag rather
        // than merely every 60th frame. At 30 fps per tile this was ~2 lines per
        // second per tile regardless of any debug setting.
        val verbose = util.OrbitLog.debugEnabled

        val now = System.nanoTime()
        if (now - lastFrameNanos < 66_000_000L) {
            frame.release()
            return
        }
        lastFrameNanos = now

        if (verbose) {
            println("DEBUG: [WebRTC] DesktopVideoRenderer (isScreen=$isScreenShare) received frame: ${frame.buffer.width}x${frame.buffer.height}, format=${frame.buffer.javaClass.simpleName}")
        }

        var i420: I420Buffer? = null
        try {
            i420 = frame.buffer.toI420()
            if (verbose && i420 != null) {
                println("DEBUG: [WebRTC] DesktopVideoRenderer I420Buffer size: ${i420.width}x${i420.height}, Y-stride: ${i420.strideY}")
            }
            _frameImage.value = i420.toBufferedImage()
        } catch (error: Throwable) {
            println("ERROR: [WebRTC] Desktop frame render failed: ${error.message}")
            error.printStackTrace()
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
        val image = BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB)
        val yPlane = getDataY()
        val uPlane = getDataU()
        val vPlane = getDataV()

        for (y in 0 until frameHeight) {
            for (x in 0 until frameWidth) {
                val yValue = yPlane.get(y * getStrideY() + x).toInt() and 0xFF
                val uValue = uPlane.get((y / 2) * getStrideU() + (x / 2)).toInt() and 0xFF
                val vValue = vPlane.get((y / 2) * getStrideV() + (x / 2)).toInt() and 0xFF

                // Boost brightness and slightly reduce contrast to fix the "too dark / high contrast" issue
                val c = ((yValue * 0.85) + 35).toInt()
                val d = uValue - 128
                val e = vValue - 128

                val red = clamp(c + ((e * 359) shr 8))
                val green = clamp(c - ((d * 88) shr 8) - ((e * 183) shr 8))
                val blue = clamp(c + ((d * 454) shr 8))

                // Ensure alpha is 0xFF (fully opaque) to prevent Compose from treating it as transparent
                image.setRGB(x, y, (0xFF shl 24) or (red shl 16) or (green shl 8) or blue)
            }
        }

        if (util.OrbitLog.debugEnabled && frameCount % 60 == 0) {
            val centerPixel = image.getRGB(frameWidth / 2 + 5, frameHeight / 2 + 5)
            val r = (centerPixel shr 16) and 0xFF
            val g = (centerPixel shr 8) and 0xFF
            val b = centerPixel and 0xFF
            println("DEBUG: [WebRTC] DesktopVideoRenderer Frame ${frameWidth}x${frameHeight} generated. Sample pixel RGB: $r, $g, $b")
        }

        return image
    }

    private fun clamp(value: Int): Int = value.coerceIn(0, 255)
}
