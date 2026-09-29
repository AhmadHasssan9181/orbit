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
                // runCatching per frame: an exception escaping this loop ends
                // the only collector, and since the channel is CONFLATED later
                // snapshots would still "succeed" at trySend and be discarded
                // forever.
                runCatching { convertI420ToRgba(snapshot) }
                    .onSuccess { previewFrame.value = it }
                    .onFailure { println("WARN [LocalSharePreview] conversion failed: ${it.message}") }
            }
        }
    }

    private var lastPreviewNs = 0L

    val sink = object : VideoTrackSink {
        override fun onVideoFrame(frame: WrtcVideoFrame) {
            // The capturer hands ownership of the native I420 frame to the sink,
            // so it must be released on every path (including the early return
            // below) or the media engine leaks ~94 MB/s at 1080p30.
            try {
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
                CallPathStats.onShareFramePushed(packed.remaining())
            } finally {
                runCatching { frame.release() }
            }
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
        // Always allocate the full requested size. This used to clamp to
        // view.remaining(), so a plane whose capacity was smaller than
        // stride*rows produced a SHORT array with no diagnostic -- and
        // convertI420ToRgba then indexed past its end, throwing
        // ArrayIndexOutOfBoundsException inside the preview collector, which
        // (SupervisorJob, so it did not kill the scope) left the "you are
        // sharing this" tile frozen on a stale frame for the rest of the
        // session with nothing able to restart it.
        val bytes = ByteArray(byteCount)
        val available = view.remaining().coerceAtMost(byteCount)
        if (available < byteCount) {
            println(
                "WARN [LocalSharePreview] plane is $available bytes, expected $byteCount; " +
                    "zero-filling the tail so conversion stays in bounds"
            )
        }
        view.get(bytes, 0, available)
        return bytes
    }

    // BT.601 limited-range YUV → RGBA, matching how remote frames are decoded.
    private fun convertI420ToRgba(snapshot: I420Snapshot): LocalSharePreviewFrame {
        val w = snapshot.width
        val h = snapshot.height
        val rgba = ByteArray(w * h * 4)
        for (row in 0 until h) {
            val yRow = row * snapshot.strideY
            // Each plane is a tight copy of stride*rows bytes, so chroma rows are
            // addressed with their own stride — the V plane may be padded
            // differently from U.
            val uRow = (row / 2) * snapshot.strideU
            val vRow = (row / 2) * snapshot.strideV
            var outIndex = row * w * 4
            for (column in 0 until w) {
                val y = (snapshot.y[yRow + column].toInt() and 0xFF) - 16
                val u = (snapshot.u[uRow + (column / 2)].toInt() and 0xFF) - 128
                val v = (snapshot.v[vRow + (column / 2)].toInt() and 0xFF) - 128
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

    // The share track is single-instance and the native push memcpy's the buffer
    // before returning, so one direct buffer is reused across frames: a fresh
    // 3 MB direct allocation per frame forces Bits.reserveMemory into full GC
    // runs, which show up as UI freezes and audio underruns.
    private val packedLock = Any()
    private var packedBuffer: ByteBuffer? = null
    private var packedBytes = 0
    private var rowScratch = ByteArray(0)

    private fun packI420(buffer: I420Buffer): ByteBuffer = synchronized(packedLock) {
        val width = buffer.width
        val height = buffer.height
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val bytes = width * height + chromaWidth * chromaHeight * 2
        // Only reuse an exactly-sized buffer: the native side validates
        // capacity against the tight-plane size it expects.
        val existing = packedBuffer
        val packed = if (existing != null && packedBytes == bytes) {
            existing
        } else {
            ByteBuffer.allocateDirect(bytes).also {
                packedBuffer = it
                packedBytes = bytes
            }
        }
        val maxColumns = maxOf(width, chromaWidth)
        if (rowScratch.size < maxColumns) {
            rowScratch = ByteArray(maxColumns)
        }
        packed.clear()
        copyPlane(buffer.dataY, packed, height, width, buffer.strideY)
        copyPlane(buffer.dataU, packed, chromaHeight, chromaWidth, buffer.strideU)
        copyPlane(buffer.dataV, packed, chromaHeight, chromaWidth, buffer.strideV)
        packed.flip()
        packed
    }

    private fun copyPlane(source: ByteBuffer, destination: ByteBuffer, rows: Int, columns: Int, stride: Int) {
        val scratch = rowScratch
        // Hoisted: the old code duplicated the buffer inside the loop, i.e. once
        // per row (2160 allocations for a 1080p frame). One duplicate, mutated
        // per row. Reads capacity, not limit, so an already-positioned buffer is
        // handled the same as a fresh one.
        val rowView = source.duplicate()
        for (row in 0 until rows) {
            val rowStart = row * stride
            // Clamp rather than throw. position(rowStart + columns) with a
            // short plane throws IllegalArgumentException, and this function has
            // no catch on the capture path, so a padded/mismatched I420 plane
            // turned into a hard camera failure instead of a dropped frame.
            val from = rowStart.coerceIn(0, rowView.capacity())
            val to = (rowStart + columns).coerceIn(from, rowView.capacity())
            val count = to - from
            if (count < columns) {
                java.util.Arrays.fill(scratch, 0, columns, 0)
            }
            rowView.position(from)
            rowView.limit(to)
            rowView.get(scratch, 0, count)
            destination.put(scratch, 0, columns)
        }
    }
}
