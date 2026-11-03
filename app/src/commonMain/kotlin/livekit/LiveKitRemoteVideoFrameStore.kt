package livekit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Remote screen shares that are published, named as screen shares, and have
     * had no track behind them long enough that retrying has stopped helping.
     *
     * The tile for one of these has nothing to draw and never will: there is no
     * reader, no stream, no frames, because the SDK never produced a track object.
     * Retrying the subscription fixes a share's audio track and never its video
     * one; rebuilding the SFU session does, because the fresh connection picks the
     * still-running share up with its track attached. So this set exists to give
     * the UI something to act on instead of leaving an invisible share on screen.
     */
    private val _stuckTracks = MutableStateFlow<Map<String, String>>(emptyMap())
    val stuckTracks: StateFlow<Map<String, String>> = _stuckTracks

    /** Track sid -> participant identity, for a share with no track behind it. */
    fun trackStuck(participantIdentity: String, trackSid: String) {
        if (trackSid.isBlank()) return
        markPublishing(participantIdentity)
        if (_stuckTracks.value.containsKey(trackSid)) return
        _stuckTracks.value = _stuckTracks.value + (trackSid to participantIdentity)
    }

    fun clearStuckTracks() {
        if (_stuckTracks.value.isEmpty()) return
        _stuckTracks.value = emptyMap()
    }
    private val _framesByTrack = MutableStateFlow<Map<String, LiveKitRemoteVideoFrame>>(emptyMap())
    val framesByTrack: StateFlow<Map<String, LiveKitRemoteVideoFrame>> = _framesByTrack
    private val _rawRemoteParticipants = MutableStateFlow<Set<String>>(emptySet())
    val rawRemoteParticipants: StateFlow<Set<String>> = _rawRemoteParticipants
    private val _remoteParticipants = MutableStateFlow<Set<String>>(emptySet())
    val remoteParticipants: StateFlow<Set<String>> = _remoteParticipants
    private val publishingParticipants = ConcurrentHashMap.newKeySet<String>()

    private fun isDisplayable(identity: String): Boolean =
        identity in publishingParticipants || CallRosterReconciliation.userIdOf(identity) != null

    private fun markPublishing(identity: String) {
        if (identity.isBlank()) return
        publishingParticipants.add(identity)
        _rawRemoteParticipants.update { if (identity in it) it else it + identity }
        _remoteParticipants.update { if (identity in it) it else it + identity }
    }

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

    /**
     * Smallest width or height a frame may claim.
     *
     * Set far below anything real. See the comment at the check in [updateFrame] for
     * why a size floor is needed at all when a buffer-size check exists: a
     * degenerate frame can be perfectly self-consistent, so only a floor on the
     * dimensions catches it.
     */
    private const val MIN_FRAME_EDGE = 16

    /**
     * Frames rejected because they were degenerate or their buffer could not hold
     * the picture they claimed.
     *
     * Atomic because [updateFrame] runs on one native reader thread per remote
     * video track, and a plain `var` would lose counts -- which for a counter whose
     * entire job is to prove a fault is rare is the one failure mode that matters.
     */
    private val rejectedFrames = AtomicLong(0L)

    /**
     * The rejection tally, as one line, appended to each rejection.
     *
     * The point is the rate, not the total. A handful of rejected frames on a long
     * call is a decoder hiccup; the same rejections every second is a broken track
     * that a user would describe as "the video is stuck". The cumulative count
     * alongside it is what separates those two without a second log grep.
     */
    private fun rejectionLine(): String {
        val total = rejectedFrames.get()
        val accepted = sequence.get() - total
        return "frames accepted=$accepted rejected=$total"
    }

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

    /**
     * Tracks that were deliberately stopped and must not be resurrected by a frame
     * that was already in flight when the stop landed.
     *
     * Native stops a reader by clearing `running` and closing the stream, which
     * does not retract the frames already handed to JNI. Those arrive after
     * [trackStopped] has run, and `updateFrame` re-registers any track it does not
     * recognise -- so without this, a stopped track came straight back, along with
     * its frame, and the tile sat there showing the last picture as if the stream
     * were still live. That was half of the freeze Element's "stop sharing"
     * produced; the other half was the reaper never joining the reader it had
     * stopped, which is fixed on the native side.
     *
     * Bounded by [STOP_TOMBSTONE_MS] rather than cleared on the next frame: the
     * point is to swallow the stragglers, and a genuinely re-subscribed track
     * announces itself through trackStarted, which clears its entry.
     */
    private val stoppedTracks = LinkedHashMap<String, Long>()

    private fun markTrackStopped(trackSid: String) {
        if (trackSid.isBlank()) return
        val now = System.currentTimeMillis()
        synchronized(stoppedTracks) {
            val cutoff = now - STOP_TOMBSTONE_MS
            stoppedTracks.entries.removeAll { it.value < cutoff }
            if (stoppedTracks.size >= MAX_TOMBSTONES && trackSid !in stoppedTracks) {
                val oldest = stoppedTracks.values.minOrNull() ?: now
                stoppedTracks.entries.removeAll { it.value == oldest }
            }
            stoppedTracks[trackSid] = now
        }
    }

    private fun isTrackStopped(trackSid: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(stoppedTracks) {
            val at = stoppedTracks[trackSid] ?: return false
            if (now - at >= STOP_TOMBSTONE_MS) {
                stoppedTracks.remove(trackSid)
                return false
            }
            return true
        }
    }

    private fun clearStopTombstone(trackSid: String) {
        if (trackSid.isBlank()) return
        synchronized(stoppedTracks) { stoppedTracks.remove(trackSid) }
    }

    private const val STOP_TOMBSTONE_MS = 5_000L
    private const val MAX_TOMBSTONES = 64

    /** Arms the store for one native session; call before the reader sink is attached. */
    fun beginSession(sessionId: Long, epoch: Long) {
        activeSession = SessionEpoch(sessionId, epoch)
    }

    fun participantConnected(participantIdentity: String) {
        if (participantIdentity.isBlank()) return
        _rawRemoteParticipants.update { it + participantIdentity }
        if (isDisplayable(participantIdentity)) {
            _remoteParticipants.update { it + participantIdentity }
        }
    }

    fun participantDisconnected(participantIdentity: String) {
        if (participantIdentity.isBlank()) return
        publishingParticipants.remove(participantIdentity)
        _rawRemoteParticipants.update { it - participantIdentity }
        _remoteParticipants.update { it - participantIdentity }
        _remoteAudioMuted.update { it - participantIdentity }
        _connectionQuality.update { it - participantIdentity }
        synchronized(commitLock) {
            val toRemoveTracks = _activeTracks.value.filter { it.value.participantIdentity == participantIdentity }.keys
            val toRemoveFrames = _framesByTrack.value.filter { it.value.participantIdentity == participantIdentity }.keys
            val toRemove = (toRemoveTracks + toRemoveFrames)
            // Tombstone every track this participant had, not just the ones the
            // maps still know about. The maps are about to lose these entries and a
            // frame already in flight would otherwise re-register one of them and
            // leave a ghost tile for a participant who has gone.
            toRemove.forEach { markTrackStopped(it) }
            if (toRemove.isNotEmpty()) {
                _activeTracks.update { current -> current.filterKeys { it !in toRemove } }
                _framesByTrack.update { current -> current.filterKeys { it !in toRemove } }
            }
            if (_latestScreenShareFrame.value?.participantIdentity == participantIdentity) {
                _latestScreenShareFrame.value = null
            }
            if (_latestCameraFrame.value?.participantIdentity == participantIdentity) {
                _latestCameraFrame.value = null
            }
            if (_latestFrame.value?.participantIdentity == participantIdentity ||
                (_latestFrame.value != null && !_framesByTrack.value.containsKey(_latestFrame.value?.trackSid))) {
                val remaining = _framesByTrack.value
                _latestFrame.value = remaining.values.lastOrNull()
                activeTrackSid = remaining.keys.lastOrNull()
            }
        }
    }

    fun remoteConnectionQuality(
        participantIdentity: String,
        jitterMs: Long,
        rttMs: Long,
        lossPct: Double
    ) {
        if (participantIdentity.isBlank()) return
        markPublishing(participantIdentity)
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
        markPublishing(participantIdentity)
        _remoteAudioMuted.update { current ->
            val updated = if (muted) current + participantIdentity else current - participantIdentity
            if (updated == current) current else updated
        }
    }

    /**
     * A remote video track exists in the room, and we have not seen a frame from
     * it yet -- there may not even be a track object behind it.
     *
     * Registering it here is what makes the wait visible. The call tile is built
     * from [activeTracks], and previously a track only entered that map from
     * [updateFrame], so a share that took eighteen seconds to attach had no tile at
     * all for those eighteen seconds. The tile appeared at the same instant as the
     * picture, which made the "waiting" state unreachable rather than merely unused.
     *
     * [trackStarted] supersedes this once a real track arrives; the entry is
     * overwritten with the authoritative source and name.
     */
    fun trackAnnounced(participantIdentity: String, trackSid: String, trackSource: String = "") {
        if (trackSid.isBlank()) return
        markPublishing(participantIdentity)
        // An announcement is native telling us this track exists, which is exactly
        // what clears a stop tombstone: the track is back, so frames for it are
        // wanted again.
        clearStopTombstone(trackSid)
        if (_activeTracks.value.containsKey(trackSid)) return
        _activeTracks.update {
            it + (
                trackSid to LiveKitRemoteVideoTrackState(
                    participantIdentity = participantIdentity,
                    trackSid = trackSid,
                    trackSource = trackSource,
                    trackName = ""
                )
                )
        }
    }

    fun trackStarted(participantIdentity: String, trackSid: String, trackSource: String = "", trackName: String = "") {
        markPublishing(participantIdentity)
        // A track existing at all beats a tombstone: this is the re-subscribe path,
        // and leaving the tombstone in place would make updateFrame drop the frames
        // for a track that is demonstrably live again.
        clearStopTombstone(trackSid)
        // A track existing at all means the share attached after all, so any stuck
        // marker for it is stale. Left in place it would keep offering a reconnect
        // for a share that is working.
        if (_stuckTracks.value.containsKey(trackSid)) {
            _stuckTracks.value = _stuckTracks.value - trackSid
        }
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

        // Reject a frame whose buffer cannot hold the picture it claims to.
        //
        // Shipped in 0.2.1: a run logged `width=8 height=8 bytes=256 format=0`, a
        // frame eight pixels on a side, and it was accepted and published. Nothing
        // downstream checks the arithmetic, so an 8x8 frame reaches the renderer,
        // which allocates an 8x8 image and stretches it across the tile -- a
        // frozen, blocky smear of whatever the previous frame left behind, on a
        // track that is otherwise live and delivering.
        //
        // The existing guard in onRemoteVideoFrame only caught width<=0, height<=0
        // and an empty buffer, and this frame cleared all three. A non-positive
        // dimension is a missing frame; this is a frame that contradicts itself,
        // which is a different fault and needs its own check.
        //
        // THE ORDER MATTERS, and getting it wrong is instructive.
        //
        // The first attempt at this fix was the buffer arithmetic alone --
        // `buffer.size >= strideY * height`. It is a real check, and it does not
        // catch the frame it was written for. The 8x8 frame came with 256 bytes
        // and strideY=32, and 32 * 8 == 256, so it is arithmetically PERFECT. A
        // frame that claims to be 8x8 and carries exactly 8 rows of 32 bytes has
        // nothing wrong with it by that test, and it was still published.
        //
        // The fault is not a mismatch between the buffer and the dimensions. It is
        // that the dimensions are not a picture. So the floor has to be on size,
        // and it has to come first, because a degenerate frame can be
        // self-consistent and the arithmetic check will wave it through.
        //
        // 16 is deliberately far below anything real. The smallest thing a capture
        // device or a software encoder produces is orders of magnitude larger, so
        // 16 cannot reject a genuine frame, while every degenerate size in the
        // field is under it. A floor set at something like 64 would have looked
        // more decisive and would eventually eat a real low-resolution stream.
        //
        // The arithmetic check below is kept because it catches a different real
        // fault -- a caller that under-allocates by a byte -- which the size floor
        // cannot see.
        if (width < MIN_FRAME_EDGE || height < MIN_FRAME_EDGE) {
            rejectedFrames.incrementAndGet()
            println(
                "WARN [RemoteVideo] rejected degenerate frame " +
                    "participantIdentity=$participantIdentity trackSid=$trackSid " +
                    "width=$width height=$height strideY=$strideY format=$format " +
                    "bytes=${buffer.size} minEdge=$MIN_FRAME_EDGE ${rejectionLine()}"
            )
            return
        }
        val lumaBytes = strideY.toLong() * height.toLong()
        if (buffer.size.toLong() < lumaBytes || lumaBytes > Int.MAX_VALUE.toLong()) {
            rejectedFrames.incrementAndGet()
            println(
                "WARN [RemoteVideo] rejected frame whose buffer cannot hold its picture " +
                    "participantIdentity=$participantIdentity trackSid=$trackSid " +
                    "width=$width height=$height strideY=$strideY format=$format " +
                    "bytes=${buffer.size} lumaBytes=$lumaBytes ${rejectionLine()}"
            )
            return
        }

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
        // A track that was deliberately STOPPED is not that race and is not
        // repaired here. Native clears the reader's `running` flag and closes the
        // stream, but frames already handed to JNI still arrive, and re-registering
        // on them brought the tile straight back after Element stopped sharing --
        // which is the "Orbit still shows the stream as if it were live" half of
        // the freeze. Dropped instead. trackStarted clears the tombstone, so a real
        // re-subscribe still registers normally.
        if (isTrackStopped(trackSid)) {
            return
        }
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
        // The sender withdrew it, so there is nothing left to reconnect for.
        if (_stuckTracks.value.containsKey(trackSid)) {
            _stuckTracks.value = _stuckTracks.value - trackSid
        }
        // Tombstone, so a frame that was already in flight when the stop landed
        // cannot re-register the track on its way through updateFrame. Without it
        // a stopped track always came back, which is the "Orbit still shows the
        // stream as if it were live" half of the freeze. Cleared by trackStarted,
        // so a genuine re-subscribe is not blocked.
        markTrackStopped(trackSid)
        // Under the same lock as the frame commit, so a frame in flight cannot
        // resurrect the track we are removing, and the "promote an arbitrary
        // remaining entry" below cannot observe a map being rebuilt underneath it.
        synchronized(commitLock) {
            val matchedTrackKeys = _activeTracks.value.filter { (key, state) ->
                key == trackSid || state.trackSid == trackSid
            }.keys
            val toRemove = (matchedTrackKeys + trackSid).toSet()

            val wasScreenShare = toRemove.any { key ->
                _activeTracks.value[key]?.isScreenShare == true
            } || _latestScreenShareFrame.value?.trackSid in toRemove

            // A publication is announced under its publicationSid and started under
            // its trackSid, so stopping the real track leaves the announcement stub
            // behind and the tile never goes away. The stub is recognisable: it is a
            // track with no frame of its own, because only the started trackSid ever
            // receives one.
            //
            // Deliberately narrow. An earlier version instead deleted every
            // screen-share track the participant had, which meant a sender that
            // stopped its camera while its screen share was up took the live share
            // down with it -- and that share DOES have a frame, so it is not a stub
            // and is not touched here. Purging everything a participant owns is
            // participantDisconnected's job, and it does that.
            val announcementStubs = if (participantIdentity.isNotBlank()) {
                _activeTracks.value.filter { (key, state) ->
                    state.participantIdentity == participantIdentity &&
                        state.isScreenShare &&
                        !_framesByTrack.value.containsKey(key)
                }.keys
            } else {
                emptySet()
            }

            val allToRemove = toRemove + announcementStubs

            if (allToRemove.any { _stuckTracks.value.containsKey(it) }) {
                _stuckTracks.update { current -> current.filterKeys { it !in allToRemove } }
            }
            _activeTracks.update { current -> current.filterKeys { it !in allToRemove } }
            _framesByTrack.update { current -> current.filterKeys { it !in allToRemove } }
            allToRemove.forEach { markTrackStopped(it) }

            // Clear shared single-slot views only when they display THIS track —
            // other same-source tracks may still be flowing.
            if (wasScreenShare || _latestScreenShareFrame.value?.trackSid in allToRemove) {
                _latestScreenShareFrame.value = null
            }
            if (_latestCameraFrame.value?.trackSid in allToRemove) {
                _latestCameraFrame.value = null
            }
            if (_latestFrame.value?.trackSid in allToRemove ||
                (_latestFrame.value != null && !_framesByTrack.value.containsKey(_latestFrame.value?.trackSid))) {
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
        var framesThisCall = 0L
        // Captured before the reset, because after it there is nothing left to
        // count. See the WARN below for why this is captured at all.
        val participantsLost = _rawRemoteParticipants.value.size
        val tracksLost = _activeTracks.value.size
        synchronized(commitLock) {
            if (_latestFrame.value != null || activeTrackSid != null) {
                println("DEBUG [RemoteVideoUI] render stopped reason=$reason trackSid=${activeTrackSid ?: "<none>"}")
            }
            activeTrackSid = null
            activeSession = null
            synchronized(stoppedTracks) { stoppedTracks.clear() }
            _activeTracks.value = emptyMap()
            _framesByTrack.value = emptyMap()
            _rawRemoteParticipants.value = emptySet()
            _remoteParticipants.value = emptySet()
            publishingParticipants.clear()
            _remoteAudioMuted.value = emptySet()
            _connectionQuality.value = emptyMap()
            _stuckTracks.value = emptyMap()
            _latestFrame.value = null
            _latestCameraFrame.value = null
            _latestScreenShareFrame.value = null
            // Read BEFORE the reset below, or the tally printed for this call is
            // always zero -- which is the one number that must not be wrong here.
            //
            // Both counters are call-scoped, not process-scoped. Left across
            // sessions they would make the second call's tally a running total
            // including the first, which is how a per-call fault rate becomes a
            // number nobody can act on.
            framesThisCall = rejectedFrames.get()
            rejectedFrames.set(0L)
            sequence.set(0L)
        }
        // Printed at teardown, where an imbalance names exactly who was left behind.
        // Run 62 ended a call with 16 participant-connects against 1 disconnect and
        // nothing anywhere saying so. A leak that only shows up in a log you have to
        // grep and count is a leak nobody reports.
        //
        // The frame tally is printed alongside it for the same reason: a track that
        // rejected every frame it was sent looks identical to a track that never
        // delivered any, and "the video is stuck" is the same sentence either way.
        if (framesThisCall > 0L) {
            println(
                "WARN [RemoteVideoUI] frames were rejected during this call " +
                    "rejected=$framesThisCall reason=$reason"
            )
        }
        // WARN, and unconditional on the debug gate, because this is the line
        // that makes the "everyone can hear me and I can see nobody" bug
        // findable from a user's log.
        //
        // A refresh calls clear() with a LIVE roster. Every tile the call
        // surface draws comes from `_activeTracks` or `_remoteParticipants`, so
        // emptying them takes the whole grid down to the local node -- and audio
        // is unaffected, because it never touches this store. That combination
        // reads as "the UI crashed" and produces no exception, so with only
        // DEBUG logging on there was nothing at all in the file.
        //
        // The roster is expected to come back: the rescan re-announces it. So
        // this is a warning about a window, not a failure claim -- but it is the
        // difference between a bug report with a cause in it and one without.
        if (participantsLost > 0 || tracksLost > 0) {
            util.OrbitLog.w(
                "RemoteVideoUI",
                "roster cleared while participants were present " +
                    "reason=$reason participants=$participantsLost tracks=$tracksLost " +
                    "(tiles rebuild on the next rescan; if they do not, this is the line to quote)"
            )
        }
        NativeParticipantLedger.report()
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
