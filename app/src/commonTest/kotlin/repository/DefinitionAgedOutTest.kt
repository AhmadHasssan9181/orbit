package repository

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The 3-second poller that would not stop.
 *
 * Two real logs from a user's group call contained the same line, repeating every
 * three seconds for the whole session:
 *
 * ```
 * DEBUG [MatrixCallsRepository] roster tick !GfDlxb…: current call began 10871050237ms ago
 * ```
 *
 * 10,871,050 seconds is **125 days**, and in the running instance it accounted for
 * **44.7% of the entire log file** — one dead room, a full `/rooms/{id}/state`
 * download every three seconds, forever.
 *
 * The self-terminating guard reads `callStartFromState`, which returns null once
 * `definitionAgedOut` says the definition is over. And `definitionAgedOut` said
 * never, because the definition carries **no `lifetime` field** to age out
 * against — and the old code treated a missing lifetime as "cannot prove
 * expired", i.e. as immortal.
 *
 * That default was defensible in isolation and wrong in practice. A wrong "expired"
 * makes a live call unjoinable, which is visible immediately. A wrong "not expired"
 * costs unbounded background traffic against someone's homeserver and is invisible.
 * The floor below is 12 hours — far longer than any real call, so being wrong costs
 * nothing — and it only exists to make "no lifetime" stop meaning "forever".
 */
class DefinitionAgedOutTest {

    private val now = 1_800_000_000_000L

    private fun definition(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    /** A live definition: an hour old, declares an hour of lifetime. */
    @Test
    fun aLiveDefinitionWithALifetimeIsNotAgedOut() {
        val content = definition("lifetime" to (60 * 60 * 1000).toString())
        assertFalse(CallStateResolver.definitionAgedOut(content, now - 60_000, now))
    }

    @Test
    fun aDefinitionPastItsOwnLifetimeIsAgedOut() {
        val content = definition("lifetime" to (60 * 60 * 1000).toString())
        assertTrue(
            CallStateResolver.definitionAgedOut(content, now - 7_200_000, now),
            "two hours old against a one-hour lifetime is over"
        )
    }

    /**
     * The regression. No `lifetime`, and 125 days old — the exact shape from the
     * log. This returned false forever, so the poller's stop condition could never
     * be met.
     */
    @Test
    fun anAncientDefinitionWithNoLifetimeIsAgedOut() {
        val content = definition("m.call.member" to "{}") // deliberately no `lifetime`
        val oneHundredTwentyFiveDays = 10_871_050_237L

        assertTrue(
            CallStateResolver.definitionAgedOut(content, now - oneHundredTwentyFiveDays, now),
            "a 125-day-old definition with no lifetime must age out, or the poller never stops"
        )
    }

    /**
     * The other direction, and the reason the floor is 12 hours rather than
     * something short. A definition published moments ago with no lifetime is
     * somebody's live call; ageing it out would make the room unjoinable.
     */
    @Test
    fun aFreshDefinitionWithNoLifetimeIsStillLive() {
        val content = definition("anything" to "value")
        assertFalse(
            CallStateResolver.definitionAgedOut(content, now - 30_000, now),
            "a half-minute-old definition must never be treated as over"
        )
    }

    /** Right up to the floor, still live. One second past it, over. */
    @Test
    fun theNoLifetimeFloorIsGenerousButFinite() {
        val content = definition("x" to "y")
        val twelveHours = 12L * 60L * 60L * 1000L

        assertFalse(CallStateResolver.definitionAgedOut(content, now - twelveHours + 60_000, now))
        assertTrue(CallStateResolver.definitionAgedOut(content, now - twelveHours - 60_000, now))
    }

    /** "Cannot prove" still applies when there is no timestamp to reason about. */
    @Test
    fun aDefinitionWithNoTimestampIsLeftAlone() {
        val content = definition("lifetime" to "3600000")
        assertFalse(CallStateResolver.definitionAgedOut(content, null, now))
        assertFalse(CallStateResolver.definitionAgedOut(content, 0L, now))
        assertFalse(CallStateResolver.definitionAgedOut(content, -1L, now))
    }

    /** A zero lifetime is meaningless rather than "expires immediately". */
    @Test
    fun aZeroLifetimeIsIgnoredRatherThanTreatedAsAlreadyOver() {
        val content = definition("lifetime" to "0")
        assertFalse(
            CallStateResolver.definitionAgedOut(content, now - 999_999_999L, now),
            "lifetime<=0 cannot be aged against; 'cannot prove' still wins here"
        )
    }
}
