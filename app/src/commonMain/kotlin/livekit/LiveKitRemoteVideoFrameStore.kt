package livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class LiveKitRemoteVideoFrame(
    val sessionId: Long,
    val epoch: Long,
    val participantIdentity: String,
    val trackSid: String,
    val buffer: ByteArray,
    val width: Int,
    val height: Int,
    val format: Int,
    val strideY: Int,
    val strideU: Int,
    val strideV: Int,
    val timestampNs: Long,
    val rotation: Int,
    val sequence: Long
)

data class LiveKitRemoteVideoTrackState(
    val participantIdentity: String,
    val trackSid: String,
    val trackSource: String = "",
    val trackName: String = ""
) {
    val isScreenShare: Boolean
        get() = trackSource.equals("screenshare", ignoreCase = true) ||
                trackSource.equals("screen", ignoreCase = true) ||
                trackSource.equals("screen_share", ignoreCase = true) ||
                trackSource.equals("SOURCE_SCREENSHARE", ignoreCase = true) ||
                trackSource.equals("3", ignoreCase = true) ||
                trackSource.contains("screen", ignoreCase = true) ||
                trackName.contains("screen", ignoreCase = true) ||
                trackName.contains("share", ignoreCase = true) ||
                trackName.contains("desktop", ignoreCase = true) ||
                trackName.contains("display", ignoreCase = true) ||
                trackSid.contains("screen", ignoreCase = true) ||
                trackSid.contains("share", ignoreCase = true)
}

data class LiveKitLocalVideoFrame(
    val buffer: ByteArray,
    val width: Int,
    val height: Int,
    val format: Int,
    val strideY: Int,
    val timestampNs: Long,
    val rotation: Int,
    val sequence: Long
)

/**
 * One WebRTC-stats sample for a remote participant's audio connection.
 * `-1` means the metric had no sample yet in this interval.
 */
data class ConnectionQualitySample(
    val jitterMs: Long = -1L,
    val rttMs: Long = -1L,
    val lossPct: Double = -1.0
) {
    /** Discord-style 1..3 signal bars; 0 when nothing measurable yet. */
    val bars: Int
        get() {
            if (lossPct < 0.0 && jitterMs < 0 && rttMs < 0) return 0
            var score = 3
            if (lossPct >= 2.0 || (rttMs >= 0 && rttMs > 150)) score -= 1
            if (jitterMs >= 0 && jitterMs > 30) score -= 1
            if (lossPct >= 5.0 || (rttMs >= 0 && rttMs > 300)) score -= 2
            return score.coerceIn(0, 3)
        }
}

object LiveKitRemoteVideoFrameStore {
    private val _latestFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestFrame
    private val _latestCameraFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestCameraFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestCameraFrame
    private val _latestScreenShareFrame = MutableStateFlow<LiveKitRemoteVideoFrame?>(null)
    val latestScreenShareFrame: StateFlow<LiveKitRemoteVideoFrame?> = _latestScreenShareFrame
    private val _activeTracks = MutableStateFlow<Map<String, LiveKitRemoteVideoTrackState>>(emptyMap())
    val activeTracks: StateFlow<Map<String, LiveKitRemoteVideoTrackState>> = _activeTracks
    private val _framesByTrack = MutableStateFlow<Map<String, LiveKitRemoteVideoFrame>>(emptyMap())
    val framesByTrack: StateFlow<Map<String, LiveKitRemoteVideoFrame>> = _framesByTrack
    private val _remoteParticipants = MutableStateFlow<Set<String>>(emptySet())
    val remoteParticipants: StateFlow<Set<String>> = _remoteParticipants

    // LiveKit identities whose remote mic track is currently muted. Fed from
    // the native room delegate (initial state at subscribe + TrackMuted/Unmuted).
    private val _remoteAudioMuted = MutableStateFlow<Set<String>>(emptySet())
    val remoteAudioMuted: StateFlow<Set<String>> = _remoteAudioMuted

    // Latest connection-quality sample per remote identity (jitter/RTT/loss),
    // pushed by the native stats watcher every ~5s.
    private val _connectionQuality = MutableStateFlow<Map<String, ConnectionQualitySample>>(emptyMap())
    val connectionQuality: StateFlow<Map<String, ConnectionQualitySample>> = _connectionQuality

    // One native reader thread exists per remote video track, so every mutation
    // below can run concurrently. `sequence += 1` and `_flow.value = _flow.value + x`
    // are non-atomic read-modify-writes: they lose updates (a tile whose map entry
    // is dropped then shows "Video startingÃ¢â‚¬Â¦" forever) and hand out duplicate
    // sequence numbers.
    private val sequence = AtomicLong(0L)
    @Volatile private var activeTrackSid: String? = null

