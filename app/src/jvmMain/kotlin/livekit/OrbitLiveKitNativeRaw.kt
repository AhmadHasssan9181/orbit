package livekit

import java.nio.ByteBuffer

object OrbitLiveKitNativeRaw {
    init {
        webrtc.WebrtcNativeDepsLoader.load()
    }

    @JvmStatic
    external fun nativeCreateSession(): Long

    @JvmStatic
    external fun nativeDestroySession(sessionId: Long): Int

    @JvmStatic
    external fun nativeSetEventSink(sink: NativeMediaEventSink?): Int

    @JvmStatic
    external fun nativeSetMixedAudioSink(sink: NativeMixedAudioSink?): Int

    @JvmStatic
    external fun nativeSetRemoteVideoSink(sink: NativeRemoteVideoSink?): Int

    @JvmStatic
    external fun nativeConnectAsync(
        sessionId: Long,
        epoch: Long,
        url: String,
        token: String,
        identity: String
    ): Int

    @JvmStatic
    external fun nativeReconnectAsync(
        sessionId: Long,
        epoch: Long,
        url: String,
        token: String
    ): Int

    @JvmStatic
    external fun nativeDisconnect(sessionId: Long, epoch: Long, userInitiated: Boolean): Int

    @JvmStatic
    external fun nativePublishAudio(
        sessionId: Long,
        epoch: Long,
        sampleRate: Int,
        channels: Int,
        queueSizeMs: Int,
        expectedTestTone: Boolean
    ): Int

    @JvmStatic
    external fun nativePushAudioFrame(
        sessionId: Long,
        epoch: Long,
        buffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int,
        timestampNs: Long
    ): Int

    @JvmStatic
    external fun nativePublishVideo(
        sessionId: Long,
        epoch: Long,
        width: Int,
        height: Int,
        fps: Int,
        format: Int
    ): Int

    @JvmStatic
    external fun nativePushVideoFrame(
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
    ): Int

    @JvmStatic
    external fun nativeSetAudioMuted(sessionId: Long, epoch: Long, muted: Boolean): Int

    @JvmStatic
    external fun nativeSetVideoMuted(sessionId: Long, epoch: Long, muted: Boolean): Int

    @JvmStatic
    external fun nativeGetLastErrorJson(sessionId: Long): String

    @JvmStatic
    external fun nativeGetReadinessJson(sessionId: Long): String
}
