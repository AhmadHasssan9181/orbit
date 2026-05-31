package livekit

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame as WrtcVideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer

object LiveKitLocalTrack {
    private var sourceHandle: Long = 0
    private var isStarted = false

    val sink = object : VideoTrackSink {
        override fun onVideoFrame(frame: WrtcVideoFrame) {
            if (!isStarted) return
            
            val buffer = frame.buffer as? I420Buffer ?: return
            
            // Get raw pointers from direct buffers
            val yAddr = getBufferAddress(buffer.dataY)
            val uAddr = getBufferAddress(buffer.dataU)
            val vAddr = getBufferAddress(buffer.dataV)
            
            if (yAddr == 0L || uAddr == 0L || vAddr == 0L) return

            val videoBufferInfo = livekit.proto.VideoFrame.VideoBufferInfo.newBuilder()
                .setType(livekit.proto.VideoFrame.VideoBufferType.I420)
                .setWidth(buffer.width)
                .setHeight(buffer.height)
                .addComponents(livekit.proto.VideoFrame.VideoBufferInfo.ComponentInfo.newBuilder()
                    .setDataPtr(yAddr)
                    .setStride(buffer.strideY)
                    .setSize(buffer.strideY * buffer.height)
                    .build())
                .addComponents(livekit.proto.VideoFrame.VideoBufferInfo.ComponentInfo.newBuilder()
                    .setDataPtr(uAddr)
                    .setStride(buffer.strideU)
                    .setSize(buffer.strideU * ((buffer.height + 1) / 2))
                    .build())
                .addComponents(livekit.proto.VideoFrame.VideoBufferInfo.ComponentInfo.newBuilder()
                    .setDataPtr(vAddr)
                    .setStride(buffer.strideV)
                    .setSize(buffer.strideV * ((buffer.height + 1) / 2))
                    .build())
                .build()

            val rotNum = when (frame.rotation) {
                0 -> 0
                90 -> 1
                180 -> 2
                270 -> 3
                else -> 0
            }

            val captureReq = livekit.proto.VideoFrame.CaptureVideoFrameRequest.newBuilder()
                .setSourceHandle(sourceHandle)
                .setBuffer(videoBufferInfo)
                .setRotation(livekit.proto.VideoFrame.VideoRotation.forNumber(rotNum))
                .setTimestampUs(frame.timestampNs / 1000)
                .build()
                
            val request = livekit.proto.Ffi.FfiRequest.newBuilder()
                .setCaptureVideoFrame(captureReq)
                .build()
                
            LiveKitClient.request(request)
        }
    }

    fun startCapture(width: Int, height: Int) {
        if (isStarted) return
        
        // 1. Create native source in LiveKit FFI
        val sourceReq = livekit.proto.VideoFrame.NewVideoSourceRequest.newBuilder()
            .setType(livekit.proto.VideoFrame.VideoSourceType.VIDEO_SOURCE_NATIVE)
            .setResolution(livekit.proto.VideoFrame.VideoSourceResolution.newBuilder()
                .setWidth(width)
                .setHeight(height)
                .build())
            .build()
            
        val request = livekit.proto.Ffi.FfiRequest.newBuilder()
            .setNewVideoSource(sourceReq)
            .build()
            
        val response = LiveKitClient.request(request)
        if (response?.hasNewVideoSource() == true) {
            sourceHandle = response.newVideoSource.source.handle.id
            println("DEBUG [LiveKitLocalTrack] Created native video source with handle: $sourceHandle")
            isStarted = true
        } else {
            println("ERROR [LiveKitLocalTrack] Failed to create native video source")
        }
    }
    
    fun getSourceHandle(): Long = sourceHandle

    external fun getBufferAddress(buffer: ByteBuffer): Long
}
