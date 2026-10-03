package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import livekit.MatrixRtcCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Does this room have a call right now?"
 *
 * These rules are pinned here because the two ways they were wrong are invisible
 * until you are in a room that has hosted a call in the past -- which is every
 * room in a long-lived server. Both mistakes produced the same symptom the user
 * reported: a room with nobody in it showing a live-call banner and a member list
 * of everyone who had ever been in it.
 */
class CallStateResolverTest {

    private val sfu = "https://livekit.example.test"
    private val now = System.currentTimeMillis()
    private val TWELVE_HOURS = 43_200_000L

    private fun entry(type: String, content: String, stateKey: String = "") =
        CallStateEntry(type, stateKey, Json.parseToJsonElement(content) as JsonObject, originServerTs = now)

    /** A live definition carrying a LiveKit focus, which is the healthy case. */
    private fun liveDefinition(callId: String = "m.call") = entry(
        "m.call",
        """{"version":"1","lifetime":3600000,"foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}""",
        callId
    )

    /** A definition a client explicitly marked finished. */
    private fun terminatedDefinition() = entry(
        "m.call",
        """{"version":"1","lifetime":3600000,"m.terminated":true,"foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}""",
        "m.call"
    )

    /** A membership record from a call that has been over since `createdAt`. */
    private fun staleMembership(sinceMs: Long = 7_200_000) = entry(
        "m.call.member",
        """{"membershipID":"@a:b:D","device_id":"D","application":"m.call",
            "created_ts":${now - sinceMs},"expires":$TWELVE_HOURS,
            "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}"""
    )

    private fun freshMembership() = entry(
        "m.call.member",
        """{"membershipID":"@a:b:D","device_id":"D","application":"m.call",
            "created_ts":${now - 30_000},"expires":$TWELVE_HOURS,
            "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}"""
    )

    private fun resolve(vararg entries: CallStateEntry, nowMs: Long = now) =
        CallStateResolver.resolve(
            entries = entries.toList(),
            nowMs = nowMs,
            liveKitFocusOf = { focus ->
                // Stand-in for RtcFocusParser: pick up the declared service url.
                runCatching {
                    (focus["foci"] as? kotlinx.serialization.json.JsonArray)
                        ?.firstOrNull()?.let { it as JsonObject }
                        ?.get("livekit_service_url")
                        ?.let { it as kotlinx.serialization.json.JsonPrimitive }
                        ?.content
                }.getOrNull()
            }
        )

    // ── The reported bug ──────────────────────────────────────────────────────

    @Test
    fun `leftover memberships do not resurrect a terminated call`() {
        // THE bug. A room whose call ended leaves an `m.call.member` record
        // behind forever. Believing it made Orbit report a live call and list the
        // old participants as if they were present.
        val verdict = resolve(terminatedDefinition(), staleMembership())
        assertFalse(verdict.exists, "a terminated call was reported as live")
        assertTrue(
            verdict.reason?.contains("terminated") == true,
            "expected the reason to name the termination, got ${verdict.reason}"
        )
    }

    @Test
    fun `an expired membership with no definition is not a call`() {
        // Past its window AND no definition to corroborate it. This is the
        // strongest no-definition case available.
        val expired = entry(
            "m.call.member",
            """{"membershipID":"@a:b:D","device_id":"D","application":"m.call",
                "created_ts":${now - TWELVE_HOURS - 1_000},"expires":$TWELVE_HOURS,
                "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}"""
        )
        assertFalse(resolve(expired).exists)
    }

