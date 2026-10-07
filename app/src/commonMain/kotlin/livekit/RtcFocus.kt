package livekit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed class RtcFocus {
    data class LiveKit(
        val serviceUrl: String,
        val alias: String?,
        val raw: String
    ) : RtcFocus()

    data class Unknown(
        val type: String?,
        val raw: String
    ) : RtcFocus()
}

object RtcFocusParser {
    private val focusKeys = listOf(
        "m.foci_preferred",
        "foci_preferred",
        "foci_active",
        "org.matrix.msc4143.rtc_foci"
    )

    fun parse(element: JsonElement): RtcFocus {
        val obj = element as? JsonObject ?: return RtcFocus.Unknown(null, element.toString())
        val type = obj.string("type")
        val serviceUrl = obj.string("livekit_service_url")
            ?: obj.string("m.livekit_service_url")
            ?: obj.string("service_url")
        val alias = obj.string("livekit_alias") ?: obj.string("alias")
        val looksLikeLiveKit = type?.contains("livekit", ignoreCase = true) == true ||
            serviceUrl?.contains("livekit", ignoreCase = true) == true

        return if (serviceUrl != null && looksLikeLiveKit) {
            RtcFocus.LiveKit(serviceUrl = serviceUrl, alias = alias, raw = obj.toString())
        } else {
            RtcFocus.Unknown(type = type, raw = obj.toString())
        }
    }

    fun parseAll(content: JsonObject): List<RtcFocus> =
        focusKeys.flatMap { key -> parseFocusValue(content[key]) }

    fun firstLiveKit(content: JsonObject): RtcFocus.LiveKit? =
        parseAll(content).firstOrNull { it is RtcFocus.LiveKit } as? RtcFocus.LiveKit

    private fun parseFocusValue(value: JsonElement?): List<RtcFocus> =
        when (value) {
            is JsonArray -> value.map(::parse)
            is JsonObject -> {
                val nestedFoci = value["foci"] ?: value["m.foci"]
                if (nestedFoci != null) parseFocusValue(nestedFoci) else listOf(parse(value))
            }
            null -> emptyList()
            else -> listOf(parse(value))
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}

object MatrixRtcCalls {
    val DEFINITION_TYPES = listOf("m.call", "org.matrix.msc3401.call")
    val MEMBER_TYPES = listOf("org.matrix.msc3401.call.member", "m.call.member")

    /**
     * True when a call definition says the call is over.
     *
     * `m.terminated` is `true`, or an object like `{"reason":"..."}`. It was
     * previously read as "the key is present at all", which made a literal
     * `m.terminated: false` -- i.e. explicitly NOT terminated -- read as
     * terminated. Since this predicate decides whether a room currently has a
     * call, getting it backwards on `false` means a live call is reported as over
     * and the user cannot join it.
     *
     * `content.isEmpty()` still counts as terminated: with nothing in it there is
     * no call to describe, and treating it as live would invent one.
     */
    fun isDefinitionTerminated(content: JsonObject): Boolean {
        if (content.isEmpty()) return true
        val intent = (content["m.intent"] as? JsonPrimitive)?.contentOrNull
        if (intent == "m.terminated") return true
        return when (val terminated = content["m.terminated"]) {
            null -> false
            // Canonical form: m.terminated: true.
            // MSC3401/MatrixRTC also supports string reasons (e.g. "m.user_hangup", "m.timeout").
            // Only explicit "false" or blank values assert that the call is NOT terminated.
            is JsonPrimitive -> {
                val value = terminated.contentOrNull
                value != null && value != "false" && value.isNotBlank()
            }
            // Present with a reason: m.terminated: { "reason": "..." }
            is JsonObject -> true
            // Any other structured termination representation
            else -> false
        }
    }
}

data class RtcCapabilities(
    val audio: Boolean,
    val video: Boolean,
    val screenShare: Boolean = false
)
