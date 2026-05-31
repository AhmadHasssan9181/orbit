package ui.screen.call

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Color
import livekit.LiveKitVideoSink
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer

@Composable
actual fun LiveKitVideoRenderer(modifier: Modifier) {
    // Collect the frame counter — this increments on every new frame, forcing recomposition
    val frameCount by LiveKitVideoSink.frameCounter.collectAsState()
    val activeSpeakers: Set<String> by livekit.LiveKitClient.activeSpeakers.collectAsState(emptySet())
    
    val isSpeaking = activeSpeakers.isNotEmpty() 
    
    val borderColor by androidx.compose.animation.animateColorAsState(
        if (isSpeaking) Color(0xFF22C55E) else Color.Transparent
    )

    Box(
        modifier = modifier
            .background(Color.Black)
            .border(if (isSpeaking) 2.dp else 0.dp, borderColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        val buffer = LiveKitVideoSink.currentBuffer
        val width = LiveKitVideoSink.currentWidth
        val height = LiveKitVideoSink.currentHeight
        
        // Ensure buffer capacity matches width and height to prevent race conditions during resolution changes
        val expectedCapacity = width * height * 4
        
        if (buffer != null && width > 0 && height > 0 && frameCount > 0 && buffer.capacity() >= expectedCapacity) {
            // Read the ARGB data from the direct buffer
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
                modifier = Modifier.fillMaxSize()
            )
        } else {
            println("DEBUG [LiveKitVideoRenderer] Showing empty state! buffer=${buffer != null}, width=$width, height=$height, frameCount=$frameCount")
            // Either waiting for video to start, or the remote participants have their cameras off.
            Text("Voice Call Connected (No Video)", color = Color.White)
        }
    }
}
