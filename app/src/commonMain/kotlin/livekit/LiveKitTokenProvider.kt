package livekit

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LiveKitAuthResponse(
    val token: String,
    val serverUrl: String,
    val expiresAtEpochSeconds: Long? = null,
    val roomName: String? = null,
    val identity: String? = null,
    val matrixRtcMemberId: String? = null,
    val claimedDeviceId: String? = null,
    val grants: LiveKitVideoGrant? = null
)

object LiveKitTokenProvider {
    suspend fun fetchLiveKitToken(
        roomId: String,
        callId: String,
        focus: RtcFocus.LiveKit,
        identity: String,
        userId: String,
        homeserverUrl: String,
        accessToken: String,
        httpClient: HttpClient,
        forceLegacyJwtEndpoint: Boolean = false
    ): NativeResult<LiveKitTokenResult> =
        runCatching {
            fetchToken(
                roomId = roomId,
                identity = identity,
                userId = userId,
                homeserverUrl = homeserverUrl,
                accessToken = accessToken,
                livekitServiceUrl = focus.serviceUrl,
                httpClient = httpClient,
                forceLegacyJwtEndpoint = forceLegacyJwtEndpoint
            )
        }.fold(
            onSuccess = { it.toTokenResult() },
            onFailure = { error ->
                NativeResult.failure(
                    NativeError(
                        code = NativeErrorCode.AUTH_FAILED,
                        message = error.message ?: "Failed to fetch LiveKit token",
                        operation = "fetchLiveKitToken",
                        recoverable = true
                    )
                )
            }
        )

    suspend fun refreshLiveKitToken(
        roomId: String,
        callId: String,
        focus: RtcFocus.LiveKit,
        identity: String,
        userId: String,
        homeserverUrl: String,
        accessToken: String,
        httpClient: HttpClient,
        forceLegacyJwtEndpoint: Boolean = false
    ): NativeResult<LiveKitTokenResult> {
        println("DEBUG [LiveKitTokenProvider] Token refresh started for call $callId")
        return fetchLiveKitToken(
            roomId = roomId,
            callId = callId,
            focus = focus,
            identity = identity,
            userId = userId,
            homeserverUrl = homeserverUrl,
            accessToken = accessToken,
            httpClient = httpClient,
            forceLegacyJwtEndpoint = forceLegacyJwtEndpoint
        )
    }

    suspend fun fetchToken(
        roomId: String,
        identity: String,
        userId: String,
        homeserverUrl: String,
        accessToken: String,
        livekitServiceUrl: String,
        httpClient: HttpClient,
        forceLegacyJwtEndpoint: Boolean = false
    ): LiveKitAuthResponse {
        println("DEBUG [LiveKitTokenProvider] Starting handshake for room: $roomId")

        // 1. Request Matrix OpenID Token
        // Ensure userId is just @user:domain (strip any :deviceId suffix if present)
        val cleanUserId = userId.split(":").take(2).joinToString(":")
        val claimedDeviceId = deviceIdFromMemberId(identity)
        println(
            "DEBUG [MatrixRTCMapping] token request member.id=$identity claimed_user_id=$cleanUserId claimed_device_id=$claimedDeviceId slot_id=m.call#ROOM room_id=$roomId"
        )
        
        val openIdUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/user/${cleanUserId}/openid/request_token"
        println("DEBUG [LiveKitTokenProvider] Requesting OpenID token from $openIdUrl")
        
        val openIdResponse = httpClient.post(openIdUrl) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }
        
        if (openIdResponse.status.value !in 200..299) {
            val errBody = openIdResponse.bodyAsText()
            throw Exception("Failed to fetch OpenID token (status ${openIdResponse.status.value}): $errBody")
        }
        
        val openIdToken = openIdResponse.body<JsonObject>()
        println("DEBUG [LiveKitTokenProvider] Received OpenID token")

