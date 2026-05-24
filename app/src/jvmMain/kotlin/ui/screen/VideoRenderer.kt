package ui.screen

<<<<<<< HEAD
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
=======
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import webrtc.DesktopVideoRenderer

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    when (renderer) {
        is DesktopVideoRenderer -> {
            val frame by renderer.frameImage.collectAsState()
            Box(
                modifier = modifier
                    .clipToBounds()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                val image = frame
                if (image != null) {
                    Image(
                        bitmap = image.toComposeImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (renderer.mirror) -1f else 1f
                            }
                    )
                } else {
                    Text("Starting video", color = Color.White)
                }
            }
        }
        else -> {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Starting video", color = Color.White)
            }
        }
>>>>>>> 56b3d3662f481fe90306b4ac4d9afd5b3094a503
    }
}
