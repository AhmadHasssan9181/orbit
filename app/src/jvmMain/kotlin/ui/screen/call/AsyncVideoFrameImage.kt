package ui.screen.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.atomic.AtomicReference
import livekit.LiveKitLocalVideoFrame
import livekit.LiveKitRemoteVideoFrame

// RGBA→BufferedImage conversion MUST NOT run inside composition: a 1080p frame
// is two million pixels of per-pixel work on the Compose main thread, which
// starves the UI loop completely (window stops painting while audio keeps
// flowing). Each tile instead owns a conflated Channel<Unit> plus a single
// long-lived collector coroutine: the LaunchedEffect(frame) only signals "newer
// data is available", and the collector keeps at most one conversion in flight,
// so a slow conversion degrades framerate instead of piling up work on
// Dispatchers.Default.
//
// Each produced Skia ImageBitmap owns native memory (~8 MB at 1080p) and this
// runs per frame per tile. See the note below rememberConvertedRgbaFrame for
// why those are reclaimed by skiko's Cleaner rather than closed explicitly.

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
        util.OrbitLog.w(
            "OrbitCallUI",
            "unsupported $label frame layout trackSid=$trackSid " +
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

/** Null [frame] is wrapped too, so the collector can tell "clear the tile" apart from "no news yet". */
private class PendingFrame<T : Any>(val frame: T?)

@Composable
private fun <T : Any> rememberConvertedRgbaFrame(
    frame: T?,
    convert: (T) -> BufferedImage
): ImageBitmap? {
    val converter = rememberUpdatedState(convert)
    val requests = remember { AtomicReference<PendingFrame<T>?>(null) }
    val signal = remember { Channel<Unit>(Channel.CONFLATED) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        for (unit in signal) {
            val request = requests.getAndSet(null) ?: continue
            val current = request.frame
            if (current == null) {
                // The source went away: drop the last frame so the tile falls
                // back to its placeholder instead of freezing forever.
                image = null
                continue
            }
            image = withContext(Dispatchers.Default) {
                converter.value(current).toComposeImageBitmap()
            }
        }
    }

    LaunchedEffect(frame) {
        requests.set(PendingFrame(frame))
        signal.trySend(Unit)
    }

    return image
}

/**
 * Why the produced ImageBitmaps are not closed explicitly.
 *
 * The obvious route is `asSkiaBitmap(image).close()`: `javap` on the resolved
 * ui-graphics-desktop 1.7.3 does show `SkiaImageAsset_skikoKt.asSkiaBitmap` as a
 * public static method returning a `Bitmap` (which extends `Managed`, an
 * `AutoCloseable`). But the Kotlin compiler does not resolve it from here --
 * Compose exposes that facade at the JVM level only, so this is not a usable
 * API from consumer code. `ImageBitmap.close()` itself is internal.
 *
 * So reclamation is left to skiko's Cleaner, which `org.jetbrains.skia.impl.
 * Managed` does register for every instance. That is deferred, not immediate,
 * which is the real cost: the conflated channel above bounds the native
 * BACKLOG (one conversion in flight per tile, superseded work dropped before it
 * starts) but NOT the allocation rate -- each 1080p frame still costs a ~8 MB
 * BufferedImage plus a ~8 MB Bitmap, at up to 30 fps per tile.
 *
 * If that shows up as native memory pressure, the fix is to stop round-tripping
 * through BufferedImage and hand Compose a pre-allocated skia bitmap updated in
 * place, not to reach past the API for a close().
 */

