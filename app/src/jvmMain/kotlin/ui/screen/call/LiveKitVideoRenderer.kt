package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import livekit.LiveKitRemoteVideoFrame
import livekit.LiveKitRemoteVideoFrameStore
import livekit.LiveKitVideoSink

@Composable
actual fun LiveKitVideoRenderer(modifier: Modifier) {
    val remoteFrame by LiveKitRemoteVideoFrameStore.latestFrame.collectAsState()
    val frameCount by LiveKitVideoSink.frameCounter.collectAsState()
    val activeSpeakers: Set<String> by livekit.LiveKitClient.activeSpeakers.collectAsState(emptySet())

    val isSpeaking = activeSpeakers.isNotEmpty()
    val borderColor by animateColorAsState(
        if (isSpeaking) Color(0xFF22C55E) else Color.Transparent
    )

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(if (isSpeaking) 2.dp else 0.dp, borderColor, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val frame = remoteFrame
        if (frame != null) {
            val image = remember(frame.sequence) { frame.toBufferedImage() }
            Image(
                bitmap = image.toComposeImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            LegacyLiveKitVideoFallback(frameCount)
        }
    }
}

@Composable
private fun LegacyLiveKitVideoFallback(frameCount: Long) {
    val buffer = LiveKitVideoSink.currentBuffer
    val width = LiveKitVideoSink.currentWidth
    val height = LiveKitVideoSink.currentHeight
    val expectedCapacity = width * height * 4

    if (buffer != null && width > 0 && height > 0 && frameCount > 0 && buffer.capacity() >= expectedCapacity) {
        val argbArray = IntArray(width * height)
        synchronized(buffer) {
            buffer.position(0)
            buffer.asIntBuffer().get(argbArray)
        }

        val bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val data = (bufferedImage.raster.dataBuffer as DataBufferInt).data
        System.arraycopy(argbArray, 0, data, 0, argbArray.size)

        Image(
            bitmap = bufferedImage.toComposeImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        println("DEBUG [LiveKitVideoRenderer] Showing empty state! buffer=${buffer != null}, width=$width, height=$height, frameCount=$frameCount")
        Text("Voice Call Connected (No Video)", color = Color.White)
    }
}

private fun LiveKitRemoteVideoFrame.toBufferedImage(): BufferedImage {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val pixels = (image.raster.dataBuffer as DataBufferInt).data
    val rowStride = if (strideY > 0) strideY else width * 4
    val minimumBytes = if (height == 0) 0 else ((height - 1) * rowStride) + (width * 4)
    if (format != 0 || buffer.size < minimumBytes) {
        println(
            "WARN [RemoteVideoUI] unsupported frame layout trackSid=$trackSid format=$format " +
                "width=$width height=$height strideY=$strideY bytes=${buffer.size}"
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