    // Session/epoch the store is currently showing. Native reader threads can
    // outlive a call by one frame, so a frame from a torn-down epoch would
    // otherwise repopulate the store right after clear().
    //
    // One immutable holder, not two volatiles: a reader that saw the two halves
    // written independently could observe a new sessionId beside the old epoch.
    // Null means DISARMED, and updateFrame rejects while null -- the previous
    // encoding (activeSessionId == 0L as "not armed") short-circuited the check
    // and accepted everything, so clear() silently re-enabled the store instead
    // of disabling it.
    private data class SessionEpoch(val sessionId: Long, val epoch: Long)

    @Volatile private var activeSession: SessionEpoch? = null

    /**
     * Guards the check-then-commit in [updateFrame] against [clear] and
     * [trackStopped].
     *
     * A native reader thread calls `updateFrame` 30-60 times a second per track,
     * and `clear()` is called from teardown on another thread. Without this, the
     * sequence "read activeSession, do some work, write five StateFlows" lets a
     * concurrent `clear()` slip in the middle: the store is disarmed and emptied,
     * and then the in-flight frame's writes repopulate it Ã¢â‚¬â€ a ghost tile that
     * outlives the call.
     *
     * Held only for the writes and the re-validation, never across buffer
     * copying, so it cannot stall a reader thread for long.
     */
    private val commitLock = Any()

    /** Arms the store for one native session; call before the reader sink is attached. */
    fun beginSession(sessionId: Long, epoch: Long) {
        activeSession = SessionEpoch(sessionId, epoch)
    }

    fun participantConnected(participantIdentity: String) {
        if (participantIdentity.isBlank()) return
        _remoteParticipants.update { it + participantIdentity }
    }

    fun participantDisconnected(participantIdentity: String) {
        if (participantIdentity.isBlank()) return
        _remoteParticipants.update { it - participantIdentity }
        _remoteAudioMuted.update { it - participantIdentity }
        _connectionQuality.update { it - participantIdentity }
    }

    fun remoteConnectionQuality(
        participantIdentity: String,
        jitterMs: Long,
        rttMs: Long,
        lossPct: Double
    ) {
        if (participantIdentity.isBlank()) return
        _connectionQuality.update {
            it + (
                participantIdentity to ConnectionQualitySample(
                    jitterMs = jitterMs,
                    rttMs = rttMs,
                    lossPct = lossPct
                )
                )
        }
    }

    fun remoteAudioMuteChanged(participantIdentity: String, muted: Boolean) {
        if (participantIdentity.isBlank()) return
        _remoteAudioMuted.update { current ->
            val updated = if (muted) current + participantIdentity else current - participantIdentity
            if (updated == current) current else updated
        }
    }

    fun trackStarted(participantIdentity: String, trackSid: String, trackSource: String = "", trackName: String = "") {
        participantConnected(participantIdentity)
        _activeTracks.update {
            it + (
                trackSid to LiveKitRemoteVideoTrackState(
                    participantIdentity = participantIdentity,
                    trackSid = trackSid,
                    trackSource = trackSource,
                    trackName = trackName
                )
                )
        }
        if (activeTrackSid != trackSid) {
            println(
                "DEBUG [RemoteVideoUI] render started participantIdentity=$participantIdentity " +
                    "trackSid=$trackSid trackSource=$trackSource trackName=$trackName"
            )
        }
        activeTrackSid = trackSid
    }

    fun updateFrame(
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
        // Reject frames from a torn-down or not-yet-armed session: the native
        // reader thread can deliver one more frame after clear(), and accepting
        // it repopulates a store the UI already reset. Disarmed (null) is a
        // rejection too, not a bypass.
        //
        // That check is then REPEATED under [commitLock] immediately before the
        // writes, because the first one is not enough on its own. Reading
        // `activeSession` and then writing five separate StateFlows left a window
        // for `clear()` to run in between: the clear disarmed the store and wiped
        // the maps, and then this method's pending writes landed on top and
        // repopulated them Ã¢â‚¬â€ a ghost tile that survived into the NEXT call.
        //
        // The comment below used to claim the ordering ("Disarm BEFORE the map
        // resets") protected against exactly this. Ordering does not help when
        // the other side has already passed its check; only mutual exclusion does.
        val frameSequence = sequence.incrementAndGet()
        // Frame-before-trackAdded race (or a late frame after trackStopped): register
        // the track so the tile is addressable and the entry can be cleaned up later.
        //
        // The source has to come from the CALLER, not from a default. Native starts
        // the reader thread straight after queuing onRemoteVideoTrackAdded, and that
        // queue is only run inline when the session lock is free Ã¢â‚¬â€ so the first
        // frames really can arrive first. Registering with an empty source made
        // isScreenShare false, which filed a 1080p screen share as a camera track:
        // half-size tile, no spotlight, and `isScreenShare` false on the node the
        // whole CallGrid layout keys off. The native reader already knows the
        // source, so it is passed with every frame.
        if (_activeTracks.value[trackSid] == null) {
            trackStarted(participantIdentity, trackSid, trackSource)
        }
        if (frameSequence <= 3L || frameSequence % 60L == 0L) {
            println(
                "DEBUG [RemoteVideoUI] frame updated participantIdentity=$participantIdentity " +
                    "trackSid=$trackSid seq=$frameSequence width=$width height=$height " +
                    "format=$format bytes=${buffer.size} timestampNs=$timestampNs rotation=$rotation"
            )
        }
        val frame = LiveKitRemoteVideoFrame(
            sessionId = sessionId,
            epoch = epoch,
            participantIdentity = participantIdentity,
            trackSid = trackSid,
            buffer = buffer,
            width = width,
            height = height,
            format = format,
            strideY = strideY,
            strideU = strideU,
            strideV = strideV,
            timestampNs = timestampNs,
            rotation = rotation,
            sequence = frameSequence
        )
        synchronized(commitLock) {
            val current = activeSession ?: return
            if (sessionId != current.sessionId || epoch < current.epoch) {
                // clear() (or an arm for a newer session) landed while we were
                // building the frame. Dropping it is correct: the reader thread for
                // the old session is still finishing its callback.
                return
            }
            _latestFrame.value = frame
            _framesByTrack.update { it + (trackSid to frame) }
            val trackState = _activeTracks.value[trackSid]
            if (trackState?.isScreenShare == true) {
                _latestScreenShareFrame.value = frame
            } else {
                _latestCameraFrame.value = frame
            }
        }
    }

