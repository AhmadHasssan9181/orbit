package livekit

import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer

object LiveKitVideoSink {
    // Use a frame counter to force StateFlow to emit — ByteBuffer identity doesn't change
    private val _frameCounter = MutableStateFlow(0L)
    val frameCounter = _frameCounter

    // The actual buffer is read directly by the renderer
    @Volatile var currentBuffer: ByteBuffer? = null
        private set
    @Volatile var currentWidth: Int = 0
        private set
    @Volatile var currentHeight: Int = 0
        private set

    // Also expose the old frameFlow for backwards compat in the renderer
    val frameFlow get() = _frameCounter

    init {
        try {
            webrtc.WebrtcNativeDepsLoader.load()
            nativeInit()
        } catch (e: UnsatisfiedLinkError) {
            println("ERROR: Could not initialize native video bridge: ${e.message}")
        }
    }

    private external fun nativeInit()
    
    private var directBuffer: ByteBuffer? = null
    
    private var logCount = 0
    fun pushFrame(
        width: Int, 
        height: Int, 
        yPtr: Long, yStride: Int, ySize: Int,
        uPtr: Long, uStride: Int, uSize: Int,
        vPtr: Long, vStride: Int, vSize: Int
    ) {
        if (logCount++ % 100 == 0) {
            println("DEBUG [LiveKitVideoSink] Received frame #$logCount: ${width}x${height}")
        }
        currentWidth = width
        currentHeight = height
        val size = width * height * 4 // ARGB
        var buffer = directBuffer
        if (buffer == null || buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size)
            directBuffer = buffer
        }
        
        nativePushFrame(
            buffer, width, height,
            yPtr, yStride, ySize,
            uPtr, uStride, uSize,
            vPtr, vStride, vSize
        )
        currentBuffer = buffer
        // Increment counter to force StateFlow emission (same ByteBuffer reference won't trigger recomposition)
        _frameCounter.value = _frameCounter.value + 1
    }

    private external fun nativePushFrame(
        buffer: ByteBuffer, width: Int, height: Int,
        yPtr: Long, yStride: Int, ySize: Int,
        uPtr: Long, uStride: Int, uSize: Int,
        vPtr: Long, vStride: Int, vSize: Int
    )
}
