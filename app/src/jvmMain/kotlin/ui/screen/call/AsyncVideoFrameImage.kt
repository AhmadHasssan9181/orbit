package ui.screen.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import livekit.LiveKitLocalVideoFrame
import livekit.LiveKitRemoteVideoFrame

// RGBA→BufferedImage conversion MUST NOT run inside composition: a 1080p frame
// is two million pixels of per-pixel work on the Compose main thread, which
// starves the UI loop completely (window stops painting while audio keeps
// flowing). Frames are conflated through a channel and converted on the Default
// dispatcher; while a conversion runs, newer frames overwrite the pending one,
// so slow conversion degrades framerate instead of freezing the UI.

internal fun rgbaToBufferedImage(
    buffer: ByteArray,
    width: Int,
    height: Int,
    format: Int,
    strideY: Int,
    label: String,
    trackSid: String
): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = (image.raster.dataBuffer as DataBufferInt).data
    val rowStride = if (strideY > 0) strideY else width * 4
    val minimumBytes = if (height == 0) 0 else ((height - 1) * rowStride) + (width * 4)
    if (format != 0 || buffer.size < minimumBytes) {
        println(
            "WARN [OrbitCallUI] unsupported $label frame layout trackSid=$trackSid " +
                "format=$format width=$width height=$height strideY=$strideY bytes=${buffer.size}"
        )
        return image
    }

    var pixelIndex = 0
    for (y in 0 until height) {
        var byteIndex = y * rowStride
        for (x in 0 until width) {
            val r = buffer[byteIndex].toInt() and 0xFF
            val g = buffer[byteIndex + 1].toInt() and 0xFF
            val b = buffer[byteIndex + 2].toInt() and 0xFF
            val a = buffer[byteIndex + 3].toInt() and 0xFF
            pixels[pixelIndex] = (a shl 24) or (r shl 16) or (g shl 8) or b
            pixelIndex += 1
            byteIndex += 4
        }
    }
    return image
}

@Composable
internal fun rememberRemoteFrameImage(frame: LiveKitRemoteVideoFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToBufferedImage(f.buffer, f.width, f.height, f.format, f.strideY, "remote", f.trackSid)
    }

@Composable
internal fun rememberLocalFrameImage(frame: LiveKitLocalVideoFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToBufferedImage(f.buffer, f.width, f.height, f.format, f.strideY, "local", "preview")
    }

@Composable
internal fun rememberLocalSharePreviewImage(frame: livekit.LiveKitLocalScreenShareTrack.LocalSharePreviewFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToBufferedImage(f.buffer, f.width, f.height, 0, f.width * 4, "local-share", "preview")
    }

@Composable
private fun <T : Any> rememberConvertedRgbaFrame(
    frame: T?,
    convert: (T) -> BufferedImage
): ImageBitmap? {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val channel = remember { Channel<T>(Channel.CONFLATED) }
    var lastSent by remember { mutableStateOf<T?>(null) }
    var lastEmitMs by remember { mutableStateOf(0L) }

    // Each conversion allocates a full-frame BufferedImage plus a native Skia
    // bitmap; at capture rate across several tiles that garbage outpaces
    // cleanup and drives RSS into the gigabytes. ~20 fps per tile is visually
    // indistinguishable for call tiles and cuts conversion churn ~4x. The next
    // arriving frame retries, so a skipped final frame costs nothing.
    SideEffect {
        val current = frame
        if (current != null && current !== lastSent) {
            val now = System.currentTimeMillis()
            if (lastEmitMs == 0L || now - lastEmitMs >= 50L) {
                lastEmitMs = now
                lastSent = current
                channel.trySend(current)
            }
        }
    }
    LaunchedEffect(channel) {
        for (f in channel) {
            val bitmap = withContext(Dispatchers.Default) {
                convert(f).toComposeImageBitmap()
            }
            image = bitmap
        }
    }
    return image
}
