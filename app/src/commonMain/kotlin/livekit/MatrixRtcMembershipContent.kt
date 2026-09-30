package livekit

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure builders for the MatrixRTC membership event Orbit publishes to room state.
 * The Element-mirroring (legacy) output here is the shape that won interop — it is
 * frozen by MatrixRtcMembershipShapeTest against docs/planning/ELEMENT_CALL_ENDPOINTS.md.
 * Change only together with an interop re-test against real Element clients.
 */
object MatrixRtcMembershipContent {

    const val LEGACY_EXPIRES_MS = 43_200_000L // 12 h — Element mirror shape
    const val MODERN_EXPIRES_MS = 14_400_000L // 4 h — modern shape

    /**
     * `lifetime` for the `m.call` **definition** event Orbit publishes.
     *
     * This is a different field from the membership event's `expires`, and its
     * absence was a real defect.
     *
     * MSC3401 gives a call definition a `lifetime` precisely so a call can end
     * without anyone publishing a termination: the definition stops being a call
     * `origin_server_ts + lifetime` milliseconds after it was sent. That covers
     * the ordinary way calls die -- whoever started it crashes, is killed, or
     * loses connectivity at hangup and never gets round to writing
     * `m.terminated`.
     *
     * `startGroupCall` published only `m.intent` and `m.foci_preferred`, so every
     * call Orbit ever started had an immortal definition. The evidence is in a
     * run log: a room still reporting `current call began 10534060322ms ago`
     * -- 121 days -- with its definition un-terminated and therefore
     * un-terminatable. Because the definition could never age out,
     * `CallStateResolver.definitionAgedOut` returned false ("cannot prove it
     * expired" -- there was no `lifetime` to prove anything with), the room read
     * as permanently live, and its 3-second roster poller ran for the rest of
     * the session.
     *
     * 8 hours matches what Element Web publishes, and is comfortably longer than
     * any real call so a long conversation is never cut off mid-sentence.
     */
    const val DEFINITION_LIFETIME_MS = 28_800_000L // 8 h — Element's own value

    fun expiresMs(legacyElementShape: Boolean): Long =
        if (legacyElementShape) LEGACY_EXPIRES_MS else MODERN_EXPIRES_MS

    fun resolveVideoIntent(
        video: Boolean,
        legacyElementShape: Boolean,
        videoMembershipMode: String,
        referenceIntent: String?
    ): String {
        val publishIntentVideo = video && videoMembershipMode == "intent-video"
        val publishElementReference = video && videoMembershipMode == "element-reference"
        return when {
            publishElementReference && !referenceIntent.isNullOrBlank() -> referenceIntent
            publishIntentVideo && (referenceIntent == "video" || referenceIntent == "m.room") -> referenceIntent
            publishIntentVideo -> "video"
            legacyElementShape -> "audio"
            video -> "video"
            else -> "audio"
        }
    }

    /**
     * Mirrors an Element member event's state-key suffix so joiners and leavers stay
     * grouped under one key per (user, device, call); falls back to the Orbit layout.
     */
    fun selectStateKey(
        referenceStateKey: String?,
        referenceMembershipId: String?,
        referenceDeviceId: String?,
        userId: String,
        deviceId: String,
        callId: String
    ): String {
        val refUserId = if (!referenceMembershipId.isNullOrBlank() && !referenceDeviceId.isNullOrBlank()) {
            referenceMembershipId.removeSuffix(":$referenceDeviceId")
        } else {
            null
        }
        val refPrefix = if (!refUserId.isNullOrBlank() && !referenceDeviceId.isNullOrBlank()) {
            "_${refUserId}_${referenceDeviceId}_"
        } else {
            null
        }
        val refCallSuffix = if (referenceStateKey != null && refPrefix != null && referenceStateKey.startsWith(refPrefix)) {
            referenceStateKey.removePrefix(refPrefix)
        } else {
            null
        }
        return if (!refCallSuffix.isNullOrBlank()) "_${userId}_${deviceId}_$refCallSuffix" else "_${userId}_${deviceId}_$callId"
    }

    /** Legacy mirror mode always publishes a blank call_id (room-scoped membership). */
    fun selectContentCallId(
        referenceContainsCallId: Boolean,
        referenceCallId: String?,
        callId: String,
        legacyElementShape: Boolean
    ): String {
        if (legacyElementShape) return ""
        return if (referenceContainsCallId) (referenceCallId ?: "") else callId
    }

    data class PayloadInputs(
        val deviceId: String,
        val membershipId: String,
        val createdTs: Long,
        val roomId: String,
        val defaultLiveKitServiceUrl: String,
        val jsonCallId: String,
        val audio: Boolean,
        val video: Boolean,
        val legacyElementShape: Boolean,
        val videoMembershipMode: String,
        val videoIntent: String,
        val expiresMs: Long,
        val referenceFociPreferred: JsonElement? = null,
        val referenceFocusActive: JsonElement? = null,
        val referenceFociActive: JsonElement? = null,
        val referenceCapabilities: JsonElement? = null
    )

    fun buildPayload(i: PayloadInputs): JsonObject {
        val publishCapabilitiesVideo = i.video && i.videoMembershipMode == "capabilities-video"
        val publishElementReference = i.video && i.videoMembershipMode == "element-reference"
        return buildJsonObject {
            put("application", "m.call")
            put("call_id", i.jsonCallId)
            put("device_id", i.deviceId)
            // Keep membershipID aligned with the MatrixRTC token request member.id.
            // LiveKit JWT sub is allowed to be an opaque service-generated participant identity.
            put("membershipID", i.membershipId)
            // MSC3401 strictly requires created_ts for focus selection
            put("created_ts", i.createdTs)
            put("m.call.intent", i.videoIntent)
            put("scope", "m.room")
            put("expires", i.expiresMs)
            if (publishElementReference && i.referenceFociPreferred != null) {
                put("foci_preferred", i.referenceFociPreferred)
            } else {
                put("foci_preferred", buildJsonArray {
                    add(buildJsonObject {
                        put("livekit_service_url", i.defaultLiveKitServiceUrl)
                        put("livekit_alias", i.roomId)
                        put("type", "livekit")
                    })
                })
            }
            if (publishElementReference && i.referenceFocusActive != null) {
                put("focus_active", i.referenceFocusActive)
            } else {
                put("focus_active", buildJsonObject {
                    put("type", "livekit")
                    put("focus_selection", "oldest_membership")
                })
            }
            if (!i.legacyElementShape) {
                // Add modern foci_active for newer clients too.
                put("foci_active", buildJsonArray {
                    add(buildJsonObject {
                        put("foci_id", "livekit")
                        put("type", "livekit")
                        put("livekit_service_url", i.defaultLiveKitServiceUrl)
                        put("livekit_alias", i.roomId)
                    })
                })
                put("capabilities", buildJsonObject {
                    put("audio", i.audio)
                    put("video", i.video)
                })
            } else if (publishCapabilitiesVideo) {
                put("capabilities", buildJsonObject {
                    put("audio", i.audio)
                    put("video", true)
                })
            } else if (publishElementReference) {
                i.referenceFociActive?.let { put("foci_active", it) }
                i.referenceCapabilities?.let { put("capabilities", it) }
            }
        }
    }
}
