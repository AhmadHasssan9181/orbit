package livekit

import java.nio.ByteBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class OrbitLiveKitNativeBridge(
    private val raw: OrbitLiveKitNativeRaw = OrbitLiveKitNativeRaw
) {
    fun createSession(): NativeResult<Long> =
        runCatching {
            val sessionId = raw.nativeCreateSession()
            if (sessionId > 0L) {
                NativeResult.success(sessionId)
            } else {
                NativeResult.failure(fallbackError(NativeErrorCode.NATIVE_EXCEPTION, "nativeCreateSession returned $sessionId", "nativeCreateSession"))
            }
        }.getOrElse { NativeResult.failure(exceptionError(it, "nativeCreateSession")) }

    fun destroySession(sessionId: Long): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeDestroySession(sessionId), "nativeDestroySession")

    fun setEventSink(sink: NativeMediaEventSink?): NativeResult<Unit> =
        statusToResult(0L, raw.nativeSetEventSink(sink), "nativeSetEventSink")

    fun setMixedAudioSink(sink: NativeMixedAudioSink?): NativeResult<Unit> =
        statusToResult(0L, raw.nativeSetMixedAudioSink(sink), "nativeSetMixedAudioSink")

    fun setRemoteVideoSink(sink: NativeRemoteVideoSink?): NativeResult<Unit> =
        statusToResult(0L, raw.nativeSetRemoteVideoSink(sink), "nativeSetRemoteVideoSink")

    // BUG-B fix: idempotent rescan that starts frame readers for subscribed remote
    // video tracks the event-driven path missed (safe to call repeatedly).
    fun rescanRemoteVideoTracks(sessionId: Long, epoch: Long): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeRescanRemoteVideoTracks(sessionId, epoch), "nativeRescanRemoteVideoTracks")

    fun connectAsync(sessionId: Long, epoch: Long, url: String, token: String, identity: String): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeConnectAsync(sessionId, epoch, url, token, identity), "nativeConnectAsync")

    fun reconnectAsync(sessionId: Long, epoch: Long, url: String, token: String): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeReconnectAsync(sessionId, epoch, url, token), "nativeReconnectAsync")

    fun disconnect(sessionId: Long, epoch: Long, userInitiated: Boolean): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeDisconnect(sessionId, epoch, userInitiated), "nativeDisconnect")

    fun publishAudio(
        sessionId: Long,
        epoch: Long,
        sampleRate: Int,
        channels: Int,
        queueSizeMs: Int,
        expectedTestTone: Boolean,
        maxBitrateBps: Int
    ): NativeResult<Unit> =
        statusToResult(
            sessionId,
            raw.nativePublishAudio(sessionId, epoch, sampleRate, channels, queueSizeMs, expectedTestTone, maxBitrateBps),
            "nativePublishAudio"
        )

    fun pushAudioFrame(
        sessionId: Long,
        epoch: Long,
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int,
        timestampNs: Long
    ): NativeResult<Unit> =
        statusToResult(
            sessionId,
            raw.nativePushAudioFrame(sessionId, epoch, buffer, sampleRate, channels, samplesPerChannel, timestampNs),
            "nativePushAudioFrame"
        )

    fun publishVideo(
        sessionId: Long,
        epoch: Long,
        width: Int,
        height: Int,
        fps: Int,
        format: Int,
        maxBitrateBps: Int
    ): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativePublishVideo(sessionId, epoch, width, height, fps, format, maxBitrateBps), "nativePublishVideo")

    fun publishScreenShare(
        sessionId: Long,
        epoch: Long,
        width: Int,
        height: Int,
        fps: Int,
        maxBitrateBps: Int
    ): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativePublishScreenShare(sessionId, epoch, width, height, fps, maxBitrateBps), "nativePublishScreenShare")

    fun unpublishScreenShare(sessionId: Long, epoch: Long): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeUnpublishScreenShare(sessionId, epoch), "nativeUnpublishScreenShare")

    fun publishScreenShareAudio(sessionId: Long, epoch: Long): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativePublishScreenShareAudio(sessionId, epoch), "nativePublishScreenShareAudio")

    fun unpublishScreenShareAudio(sessionId: Long, epoch: Long): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeUnpublishScreenShareAudio(sessionId, epoch), "nativeUnpublishScreenShareAudio")

    /** 0 = pending, 1 = own process excluded, 2 = full-device fallback. */
    fun shareLoopbackMode(): Int = raw.nativeGetShareLoopbackMode()

    fun pushScreenShareFrame(
        sessionId: Long,
        epoch: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        timestampNs: Long,
        rotation: Int
    ): NativeResult<Unit> =
        statusToResult(
            sessionId,
            raw.nativePushScreenShareFrame(sessionId, epoch, buffer, width, height, format, strideY, strideU, strideV, timestampNs, rotation),
            "nativePushScreenShareFrame"
        )

    fun pushVideoFrame(
        sessionId: Long,
        epoch: Long,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        timestampNs: Long,
        rotation: Int
    ): NativeResult<Unit> =
        statusToResult(
            sessionId,
            raw.nativePushVideoFrame(sessionId, epoch, buffer, width, height, format, strideY, strideU, strideV, timestampNs, rotation),
            "nativePushVideoFrame"
        )

    fun setAudioMuted(sessionId: Long, epoch: Long, muted: Boolean): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeSetAudioMuted(sessionId, epoch, muted), "nativeSetAudioMuted")

    fun setVideoMuted(sessionId: Long, epoch: Long, muted: Boolean): NativeResult<Unit> =
        statusToResult(sessionId, raw.nativeSetVideoMuted(sessionId, epoch, muted), "nativeSetVideoMuted")

    fun getReadiness(sessionId: Long): NativeResult<NativeMediaReadiness> =
        runCatching {
            val readiness = parseReadiness(raw.nativeGetReadinessJson(sessionId))
            NativeResult.success(readiness)
        }.getOrElse { NativeResult.failure(exceptionError(it, "nativeGetReadinessJson")) }

    private fun statusToResult(sessionId: Long, status: Int, operation: String): NativeResult<Unit> =
        if (status == NativeStatusCode.OK.code) {
            NativeResult.success(Unit)
        } else {
            NativeResult.failure(parseNativeError(sessionId, status, operation))
        }

    private fun parseNativeError(sessionId: Long, status: Int, operation: String): NativeError =
        runCatching {
            val json = raw.nativeGetLastErrorJson(sessionId)
            val obj = Json.parseToJsonElement(json).jsonObject
            NativeError(
                code = NativeErrorCode.fromName(obj.string("code")) ,
                message = obj.string("message") ?: "Native operation failed with status $status",
                operation = obj.string("operation") ?: operation,
                recoverable = obj.boolean("recoverable") ?: false
            )
        }.getOrElse {
            val statusCode = NativeStatusCode.fromCode(status)
            fallbackError(
                code = NativeErrorCode.fromStatus(statusCode),
                message = "Native operation failed with status $status",
                operation = operation
            )
        }

    private fun parseReadiness(json: String): NativeMediaReadiness {
        val obj = Json.parseToJsonElement(json).jsonObject
        val lastError = (obj["lastError"] as? JsonObject)?.let { errorObj ->
            NativeError(
                code = NativeErrorCode.fromName(errorObj.string("code")),
                message = errorObj.string("message") ?: "",
                operation = errorObj.string("operation") ?: "native",
                recoverable = errorObj.boolean("recoverable") ?: false
            )
        }
        return NativeMediaReadiness(
            sessionId = obj.long("sessionId") ?: 0L,
            epoch = obj.long("epoch") ?: 0L,
            state = NativeMediaState.values().firstOrNull { it.name == obj.string("state") } ?: NativeMediaState.Failed,
            connected = obj.boolean("connected") ?: false,
            audioPublished = obj.boolean("audioPublished") ?: false,
            videoPublished = obj.boolean("videoPublished") ?: false,
            acceptingAudioFrames = obj.boolean("acceptingAudioFrames") ?: false,
            acceptingVideoFrames = obj.boolean("acceptingVideoFrames") ?: false,
            cameraRequested = obj.boolean("cameraRequested") ?: false,
            cameraRequired = obj.boolean("cameraRequired") ?: false,
            cameraState = CameraPublishState.values().firstOrNull { it.name == obj.string("cameraState") } ?: CameraPublishState.Failed,
            joinedAsAudioOnlyFallback = obj.boolean("joinedAsAudioOnlyFallback") ?: false,
            tokenRefreshScheduled = obj.boolean("tokenRefreshScheduled") ?: false,
            lastError = lastError
        )
    }

    private fun fallbackError(code: NativeErrorCode, message: String, operation: String): NativeError =
        NativeError(code = code, message = message, operation = operation, recoverable = false)

    private fun exceptionError(error: Throwable, operation: String): NativeError =
        NativeError(
            code = NativeErrorCode.NATIVE_EXCEPTION,
            message = error.message ?: error::class.simpleName.orEmpty(),
            operation = operation,
            recoverable = false
        )

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull
}
