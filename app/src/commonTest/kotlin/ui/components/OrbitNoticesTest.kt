package ui.components

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
// Member extension declared inside the object: needs its own import even though
// the object is in the same package.
import ui.components.OrbitNotices.onFailureOrNotify

/**
 * The notice store is the app's only user-visible channel for "that did not
 * work", and it replaced fifteen call sites that were throwing a `Result` away.
 *
 * These tests are here because the store is the one piece of this work that
 * *is* pure: it holds no clock, no scope and no Android types, so unlike the
 * frame-store commit lock and the COM de-duplication it can actually be pinned.
 *
 * The property that matters most is the last one: a retryable failure must stay
 * up long enough to be acted on, and must not be silently swallowed by the
 * visible cap when several failures stack up.
 */
class OrbitNoticesTest {

    @AfterTest
    fun clear() {
        OrbitNotices.clear()
    }

    @Test
    fun `a reported failure becomes a visible error notice`() {
        OrbitNotices.error("Message not sent")

        val notices = OrbitNotices.notices.value
        assertEquals(1, notices.size)
        assertEquals(OrbitNotice.Kind.Error, notices[0].kind)
        assertEquals("Message not sent", notices[0].text)
        assertFalse(notices[0].actionable, "a plain report offers no retry")
    }

    @Test
    fun `ids are unique so one dismissal cannot drop two notices`() {
        // Kept within MAX_VISIBLE so nothing is evicted by the cap and the
        // dismissal is actually testing dismissal.
        val ids = (1..3).map { OrbitNotices.error("failure $it") }

        assertEquals(3, ids.toSet().size, "ids must not repeat")
        OrbitNotices.dismiss(ids[1])
        assertEquals(listOf(ids[0], ids[2]), OrbitNotices.notices.value.map { it.id })
        assertFalse(OrbitNotices.notices.value.any { it.id == ids[1] })
    }

    @Test
    fun `ids stay unique across more pushes than the stack holds`() {
        // The cap evicts, so this cannot assert on the visible list — it asserts
        // that eviction never hands the same id to two different notices, which
        // is what would make a dismissal remove the wrong one.
        val ids = (1..OrbitNotices.MAX_VISIBLE * 4).map { OrbitNotices.error("x$it") }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(OrbitNotices.MAX_VISIBLE, OrbitNotices.notices.value.size)
        assertEquals(
            OrbitNotices.MAX_VISIBLE,
            OrbitNotices.notices.value.map { it.id }.toSet().size,
            "no two visible notices may share an id"
        )
    }

    @Test
    fun `dismiss removes only the requested notice`() {
        val a = OrbitNotices.error("first")
        val b = OrbitNotices.error("second")
        val c = OrbitNotices.error("third")

        OrbitNotices.dismiss(b)

        assertEquals(listOf(a, c), OrbitNotices.notices.value.map { it.id })
    }

    @Test
    fun `dismissing an unknown id is a no-op`() {
        OrbitNotices.error("only one")
        OrbitNotices.dismiss(999_999L)

        assertEquals(1, OrbitNotices.notices.value.size)
    }

    @Test
    fun `a retryable failure carries the action and outlives a plain one`() {
        var retried = false
        OrbitNotices.error("Message not sent", retry = { retried = true })

        val notice = OrbitNotices.notices.value.single()
        assertTrue(notice.actionable)
        assertNotNull(notice.retry)

        // Long enough to notice and click, and pinned so it cannot expire
        // unread while a destructive action went unconfirmed.
        assertEquals(
            OrbitNotice.ACTIONABLE_DISMISS_MS,
            notice.autoDismissMs,
            "a retryable failure must not vanish as fast as a status update"
        )
        assertEquals(OrbitNotice.ACTIONABLE_DISMISS_MS, notice.mustActMs)

        notice.retry!!.invoke()
        assertTrue(retried)
    }

    @Test
    fun `a plain failure expires quickly and is not pinned`() {
        OrbitNotices.error("Presence update")

        val notice = OrbitNotices.notices.value.single()
        assertEquals(OrbitNotice.DEFAULT_DISMISS_MS, notice.autoDismissMs)
        assertNull(notice.mustActMs, "nothing to act on, so nothing to hold open")
    }

    @Test
    fun `the stack is capped so a failing poll cannot paper over the window`() {
        // A membership or presence poll that fails every few seconds would
        // otherwise stack unbounded and bury the message list.
        repeat(OrbitNotices.MAX_VISIBLE * 3) { OrbitNotices.error("poll failure $it") }

        val notices = OrbitNotices.notices.value
        assertEquals(OrbitNotices.MAX_VISIBLE, notices.size)
        // The newest survives; the oldest is the one dropped.
        assertEquals("poll failure ${OrbitNotices.MAX_VISIBLE * 3 - 1}", notices.last().text)
    }

    @Test
    fun `onFailureOrNotify reports a failure and passes success through untouched`() {
        val boom = IllegalStateException("homeserver unreachable")

        Result.failure<String>(boom).onFailureOrNotify("Delete", "ChatDelete")
        assertEquals(1, OrbitNotices.notices.value.size)
        assertTrue(OrbitNotices.notices.value.single().text.contains("Delete"))
        assertTrue(
            OrbitNotices.notices.value.single().text.contains("homeserver unreachable"),
            "the underlying reason must survive, not just 'it failed'"
        )

        OrbitNotices.clear()
        Result.success("txn-1").onFailureOrNotify("Delete", "ChatDelete")
        assertTrue(OrbitNotices.notices.value.isEmpty(), "success must not raise a notice")
    }

    @Test
    fun `onFailureOrNotify returns the receiver so it can be chained`() {
        val original: Result<Int> = Result.success(7)
        assertEquals(original, original.onFailureOrNotify("Anything"))
    }

    @Test
    fun `onFailureOrNotify attaches the retry it was given`() {
        var attempts = 0

        // First attempt fails, so a notice appears carrying the retry closure.
        Result.failure<Unit>(IllegalStateException("offline"))
            .onFailureOrNotify("Send", "ChatSend") { attempts++ }

        val notice = OrbitNotices.notices.value.single()
        assertTrue(notice.actionable, "a retry was supplied, so the notice must be actionable")

        notice.retry!!.invoke()
        assertEquals(1, attempts)
    }

    @Test
    fun `onFailureOrNotify without a retry is not actionable`() {
        // Presence and status updates report but do not offer Retry: nagging
        // someone to retry something they did not ask for is worse than the
        // status being briefly wrong.
        Result.failure<Unit>(IllegalStateException("offline"))
            .onFailureOrNotify("Presence update", "Presence")

        assertFalse(OrbitNotices.notices.value.single().actionable)
    }

    @Test
    fun `info is reported as information not as an error`() {
        OrbitNotices.info("Avatar updated")

        val notice = OrbitNotices.notices.value.single()
        assertEquals(OrbitNotice.Kind.Info, notice.kind)
        assertFalse(notice.actionable)
    }

    @Test
    fun `clear empties the stack`() {
        repeat(5) { OrbitNotices.error("x$it") }
        OrbitNotices.clear()
        assertTrue(OrbitNotices.notices.value.isEmpty())
    }
}
