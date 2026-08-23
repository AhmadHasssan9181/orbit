package livekit

import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame as WrtcVideoFrame
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Screen-share frame pump for group calls: the WebRTC desktop capturer's track
 * sinks frames here and the native C++ session publishes them as a
 * SOURCE_SCREENSHARE LiveKit track (armed/disarmed by CallManager).
 */
object LiveKitLocalScreenShareTrack {
    /** Downsampled-rate copy of our own share for the local preview tile. */
    class LocalSharePreviewFrame(val buffer: ByteArray, val width: Int, val height: Int)

    val previewFrame = MutableStateFlow<LocalSharePreviewFrame?>(null)

    fun clearPreview() {
        previewFrame.value = null
    }

    private class I420Snapshot(
        val y: ByteArray, val u: ByteArray, val v: ByteArray,
        val width: Int, val height: Int,
        val strideY: Int, val strideU: Int, val strideV: Int
    )

    private val previewScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val previewChannel = Channel<I420Snapshot>(Channel.CONFLATED)

    init {
        previewScope.launch {
            for (snapshot in previewChannel) {
                previewFrame.value = convertI420ToRgba(snapshot)
            }
        }
    }

    private var lastPreviewNs = 0L

    val sink = object : VideoTrackSink {
        override fun onVideoFrame(frame: WrtcVideoFrame) {
            val buffer = frame.buffer as? I420Buffer ?: return

            maybeEmitPreview(buffer)

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

    // ~10 fps is plenty for a "you are sharing this" tile and keeps the
    // conversion cost off the capture thread's publish path.
    private fun maybeEmitPreview(buffer: I420Buffer) {
        val now = System.nanoTime()
        if (now - lastPreviewNs < 100_000_000L) return
        lastPreviewNs = now
        val chromaWidth = (buffer.width + 1) / 2
        val chromaHeight = (buffer.height + 1) / 2
        previewChannel.trySend(
            I420Snapshot(
                y = copyBytes(buffer.dataY, buffer.strideY * buffer.height),
                u = copyBytes(buffer.dataU, buffer.strideU * chromaHeight),
                v = copyBytes(buffer.dataV, buffer.strideV * chromaHeight),
                width = buffer.width,
                height = buffer.height,
                strideY = buffer.strideY,
                strideU = buffer.strideU,
                strideV = buffer.strideV
            )
        )
    }

    private fun copyBytes(source: ByteBuffer, byteCount: Int): ByteArray {
        val view = source.duplicate()
        view.rewind()
        val bytes = ByteArray(byteCount.coerceAtMost(view.remaining()))
        view.get(bytes)
        return bytes
    }

    // BT.601 limited-range YUV → RGBA, matching how remote frames are decoded.
    private fun convertI420ToRgba(snapshot: I420Snapshot): LocalSharePreviewFrame {
        val w = snapshot.width
        val h = snapshot.height
        val rgba = ByteArray(w * h * 4)
        val chromaWidth = (w + 1) / 2
        for (row in 0 until h) {
            val yRow = row * snapshot.strideY
            val uvRow = (row / 2) * snapshot.strideU
            var outIndex = row * w * 4
            for (column in 0 until w) {
                val y = (snapshot.y[yRow + column].toInt() and 0xFF) - 16
                val u = (snapshot.u[uvRow + (column / 2)].toInt() and 0xFF) - 128
                val v = (snapshot.v[(uvRow / snapshot.strideU * chromaWidth) + (column / 2)].toInt() and 0xFF) - 128
                var r = (1191 * y + 1634 * v) shr 10
                var g = (1191 * y - 833 * v - 400 * u) shr 10
                var b = (1191 * y + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                rgba[outIndex] = r.toByte()
                rgba[outIndex + 1] = g.toByte()
                rgba[outIndex + 2] = b.toByte()
                rgba[outIndex + 3] = -1
                outIndex += 4
            }
        }
        return LocalSharePreviewFrame(rgba, w, h)
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
