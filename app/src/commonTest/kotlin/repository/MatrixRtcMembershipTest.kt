package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An `m.call.member` event is a MEMBERSHIP RECORD, not a presence signal.
 *
 * Two writers maintain the participant roster -- the 3-second poller and the
 * sync collector -- and they previously had separate copies of these rules.
 * The sync copy had no expiry check, so a lapsed membership from a call that
 * ended hours ago could repopulate a roster the poller had just pruned, putting
 * people on screen who were not in the call.
 */
class MatrixRtcMembershipTest {

    private val now = System.currentTimeMillis()
    private val TWELVE_HOURS = 43_200_000L

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test
    fun `a membership inside its window counts`() {
        val content = obj(
            """{"membershipID":"@a:b:D","device_id":"D","application":"m.call",
               "m.call.intent":"audio","created_ts":${now - 1_000},"expires":$TWELVE_HOURS}"""
        )
        assertTrue(MatrixRtcMembership.isCurrentlyValid(content))
    }

    @Test
    fun `a lapsed membership does not count`() {
        // This is the bug: nobody publishes a leave when the window closes.
        val content = obj(
            """{"membershipID":"@a:b:D","device_id":"D","application":"m.call",
               "m.call.intent":"audio","created_ts":${now - TWELVE_HOURS - 1_000},"expires":$TWELVE_HOURS}"""
        )
        assertFalse(
            MatrixRtcMembership.isCurrentlyValid(content),
            "an expired membership was treated as live"
        )
    }

    @Test
    fun `an explicit leave never counts, even with a live window`() {
        val content = obj(
            """{"membership":"leave","membershipID":"@a:b:D","device_id":"D",
               "created_ts":${now - 1_000},"expires":$TWELVE_HOURS}"""
        )
        assertFalse(MatrixRtcMembership.isCurrentlyValid(content))
    }

    @Test
    fun `the nested memberships array is filtered per entry`() {
        val content = obj(
            """{"memberships":[
                 {"membership":"join","membershipID":"@a:b:D1","device_id":"D1",
                  "created_ts":${now - 1_000},"expires":$TWELVE_HOURS},
                 {"membership":"join","membershipID":"@a:b:D2","device_id":"D2",
                  "created_ts":${now - TWELVE_HOURS - 1_000},"expires":$TWELVE_HOURS}
               ]}"""
        )
        val valid = MatrixRtcMembership.activeUnexpiredMemberships(content)
        assertEquals(1, valid.size, "the expired entry was not dropped")
        assertEquals("@a:b:D1", (valid[0]["membershipID"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `a bare event with no membership shape is not a member event`() {
        val content = obj("""{"unrelated":"value"}""")
        assertFalse(MatrixRtcMembership.isCurrentlyValid(content))
    }

    @Test
    fun `an event with no timestamps is treated as live`() {
        // Some clients omit created_ts/expires. Assuming "live" is the safe
        // direction: it can briefly over-count, but it cannot hide a real
        // participant.
        val content = obj("""{"membershipID":"@a:b:D","device_id":"D"}""")
        assertTrue(MatrixRtcMembership.isCurrentlyValid(content))
    }

    @Test
    fun `expiry countdown is reported and clamps to zero`() {
        val live = obj(
            """{"membershipID":"@a:b:D","device_id":"D","created_ts":${now},"expires":60000}"""
        )
        val remaining = MatrixRtcMembership.expiresRemainingMs(live)
        assertTrue(remaining in 1..60_000, "expected ~60s remaining, got $remaining")

        val dead = obj(
            """{"membershipID":"@a:b:D","device_id":"D","created_ts":${now - 1000},"expires":1000}"""
        )
        assertEquals(0L, MatrixRtcMembership.expiresRemainingMs(dead))
    }

    // ── Current-call gating ──────────────────────────────────────────────────
    //
    // The window above is TWELVE hours because that is roughly what real clients
    // publish. That is the whole problem: a membership from a call that ended an
    // hour ago is still comfortably "unexpired", and a room keeps one state
    // event per device per call forever. Expiry alone therefore cannot answer
    // "is this person in the call happening now". These tests pin the second
    // half of the rule.

    @Test
    fun `a membership older than the current call is rejected`() {
        // Call started an hour ago; this record was written two hours ago, so it
        // belongs to the PREVIOUS call and is still inside its 12h window.
        val callStartedAt = now - 3_600_000L
        val content = obj(
            """{"membershipID":"@a:b:D","device_id":"D","created_ts":${now - 7_200_000},"expires":$TWELVE_HOURS}"""
        )
        assertTrue(
            MatrixRtcMembership.isCurrentlyValid(content, minCreatedTs = null),
            "without a known call start this is the expiry-only behaviour"
        )
        assertFalse(
            MatrixRtcMembership.isCurrentlyValid(content, minCreatedTs = callStartedAt),
            "a lapsed-window record from the previous call was still counted"
        )
    }

    @Test
    fun `a membership newer than the current call is accepted`() {
        val callStartedAt = now - 600_000L
        val content = obj(
            """{"membershipID":"@a:b:D","device_id":"D","created_ts":${now - 30_000},"expires":$TWELVE_HOURS}"""
        )
        assertTrue(MatrixRtcMembership.isCurrentlyValid(content, callStartedAt))
    }

    @Test
    fun `clock skew does not drop a genuine participant`() {
        // A locally-generated membership can land a hair BEFORE the definition
        // event that started the call was committed by the server.
        val callStartedAt = now
        val content = obj(
            """{"membershipID":"@a:b:D","device_id":"D","created_ts":${now - 5_000},"expires":$TWELVE_HOURS}"""
        )
        assertTrue(
            MatrixRtcMembership.isCurrentlyValid(content, callStartedAt),
            "a 5s pre-call timestamp was rejected; the skew allowance is ${MatrixRtcMembership.CALL_START_SKEW_MS}ms"
        )
    }

    @Test
    fun `call-start gating filters the nested memberships array per entry`() {
        val callStartedAt = now - 600_000L
        val content = obj(
            """{"memberships":[
                 {"membership":"join","membershipID":"@a:b:D1","device_id":"D1",
                  "created_ts":${now - 7_200_000},"expires":$TWELVE_HOURS},
                 {"membership":"join","membershipID":"@a:b:D2","device_id":"D2",
                  "created_ts":${now - 30_000},"expires":$TWELVE_HOURS}
               ]}"""
        )
        val valid = MatrixRtcMembership.activeUnexpiredMemberships(content, callStartedAt)
        assertEquals(1, valid.size, "the previous call's membership was not dropped")
        assertEquals("@a:b:D2", (valid[0]["membershipID"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `an undated membership is not dropped by call-start gating`() {
        // No created_ts means we cannot date it, so we cannot blame it on an
        // older call either. Same safe direction as the expiry-only case.
        val callStartedAt = now - 600_000L
        val content = obj("""{"membershipID":"@a:b:D","device_id":"D"}""")
        assertTrue(MatrixRtcMembership.isCurrentlyValid(content, callStartedAt))
    }

    @Test
    fun `a definition event with no usable timestamp yields no call start`() {
        val definition = obj("""{"version":"1","lifetime":3600000}""")
        assertEquals(null, MatrixRtcMembership.callStartFromDefinition(definition, originServerTs = null))
        assertEquals(null, MatrixRtcMembership.callStartFromDefinition(definition, originServerTs = 0L))
        assertEquals(
            1_700_000_000_000L,
            MatrixRtcMembership.callStartFromDefinition(definition, originServerTs = 1_700_000_000_000L)
        )
    }
}
