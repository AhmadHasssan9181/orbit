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
        trackSource: String,
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

    /**
     * A remote video track exists in the room and Orbit has not seen a frame from
     * it yet -- it may not even have a track object behind it.
     *
     * This is what makes the waiting state visible. The call tile is built from
     * `activeTracks`, and a track only entered that map when its first FRAME
     * arrived, so a share that took twenty seconds to attach had no tile at all
     * for those twenty seconds: nothing to show a spinner on, and the tile
     * materialised at the same instant as the picture, which made the spinner
     * unreachable in practice.
     *
     * Fired once per publication, whether or not a track exists behind it.
     */
    fun onRemoteVideoAnnounced(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String,
        trackSource: String
    )

    /**
     * A remote screen share has been published and visible to us for long enough
     * that it is clearly never going to attach: the publication exists, we know it
     * is a screenshare, and it still has no track behind it after the whole
     * retry ladder.
     *
     * This is the only point at which Orbit can act, because nothing else in the
     * pipeline has a track to work with -- no reader, no stream, no frames. A
     * fresh SFU connection is the only thing that recovers it (verified: the same
     * still-running share acquires its track immediately after a rejoin), so the
     * UI offers that rather than leaving an invisible share on screen.
     */
    fun onRemoteVideoTrackStuck(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String
    )

    /**
     * Remote mic mute state changed (or initial state announced at subscribe
     * time). Drives the Discord-style mic-off badges on tiles and the member
     * list; identities are LiveKit identities (`${userId}:${deviceId}`).
     */
    fun onRemoteAudioMuteChanged(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        muted: Boolean
    )

    /**
     * Sampled every ~5s per remote audio track from WebRTC stats. Drives the
     * connection-quality bars on tiles; `-1` means no sample for that metric
     * yet.
     */
    fun onRemoteConnectionQuality(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        jitterMs: Long,
        rttMs: Long,
        lossPct: Double
    )
}
