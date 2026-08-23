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

    fun isDefinitionTerminated(content: JsonObject): Boolean =
        content["m.intent"]?.jsonPrimitive?.content == "m.terminated" ||
            content["m.terminated"] != null ||
            content.isEmpty()
}

data class RtcCapabilities(
    val audio: Boolean,
    val video: Boolean,
    val screenShare: Boolean = false
)