        if (forceLegacyJwtEndpoint) {
            println(
                "DEBUG [MatrixRTCMapping] forcing legacy /sfu/get JWT endpoint so LiveKit identity should equal MatrixRTC membershipID=$identity"
            )
            return fetchTokenLegacy(roomId, identity, cleanUserId, openIdToken, livekitServiceUrl, httpClient)
        }

        // 2. Exchange OpenID for LiveKit JWT
        val lkAuthUrl = "${livekitServiceUrl.trimEnd('/')}/get_token"
        println("DEBUG [LiveKitTokenProvider] Exchanging for LiveKit JWT at $lkAuthUrl")
        
        val lkResponse = httpClient.post(lkAuthUrl) {
            contentType(ContentType.Application.Json)
            val requestBody = buildJsonObject {
                put("room_id", roomId)
                put("slot_id", "m.call#ROOM")
                put("member", buildJsonObject {
                    put("id", identity) // Use the full MSC3401 identity here
                    put("claimed_user_id", cleanUserId)
                    put("claimed_device_id", claimedDeviceId)
                })
                put("openid_token", openIdToken)
            }
            println("DEBUG [LiveKitTokenProvider] Request Body: $requestBody")
            setBody(requestBody.toString())
        }
        
        if (lkResponse.status.value !in 200..299) {
            val errBody = lkResponse.bodyAsText()
            // Fallback for older deployments
            if (lkResponse.status.value == 404) {
                println("DEBUG [LiveKitTokenProvider] /get_token returned 404, falling back to legacy /sfu/get body")
                return fetchTokenLegacy(roomId, identity, cleanUserId, openIdToken, livekitServiceUrl, httpClient)
            }
            throw Exception("Failed to fetch LiveKit JWT (status ${lkResponse.status.value}): $errBody")
        }
        
        val lkBody = lkResponse.body<JsonObject>()
        val token = lkBody["token"]?.jsonPrimitive?.content ?: lkBody["jwt"]?.jsonPrimitive?.content
        val sfuUrl = lkBody["url"]?.jsonPrimitive?.content ?: lkBody["livekit_service_url"]?.jsonPrimitive?.content
        
        if (token.isNullOrBlank() || sfuUrl.isNullOrBlank()) {
            throw Exception("LiveKit token or URL not found in response: $lkBody")
        }
        
