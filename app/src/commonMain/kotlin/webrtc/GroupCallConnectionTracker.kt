package webrtc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import livekit.NativeMediaReadiness
import livekit.NativeMediaState

/**
 * Explicit stage ladder for group-call joins (master plan E1):
 * Idle → Joining → Joined (MatrixRTC membership visible) → Connected (LiveKit room)
 * → AudioPublished (ack) → VideoPublished (ack, video calls) → InCall (stable voice call
 * or first remote frame rendered). Reconnecting and Failed are surfaced states, never
 * silent fallbacks.
 *
 * The tracker holds no coroutines: CallManager feeds it events and evaluates
 * [evaluateDeadlines] on its own watchdog, keeping the ladder deterministic and
 * unit-testable with a fake clock.
 */
enum class GroupCallStage {
    Idle,
    Joining,
    Joined,
    Connected,
    AudioPublished,
    VideoPublished,
    InCall,
    Reconnecting,
    Failed
}

data class GroupCallConnection(
    val stage: GroupCallStage = GroupCallStage.Idle,
    val failedStage: GroupCallStage? = null,
    val message: String? = null,
    val attempt: Int = 1,
    val audioOnlyFallback: Boolean = false,
    val waitingForRemoteMedia: Boolean = false
)

enum class TrackerDecision { None, GiveUp }

