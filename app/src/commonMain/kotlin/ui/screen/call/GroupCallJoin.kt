package ui.screen.call

import repository.MatrixOrbitRepository
import repository.OrbitRepository
import ui.components.OrbitNotices
import webrtc.CallManager

/**
 * Joining the MatrixRTC call in a room. One implementation, two entry points.
 *
 * ## Why this file exists
 *
 * There are two places a person can reasonably press to get into a call:
 * the drawer's `Voice` row under a room, and the in-room "join" banner. They
 * used to be different code — the banner had the whole resolve-then-join
 * sequence inline, and the drawer had *nothing*, so the drawer's voice affordance
 * had to go through `CallManager.startCall`, which is the **1:1 WebRTC** path
 * (`isGroupCall = false`, an SDP offer, a `m.call.invite`). That is a different
 * protocol with a different signalling path, so "the voice button in the drawer"
 * and "join the call in this room" were never the same action no matter how the
 * labels read.
 *
 * Duplicating the sequence would have been worse than either: the two copies
 * would drift, and the second one would be the copy nobody tested.
 */
data class GroupCallJoinTarget(
    val callId: String,
    val livekitUrl: String?
)

/**
 * Read a call target out of whatever the room-state `activeCallUrl` happens to
 * contain.
 *
 * Two shapes exist in the wild: the `matrix-call://<callId>` scheme Orbit writes
 * when it has no SFU to hand, and `<sfuUrl>?callId=<callId>` which is what
 * `observeActiveGroupCall` produces once a LiveKit focus is known. A blank or
 * absent URL is legal and means "no hint" — the caller's fallback applies, which
 * is how a room with no call running yet can still be joined.
 */
fun parseGroupCallJoinTarget(url: String?, fallbackLiveKitUrl: String?): GroupCallJoinTarget {
    val raw = url.orEmpty()
    if (raw.startsWith("matrix-call://")) {
        val urlContent = raw.substringAfter("://")
        val callId = urlContent.substringAfterLast("/", urlContent).ifBlank { "m.call" }
        return GroupCallJoinTarget(callId = callId, livekitUrl = fallbackLiveKitUrl)
    }

    val callId = raw.substringAfter("?callId=", missingDelimiterValue = "m.call")
        .substringBefore("&")
        .ifBlank { "m.call" }
    val livekitUrl = raw.substringBefore("?").ifBlank { fallbackLiveKitUrl }
    return GroupCallJoinTarget(callId = callId, livekitUrl = livekitUrl)
}

/**
 * Join (or start) the MatrixRTC call in [roomId].
 *
 * The sequence, and why each step is here:
 *
 * 1. **Resolve the join target.** `resolveGroupCallJoinTarget` runs an
 *    all-state scan, which is the only source carrying `origin_server_ts` and so
 *    the only one that can tell a live call from a definition somebody started
 *    twelve days ago and never terminated. It is authoritative.
 * 2. **Fall back to the room's own hint URL**, then to the account's configured
 *    SFU. Needed because step 1 legitimately returns "no call" — see step 4.
 * 3. **Publish a call definition when there is not one.** This is what makes the
 *    drawer's `Voice` row work in a quiet room. `OrbitRepository.startGroupCall`
 *    had no caller at all until now: it was implemented, documented, and dead.
 *    It is safe to call unconditionally because its first action is to
 *    re-resolve and *return early* if somebody else's definition is already
 *    live — publishing over it would split the room across two LiveKit aliases.
 * 4. **Hand off to `CallManager.joinGroupCall`**, which owns the native media
 *    session, the MSC3401 membership publish and the LiveKit connect.
 *
 * Failures are surfaced through [OrbitNotices] and cancellation is rethrown —
 * see `OrbitNotices.notifying`.
 *
 * Deliberately **no retry button** on the notice. Re-joining is a single click
 * away from wherever the user already is (the drawer's `Voice` row, or the room
 * banner), and a `Retry` affordance that is not bound to a re-invocation of
 * *this* function is a button that lies about doing something.
 */
suspend fun joinRoomGroupCall(
    repository: OrbitRepository,
    callManager: CallManager?,
    roomId: String,
    hintUrl: String? = null,
    isVideo: Boolean = false
): Result<Unit> = OrbitNotices.notifying(
    what = "Joining the call",
    subsystem = "GroupCallJoin"
) {
    val manager = callManager ?: error("Call stack is not available")
    val fallback = (repository as? MatrixOrbitRepository)
        ?.context
        ?.effectiveLiveKitServiceUrl()

    // Timed because this is the part the user waits through before ANY of
    // CallManager's own phases start, so it would otherwise be invisible in the
    // JOIN-TIMING line that connectNativeGroupMedia prints.
    val resolveStart = System.nanoTime()
    val resolved = repository.resolveGroupCallJoinTarget(roomId)
    val resolveMs = (System.nanoTime() - resolveStart) / 1_000_000L
    val parsed = parseGroupCallJoinTarget(hintUrl, fallback)
    val callId = resolved.callId ?: parsed.callId
    val sfu = resolved.serviceUrl ?: parsed.livekitUrl
    if (sfu.isNullOrBlank()) error("No LiveKit service URL is available for $roomId")

    if (!resolved.exists) {
        // No definition yet. Publish one so this client is the one that created
        // the call, which is what Element does when you press "Join" in a silent
        // room.
        val start = System.nanoTime()
        repository.startGroupCall(roomId, callId)
        util.OrbitLog.d(
            "GroupCallJoin",
            "JOIN-RESOLVE target=${resolveMs}ms publishDefinition=${(System.nanoTime() - start) / 1_000_000L}ms"
        )
    } else {
        util.OrbitLog.d("GroupCallJoin", "JOIN-RESOLVE target=${resolveMs}ms (call already exists)")
    }

    manager.joinGroupCall(roomId, callId, isVideo, sfu) {
        repository.fetchLiveKitToken(roomId, callId, sfu)
    }
}

/**
 * The live call, rendered in the right-hand side panel.
 *
 * The call used to sit above the timeline at `weight(0.55f)`, which meant the
 * video grid was a wide shallow letterbox and the controls were squeezed
 * against the composer. It now shares the column with the chat and the panel
 * slot with Members and Pins, so the two are visible at once and neither has to
 * give up the width that makes the other usable.
 *
 * Sized to [OrbitMetrics.sidePanelWidth] with the same background and border as
 * the other panels, so swapping between call / members / pins slides one panel
 * past another rather than restacking the window.
 */
// The call used to live here -- first stacked above the chat at 55/45, then in
// this slot. It no longer does. `CallSidePanel` was deleted because the layout
// it existed to serve was wrong twice: a 264dp letterbox is a bad video grid, and
// giving the conversation the whole window means the call is what you have to
// squint at. The call now has the main column and the timeline has the panel
// (see `ChatSidePanel` in ChatScreen.kt).
//
// `joinRoomGroupCall` above is not affected: the drawer's `Voice` row is the one
// way into a call now that the in-room banner is gone, so it is the whole of
// this file's remaining reason to exist.
