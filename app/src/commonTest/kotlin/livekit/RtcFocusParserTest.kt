package livekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtcFocusParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun obj(s: String) = json.parseToJsonElement(s).jsonObject

    @Test
    fun elementDefinitionPrefixedFociResolvesLiveKit() {
        val content = obj(
            """
            {
              "m.type": "m.call",
              "m.intent": "m.ring",
              "m.foci_preferred": [
                { "type": "livekit",
                  "livekit_service_url": "https://livekit-jwt.call.matrix.org",
                  "livekit_alias": "!room:example.org" }
              ]
            }
            """.trimIndent()
        )
        val focus = RtcFocusParser.firstLiveKit(content)
        assertTrue(focus != null, "definition must resolve its livekit focus")
        assertEquals("https://livekit-jwt.call.matrix.org", focus.serviceUrl)
        assertEquals("!room:example.org", focus.alias)
    }

    @Test
    fun elementMembershipFlatContentResolvesLiveKit() {
        // Shape per docs/planning/ELEMENT_CALL_ENDPOINTS.md 3.2: flat membership content with
        // focus_active + foci_preferred side by side.
        val content = obj(
            """
            {
              "application": "m.call",
              "call_id": "",
              "scope": "m.room",
              "device_id": "DEVICE123",
              "membershipID": "@alice:example.org:DEVICE123",
              "expires": 14400000,
              "focus_active": { "type": "livekit", "focus_selection": "oldest_membership" },
              "foci_preferred": [
                { "type": "livekit", "livekit_service_url": "https://sfu.example.org" }
              ],
              "created_ts": 1755900000000
            }
            """.trimIndent()
        )
        val focus = RtcFocusParser.firstLiveKit(content)
        assertTrue(focus != null, "membership must resolve its livekit focus")
        assertEquals("https://sfu.example.org", focus.serviceUrl)
    }

    @Test
    fun legacyUnprefixedFociKeyResolves() {
        val content = obj(
            """{ "foci_preferred": [ { "type": "livekit", "livekit_service_url": "https://legacy.example.org" } ] }"""
        )
        assertEquals("https://legacy.example.org", RtcFocusParser.firstLiveKit(content)?.serviceUrl)
    }

    @Test
    fun fociActiveFallbackResolves() {
        val content = obj(
            """{ "foci_active": [ { "type": "livekit", "livekit_service_url": "https://active.example.org" } ] }"""
        )
        assertEquals("https://active.example.org", RtcFocusParser.firstLiveKit(content)?.serviceUrl)
    }

    @Test
    fun nestedFociObjectResolves() {
        val content = obj(
            """
            {
              "org.matrix.msc4143.rtc_foci": {
                "foci": [
                  { "type": "livekit", "livekit_service_url": "https://nested.example.org" }
                ]
              }
            }
            """.trimIndent()
        )
        assertEquals("https://nested.example.org", RtcFocusParser.firstLiveKit(content)?.serviceUrl)
    }

    @Test
    fun nonLiveKitFocusReturnsNull() {
        val content = obj(
            """
            {
              "m.foci_preferred": [
                { "type": "jitsi", "domain": "jitsi.riot.im" },
                { "type": "livekit" }
              ]
            }
            """.trimIndent()
        )
        assertNull(RtcFocusParser.firstLiveKit(content))
    }

    @Test
    fun terminatedDefinitionsAreDetected() {
        assertTrue(MatrixRtcCalls.isDefinitionTerminated(obj("""{ "m.intent": "m.terminated" }""")))
        assertTrue(MatrixRtcCalls.isDefinitionTerminated(obj("""{ "m.terminated": true }""")))
        assertTrue(MatrixRtcCalls.isDefinitionTerminated(obj("{}")))
        assertFalse(
            MatrixRtcCalls.isDefinitionTerminated(obj("""{ "m.intent": "m.ring", "m.foci_preferred": [] }"""))
        )
    }

    @Test
    fun bothNamespacesAreFrozenInTheContract() {
        assertEquals(listOf("m.call", "org.matrix.msc3401.call"), MatrixRtcCalls.DEFINITION_TYPES)
        assertEquals(
            listOf("org.matrix.msc3401.call.member", "m.call.member"),
            MatrixRtcCalls.MEMBER_TYPES
        )
    }
}