class GroupCallConnectionTracker(
    private val nowMs: () -> Long
) {
    private val _connection = MutableStateFlow(GroupCallConnection())
    val connection: StateFlow<GroupCallConnection> = _connection

    private var stageEnteredAtMs: Long = 0L
    private var sawAudioPublished = false

    fun begin() {
        stageEnteredAtMs = nowMs()
        sawAudioPublished = false
        _connection.value = GroupCallConnection(stage = GroupCallStage.Joining)
    }

    fun reset() {
        stageEnteredAtMs = 0L
        sawAudioPublished = false
        _connection.value = GroupCallConnection()
    }

    /** JOINED: the m.call.member event is confirmed visible in room state. */
    fun onMembershipPublished(visible: Boolean) {
        if (terminal()) return
        if (visible) {
            stageEnteredAtMs = nowMs()
            _connection.value = _connection.value.copy(stage = GroupCallStage.Joined, message = null)
        } else {
            fail(GroupCallStage.Joining, "Membership event was not visible after publish")
        }
    }

    /**
     * Feed for polled native readiness while the join coroutine is in flight;
     * advances the forward ladder and surfaces Reconnecting without regressing stages.
     */
    fun onNativeReadiness(readiness: NativeMediaReadiness?) {
        if (readiness == null || terminal()) return
        val current = _connection.value
        when (readiness.state) {
            NativeMediaState.LiveKitConnected,
            NativeMediaState.PublishingAudio -> {
                if (current.stage == GroupCallStage.Joined || current.stage == GroupCallStage.Reconnecting) {
                    stageEnteredAtMs = nowMs()
                    _connection.value = current.copy(stage = GroupCallStage.Connected)
                }
            }
            NativeMediaState.AudioPublished,
            NativeMediaState.PublishingVideo -> {
                sawAudioPublished = true
                if (stageRank(current.stage) < stageRank(GroupCallStage.AudioPublished)) {
                    stageEnteredAtMs = nowMs()
                    _connection.value = current.copy(stage = GroupCallStage.AudioPublished)
                }
            }
            NativeMediaState.VideoPublished,
            NativeMediaState.MediaReady -> {
                sawAudioPublished = true
                if (stageRank(current.stage) < stageRank(GroupCallStage.VideoPublished)) {
                    stageEnteredAtMs = nowMs()
                    _connection.value = current.copy(stage = GroupCallStage.VideoPublished)
                }
            }
            NativeMediaState.Reconnecting -> {
                if (current.stage != GroupCallStage.Failed) {
                    // Stamp the stage-entry time ON THE TRANSITION ONLY, which every
                    // other forward stage already does and this one did not.
                    //
                    // Two things follow from that, and both matter:
                    //  * without the stamp, stageEnteredAtMs still held the value
                    //    from the original join, so any deadline measured from it
                    //    would fire instantly the moment a call dropped. Adding
                    //    RECONNECT_TIMEOUT_MS without this line would have turned
                    //    every transient network blip into a failed call.
                    //  * it must not stamp on every poll. This readiness callback
                    //    arrives repeatedly while the reconnect is in flight, and
                    //    re-stamping each time would reset the clock forever, so
                    //    the deadline would never be reached at all.
                    if (current.stage != GroupCallStage.Reconnecting) {
                        stageEnteredAtMs = nowMs()
                    }
                    _connection.value = current.copy(stage = GroupCallStage.Reconnecting)
                }
            }
            NativeMediaState.Failed -> fail(
                stageForFailureName(videoRequested = false),
                readiness.lastError?.message ?: "Native media failed"
            )
            else -> Unit
        }
        if (readiness.joinedAsAudioOnlyFallback && !_connection.value.audioOnlyFallback) {
            _connection.value = _connection.value.copy(audioOnlyFallback = true)
        }
    }

    /**
     * Authoritative end-of-join verdict from connectAndPublishToLiveKit.
     * Returns true when audio is published and frames are flowing.
     */
    fun finalizeJoin(readiness: NativeMediaReadiness?, videoRequested: Boolean): Boolean {
        if (terminal()) return false
        if (readiness == null || !readiness.isPublishStable()) {
            fail(
                stageForFailureName(videoRequested),
                readiness?.lastError?.message ?: "LiveKit connect did not complete"
            )
            return false
        }
        onNativeReadiness(readiness.copy(state = NativeMediaState.MediaReady))
        // Voice calls are stable as soon as audio is published and accepted;
        // video calls reach InCall when remote media actually renders (or solo room).
        if (!videoRequested && _connection.value.stage != GroupCallStage.InCall) {
            stageEnteredAtMs = nowMs()
            _connection.value = _connection.value.copy(stage = GroupCallStage.InCall)
        }
        return true
    }

    /** RENDERING evidence: the frame store saw its first remote track/frame. */
    fun onFirstRemoteFrame() {
        if (terminal()) return
        val current = _connection.value
        val published = stageRank(current.stage) >= stageRank(GroupCallStage.AudioPublished)
        if (published && stageRank(current.stage) < stageRank(GroupCallStage.InCall)) {
            awaitingRemoteMediaCleared()
            stageEnteredAtMs = nowMs()
            _connection.value = current.copy(stage = GroupCallStage.InCall)
        }
    }

    /** H1: a mid-call reconnect completed — return from Reconnecting to InCall. */
    fun onReconnectComplete() {
        if (terminal()) return
        val current = _connection.value
        if (current.stage == GroupCallStage.Reconnecting) {
            stageEnteredAtMs = nowMs()
            _connection.value = current.copy(
                stage = GroupCallStage.InCall,
                waitingForRemoteMedia = false,
                message = null
            )
        }
    }

    /** Soft marker: media expected but nothing rendering — shown in UI, never fatal. */
    fun noteWaitingForRemoteMedia() {
        if (terminal()) return
        _connection.value = _connection.value.copy(waitingForRemoteMedia = true)
    }

    /**
     * Deadline evaluation, called periodically by the owner's watchdog.
     * Any breach gives up visibly; bounded connect retries are the sequential
     * loop's job (see CallManager.connectNativeGroupMedia).
     */
    fun evaluateDeadlines(now: Long, videoRequested: Boolean, remoteMediaExpected: Boolean): TrackerDecision {
        val current = _connection.value
        if (current.stage == GroupCallStage.Idle ||
            current.stage == GroupCallStage.Failed ||
            current.stage == GroupCallStage.InCall
        ) {
            return TrackerDecision.None
        }
        val elapsedHere = now - stageEnteredAtMs
        return when (current.stage) {
            GroupCallStage.Joining ->
                if (elapsedHere > MEMBERSHIP_TIMEOUT_MS) TrackerDecision.GiveUp else TrackerDecision.None
            GroupCallStage.Joined ->
                if (elapsedHere > CONNECT_TIMEOUT_MS) TrackerDecision.GiveUp else TrackerDecision.None
            GroupCallStage.Connected ->
                if (elapsedHere > AUDIO_PUBLISH_TIMEOUT_MS) TrackerDecision.GiveUp else TrackerDecision.None
            GroupCallStage.AudioPublished ->
                if (videoRequested && elapsedHere > VIDEO_PUBLISH_TIMEOUT_MS) TrackerDecision.GiveUp else TrackerDecision.None
            GroupCallStage.VideoPublished ->
                if (remoteMediaExpected && !current.waitingForRemoteMedia && elapsedHere > REMOTE_MEDIA_SOFT_WARN_MS) {
                    noteWaitingForRemoteMedia()
                    TrackerDecision.None
                } else if (!remoteMediaExpected && elapsedHere > SOLO_ROOM_SETTLE_MS) {
                    // Nobody else is publishing — a solo call is already in-call.
                    _connection.value = current.copy(stage = GroupCallStage.InCall)
                    TrackerDecision.None
                } else TrackerDecision.None
            GroupCallStage.Reconnecting ->
                // Reconnecting used to fall through to `else`, which had no
                // deadline at all. Every other stage on the ladder is bounded, so a
                // socket that dies and never comes back left the tracker
                // advertising "Reconnecting…" indefinitely: the reconnect loop kept
                // firing, the token kept refreshing, and nothing ever told the user
                // their call was not coming back.
                //
                // Long, because a transient network drop is the common case and
                // giving up on a call that would have recovered a minute later is
                // worse than waiting. Bounded, because an unbounded wait is not a
                // recovery strategy.
                if (elapsedHere > RECONNECT_TIMEOUT_MS) TrackerDecision.GiveUp else TrackerDecision.None
            else -> TrackerDecision.None
        }
    }

    /** Arms the second (final) connect attempt; returns false if the tracker is terminal. */
    fun noteRetryStarted(): Boolean {
        val current = _connection.value
        if (current.stage == GroupCallStage.Failed) return false
        stageEnteredAtMs = nowMs()
        _connection.value = current.copy(
            stage = GroupCallStage.Joined,
            attempt = current.attempt + 1,
            message = null
        )
        return true
    }

    /**
     * Progress stamp for the Joining stage: resets the deadline clock WITHOUT
     * advancing the ladder. The owner calls this after slow pre-join network
     * hops (ICE servers, OpenID/SFU token fetch) so MEMBERSHIP_TIMEOUT_MS
     * measures the membership publish alone rather than the whole prelude.
     */
    fun noteJoiningProgress() {
        if (terminal()) return
        if (_connection.value.stage == GroupCallStage.Joining) stageEnteredAtMs = nowMs()
    }

    fun markFailed(message: String) {
        fail(stageForFailureName(false), message)
    }

    private fun awaitingRemoteMediaCleared() {
        _connection.value = _connection.value.copy(waitingForRemoteMedia = false)
    }

    private fun stageForFailureName(videoRequested: Boolean): GroupCallStage =
        when (val stage = _connection.value.stage) {
            GroupCallStage.Joining, GroupCallStage.Idle -> GroupCallStage.Joining
            GroupCallStage.Joined, GroupCallStage.Connected -> GroupCallStage.Connected
            GroupCallStage.AudioPublished ->
                if (videoRequested) GroupCallStage.VideoPublished else GroupCallStage.AudioPublished
            else -> stage
        }

    private fun fail(fromStage: GroupCallStage, message: String) {
        _connection.value = _connection.value.copy(
            stage = GroupCallStage.Failed,
            failedStage = fromStage,
            message = message
        )
    }

    private fun terminal(): Boolean = _connection.value.stage == GroupCallStage.Failed

    private fun stageRank(stage: GroupCallStage): Int = when (stage) {
        GroupCallStage.Idle, GroupCallStage.Joining -> 0
        GroupCallStage.Joined -> 1
        GroupCallStage.Connected, GroupCallStage.Reconnecting -> 2
        GroupCallStage.AudioPublished -> 3
        GroupCallStage.VideoPublished -> 4
        GroupCallStage.InCall -> 5
        GroupCallStage.Failed -> -1
    }

    companion object {
        // Covers the membership state-event PUT alone: CallManager stamps
        // Joining progress after the token fetch, so this must not also fit
        // the OpenID + SFU-JWT round trips (slow homeservers blew past 8s and
        // the watchdog killed joins that were about to succeed).
        const val MEMBERSHIP_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val AUDIO_PUBLISH_TIMEOUT_MS = 20_000L
        const val VIDEO_PUBLISH_TIMEOUT_MS = 30_000L
        const val REMOTE_MEDIA_SOFT_WARN_MS = 15_000L
        const val SOLO_ROOM_SETTLE_MS = 10_000L
        const val MAX_CONNECT_ATTEMPTS = 2

        // How long a mid-call reconnect may run before the call is declared lost.
        //
        // Generous, because the overwhelmingly common cause is a transient network
        // drop and a call that would have come back is worth far more than a fast
        // failure. Finite, because the alternative -- which is what this replaced,
        // since Reconnecting fell through to a branch with no deadline -- is a dead
        // socket reconnecting forever while the UI sits on "Reconnecting…" with no
        // way to learn that nothing is coming back.
        const val RECONNECT_TIMEOUT_MS = 90_000L
    }
}

private fun NativeMediaReadiness.isPublishStable(): Boolean =
    state == NativeMediaState.MediaReady && audioPublished && acceptingAudioFrames