    @Test
    fun `an unexpired membership with no definition is believed, and that is the honest limit`() {
        // DOCUMENTED LIMITATION, not an oversight.
        //
        // With no `m.call` definition there is no timestamp anywhere saying when
        // the call started, so the membership's own window is the only evidence
        // available -- and per the spec that window IS the presence signal.
        // Clients that publish no definition events (the reason this fallback
        // exists at all) give us nothing better, so we believe them.
        //
        // The consequence is bounded and worth stating plainly: on such a server a
        // room can look like it has a call for as long as the membership window
        // runs. The rule that fixes the reported bug -- a TERMINATED definition
        // overriding leftover memberships -- needs the definition event to fire,
        // and matrix.org publishes one, which is where the ghost rosters came from.
        val verdict = resolve(staleMembership())
        assertTrue(
            verdict.exists,
            "an unexpired membership with no definition must still be believed; " +
                "the alternative is making such calls unjoinable"
        )
        assertEquals(CallStateResolver.SOURCE_MEMBER_EVENT, verdict.source)
    }

    @Test
    fun `an explicit leave never counts`() {
        val leave = entry(
            "m.call.member",
            """{"membership":"leave","membershipID":"@a:b:D","device_id":"D",
                "created_ts":${now - 1_000},"expires":$TWELVE_HOURS,
                "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}"""
        )
        assertFalse(resolve(leave).exists)
    }

    // ── The healthy cases must not regress ───────────────────────────────────

    @Test
    fun `a live definition resolves with its focus`() {
        val verdict = resolve(liveDefinition())
        assertTrue(verdict.exists)
        assertEquals(sfu, verdict.serviceUrl)
        assertEquals(CallStateResolver.SOURCE_ALL_STATE_DEFINITION, verdict.source)
    }

    @Test
    fun `a fresh membership alone still counts when no definition exists`() {
        // Some deployments publish membership records without a definition event.
        // The fallback exists for them and must keep working.
        val verdict = resolve(freshMembership())
        assertTrue(verdict.exists, "the member-event fallback stopped working")
        assertEquals(CallStateResolver.SOURCE_MEMBER_EVENT, verdict.source)
        assertEquals(sfu, verdict.serviceUrl)
    }

    @Test
    fun `a terminated definition alongside a fresh call keeps the fresh call`() {
        // A room can hold the OLD call's terminated definition under its state
        // key AND a new call's live one. Suppressing on "saw a termination"
        // alone would kill a call that is genuinely running.
        val verdict = resolve(terminatedDefinition(), liveDefinition("newcall"))
        assertTrue(verdict.exists, "a live call was suppressed by an old termination")
        assertEquals(sfu, verdict.serviceUrl)
    }

    @Test
    fun `a live definition without a declared focus is still an active call`() {
        // Element-created definitions sometimes omit the focus here. The call is
        // running even though we cannot read its SFU from the definition.
        val focusless = entry("m.call", """{"version":"1","lifetime":3600000}""", "m.call")
        val verdict = resolve(focusless)
        assertTrue(verdict.exists, "a focusless live definition was treated as over")
        assertNull(verdict.serviceUrl)
        assertEquals(CallStateResolver.SOURCE_DEFINITION_NO_FOCUS, verdict.source)
    }

    @Test
    fun `a live definition with no focus outranks stale memberships`() {
        val focusless = entry("m.call", """{"version":"1","lifetime":3600000}""", "m.call")
        val verdict = resolve(terminatedDefinition(), focusless, staleMembership())
        assertTrue(verdict.exists)
        assertEquals(CallStateResolver.SOURCE_DEFINITION_NO_FOCUS, verdict.source)
    }

    @Test
    fun `an empty room reports no call and says why`() {
        val verdict = resolve()
        assertFalse(verdict.exists)
        assertEquals("no MatrixRTC state", verdict.reason)
    }

    @Test
    fun `unrelated state events are ignored`() {
        val verdict = resolve(
            entry("m.room.name", """{"name":"general"}"""),
            entry("m.room.power_levels", """{"users":{}}"""),
            entry("m.mentions", """{"user_ids":[]}""")
        )
        assertFalse(verdict.exists)
    }

