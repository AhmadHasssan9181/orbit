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
        NativeParticipantLedger.onConnect(participantIdentity)
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
        NativeParticipantLedger.onDisconnect(participantIdentity)
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
        // Also calls participantConnected, identically to the participant-connected
        // callback. That is worth seeing rather than assuming it is harmless: if a
        // track add is what re-announces a participant after a rejoin, then the
        // connect count in run 62 was inflated by track adds, not by native
        // re-announcing participants. The ledger prints both counts so the two
        // hypotheses are distinguishable instead of a matter of opinion.
        NativeParticipantLedger.onVideoTrackAdded()
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
        NativeParticipantLedger.onVideoTrackRemoved()
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
        // This path had no logging at all. The values arrived from native, were
        // stored, and drove the signal bars -- so a peer going bad was visible in
        // the UI and completely invisible in the log. "His ping turned yellow" was
        // unreportable for exactly that reason: there was nothing to grep.
        //
        // The JNI thread-attach line proved the callback fires, which is what made it
        // clear the silence was here and not in native.
        //
        // Logged on change rather than every tick. Native calls this on its own
        // interval and mostly reports the same three numbers twice in a row; only a
        // transition says anything, and an unconditional print buries the transitions
        // in the repetition.
        val previous = LiveKitRemoteVideoFrameStore.connectionQuality.value[participantIdentity]
        LiveKitRemoteVideoFrameStore.remoteConnectionQuality(participantIdentity, jitterMs, rttMs, lossPct)
        if (previous == null ||
            previous.jitterMs != jitterMs || previous.rttMs != rttMs || previous.lossPct != lossPct
        ) {
            val bars = LiveKitRemoteVideoFrameStore.connectionQuality.value[participantIdentity]?.bars ?: 0
            println(
                "CONNQUAL identity=$participantIdentity jitterMs=$jitterMs rttMs=$rttMs " +
                    "lossPct=$lossPct bars=$bars"
            )
        }
    }
}
