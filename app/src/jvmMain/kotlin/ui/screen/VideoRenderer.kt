package ui.screen.call

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
import livekit.LiveKitLocalVideoFrame
import livekit.LiveKitLocalVideoFrameStore
import livekit.LiveKitRemoteVideoFrame
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoTileRenderer
import webrtc.DesktopVideoRenderer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

@Composable
actual fun VideoRenderer(renderer: Any?, modifier: Modifier) {
    when (renderer) {
        is LiveKitVideoTileRenderer.LocalPreview -> {
            val frame by LiveKitLocalVideoFrameStore.latestFrame.collectAsState()
            LiveKitFrameRenderer(
                image = frame?.toBufferedImage(),
                modifier = modifier,
                contentScale = ContentScale.Crop,
                fallbackText = ""
            )
        }
        is LiveKitVideoTileRenderer.Remote -> {
            val frame by LiveKitRemoteVideoFrameStore.latestFrame.collectAsState()
            val matchingFrame = frame?.takeIf { current ->
                renderer.participantIdentity == null || current.participantIdentity == renderer.participantIdentity
            }
            LiveKitFrameRenderer(
                image = matchingFrame?.toBufferedImage(),
                modifier = modifier,
                contentScale = ContentScale.Crop,
                fallbackText = ""
            )
        }
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
                        contentScale = if (renderer.isScreenShare) ContentScale.Fit else ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (renderer.mirror && !renderer.isScreenShare) -1f else 1f
                                scaleY = if (renderer.isScreenShare) -1f else 1f
                            }
                    )
                } else {
                    Text("Starting video", color = Color.White)
                }
            }
        }
        "livekit_remote" -> {
            LiveKitVideoRenderer(modifier)
        }
        else -> {
            Box(
                modifier = modifier.background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Starting video", color = Color.White)
            }
        }
    }
}

@Composable
private fun LiveKitFrameRenderer(
    image: BufferedImage?,
    modifier: Modifier,
    contentScale: ContentScale,
    fallbackText: String
) {
    Box(
        modifier = modifier
            .clipToBounds()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image.toComposeImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackText.isNotBlank()) {
            Text(fallbackText, color = Color.White)
        }
    }
}

private fun LiveKitRemoteVideoFrame.toBufferedImage(): BufferedImage =
    rgbaToBufferedImage(buffer, width, height, format, strideY, "remote", trackSid)

private fun LiveKitLocalVideoFrame.toBufferedImage(): BufferedImage =
    rgbaToBufferedImage(buffer, width, height, format, strideY, "local", "preview")

private fun rgbaToBufferedImage(
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
