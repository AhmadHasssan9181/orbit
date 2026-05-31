package livekit

import java.nio.ByteBuffer

object LiveKitNativeAudioBridge {
    val enabled: Boolean
        get() = System.getProperty("orbit.livekit.cppAudio", "true").toBoolean()

    val acceptingFrames: Boolean
        get() = runCatching { nativeIsAcceptingFrames() }.getOrDefault(false)

    @Volatile private var frameDropLogged = false

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
    external fun nativeIsAcceptingFrames(): Boolean

    @JvmStatic
    external fun nativeSetAcceptingFrames(accepting: Boolean)

    @JvmStatic
    external fun nativeSetRemoteAudioSink(sink: RemoteAudioSink?)

    fun pushAudioFrame(
        pcm: ByteBuffer,
        byteLength: Int,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int
    ): Boolean {
        if (!acceptingFrames) {
            if (!frameDropLogged) {
                frameDropLogged = true
                println("DEBUG [LiveKitNativeAudioBridge] frame dropped because native audio not ready")
            }
            return false
        }

        return runCatching {
            nativePushAudioFrame(pcm, byteLength, sampleRate, channels, samplesPerChannel)
        }.getOrDefault(false)
    }

    fun resetFrameDropLog() {
        frameDropLogged = false
    }

    interface RemoteAudioSink {
        fun onAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int, samplesPerChannel: Int)
    }
}
