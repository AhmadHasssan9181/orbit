package ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import dev.onvoid.webrtc.media.video.VideoTrackSink
import dev.onvoid.webrtc.media.video.VideoTrack
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    val videoTrack = renderer as? VideoTrack
    
    if (videoTrack == null) {
        Box(
            modifier = modifier.background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            Text("No Video Stream", color = Color.White)
        }
        return
    }

    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    // Reusable resources for performance
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rgbaBuffer by remember { mutableStateOf<ByteBuffer?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }

    DisposableEffect(videoTrack) {
        val sink = VideoTrackSink { frame ->
            try {
                val videoBuffer = frame.buffer
                val width = videoBuffer.width
                val height = videoBuffer.height

                // Reallocate buffers only if resolution changes
                if (bitmap == null || bitmap!!.width != width || bitmap!!.height != height) {
                    bitmap = Bitmap().apply {
                        allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
                    }
                    rgbaBuffer = ByteBuffer.allocateDirect(width * height * 4)
                    bytes = ByteArray(width * height * 4)
                }

                // Convert YUV I420 to RGBA
                rgbaBuffer?.let { dest ->
                    dest.clear()
                    VideoBufferConverter.convertFromI420(videoBuffer, dest, FourCC.RGBA)
                    
                    // Update Skia Bitmap pixels
                    dest.rewind()
                    bytes?.let { b ->
                        dest.get(b)
                        bitmap?.installPixels(b)
                    }
                    
                    // Trigger Compose recomposition
                    imageBitmap = bitmap?.asComposeImageBitmap()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                frame.release()
            }
        }

        videoTrack.addSink(sink)
        onDispose {
            videoTrack.removeSink(sink)
        }
    }

    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        imageBitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Video Stream",
                modifier = Modifier.fillMaxSize()
            )
        } ?: Text("Connecting...", color = Color.White)
    }
}