    @Test
    fun `the call id is read from the content, then the state key`() {
        val withContent = entry(
            "m.call.member",
            """{"call_id":"from-content","device_id":"D","created_ts":${now - 5_000},"expires":$TWELVE_HOURS,
                "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}"""
        )
        assertEquals("from-content", resolve(withContent).callId)

        // Real key format is `_<userId>_<deviceId>[_<callId>]`, and the device id
        // is taken by splitting on the LAST underscore -- splitting on the colon
        // lands inside the mxid and yields a truncated user id.
        val withStateKey = entry(
            "m.call.member",
            """{"device_id":"D","created_ts":${now - 5_000},"expires":$TWELVE_HOURS,
                "foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}""",
            "_@a:b:server_DEVICEID"
        )
        assertEquals("DEVICEID", resolve(withStateKey).callId)
    }

    // ── Lifetime: the other way a call ends ──────────────────────────────────

    @Test
    fun `a definition past its lifetime is not a call`() {
        // The shape seen in the running app's log: a definition that is NOT
        // terminated, declares no LiveKit focus, and is years old. A client that
        // crashed or lost connectivity at hangup never publishes `m.terminated`,
        // so `lifetime` is the only thing that ever ends it.
        val agedOut = entry(
            "m.call",
            """{"version":"1","lifetime":3600000}""",
            "m.call"
        ).copy(originServerTs = now - 7_200_000)

        assertFalse(resolve(agedOut).exists, "a definition past its lifetime was reported as a live call")
    }

    @Test
    fun `a definition inside its lifetime is still a call`() {
        val fresh = entry(
            "m.call",
            """{"version":"1","lifetime":3600000,"foci":[{"type":"livekit","livekit_service_url":"$sfu"}]}""",
            "m.call"
        ).copy(originServerTs = now - 60_000)
        val verdict = resolve(fresh)
        assertTrue(verdict.exists, "a call 1 minute into a 1 hour lifetime was reported as over")
        assertEquals(sfu, verdict.serviceUrl)
    }

    @Test
    fun `an aged-out definition overrides leftover memberships`() {
        // The reported bug, in its real form: nobody terminated the definition, so
        // before the lifetime rule this room looked alive forever.
        val agedOut = entry(
            "m.call",
            """{"version":"1","lifetime":3600000}""",
            "m.call"
        ).copy(originServerTs = now - 86_400_000)

        val verdict = resolve(agedOut, staleMembership())
        assertFalse(verdict.exists, "an expired call with leftover memberships was reported live")
        assertTrue(
            verdict.reason?.contains("lifetime") == true,
            "expected the reason to cite the lifetime, got ${verdict.reason}"
        )
    }

    @Test
    fun `a fresh call outranks an old expired definition`() {
        val old = entry("m.call", """{"version":"1","lifetime":3600000}""", "old")
            .copy(originServerTs = now - 86_400_000)
        val verdict = resolve(old, liveDefinition("newcall").copy(originServerTs = now - 30_000))
        assertTrue(verdict.exists, "an old expired definition suppressed a genuinely live call")
    }

    @Test
    fun `a missing timestamp or an unusable lifetime never invents an expiry`() {
        // "Cannot prove it expired" must not read as expired, or a live call
        // becomes unjoinable whenever a field is spelled unusually.
        //
        // The no-`lifetime` case below is DELIBERATELY no longer in this group. It
        // used to assert that a definition with no lifetime never ages out, on the
        // reasoning that "cannot prove" must not read as "expired". That reasoning
        // cost a 3-second `/rooms/{id}/state` download for the rest of the session,
        // against a definition 125 days old, filling 44.7% of a real user's log
        // file. A missing lifetime is now aged out by a generous 12-hour floor
        // instead -- long enough that no live call is ever affected. See
        // `DefinitionAgedOutTest` for that regression and `definitionAgedOut` for
        // why the asymmetry mattered: a wrong "expired" is a visibly stuck UI, a
        // wrong "not expired" is unbounded background traffic forever.
        assertFalse(
            CallStateResolver.definitionAgedOut(
                Json.parseToJsonElement("""{"lifetime":1}""") as JsonObject,
                originServerTs = now, now = now
            ),
            "no origin_server_ts must mean 'cannot tell', not 'expired'"
        )
        assertFalse(
            CallStateResolver.definitionAgedOut(
                Json.parseToJsonElement("""{"lifetime":0}""") as JsonObject,
                originServerTs = 1, now = now
            ),
            "a zero lifetime must not expire the call"
        )
        assertTrue(
            CallStateResolver.definitionAgedOut(
                Json.parseToJsonElement("""{"lifetime":1000}""") as JsonObject,
                originServerTs = now - 5000, now = now
            )
        )
    }

