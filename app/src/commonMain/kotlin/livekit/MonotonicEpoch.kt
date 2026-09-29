package livekit

/**
 * Allocates strictly increasing session epochs.
 *
 * ## Why this is a class and not three lines in the session
 *
 * An epoch identifies one media session, and two of them being equal breaks the
 * stale-event filter in `onNativeEvent` and the epoch guard in the frame stores:
 * events from the abandoned session are accepted as belonging to the new one.
 *
 * The original code was a field plus a method that *looked* like it fixed the
 * race and did not:
 *
 * ```kotlin
 * private val epochLock = Any()
 * private fun nextEpoch(): Long = synchronized(epochLock) { epoch + 1L }.also { epoch = it }
 * ```
 *
 * `.also` runs after the monitor is released. Two threads both read 5 inside the
 * lock, both computed 6, and both stored 6 — so the increment was still a
 * lost-update read-modify-write, and the lock made the *read* atomic while
 * leaving the *write*, which is the half that mattered, unprotected.
 *
 * Extracting it means the invariant can be tested directly and concurrently,
 * which is otherwise impossible: the real field is private to a class whose
 * every path needs a live native bridge.
 */
class MonotonicEpoch(initial: Long = 0L) {

    private val lock = Any()

    /** Readable without the lock. Only ever increases, so a stale read is still valid. */
    @Volatile
    var current: Long = initial
        private set

    /**
     * Returns the next epoch. Safe to call from any number of threads: no two
     * calls can return the same value, and the returned value is always greater
     * than every value previously returned.
     */
    fun next(): Long = synchronized(lock) {
        val next = current + 1L
        // Store INSIDE the critical section. This is the whole point of the
        // class; see the note above. Verified by
        // `values are unique and strictly increasing across threads`, which fails
        // against the `.also`-after-the-lock form.
        current = next
        next
    }

    /**
     * Resets for a fresh session. Only safe while no allocator call is in flight —
     * the session calls it from `connect`, which is itself serialised against
     * `disconnect`.
     */
    fun reset() {
        synchronized(lock) { current = 0L }
    }
}
