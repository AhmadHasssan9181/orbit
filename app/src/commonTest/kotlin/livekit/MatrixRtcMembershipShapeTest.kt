package livekit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Freezes the MatrixRTC membership wire shapes Orbit publishes (E3).
 * The legacy Element-mirroring shape is the one that won interop — any diff here
 * must come with a re-test against a real Element client per ELEMENT_CALL_ENDPOINTS.md.
 */
class MatrixRtcMembershipShapeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parsed(payload: kotlinx.serialization.json.JsonObject) =
        json.parseToJsonElement(payload.toString()).jsonObject

    private val base = MatrixRtcMembershipContent.PayloadInputs(
        deviceId = "DEVICE123",
        membershipId = "@bob:example.org:DEVICE123",
        createdTs = 1_724_400_000_000,
        roomId = "!room:example.org",
        defaultLiveKitServiceUrl = "https://livekit-jwt.call.matrix.org",
        jsonCallId = "",
        audio = true,
        video = false,
        legacyElementShape = true,
        videoMembershipMode = "audio-only",
        videoIntent = "audio",
        expiresMs = MatrixRtcMembershipContent.LEGACY_EXPIRES_MS
    )

    @Test
    fun legacyPayloadMirrorsVerifiedElementShape() {
        val payload = MatrixRtcMembershipContent.buildPayload(base)

        assertEquals(
            listOf(
                "application", "call_id", "created_ts", "device_id",
                "expires", "foci_preferred", "focus_active",
                "m.call.intent", "membershipID", "scope"
            ),
            payload.keys.sorted()
        )
        assertEquals("m.call", payload["application"]?.jsonPrimitive?.content)
        assertEquals("", payload["call_id"]?.jsonPrimitive?.content)
        assertEquals("DEVICE123", payload["device_id"]?.jsonPrimitive?.content)
        assertEquals("@bob:example.org:DEVICE123", payload["membershipID"]?.jsonPrimitive?.content)
        assertEquals(1_724_400_000_000L, payload["created_ts"]?.jsonPrimitive?.long)
        assertEquals("audio", payload["m.call.intent"]?.jsonPrimitive?.content)
        assertEquals("m.room", payload["scope"]?.jsonPrimitive?.content)
        assertEquals(43_200_000L, payload["expires"]?.jsonPrimitive?.long)

        val focus = parsed(payload)["foci_preferred"]!!.jsonArray.single().jsonObject
        assertEquals("livekit", focus["type"]?.jsonPrimitive?.content)
        assertEquals("https://livekit-jwt.call.matrix.org", focus["livekit_service_url"]?.jsonPrimitive?.content)
        assertEquals("!room:example.org", focus["livekit_alias"]?.jsonPrimitive?.content)

        val focusActive = parsed(payload)["focus_active"]!!.jsonObject
        assertEquals("livekit", focusActive["type"]?.jsonPrimitive?.content)
        assertEquals("oldest_membership", focusActive["focus_selection"]?.jsonPrimitive?.content)

        assertFalse(payload.containsKey("foci_active"), "legacy mirror shape must not carry modern foci_active")
        assertFalse(payload.containsKey("capabilities"), "legacy mirror shape must not carry capabilities")
    }

    @Test
    fun modernPayloadAddsFociActiveAndCapabilities() {
        val payload = MatrixRtcMembershipContent.buildPayload(
            base.copy(
                legacyElementShape = false,
                videoMembershipMode = "modern-only",
                expiresMs = MatrixRtcMembershipContent.MODERN_EXPIRES_MS
            )
        )

        assertEquals(MatrixRtcMembershipContent.MODERN_EXPIRES_MS, payload["expires"]?.jsonPrimitive?.long)
        val fociActive = parsed(payload)["foci_active"]!!.jsonArray.single().jsonObject
        assertEquals("livekit", fociActive["foci_id"]?.jsonPrimitive?.content)
        assertEquals("livekit", fociActive["type"]?.jsonPrimitive?.content)
        assertEquals("https://livekit-jwt.call.matrix.org", fociActive["livekit_service_url"]?.jsonPrimitive?.content)
        assertEquals("!room:example.org", fociActive["livekit_alias"]?.jsonPrimitive?.content)

        val capabilities = parsed(payload)["capabilities"]!!.jsonObject
        assertTrue(capabilities["audio"]?.jsonPrimitive?.boolean == true)
        assertFalse(capabilities["video"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun capabilitiesVideoModePublishesVideoCapabilityButKeepsLegacyBase() {
        val payload = MatrixRtcMembershipContent.buildPayload(
            base.copy(video = true, videoMembershipMode = "capabilities-video")
        )

        assertEquals("audio", payload["m.call.intent"]?.jsonPrimitive?.content, "legacy shape pins intent to audio")
        assertEquals(MatrixRtcMembershipContent.LEGACY_EXPIRES_MS, payload["expires"]?.jsonPrimitive?.long)
        val capabilities = parsed(payload)["capabilities"]!!.jsonObject
        assertTrue(capabilities["audio"]?.jsonPrimitive?.boolean == true)
        assertTrue(capabilities["video"]?.jsonPrimitive?.boolean == true)
        assertFalse(payload.containsKey("foci_active"), "capabilities-video stays inside the legacy field set")
    }

    @Test
    fun intentVideoModeUsesVideoIntent() {
        assertEquals(
            "video",
            MatrixRtcMembershipContent.resolveVideoIntent(true, false, "intent-video", null)
        )
        assertEquals(
            "m.room",
            MatrixRtcMembershipContent.resolveVideoIntent(true, false, "intent-video", "m.room")
        )
        assertEquals(
            "audio",
            MatrixRtcMembershipContent.resolveVideoIntent(false, false, "intent-video", null),
            "intent only applies when the call actually has video"
        )
    }

    @Test
    fun elementReferenceModeMirrorsReferenceFociVerbatim() {
        val refFociPreferred = json.parseToJsonElement("""[{"type":"livekit","livekit_service_url":"https://sfu.example.org","livekit_alias":"!ref:example.org"}]""")
        val refFocusActive = json.parseToJsonElement("""{"type":"livekit","focus_selection":"oldest_membership"}""")
        val refFociActive = json.parseToJsonElement("""[{"foci_id":"livekit","type":"livekit","livekit_service_url":"https://sfu.example.org","livekit_alias":"!ref:example.org"}]""")
        val refCapabilities = json.parseToJsonElement("""{"audio":true,"video":true}""")

        val payload = MatrixRtcMembershipContent.buildPayload(
            base.copy(
                video = true,
                videoMembershipMode = "element-reference",
                videoIntent = MatrixRtcMembershipContent.resolveVideoIntent(true, true, "element-reference", "video"),
                referenceFociPreferred = refFociPreferred,
                referenceFocusActive = refFocusActive,
                referenceFociActive = refFociActive,
                referenceCapabilities = refCapabilities
            )
        )

        assertEquals(refFociPreferred, parsed(payload)["foci_preferred"])
        assertEquals(refFocusActive, parsed(payload)["focus_active"])
        assertEquals(refFociActive, parsed(payload)["foci_active"])
        assertEquals(refCapabilities, parsed(payload)["capabilities"])
    }

    @Test
    fun stateKeyMirrorsElementReferenceSuffix() {
        assertEquals(
            "_@bob:example.org_ORBITDEV_m.call",
            MatrixRtcMembershipContent.selectStateKey(
                referenceStateKey = "_@alice:x.org_DEVICE9_m.call",
                referenceMembershipId = "@alice:x.org:DEVICE9",
                referenceDeviceId = "DEVICE9",
                userId = "@bob:example.org",
                deviceId = "ORBITDEV",
                callId = "SOME_CALL"
            )
        )
    }

    @Test
    fun stateKeyFallsBackToOrbitLayoutWithoutReference() {
        assertEquals(
            "_@bob:example.org_ORBITDEV_mycall",
            MatrixRtcMembershipContent.selectStateKey(
                referenceStateKey = null,
                referenceMembershipId = null,
                referenceDeviceId = null,
                userId = "@bob:example.org",
                deviceId = "ORBITDEV",
                callId = "mycall"
            )
        )
    }

    @Test
    fun contentCallIdIsBlankInLegacyMirrorAndMirroredOtherwise() {
        assertEquals(
            "",
            MatrixRtcMembershipContent.selectContentCallId(
                referenceContainsCallId = true,
                referenceCallId = "",
                callId = "orbit_call_1",
                legacyElementShape = true
            )
        )
        assertEquals(
            "element_call_id",
            MatrixRtcMembershipContent.selectContentCallId(
                referenceContainsCallId = true,
                referenceCallId = "element_call_id",
                callId = "orbit_call_1",
                legacyElementShape = false
            )
        )
        assertEquals(
            "orbit_call_1",
            MatrixRtcMembershipContent.selectContentCallId(
                referenceContainsCallId = false,
                referenceCallId = null,
                callId = "orbit_call_1",
                legacyElementShape = false
            )
        )
    }
}