    fun trackStopped(participantIdentity: String, trackSid: String) {
        // Under the same lock as the frame commit, so a frame in flight cannot
        // resurrect the track we are removing, and the "promote an arbitrary
        // remaining entry" below cannot observe a map being rebuilt underneath it.
        synchronized(commitLock) {
            _activeTracks.update { it - trackSid }
            _framesByTrack.update { it - trackSid }
            // Clear shared single-slot views only when they display THIS track Ã¢â‚¬â€
            // other same-source tracks may still be flowing.
            if (_latestScreenShareFrame.value?.trackSid == trackSid) {
                _latestScreenShareFrame.value = null
            }
            if (_latestCameraFrame.value?.trackSid == trackSid) {
                _latestCameraFrame.value = null
            }
            if (_latestFrame.value?.trackSid == trackSid) {
                println(
                    "DEBUG [RemoteVideoUI] render stopped participantIdentity=$participantIdentity " +
                        "trackSid=$trackSid"
                )
                // Snapshot inside the lock. Previously this read `values.lastOrNull()`
                // off a map another thread could be rebuilding, so "the next tile to
                // show" was whichever entry happened to land last, not a stable choice.
                val remaining = _framesByTrack.value
                _latestFrame.value = remaining.values.lastOrNull()
                activeTrackSid = remaining.keys.lastOrNull()
            }
        }
    }

    fun clear(reason: String) {
        // Disarm and reset atomically with respect to a frame commit. See the note
        // in updateFrame: ordering alone never protected the empty maps, because the
        // reader thread had already passed its check.
        synchronized(commitLock) {
            if (_latestFrame.value != null || activeTrackSid != null) {
                println("DEBUG [RemoteVideoUI] render stopped reason=$reason trackSid=${activeTrackSid ?: "<none>"}")
            }
            activeTrackSid = null
            activeSession = null
            _activeTracks.value = emptyMap()
            _framesByTrack.value = emptyMap()
            _remoteParticipants.value = emptySet()
            _remoteAudioMuted.value = emptySet()
            _connectionQuality.value = emptyMap()
            _latestFrame.value = null
            _latestCameraFrame.value = null
            _latestScreenShareFrame.value = null
        }
    }
}

object LiveKitLocalVideoFrameStore {
    private val _latestFrame = MutableStateFlow<LiveKitLocalVideoFrame?>(null)
    val latestFrame: StateFlow<LiveKitLocalVideoFrame?> = _latestFrame

    // "Mirror my video" setting, applied to the group-call self tile only
    private val _mirrorPreview = MutableStateFlow(true)
    val mirrorPreview: StateFlow<Boolean> = _mirrorPreview

    fun setMirrorPreview(enabled: Boolean) {
        _mirrorPreview.value = enabled
    }

    private var sequence: Long = 0L

    fun updateFrame(
        buffer: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        strideY: Int,
        timestampNs: Long,
        rotation: Int
    ) {
        sequence += 1
        _latestFrame.value = LiveKitLocalVideoFrame(
            buffer = buffer,
            width = width,
            height = height,
            format = format,
            strideY = strideY,
            timestampNs = timestampNs,
            rotation = rotation,
            sequence = sequence
        )
    }

    fun clear(reason: String) {
        if (_latestFrame.value != null) {
            println("OrbitCallUI localPreviewFrame=cleared reason=$reason")
        }
        _latestFrame.value = null
    }
}
