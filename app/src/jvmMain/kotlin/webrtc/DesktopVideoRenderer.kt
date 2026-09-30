package webrtc

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import ui.screen.call.INBOUND_VIDEO_MAX_LONG_EDGE
import ui.screen.call.INBOUND_VIDEO_MIN_FRAME_INTERVAL_NS
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
        if (now - lastFrameNanos < INBOUND_VIDEO_MIN_FRAME_INTERVAL_NS) {
            frame.release()
            return
        }
        lastFrameNanos = now

        var i420: I420Buffer? = null
        try {
            // Inside the try, deliberately. The verbose line used to read
            // frame.buffer.width before the try, so a throw there skipped the
            // finally and leaked the frame -- the only exit that did.
            if (verbose) {
                println("DEBUG: [WebRTC] DesktopVideoRenderer (isScreen=$isScreenShare) received frame: ${frame.buffer.width}x${frame.buffer.height}, format=${frame.buffer.javaClass.simpleName}")
            }

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
        val srcWidth = this.width
        val srcHeight = this.height

        // Nearest-neighbour downscale by an integer step.
        //
        // Nearest rather than bilinear on purpose: this is a screen share, where
        // 1-pixel text IS the content. Averaging adjacent pixels turns small type
        // into grey mush, which is exactly the "blurry" complaint. Dropping pixels
        // keeps edges hard and every glyph legible. It can alias slightly on a
        // diagonal, which is a far better trade for text.
        val step = integerDownscaleStep(srcWidth, srcHeight, INBOUND_VIDEO_MAX_LONG_EDGE)
        val frameWidth = srcWidth / step
        val frameHeight = srcHeight / step

        val image = BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_INT_ARGB)
        val yPlane = getDataY()
        val uPlane = getDataU()
        val vPlane = getDataV()

        for (y in 0 until frameHeight) {
            val srcY = y * step
            val chromaRow = (srcY / 2)
            for (x in 0 until frameWidth) {
                val srcX = x * step
                val yValue = yPlane.get(srcY * getStrideY() + srcX).toInt() and 0xFF
                val uValue = uPlane.get(chromaRow * getStrideU() + (srcX / 2)).toInt() and 0xFF
                val vPlaneValue = vPlane.get(chromaRow * getStrideV() + (srcX / 2)).toInt() and 0xFF

                image.setRGB(x, y, yuvToArgb(yValue, uValue, vPlaneValue))
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

/**
 * One I420 pixel to opaque ARGB, limited-range BT.601.
 *
 * This used to fold a brightness "fix" into the luma base:
 *
 *     val c = ((yValue * 0.85) + 35)
 *
 * which is the whole reason a 1:1 looked like a washed-out green haze while group
 * calls -- which go through LiveKit and never touch this file -- looked correct.
 * A screen share is mostly dark UI, and lifting luma to `y * 0.85 + 35` moves
 * video black (16) to 48 and mid grey (128) to 144. Every dark pixel became a pale
 * grey-green, which is precisely the reported symptom. The colour channels were
 * computed from that damaged base, so no amount of adjusting them could recover it.
 *
 * Limited range because that is what video carries: luma 16-235, chroma 16-240.
 * Treating it as full range is the other half of the usual green cast, and it is
 * why the sample pixels never matched the source even on mid greys.
 *
 * The coefficients are the standard integer BT.601 ones, chosen over floats
 * because this runs per pixel per frame -- at 1280x720 and 15fps that is 14
 * million conversions a second and the arithmetic has to be cheap.
 *
 * Pure and top level so the conversion can be pinned by test against known
 * values, rather than judged by eye on a screenshot.
 */
internal fun yuvToArgb(y: Int, u: Int, v: Int): Int {
    val luma = (y - 16).coerceAtLeast(0) * 298
    val du = u - 128
    val dv = v - 128

    val red = clampChannel((luma + 409 * dv + 128) shr 8)
    val green = clampChannel((luma - 100 * du - 208 * dv + 128) shr 8)
    val blue = clampChannel((luma + 516 * du + 128) shr 8)

    // Alpha forced opaque so Compose does not treat the frame as transparent.
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun clampChannel(value: Int): Int = value.coerceIn(0, 255)

/**
 * The integer factor to shrink by, so the longest edge lands at or under [maxEdge].
 *
 * Integer-only, so the source pixel index is a multiplication rather than a
 * float multiply and a cast on every one of several million pixels. A frame
 * already inside the limit returns 1 and is untouched.
 */
internal fun integerDownscaleStep(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    val longest = maxOf(width, height)
    if (longest <= maxEdge) return 1
    // Ceiling so the result is always within the limit, never exactly on it.
    return (longest + maxEdge - 1) / maxEdge
}
