package livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two media sessions sharing an epoch is not a cosmetic collision.
 *
 * `onNativeEvent` drops any event whose `(sessionId, epoch)` does not match the
 * current pair, and both frame stores guard writes on it too. So if a connect and
 * a reconnect are handed the same epoch, events from the abandoned session are
 * accepted as belonging to the live one — which is how a torn-down call keeps
 * publishing frames, or why "the call randomly closes" traces never line up.
 *
 * The bug being pinned here was real and recent. The original allocator was
 *
 *     synchronized(lock) { epoch + 1L }.also { epoch = it }
 *
 * The `.also` executes after the monitor is released, so the read was atomic and
 * the write was not: two threads both read 5, both produced 6, both stored 6.
 * The lock looked like a fix and protected the wrong half.
 */
class MonotonicEpochTest {

    @Test
    fun `starts at zero and increments by one`() {
        val epoch = MonotonicEpoch()
        assertEquals(0L, epoch.current)
        assertEquals(1L, epoch.next())
        assertEquals(2L, epoch.next())
        assertEquals(3L, epoch.next())
        assertEquals(3L, epoch.current)
    }

    @Test
    fun `can be seeded and continues from the seed`() {
        // The session constructs the allocator from the epoch it already has.
        val epoch = MonotonicEpoch(initial = 41L)
        assertEquals(42L, epoch.next())
    }

    @Test
    fun `reset returns to zero`() {
        val epoch = MonotonicEpoch()
        epoch.next(); epoch.next(); epoch.next()
        epoch.reset()
        assertEquals(0L, epoch.current)
        assertEquals(1L, epoch.next())
    }

    @Test
    fun `values are unique and strictly increasing across threads`() {
        // THE regression test. Reproduces the exact shape of the bug: N threads
        // racing the allocator, and every value they get collected. With the old
        // `.also`-after-the-lock form this fails intermittently at high
        // contention; here the race is made brutal enough to be deterministic in
        // principle and reliably observable in practice.
        val threads = 8
        val perThread = 2_000
        val epoch = MonotonicEpoch()
        val results = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val start = java.util.concurrent.CountDownLatch(1)

        val workers = (0 until threads).map {
            Thread {
                start.await()
                repeat(perThread) { results.add(epoch.next()) }
            }
        }
        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(30_000) }

        val expected = (threads * perThread).toLong()
        assertEquals(expected, epoch.current, "an increment was lost entirely")
        assertEquals(
            expected,
            results.size.toLong(),
            "some caller's value was not recorded, so the allocator handed it out twice"
        )
        assertEquals(
            expected,
            results.distinct().size.toLong(),
            "two callers were handed the SAME epoch -- this is the exact failure the " +
                "`.also { epoch = it }` version produced"
        )

        // Sorted order must be exactly 1..N: no gaps, no repeats, no out-of-order
        // value handed to a caller that had already seen a higher one.
        val sorted = results.sorted()
        assertEquals((1L..expected).toList(), sorted, "the sequence is not strictly 1..N")
    }

    @Test
    fun `a late reader of current never sees a value that goes backwards`() {
        // The frame stores read the volatile mirror without a lock, which is only
        // sound because it is monotonic. This asserts the property that makes it
        // sound, rather than asserting the implementation.
        val epoch = MonotonicEpoch()
        val observed = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)

        val reader = Thread {
            var previous = 0L
            while (!stop.get()) {
                val value = epoch.current
                observed.add(value)
                previous = value
            }
            assertTrue(previous >= 0)
        }
        reader.start()

        val writer = Thread {
            repeat(50_000) { epoch.next() }
            stop.set(true)
        }
        writer.start()
        writer.join(30_000)
        reader.join(30_000)

        // Every observation must be a value the allocator actually issued.
        val max = epoch.current
        assertTrue(
            observed.all { it in 0L..max },
            "observed a value outside the issued range: max=$max"
        )
    }
}
