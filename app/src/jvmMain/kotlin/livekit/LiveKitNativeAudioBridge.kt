package livekit

import java.nio.ByteBuffer

object LiveKitNativeAudioBridge {
    val enabled: Boolean
        get() = System.getProperty("orbit.livekit.cppAudio", "true").toBoolean()

    init {
        webrtc.WebrtcNativeDepsLoader.load()
    }

    @JvmStatic
    external fun nativeSdkVersion(): String

    @JvmStatic
    external fun nativeConnect(url: String, token: String): Boolean

    @JvmStatic
    external fun nativeDisconnect()

    @JvmStatic
    external fun nativePublishAudio(sampleRate: Int, channels: Int, frameMs: Int): Boolean

    @JvmStatic
    external fun nativePushAudioFrame(
        pcm: ByteBuffer,
        byteLength: Int,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int
    ): Boolean

    @JvmStatic
    external fun nativeSetRemoteAudioSink(sink: RemoteAudioSink?)

    interface RemoteAudioSink {
        fun onAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int, samplesPerChannel: Int)
    }
}
