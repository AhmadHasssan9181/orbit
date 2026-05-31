package livekit

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

data class LiveKitTokenResult(
    val livekitUrl: String,
    val token: String,
    val expiresAtEpochSeconds: Long?,
    val roomName: String?,
    val identity: String?,
    val grants: LiveKitVideoGrant
)

data class LiveKitVideoGrant(
    val roomJoin: Boolean,
    val canSubscribe: Boolean,
    val canPublish: Boolean,
    val canPublishData: Boolean?,
    val canPublishSources: List<String>?
)

data class LiveKitGrantValidation(
    val audioAllowed: Boolean,
    val videoAllowed: Boolean
)

fun LiveKitAuthResponse.toTokenResult(): NativeResult<LiveKitTokenResult> =
    runCatching {
        val claims = decodeJwtPayload(token)
        val video = claims["video"]?.jsonObject ?: JsonObject(emptyMap())
        val grant = LiveKitVideoGrant(
            roomJoin = video.boolean("roomJoin") ?: false,
            canSubscribe = video.boolean("canSubscribe") ?: false,
            canPublish = video.boolean("canPublish") ?: false,
            canPublishData = video.boolean("canPublishData"),
            canPublishSources = (video["canPublishSources"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }
        )
        NativeResult.success(
            LiveKitTokenResult(
                livekitUrl = serverUrl,
                token = token,
                expiresAtEpochSeconds = claims.long("exp") ?: expiresAtEpochSeconds,
                roomName = video.string("room") ?: roomName,
                identity = claims.string("sub") ?: identity,
                grants = grant
            )
        )
    }.getOrElse { error ->
        NativeResult.failure(
            NativeError(
                code = NativeErrorCode.AUTH_FAILED,
                message = error.message ?: "Failed to decode LiveKit JWT",
                operation = "decodeLiveKitJwt",
                recoverable = false
            )
        )
    }

fun validateLiveKitGrants(
    tokenResult: LiveKitTokenResult,
    videoPolicy: JoinVideoPolicy
): NativeResult<LiveKitGrantValidation> {
    val grant = tokenResult.grants
    if (!grant.roomJoin) {
        return NativeResult.failure(authError("LiveKit JWT grant roomJoin=false"))
    }
    if (!grant.canSubscribe) {
        return NativeResult.failure(authError("LiveKit JWT grant canSubscribe=false"))
    }
    if (!grant.canPublish || !sourceAllowed(grant.canPublishSources, "microphone")) {
        return NativeResult.failure(authError("LiveKit JWT does not allow microphone publishing"))
    }

    val videoAllowed = grant.canPublish && sourceAllowed(grant.canPublishSources, "camera")
    if (videoPolicy == JoinVideoPolicy.VideoRequired && !videoAllowed) {
        return NativeResult.failure(authError("LiveKit JWT does not allow camera publishing"))
    }

    println("DEBUG [LiveKitTokenProvider] JWT grant validation passed")
    return NativeResult.success(
        LiveKitGrantValidation(
            audioAllowed = true,
            videoAllowed = videoAllowed
        )
    )
}

fun computeTokenRefreshDelayMillis(expiresAtEpochSeconds: Long?, nowEpochSeconds: Long): Long {
    if (expiresAtEpochSeconds == null) return 30L * 60L * 1000L

    val remainingSeconds = expiresAtEpochSeconds - nowEpochSeconds
    return if (remainingSeconds > 5L * 60L) {
        (remainingSeconds - 5L * 60L) * 1000L
    } else {
        maxOf(30L, (remainingSeconds * 0.8).toLong()) * 1000L
    }
}

private fun authError(message: String): NativeError =
    NativeError(
        code = NativeErrorCode.AUTH_FAILED,
        message = message,
        operation = "validateLiveKitGrants",
        recoverable = false
    )

private fun sourceAllowed(sources: List<String>?, source: String): Boolean =
    sources.isNullOrEmpty() || sources.any { it.equals(source, ignoreCase = true) }

@OptIn(ExperimentalEncodingApi::class)
private fun decodeJwtPayload(token: String): JsonObject {
    val payload = token.split('.').getOrNull(1)
        ?: error("LiveKit token is not a JWT")
    val paddedPayload = payload.padEnd(payload.length + ((4 - payload.length % 4) % 4), '=')
    val bytes = Base64.UrlSafe.decode(paddedPayload)
    return Json.parseToJsonElement(bytes.decodeToString()).jsonObject
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull
