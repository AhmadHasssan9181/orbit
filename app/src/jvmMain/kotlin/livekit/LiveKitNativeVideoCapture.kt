package livekit

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.MediaDevices
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoCaptureCapability
import dev.onvoid.webrtc.media.video.VideoDevice
import dev.onvoid.webrtc.media.video.VideoDeviceSource
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import dev.onvoid.webrtc.media.video.VideoTrack
import dev.onvoid.webrtc.media.video.VideoTrackSink
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import webrtc.WebrtcNativeDepsLoader

object LiveKitNativeVideoCapture {
    private const val DEFAULT_WIDTH = 640
    private const val DEFAULT_HEIGHT = 480
    private const val DEFAULT_FPS = 30
    private const val FORMAT_I420 = 5
    private const val FRAME_LOG_LIMIT = 8

    @Volatile
    private var activeCapture: ActiveCapture? = null

    // Virtual background: injected into the capture source at next camera open
    // using the same ONNX segmentation processor as the P2P engine.
    @Volatile
    private var virtualBackgroundEnabled = false

    fun setVirtualBackgroundEnabled(enabled: Boolean) {
        virtualBackgroundEnabled = enabled
    }

    fun openForPublish(): NativeResult<VideoPublishConfig> {
        stopAndJoin()

        val requestedWidth = intProperty("orbit.video.requestedWidth") ?: DEFAULT_WIDTH
        val requestedHeight = intProperty("orbit.video.requestedHeight") ?: DEFAULT_HEIGHT
        val requestedFps = intProperty("orbit.video.requestedFps") ?: DEFAULT_FPS

        println(
            """
            DEBUG [VideoPublish] camera open requested
            requestedWidth=$requestedWidth
            requestedHeight=$requestedHeight
            requestedFps=$requestedFps
            """.trimIndent()
        )

        WebrtcNativeDepsLoader.load()
        val candidates = runCatching { MediaDevices.getVideoCaptureDevices() }
            .getOrElse { error ->
                return NativeResult.failure(
                    NativeError(
                        code = NativeErrorCode.VIDEO_DEVICE_UNAVAILABLE,
                        message = error.message ?: "Camera discovery failed",
                        operation = "openVideoCapture",
                        recoverable = true
                    )
                )
            }
            .mapIndexed { index, device ->
                val capabilities = runCatching { MediaDevices.getVideoCaptureCapabilities(device) }
                CameraCandidate(
                    index = index,
                    device = device,
                    capabilities = capabilities.getOrDefault(emptyList()),
                    available = capabilities.isSuccess
                )
            }

        if (candidates.isEmpty()) {
            return NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.VIDEO_DEVICE_UNAVAILABLE,
                    message = "No video capture devices were found",
                    operation = "openVideoCapture",
                    recoverable = true
                )
            )
        }

        val selected = selectCamera(candidates)
            ?: return NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.VIDEO_DEVICE_UNAVAILABLE,
                    message = "No usable camera matched the configured selector",
                    operation = "openVideoCapture",
                    recoverable = true
                )
            )
        val capability = chooseCapability(selected.capabilities, requestedWidth, requestedHeight, requestedFps)
            ?: VideoCaptureCapability(requestedWidth, requestedHeight, requestedFps)

        var factory: PeerConnectionFactory? = null
        var source: VideoDeviceSource? = null
        var track: VideoTrack? = null
        val sink = PublishSink()
        var captureStarted = false

        return try {
            factory = PeerConnectionFactory()
            source = VideoDeviceSource().apply {
                setVideoCaptureDevice(selected.device)
                setVideoCaptureCapability(capability)
            }
            track = factory.createVideoTrack("orbitNativeCamera", source).apply {
                setEnabled(true)
                addSink(sink)
            }
            val capture = ActiveCapture(
                factory = factory,
                source = source,
                track = track,
                sink = sink,
                config = VideoPublishConfig(
                    width = capability.width,
                    height = capability.height,
                    fps = capability.frameRate,
                    format = FORMAT_I420,
                    cameraName = selected.name,
                    cameraIndex = selected.index
                )
            )
            sink.capture = capture
            source.start()
            captureStarted = true
            if (virtualBackgroundEnabled) {
                val modelPath = webrtc.extractModelToTempFile("selfie_segmentation.onnx")
                if (modelPath != null) {
                    runCatching { webrtc.OrbitVideoEngine.injectVideoProcessor(source, modelPath) }
                        .onSuccess { println("DEBUG [VideoPublish] virtual background processor injected") }
                        .onFailure { println("WARN [VideoPublish] virtual background injection failed: ${it.message}") }
                } else {
                    println("WARN [VideoPublish] virtual background model missing; capturing unblurred")
                }
            }
            activeCapture = capture
            println(
                """
                DEBUG [VideoPublish] camera opened
                name=${selected.name}
                index=${selected.index}
                width=${capability.width}
                height=${capability.height}
                fps=${capability.frameRate}
                """.trimIndent()
            )
            NativeResult.success(capture.config)
        } catch (error: Throwable) {
            if (captureStarted) {
                runCatching { source?.stop() }
            }
            runCatching { track?.removeSink(sink) }
            runCatching { track?.setEnabled(false) }
            runCatching { track?.dispose() }
            runCatching { source?.dispose() }
            runCatching { factory?.dispose() }
            NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.VIDEO_DEVICE_UNAVAILABLE,
                    message = error.message ?: error::class.simpleName.orEmpty(),
                    operation = "openVideoCapture",
                    recoverable = true
                )
            )
        }
    }

    fun enableNativePush(
        sessionId: Long,
        epoch: Long,
        bridge: OrbitLiveKitNativeBridge,
        onFailure: (NativeError) -> Unit,
        onActualDimensions: ((width: Int, height: Int) -> Unit)? = null
    ): NativeResult<Unit> {
        val capture = activeCapture
            ?: return NativeResult.failure(
                NativeError(
                    code = NativeErrorCode.VIDEO_CAPTURE_FAILED,
                    message = "Camera capture is not open",
                    operation = "enableVideoFramePush",
                    recoverable = true
                )
            )
        capture.enableNativePush(sessionId, epoch, bridge, onFailure, onActualDimensions)
        return NativeResult.success(Unit)
    }

    /**
     * Stops capture and disposes the native objects.
     *
     * Deliberately synchronous: the call sites interleave this with follow-up
     * publish/disarm logic that must observe the capture already gone, so
     * hopping to a background dispatcher here would break that ordering. The
     * EDT cost is handled one level up -- CallManager runs this off the Compose
     * dispatcher, rather than by making this fire-and-forget.
     */
    fun stopAndJoin() {
        val capture = activeCapture
        activeCapture = null
        LiveKitLocalVideoFrameStore.clear("capture-stop")
        capture?.stop()
    }

    private fun selectCamera(candidates: List<CameraCandidate>): CameraCandidate? {
        val indexRaw = System.getProperty("orbit.video.inputDeviceIndex")?.trim()?.takeIf { it.isNotEmpty() }
        if (indexRaw != null) {
            val requestedIndex = indexRaw.toIntOrNull()
            return candidates.firstOrNull { candidate ->
                requestedIndex != null && candidate.index == requestedIndex && candidate.available
            }
        }

        val nameRaw = System.getProperty("orbit.video.inputDeviceName")?.trim()?.takeIf { it.isNotEmpty() }
        if (nameRaw != null) {
            return candidates.firstOrNull { candidate ->
                candidate.available &&
                    (candidate.name.contains(nameRaw, ignoreCase = true) ||
                        candidate.idPath.contains(nameRaw, ignoreCase = true))
            }
        }

        return candidates.firstOrNull { it.available }
    }

    private fun chooseCapability(
        capabilities: List<VideoCaptureCapability>,
        requestedWidth: Int,
        requestedHeight: Int,
        requestedFps: Int
    ): VideoCaptureCapability? =
        capabilities.minByOrNull { capability ->
            abs(capability.width - requestedWidth) +
                abs(capability.height - requestedHeight) +
                abs(capability.frameRate - requestedFps) * 20
        }

    private fun intProperty(name: String): Int? =
        System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()

    private data class CameraCandidate(
        val index: Int,
        val device: VideoDevice,
        val capabilities: List<VideoCaptureCapability>,
        val available: Boolean
    ) {
        val name: String = device.getName() ?: ""
        val idPath: String = device.getDescriptor() ?: ""
    }

    private class ActiveCapture(
        private val factory: PeerConnectionFactory,
        private val source: VideoDeviceSource,
        private val track: VideoTrack,
        private val sink: PublishSink,
        val config: VideoPublishConfig
    ) {
        private val stopping = AtomicBoolean(false)
        private val frameSeq = AtomicInteger(0)
        private val failureReported = AtomicBoolean(false)

        @Volatile private var reportedWidth = 0
        @Volatile private var reportedHeight = 0

        @Volatile
        private var target: NativeVideoTarget? = null

        private var firstFrameTimestampNs = 0L
        private var lastFrameTimestampNs = 0L

        fun enableNativePush(
            sessionId: Long,
            epoch: Long,
            bridge: OrbitLiveKitNativeBridge,
            onFailure: (NativeError) -> Unit,
            onActualDimensions: ((width: Int, height: Int) -> Unit)? = null
        ) {
            target = NativeVideoTarget(
                sessionId = sessionId,
                epoch = epoch,
                bridge = bridge,
                onFailure = onFailure,
                onActualDimensions = onActualDimensions
            )
            println(
                "DEBUG [VideoPublish] frame push enabled camera=${config.cameraName} " +
                    "width=${config.width} height=${config.height} fps=${config.fps}"
            )
        }

        fun onFrame(frame: VideoFrame) {
            if (stopping.get()) {
                runCatching { frame.release() }
                return
            }

            val seq = frameSeq.incrementAndGet()
            var i420: I420Buffer? = null
            val originalBuffer = runCatching { frame.buffer }.getOrNull()
            val nativeTarget = target
            try {
                val buffer = originalBuffer ?: return
                i420 = buffer.toI420()
                val timestampNs = if (frame.timestampNs > 0L) frame.timestampNs else System.nanoTime()
                val monotonicNs = System.nanoTime()
                if (firstFrameTimestampNs == 0L) firstFrameTimestampNs = timestampNs
                lastFrameTimestampNs = timestampNs
                val fpsEstimate = if (seq > 1 && lastFrameTimestampNs > firstFrameTimestampNs) {
                    (seq - 1) * 1_000_000_000.0 / (lastFrameTimestampNs - firstFrameTimestampNs)
                } else {
                    0.0
                }
                val i420Bytes = i420.width * i420.height +
                    ((i420.width + 1) / 2) * ((i420.height + 1) / 2) * 2

                // Some camera drivers ignore the requested capability and deliver
                // their own mode (e.g. 1080p despite a 640x360 request). Report the
                // real dimensions whenever they change so the session can republish
                // to match — the native side rejects frames that don't match the
                // declared source. NOT gated on seq==1: capture starts producing
                // frames before the push target is attached, so frame #1 is long
                // gone by the time enableNativePress attaches the callback.
                val dimCallback = nativeTarget?.onActualDimensions
                if (dimCallback != null &&
                    (reportedWidth != i420.width || reportedHeight != i420.height)
                ) {
                    reportedWidth = i420.width
                    reportedHeight = i420.height
                    println("DEBUG [VideoPublish] actual camera dimensions ${i420.width}x${i420.height}")
                    dimCallback(i420.width, i420.height)
                }

                if (seq == 1 || seq % 3 == 0) {
                    LiveKitLocalVideoFrameStore.updateFrame(
                        buffer = packRgba(i420),
                        width = i420.width,
                        height = i420.height,
                        format = 0,
                        strideY = i420.width * 4,
                        timestampNs = timestampNs,
                        rotation = frame.rotation
                    )
                    CallPathStats.onCameraPreviewPack()
                }

                if (seq <= FRAME_LOG_LIMIT) {
                    println(
                        """
                        VideoPublish frame:
                        seq=$seq
                        width=${i420.width}
                        height=${i420.height}
                        fps=${"%.2f".format(Locale.US, fpsEstimate)}
                        sourceFormat=I420/${buffer.javaClass.simpleName}
                        livekitFormat=I420
                        bytes=$i420Bytes
                        timestampNs=$timestampNs
                        monotonic=$monotonicNs
                        """.trimIndent()
                    )
                }

                val pushTarget = nativeTarget ?: return
                val packed = packI420(i420)
                val result = pushTarget.bridge.pushVideoFrame(
                    sessionId = pushTarget.sessionId,
                    epoch = pushTarget.epoch,
                    buffer = packed.buffer,
                    width = i420.width,
                    height = i420.height,
                    format = config.format,
                    strideY = i420.width,
                    strideU = (i420.width + 1) / 2,
                    strideV = (i420.width + 1) / 2,
                    timestampNs = timestampNs,
                    rotation = frame.rotation
                )
                if (!result.ok && failureReported.compareAndSet(false, true)) {
                    pushTarget.onFailure(
                        result.error ?: NativeError(
                            code = NativeErrorCode.VIDEO_CAPTURE_FAILED,
                            message = "Native video frame push failed",
                            operation = "nativePushVideoFrame",
                            recoverable = true
                        )
                    )
                }
            } catch (error: Throwable) {
                if (nativeTarget != null && failureReported.compareAndSet(false, true)) {
                    nativeTarget.onFailure(
                        NativeError(
                            code = NativeErrorCode.VIDEO_CAPTURE_FAILED,
                            message = error.message ?: error::class.simpleName.orEmpty(),
                            operation = "pushVideoFrame",
                            recoverable = true
                        )
                    )
                }
            } finally {
                if (i420 != null && i420 !== originalBuffer) {
                    runCatching { i420.release() }
                }
                runCatching { frame.release() }
            }
        }

        fun stop() {
            if (!stopping.compareAndSet(false, true)) return
            target = null
            println("DEBUG [VideoPublish] stop requested")
            runCatching { track.removeSink(sink) }
                .onFailure { println("WARN [VideoPublish] camera sink remove failed: ${it.message}") }
            runCatching { track.setEnabled(false) }
            runCatching { source.stop() }
                .onFailure { println("WARN [VideoPublish] camera stop failed: ${it.message}") }
            // No settle sleep here: removeSink + setEnabled(false) + source.stop()
            // already quiesce the capturer, and stop() runs on the Compose/EDT
            // thread (CallManager), so sleeping it froze the window for 250 ms.
            runCatching { track.dispose() }
                .onFailure { println("WARN [VideoPublish] video track dispose failed: ${it.message}") }
            runCatching { source.dispose() }
                .onFailure { println("WARN [VideoPublish] video source dispose failed: ${it.message}") }
            runCatching { factory.dispose() }
                .onFailure { println("WARN [VideoPublish] peer connection factory dispose failed: ${it.message}") }
            println("DEBUG [VideoPublish] capture stopped")
        }

        // Direct memory is not reclaimed promptly: a fresh ~3 MB direct buffer
        // per frame makes Bits.reserveMemory force full System.gc() runs, which
        // show up as UI freezes and audio underruns. So the PUSH path
        // (packI420) reuses one direct buffer per capture instance and rewrites
        // it each frame. The lock guards that reuse (a camera mode change can
        // resize mid-frame); the native push still copies the bytes before
        // returning.
        //
        // packRgba below is the LOCAL PREVIEW path and is NOT covered by that:
        // its result escapes into LiveKitNativeVideoFrameStore and then into the
        // UI, so it cannot be a reused buffer -- it allocates width*height*4 per
        // call. It is throttled to roughly one frame in three on the producer
        // side rather than being pooled, because pooling would need a hand-off
        // protocol to prove the UI is done with the array.
        private val packLock = Any()
        private var packedDirect: ByteBuffer? = null
        private var packedDirectBytes = 0
        private var lumaScratch = ByteArray(0)
        private var chromaScratchU = ByteArray(0)
        private var chromaScratchV = ByteArray(0)
        private var rowScratch = ByteArray(0)

        private fun packRgba(buffer: I420Buffer): ByteArray = synchronized(packLock) {
            val width = buffer.width
            val height = buffer.height
            val out = ByteArray(width * height * 4)
            val yPlane = buffer.dataY
            val uPlane = buffer.dataU
            val vPlane = buffer.dataV
            val chromaWidth = (width + 1) / 2
            ensureScratch(width, chromaWidth)
            var outputIndex = 0
            for (row in 0 until height) {
                val yRow = row * buffer.strideY
                val uvRow = (row / 2) * buffer.strideU
                val vvRow = (row / 2) * buffer.strideV
                // Copy each plane row in one bulk get instead of indexing the
                // plane per byte: the plane may be padded (stride > width) or
                // direct, and this loop is the capture thread's hot path.
                val yBytes = readPlaneRow(yPlane, yRow, width, lumaScratch)
                val uBytes = readPlaneRow(uPlane, uvRow, chromaWidth, chromaScratchU)
                val vBytes = readPlaneRow(vPlane, vvRow, chromaWidth, chromaScratchV)
                for (column in 0 until width) {
                    val y = yBytes[column].toInt() and 0xFF
                    val u = uBytes[column / 2].toInt() and 0xFF
                    val v = vBytes[column / 2].toInt() and 0xFF
                    val c = y - 16
                    val d = u - 128
                    val e = v - 128
                    val r = clampRgb((298 * c + 409 * e + 128) shr 8)
                    val g = clampRgb((298 * c - 100 * d - 208 * e + 128) shr 8)
                    val b = clampRgb((298 * c + 516 * d + 128) shr 8)
                    out[outputIndex] = r.toByte()
                    out[outputIndex + 1] = g.toByte()
                    out[outputIndex + 2] = b.toByte()
                    out[outputIndex + 3] = 0xFF.toByte()
                    outputIndex += 4
                }
            }
            out
        }

        private fun clampRgb(value: Int): Int =
            value.coerceIn(0, 255)

        private fun packI420(buffer: I420Buffer): PackedI420Frame = synchronized(packLock) {
            val width = buffer.width
            val height = buffer.height
            val chromaWidth = (width + 1) / 2
            val chromaHeight = (height + 1) / 2
            val bytes = width * height + chromaWidth * chromaHeight * 2
            // Only reuse an exactly sized buffer: the native side validates the
            // capacity against the tight-plane size (w*h + cw*ch*2) it expects.
            val existing = packedDirect
            val packed = if (existing != null && packedDirectBytes == bytes) {
                existing
            } else {
                ByteBuffer.allocateDirect(bytes).also {
                    packedDirect = it
                    packedDirectBytes = bytes
                }
            }
            ensureScratch(width, chromaWidth)
            packed.clear()
            copyPlane(buffer.dataY, packed, height, width, buffer.strideY)
            copyPlane(buffer.dataU, packed, chromaHeight, chromaWidth, buffer.strideU)
            copyPlane(buffer.dataV, packed, chromaHeight, chromaWidth, buffer.strideV)
            packed.flip()
            PackedI420Frame(packed, bytes)
        }

        private fun ensureScratch(width: Int, chromaWidth: Int) {
            if (lumaScratch.size < width) {
                lumaScratch = ByteArray(width)
            }
            if (chromaScratchU.size < chromaWidth) {
                chromaScratchU = ByteArray(chromaWidth)
            }
            if (chromaScratchV.size < chromaWidth) {
                chromaScratchV = ByteArray(chromaWidth)
            }
            val rowWidth = maxOf(width, chromaWidth)
            if (rowScratch.size < rowWidth) {
                rowScratch = ByteArray(rowWidth)
            }
        }

        private fun readPlaneRow(
            plane: ByteBuffer,
            rowStart: Int,
            columns: Int,
            target: ByteArray
        ): ByteArray {
            val view = plane.duplicate()
            view.rewind()
            view.position(rowStart)
            view.limit(rowStart + columns)
            view.get(target, 0, columns)
            return target
        }

        private fun copyPlane(
            source: ByteBuffer,
            destination: ByteBuffer,
            rows: Int,
            columns: Int,
            stride: Int
        ) {
            val view = source.duplicate()
            val scratch = rowScratch
            for (row in 0 until rows) {
                val rowStart = row * stride
                val rowView = view.duplicate()
                rowView.rewind()
                rowView.position(rowStart)
                rowView.limit(rowStart + columns)
                rowView.get(scratch, 0, columns)
                destination.put(scratch, 0, columns)
            }
        }
    }

    private class PublishSink : VideoTrackSink {
        @Volatile
        var capture: ActiveCapture? = null

        override fun onVideoFrame(frame: VideoFrame) {
            capture?.onFrame(frame) ?: runCatching { frame.release() }
        }
    }

    private data class NativeVideoTarget(
        val sessionId: Long,
        val epoch: Long,
        val bridge: OrbitLiveKitNativeBridge,
        val onFailure: (NativeError) -> Unit,
        val onActualDimensions: ((width: Int, height: Int) -> Unit)?
    )

    private data class PackedI420Frame(
        val buffer: ByteBuffer,
        val bytes: Int
    )
}

data class VideoPublishConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val format: Int,
    val cameraName: String,
    val cameraIndex: Int
)
