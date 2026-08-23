package livekit

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame as WrtcVideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer

/**
 * Screen-share frame pump for group calls: the WebRTC desktop capturer's track
 * sinks frames here and the native C++ session publishes them as a
 * SOURCE_SCREENSHARE LiveKit track (armed/disarmed by CallManager).
 */
object LiveKitLocalScreenShareTrack {
    val sink = object : VideoTrackSink {
        override fun onVideoFrame(frame: WrtcVideoFrame) {
            val buffer = frame.buffer as? I420Buffer ?: return

            val packed = packI420(buffer)
            LiveKitNativeMediaSession.pushScreenShareFrame(
                buffer = packed,
                width = buffer.width,
                height = buffer.height,
                timestampNs = System.nanoTime(),
                rotation = frame.rotation
            )
        }
    }

    private fun packI420(buffer: I420Buffer): ByteBuffer {
        val width = buffer.width
        val height = buffer.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val bytes = width * height + chromaWidth * chromaHeight * 2
        val packed = ByteBuffer.allocateDirect(bytes)
        copyPlane(buffer.dataY, packed, height, width, buffer.strideY)
        copyPlane(buffer.dataU, packed, chromaHeight, chromaWidth, buffer.strideU)
        copyPlane(buffer.dataV, packed, chromaHeight, chromaWidth, buffer.strideV)
        packed.flip()
        return packed
    }

    private fun copyPlane(source: ByteBuffer, destination: ByteBuffer, rows: Int, columns: Int, stride: Int) {
        val view = source.duplicate()
        for (row in 0 until rows) {
            val rowStart = row * stride
            for (column in 0 until columns) {
                destination.put(view.get(rowStart + column))
            }
        }
    }
}
