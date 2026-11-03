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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import livekit.LiveKitLocalVideoFrame
import livekit.LiveKitRemoteVideoFrame
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

// Frame conversion MUST NOT run inside composition: a 1080p frame is two
// million pixels, and the old implementation walked every pixel twice (RGBA ->
// BufferedImage -> ARGB bytes) before Compose could draw it. That per-pixel
// work was the real reason 60 fps shares starved audio. Skia can consume the
// RGBA buffer directly: installPixels copies it into a Bitmap and
// asComposeImageBitmap wraps that Bitmap without a colour conversion loop.
//
// Each tile still owns a conflated Channel<Unit> plus one long-lived collector:
// LaunchedEffect(frame) only signals "newer data is available" and the
// collector keeps at most one conversion in flight, so a slow frame degrades
// framerate instead of piling up work.
//
// Each produced Skia ImageBitmap owns native memory (~8 MB at 1080p) and this
// runs per frame per tile. See the note below rememberConvertedRgbaFrame for
// why those are reclaimed by skiko's Cleaner rather than closed explicitly.

internal fun rgbaToImageBitmap(
    buffer: ByteArray,
    width: Int,
    height: Int,
    format: Int,
    strideY: Int,
    label: String,
    trackSid: String
): ImageBitmap? {
    val rowStride = if (strideY > 0) strideY else width * 4
    val minimumBytes = if (height <= 0) 0 else ((height - 1) * rowStride) + (width * 4)
    if (format != 0 || width <= 0 || height <= 0 || buffer.size < minimumBytes) {
        util.OrbitLog.w(
            "OrbitCallUI",
            "unsupported $label frame layout trackSid=$trackSid " +
                "format=$format width=$width height=$height strideY=$strideY bytes=${buffer.size}"
        )
        return null
    }

    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
    val bitmap = Bitmap()
    if (!bitmap.installPixels(info, buffer, rowStride)) {
        bitmap.close()
        return null
    }
    return bitmap.asComposeImageBitmap()
}

@Composable
internal fun rememberRemoteFrameImage(frame: LiveKitRemoteVideoFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToImageBitmap(f.buffer, f.width, f.height, f.format, f.strideY, "remote", f.trackSid)
    }

@Composable
internal fun rememberLocalFrameImage(frame: LiveKitLocalVideoFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToImageBitmap(f.buffer, f.width, f.height, f.format, f.strideY, "local", "preview")
    }

@Composable
internal fun rememberLocalSharePreviewImage(frame: livekit.LiveKitLocalScreenShareTrack.LocalSharePreviewFrame?): ImageBitmap? =
    rememberConvertedRgbaFrame(frame) { f ->
        rgbaToImageBitmap(f.buffer, f.width, f.height, 0, f.width * 4, "local-share", "preview")
    }

/** Null [frame] is wrapped too, so the collector can tell "clear the tile" apart from "no news yet". */
private class PendingFrame<T : Any>(val frame: T?)

@Composable
private fun <T : Any> rememberConvertedRgbaFrame(
    frame: T?,
    convert: (T) -> ImageBitmap?
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
                converter.value(current)
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
 * Bitmap at up to 60 fps per tile.
 *
 * If that shows up as native memory pressure, the fix is to update a
 * pre-allocated Skia bitmap in place, not to reach past the API for a close().
 */

