package ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import util.OrbitLog
import java.util.concurrent.atomic.AtomicLong

/**
 * A transient message shown to the user: an operation failed, or something
 * happened they should know about.
 *
 * [retry] is the important field. It exists because the alternative to retrying
 * a failed send is losing the message, and losing a typed message is not an
 * acceptable failure mode for a chat client.
 */
data class OrbitNotice(
    val id: Long,
    val kind: Kind,
    val text: String,
    val retryLabel: String? = null,
    val retry: (() -> Unit)? = null,
    /** How long the host waits before auto-dismissing. `null` means sticky. */
    val autoDismissMs: Long? = DEFAULT_DISMISS_MS,
    /** Milliseconds before [autoDismissMs] elapses at which the notice must be
     *  clicked rather than waited out. A destructive action you did not ask for
     *  should never disappear on its own. */
    val mustActMs: Long? = null
) {
    enum class Kind { Error, Info }

    val actionable: Boolean get() = retry != null

    companion object {
        const val DEFAULT_DISMISS_MS = 6_000L
        /** A notice carrying a retry stays up long enough to be noticed, clicked,
         *  and understood. */
        const val ACTIONABLE_DISMISS_MS = 15_000L
    }
}

/**
 * The app's only user-visible channel for "that did not work".
 *
 * ## Why this exists
 *
 * Fifteen repository calls returned `Result` and the UI discarded it:
 *
 * ```
 * runCatching { repository.deleteMessage(channel.id, eventId) }
 * runCatching { repository.kickUser(channel.id, userId, null).getOrThrow() }
 * else -> repository.sendMessage(channel.id, body)
 * ```
 *
 * `runCatching` catches the failure and the expression's value is thrown away,
 * so the next `getMessagesForChannel` re-renders from the server and the row
 * the user just deleted is *back*. Kick and ban are worse — they even call
 * `getOrThrow()` inside the `runCatching`, so the exception is raised and then
 * swallowed, and the UI reports success for an operation that did nothing.
 *
 * Worse, the composer's `MessageInputBar` clears its text synchronously the
 * instant send is pressed. A failed send therefore destroys the message with no
 * copy anywhere.
 *
 * The repository layer was never wrong about any of this. It returned `Result`
 * and documented the contract; the call sites threw the answer away.
 *
 * ## Design
 *
 * A process-wide list rather than a per-screen one, because the failures that
 * matter are the ones you did not initiate from the screen you are looking at
 * (a call membership update that failed, a presence change that did not land).
 * A composable-local snackbar host would have put those back in the dark.
 *
 * Deliberately not a sealed hierarchy or a sealed set of actions. A generic
 * "message + optional retry" is enough for every current site, and a richer
 * type would be a hierarchy to maintain for no current benefit.
 */
object OrbitNotices {
    private val nextId = AtomicLong(1L)
    private val _notices = MutableStateFlow<List<OrbitNotice>>(emptyList())

    /** Oldest first. The host renders bottom-up so the newest is nearest the
     *  corner and the least likely to be pushed off-screen. */
    val notices: StateFlow<List<OrbitNotice>> = _notices.asStateFlow()

    /**
     * Report a failure.
     *
     * Always logs as well. The console line is what makes a bug report
     * actionable, and several of these paths are reachable in a call with no UI
     * in front of the user at all.
     */
    fun error(
        text: String,
        subsystem: String = "UI",
        retryLabel: String? = null,
        retry: (() -> Unit)? = null
    ): Long {
        OrbitLog.e(subsystem, text)
        return push(
            OrbitNotice(
                id = nextId.getAndIncrement(),
                kind = OrbitNotice.Kind.Error,
                text = text,
                retryLabel = retryLabel,
                retry = retry,
                autoDismissMs = if (retry != null) {
                    OrbitNotice.ACTIONABLE_DISMISS_MS
                } else {
                    OrbitNotice.DEFAULT_DISMISS_MS
                },
                // An operation you did not ask for (a membership update, a
                // redaction from a sync) can be reported and forgotten. One you
                // *did* ask for must be acknowledged or retried.
                mustActMs = if (retry != null) OrbitNotice.ACTIONABLE_DISMISS_MS else null
            )
        )
    }

    /** Report something the user should know that is not a failure. */
    fun info(text: String, subsystem: String = "UI"): Long {
        OrbitLog.d(subsystem, text)
        return push(
            OrbitNotice(
                id = nextId.getAndIncrement(),
                kind = OrbitNotice.Kind.Info,
                text = text,
                autoDismissMs = OrbitNotice.DEFAULT_DISMISS_MS
            )
        )
    }

    /**
     * Report a failure from a `Result` in one call.
     *
     * Returns true on success so a call site reads as a guard:
     *
     * ```
     * repository.deleteMessage(roomId, eventId).onFailureOrNotify("Message", "Delete") { retry }
     * ```
     */
    fun <T> Result<T>.onFailureOrNotify(
        what: String,
        subsystem: String = "UI",
        retry: (() -> Unit)? = null
    ): Result<T> {
        if (isFailure) {
            error("$what failed: ${exceptionOrNull()?.message ?: "unknown error"}", subsystem, "Retry", retry)
        }
        return this
    }

    /**
     * Run a suspending block, report a failure, and **rethrow cancellation**.
     *
     * ## Why this exists instead of `runCatching { … }.onFailureOrNotify(…)`
     *
     * `runCatching` catches `Throwable`, and `CancellationException` is a
     * `Throwable`. So it swallows coroutine cancellation — which does two
     * things, one of them serious:
     *
     * 1. **It lies to the user.** `CallManager` deliberately cancels the
     *    membership-leave job after 1500 ms (`withTimeoutOrNull { leave.cancelAndJoin() }`).
     *    Every ordinary hangup therefore produced two user-visible error
     *    notices reading "Call end failed: StandaloneCoroutine was cancelled" —
     *    *after* the same write had already logged `status=200`. Confirmed in a
     *    real run, not theorised.
     * 2. **It breaks structured concurrency.** A cancelled coroutine that
     *    swallows its cancellation keeps executing instead of unwinding, so it
     *    runs past the point its caller believed it had stopped.
     *
     * The rule: never wrap a suspending call in `runCatching` on a path that can
     * be cancelled. Use this.
     */
    inline fun <T> notifying(
        what: String,
        subsystem: String = "UI",
        retryLabel: String? = null,
        noinline retry: (() -> Unit)? = null,
        block: () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
            // Not a failure. Rethrown so the caller's scope unwinds normally.
            throw cancellation
        } catch (t: Throwable) {
            error("$what failed: ${t.message ?: "unknown error"}", subsystem, retryLabel, retry)
            Result.failure(t)
        }
    }

    fun dismiss(id: Long) {
        _notices.update { list -> list.filterNot { it.id == id } }
    }

    fun clear() {
        _notices.value = emptyList()
    }

    /**
     * Replace an existing notice instead of stacking a duplicate.
     *
     * A retry that fails again should not produce "Delete failed" three times
     * for one delete attempt.
     */
    private fun push(notice: OrbitNotice): Long {
        _notices.update { list ->
            if (list.size >= MAX_VISIBLE) list.drop(list.size - MAX_VISIBLE + 1) + notice else list + notice
        }
        return notice.id
    }

    /**
     * Cap the stack. Unbounded, a failing poll (membership, presence) will
     * paper over the entire window with the same message.
     */
    const val MAX_VISIBLE = 4
}
