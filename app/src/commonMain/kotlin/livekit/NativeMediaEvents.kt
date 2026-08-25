package livekit

sealed class NativeMediaEvent {
    abstract val sessionId: Long
    abstract val epoch: Long

    data class Connecting(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()
    data class Connected(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()
    data class ConnectFailed(
        override val sessionId: Long,
        override val epoch: Long,
        val error: NativeError
    ) : NativeMediaEvent()

    data class AuthFailure(
        override val sessionId: Long,
        override val epoch: Long,
        val error: NativeError
    ) : NativeMediaEvent()

    data class AudioPublished(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()
    data class VideoPublished(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()
    data class MediaReady(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()
    data class TokenRefreshRequired(override val sessionId: Long, override val epoch: Long) : NativeMediaEvent()

    data class Disconnected(
        override val sessionId: Long,
        override val epoch: Long,
        val reason: String,
        val recoverable: Boolean
    ) : NativeMediaEvent()

    /**
     * Emitted by the native mixer when the set of REMOTELY speaking
     * participants changes (throttled, change-only). Identities are LiveKit
     * identities; levels are measured pre-gain so locally muted peers still
     * appear while talking.
     */
    data class ActiveSpeakersChanged(
        override val sessionId: Long,
        override val epoch: Long,
        val speakers: List<String>
    ) : NativeMediaEvent()

    data class Error(
        override val sessionId: Long,
        override val epoch: Long,
        val error: NativeError
    ) : NativeMediaEvent()
}

interface NativeMediaEventSink {
    fun onNativeEvent(event: NativeMediaEvent)
}

interface NativeMixedAudioSink {
    fun onMixedAudioFrame(
        sessionId: Long,
        epoch: Long,
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        samplesPerChannel: Int,
        timestampNs: Long
    )
}

interface NativeRemoteVideoSink {
    fun onRemoteParticipantConnected(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String
    )

    fun onRemoteParticipantDisconnected(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String
    )

    fun onRemoteVideoTrackAdded(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String,
        publicationSid: String,
        trackSid: String,
        trackName: String,
        trackSource: String,
        muted: Boolean
    )

    fun onRemoteVideoFrame(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String,
        buffer: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        strideU: Int,
        strideV: Int,
        timestampNs: Long,
        rotation: Int
    )

    fun onRemoteVideoTrackRemoved(sessionId: Long, epoch: Long, participantIdentity: String, trackSid: String)
}
