package livekit

import java.nio.ByteBuffer

object LiveKitNativeAudioBridge {
    val enabled: Boolean
        get() = System.getProperty("orbit.livekit.cppAudio", "true").toBoolean()

    init {
        webrtc.WebrtcNativeDepsLoader.load()
    }

    external fun nativeSdkVersion(): String

    external fun nativeConnect(url: String, token: String): Boolean

    external fun nativeDisconnect()

    external fun nativePublishAudio(sampleRate: Int, channels: Int, frameMs: Int): Boolean

    external fun nativePushAudioFrame(
        pcm: ByteBuffer,
        byteLength: Int,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int
    ): Boolean

    external fun nativeSetRemoteAudioSink(sink: RemoteAudioSink?)

    interface RemoteAudioSink {
        fun onAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int, samplesPerChannel: Int)
    }
}