        println("DEBUG [LiveKitTokenProvider] LiveKit token fetched")
        dumpTonePublisherEnvIfEnabled(sfuUrl, token)
        return enrichAuthResponse(token, sfuUrl, identity, claimedDeviceId)
    }

    private suspend fun fetchTokenLegacy(
        roomId: String,
        identity: String,
        userId: String,
        openIdToken: JsonObject,
        livekitServiceUrl: String,
        httpClient: HttpClient
    ): LiveKitAuthResponse {
        val lkAuthUrl = "${livekitServiceUrl.trimEnd('/')}/sfu/get"
        val claimedDeviceId = deviceIdFromMemberId(identity)
        println("DEBUG [LiveKitTokenProvider] Using legacy endpoint: $lkAuthUrl")
        println(
            "DEBUG [MatrixRTCMapping] legacy token request room=$roomId device_id=$claimedDeviceId expected_livekit_identity=$userId:$claimedDeviceId matrixRtcMemberId=$identity"
        )
        
        val lkResponse = httpClient.post(lkAuthUrl) {
            contentType(ContentType.Application.Json)
            val requestBody = buildJsonObject {
                put("room", roomId)
                put("openid_token", openIdToken)
                put("device_id", claimedDeviceId)
            }
            println("DEBUG [LiveKitTokenProvider] Request Body: $requestBody")
            setBody(requestBody.toString())
        }
        
        if (lkResponse.status.value !in 200..299) {
            val errBody = lkResponse.bodyAsText()
            throw Exception("Failed to fetch LiveKit JWT via legacy (status ${lkResponse.status.value}): $errBody")
        }
        
        val lkBody = lkResponse.body<JsonObject>()
        val token = lkBody["token"]?.jsonPrimitive?.content ?: lkBody["jwt"]?.jsonPrimitive?.content
        val sfuUrl = lkBody["url"]?.jsonPrimitive?.content ?: lkBody["livekit_service_url"]?.jsonPrimitive?.content
        
        if (token.isNullOrBlank() || sfuUrl.isNullOrBlank()) {
            throw Exception("LiveKit token or URL not found in legacy response: $lkBody")
        }
        
        dumpTonePublisherEnvIfEnabled(sfuUrl, token)
        return enrichAuthResponse(token, sfuUrl, identity, claimedDeviceId)
    }

    private fun enrichAuthResponse(
        token: String,
        serverUrl: String,
        matrixRtcMemberId: String,
        claimedDeviceId: String
    ): LiveKitAuthResponse {
        val tokenResult = LiveKitAuthResponse(
            token = token,
            serverUrl = serverUrl,
            identity = matrixRtcMemberId,
            matrixRtcMemberId = matrixRtcMemberId,
            claimedDeviceId = claimedDeviceId
        ).toTokenResult()
        val value = tokenResult.value
        println(
            "DEBUG [MatrixRTCMapping] LiveKit JWT sub=${value?.identity ?: "<decode-failed>"} tokenRequestMemberId=$matrixRtcMemberId tokenRequestClaimedDeviceId=$claimedDeviceId livekitUrl=$serverUrl room=${value?.roomName ?: "<none>"}"
        )
        println(
            "DEBUG [MatrixRTCMapping] LiveKit JWT sub matches MatrixRTC membershipID=${value?.identity == matrixRtcMemberId} jwtSub=${value?.identity ?: "<decode-failed>"} membershipID=$matrixRtcMemberId"
        )
        return if (tokenResult.ok && value != null) {
            LiveKitAuthResponse(
                token = token,
                serverUrl = serverUrl,
                expiresAtEpochSeconds = value.expiresAtEpochSeconds,
                roomName = value.roomName,
                identity = value.identity ?: matrixRtcMemberId,
                matrixRtcMemberId = matrixRtcMemberId,
                claimedDeviceId = claimedDeviceId,
                grants = value.grants
            )
        } else {
            LiveKitAuthResponse(
                token = token,
                serverUrl = serverUrl,
                identity = matrixRtcMemberId,
                matrixRtcMemberId = matrixRtcMemberId,
                claimedDeviceId = claimedDeviceId
            )
        }
    }

    private fun deviceIdFromMemberId(memberId: String): String =
        when {
            memberId.count { it == ':' } >= 2 -> memberId.substringAfterLast(":")
            memberId.contains("_") -> memberId.split("_").getOrNull(2)
            else -> null
        }?.takeIf { it.isNotBlank() } ?: "ORBIT_DESKTOP"

    private fun dumpTonePublisherEnvIfEnabled(serverUrl: String, token: String) {
        if (!LiveKitFeatureFlags.dumpTonePublisherEnv) return

        println("DEBUG [LiveKitTokenProvider] BEGIN ORBIT_LIVEKIT_TONE_PUBLISHER_ENV")
        println("\$env:ORBIT_LIVEKIT_URL='${serverUrl.escapePowerShellSingleQuoted()}'")
        println("\$env:ORBIT_LIVEKIT_TOKEN='${token.escapePowerShellSingleQuoted()}'")
        println("\$env:ORBIT_LIVEKIT_TONE_SECONDS='120'")
        println(".\\gradlew --no-daemon :app:runLiveKitTonePublisher")
        println("DEBUG [LiveKitTokenProvider] END ORBIT_LIVEKIT_TONE_PUBLISHER_ENV")
    }

    private fun String.escapePowerShellSingleQuoted(): String =
        replace("'", "''")
}