    @Test
    fun `a live definition with no lifetime is not expired`() {
        // The counterpart to the 12-hour floor, and the reason it is 12 hours: a
        // definition somebody is actually in must never be treated as over, no
        // matter that it carries no lifetime.
        assertFalse(
            CallStateResolver.definitionAgedOut(
                Json.parseToJsonElement("""{"version":"1"}""") as JsonObject,
                originServerTs = now - 60_000, now = now
            ),
            "a definition one minute old with no lifetime is somebody's live call"
        )
    }

    // ── Termination semantics ────────────────────────────────────────────────

    @Test
    fun `an explicitly un-terminated definition is not treated as over`() {
        // `m.terminated: false` means NOT terminated. This predicate used to read
        // "the key is present" as terminated, which made a live call invisible and
        // left the user unable to join a call that was genuinely running.
        val content = Json.parseToJsonElement("""{"m.terminated":false,"version":"1"}""") as JsonObject
        assertFalse(MatrixRtcCalls.isDefinitionTerminated(content))
        assertTrue(
            resolve(entry("m.call", """{"m.terminated":false,"version":"1"}""")).exists,
            "a live call reported as terminated cannot be joined"
        )
    }

    @Test
    fun `both termination spellings are recognised`() {
        // `true`, and the object-with-a-reason form clients actually send.
        for (raw in listOf("""{"m.terminated":true}""", """{"m.terminated":{"reason":"done"}}""")) {
            val content = Json.parseToJsonElement(raw) as JsonObject
            assertTrue(MatrixRtcCalls.isDefinitionTerminated(content), "missed $raw")
            assertFalse(resolve(entry("m.call", raw)).exists, "a terminated call survived $raw")
        }
    }

    @Test
    fun `an empty definition is treated as terminated rather than inventing a call`() {
        assertTrue(MatrixRtcCalls.isDefinitionTerminated(JsonObject(emptyMap())))
    }

    @Test
    fun `a malformed m intent does not throw`() {
        // `jsonPrimitive` on a JsonObject throws. `m.intent` is attacker-shaped
        // input from room state, so the guard has to survive it.
        val content = Json.parseToJsonElement("""{"m.intent":{"nested":"object"}}""") as JsonObject
        assertFalse(MatrixRtcCalls.isDefinitionTerminated(content))
    }

    @Test
    fun `state arrays parse into entries and tolerate junk`() {
        val entries = CallStateResolver.parseStateArray(
            """
            [
              {"type":"m.call","state_key":"m.call","origin_server_ts":1700000000000,
               "content":{"version":"1","m.terminated":true}},
              {"type":"m.call.member","state_key":"_@a:b:D","content":{"device_id":"D"}},
              {"type":"m.room.name","content":{"name":"general"}},
              {"nonsense":true},
              "not even an object"
            ]
            """.trimIndent()
        )
        assertEquals(3, entries.size, "a malformed entry was not skipped")
        assertEquals(1_700_000_000_000L, entries.first().originServerTs)
        // Content-less events become empty objects rather than being dropped, so a
        // membership event with no content still reaches the resolver and is
        // rejected there on its merits.
        assertTrue(entries.all { it.content is JsonObject })
    }

    @Test
    fun `unparseable state yields no entries rather than throwing`() {
        assertEquals(emptyList(), CallStateResolver.parseStateArray("not json at all"))
    }
}
