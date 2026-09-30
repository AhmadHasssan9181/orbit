package livekit

object LiveKitNativeRemoteVideoRenderer : NativeRemoteVideoSink {
    override fun onRemoteParticipantConnected(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String
    ) {
        if (participantIdentity.isBlank()) return
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote participant connected " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity participantSid=$participantSid"
        )
        LiveKitRemoteVideoFrameStore.participantConnected(participantIdentity)
    }

    override fun onRemoteParticipantDisconnected(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String
    ) {
        if (participantIdentity.isBlank()) return
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote participant disconnected " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity participantSid=$participantSid"
        )
        LiveKitRemoteVideoFrameStore.participantDisconnected(participantIdentity)
    }

    override fun onRemoteVideoTrackAdded(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        participantSid: String,
        publicationSid: String,
        trackSid: String,
        trackName: String,
        trackSource: String,
        muted: Boolean
    ) {
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote video track added " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity " +
                "participantSid=$participantSid publicationSid=$publicationSid trackSid=$trackSid " +
                "trackName=$trackName trackSource=$trackSource muted=$muted"
        )
        LiveKitRemoteVideoFrameStore.participantConnected(participantIdentity)
        LiveKitRemoteVideoFrameStore.trackStarted(participantIdentity, trackSid, trackSource, trackName)
    }

    override fun onRemoteVideoFrame(
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
    ) {
        if (width <= 0 || height <= 0 || buffer.isEmpty()) {
            println(
                "WARN [LiveKitNativeRemoteVideoRenderer] dropped empty remote video frame " +
                    "participantIdentity=$participantIdentity trackSid=$trackSid width=$width " +
                    "height=$height bytes=${buffer.size}"
            )
            return
        }
        LiveKitRemoteVideoFrameStore.updateFrame(
            sessionId = sessionId,
            epoch = epoch,
            participantIdentity = participantIdentity,
            trackSid = trackSid,
            trackSource = trackSource,
            buffer = buffer,
            width = width,
            height = height,
            format = format,
            strideY = strideY,
            strideU = strideU,
            strideV = strideV,
            timestampNs = timestampNs,
            rotation = rotation
        )
        CallPathStats.onRemoteVideoFrame(buffer.size)
    }

    override fun onRemoteVideoTrackRemoved(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String
    ) {
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote video track removed " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity trackSid=$trackSid"
        )
        LiveKitRemoteVideoFrameStore.trackStopped(participantIdentity, trackSid)
    }

    override fun onRemoteVideoAnnounced(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String,
        trackSource: String
    ) {
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote video track announced " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity " +
                "trackSid=$trackSid trackSource=$trackSource"
        )
        LiveKitRemoteVideoFrameStore.trackAnnounced(participantIdentity, trackSid, trackSource)
    }

    override fun onRemoteVideoTrackStuck(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        trackSid: String
    ) {
        println(
            "WARN [LiveKitNativeRemoteVideoRenderer] remote screenshare STUCK " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity trackSid=$trackSid"
        )
        LiveKitRemoteVideoFrameStore.trackStuck(participantIdentity, trackSid)
    }

    override fun onRemoteAudioMuteChanged(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        muted: Boolean
    ) {
        println(
            "DEBUG [LiveKitNativeRemoteVideoRenderer] remote audio mute changed " +
                "sessionId=$sessionId epoch=$epoch participantIdentity=$participantIdentity muted=$muted"
        )
        LiveKitRemoteVideoFrameStore.remoteAudioMuteChanged(participantIdentity, muted)
    }

    override fun onRemoteConnectionQuality(
        sessionId: Long,
        epoch: Long,
        participantIdentity: String,
        jitterMs: Long,
        rttMs: Long,
        lossPct: Double
    ) {
        LiveKitRemoteVideoFrameStore.remoteConnectionQuality(participantIdentity, jitterMs, rttMs, lossPct)
    }
}
