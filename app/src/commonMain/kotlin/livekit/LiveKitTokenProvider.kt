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
    val serverUrl: String
)

object LiveKitTokenProvider {
    suspend fun fetchToken(
        roomId: String,
        userId: String,
        deviceId: String,
        homeserverUrl: String,
        accessToken: String,
        livekitServiceUrl: String,
        httpClient: HttpClient
    ): LiveKitAuthResponse {
        println("DEBUG [LiveKitTokenProvider] Starting handshake for room: $roomId")

        // 1. Request Matrix OpenID Token
        val openIdUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/user/${userId}/openid/request_token"
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

        // 2. Exchange OpenID for LiveKit JWT
        // Note: We use /get_token. Some servers might use /sfu/get
        val lkAuthUrl = "${livekitServiceUrl.trimEnd('/')}/get_token"
        println("DEBUG [LiveKitTokenProvider] Exchanging for LiveKit JWT at $lkAuthUrl")
        
        val lkResponse = httpClient.post(lkAuthUrl) {
            contentType(ContentType.Application.Json)
            val requestBody = buildJsonObject {
                put("room_id", roomId)
                put("slot_id", "m.call#ROOM")
                put("member", buildJsonObject {
                    put("id", "$userId:$deviceId")
                    put("claimed_user_id", userId)
                    put("claimed_device_id", deviceId)
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
                return fetchTokenLegacy(roomId, userId, deviceId, openIdToken, livekitServiceUrl, httpClient)
            }
            throw Exception("Failed to fetch LiveKit JWT (status ${lkResponse.status.value}): $errBody")
        }
        
        val lkBody = lkResponse.body<JsonObject>()
        val token = lkBody["token"]?.jsonPrimitive?.content ?: lkBody["jwt"]?.jsonPrimitive?.content
        val sfuUrl = lkBody["url"]?.jsonPrimitive?.content ?: lkBody["livekit_service_url"]?.jsonPrimitive?.content
        
        if (token.isNullOrBlank() || sfuUrl.isNullOrBlank()) {
            throw Exception("LiveKit token or URL not found in response: $lkBody")
        }
        
        println("DEBUG [LiveKitTokenProvider] Successfully received LiveKit JWT and SFU URL: $sfuUrl")
        return LiveKitAuthResponse(token, sfuUrl)
    }

    private suspend fun fetchTokenLegacy(
        roomId: String,
        userId: String,
        deviceId: String,
        openIdToken: JsonObject,
        livekitServiceUrl: String,
        httpClient: HttpClient
    ): LiveKitAuthResponse {
        val lkAuthUrl = "${livekitServiceUrl.trimEnd('/')}/sfu/get"
        println("DEBUG [LiveKitTokenProvider] Retrying with legacy endpoint: $lkAuthUrl")
        
        val lkResponse = httpClient.post(lkAuthUrl) {
            contentType(ContentType.Application.Json)
            val requestBody = buildJsonObject {
                put("room_id", roomId)
                put("slot_id", "m.call#ROOM")
                put("member", buildJsonObject {
                    put("id", "$userId:$deviceId")
                    put("claimed_user_id", userId)
                    put("claimed_device_id", deviceId)
                })
                put("openid_token", openIdToken)
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
        
        return LiveKitAuthResponse(token, sfuUrl)
    }
}
