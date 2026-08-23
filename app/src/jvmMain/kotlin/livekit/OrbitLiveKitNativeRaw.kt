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
    external fun nativeRescanRemoteVideoTracks(sessionId: Long, epoch: Long): Int

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
        expectedTestTone: Boolean,
        maxBitrateBps: Int
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
        format: Int,
        maxBitrateBps: Int
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
    external fun nativePublishScreenShare(
        sessionId: Long,
        epoch: Long,
        width: Int,
        height: Int,
        fps: Int,
        maxBitrateBps: Int
    ): Int

    @JvmStatic
    external fun nativeUnpublishScreenShare(sessionId: Long, epoch: Long): Int

    @JvmStatic
    external fun nativePublishScreenShareAudio(sessionId: Long, epoch: Long): Int

    /** 0 = pending, 1 = own process excluded, 2 = full-device fallback. */
    @JvmStatic
    external fun nativeGetShareLoopbackMode(): Int

    @JvmStatic
    external fun nativeUnpublishScreenShareAudio(sessionId: Long, epoch: Long): Int

    /** Receives one echo-cancelled share-loopback PCM frame from [ShareAudioAec]. */
    @JvmStatic
    external fun nativeFeedProcessedShareAudioFrame(pcm: ByteArray)

    @JvmStatic
    external fun nativePushScreenShareFrame(
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
