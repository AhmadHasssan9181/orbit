package ui.screen.main

import androidx.compose.runtime.*
import webrtc.*
import repository.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import ui.screen.chat.*
import ui.icons.*
import ui.theme.*
import ui.screen.main.redactedIceUrl

@Composable
fun WebRtcEffects(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    scope: kotlinx.coroutines.CoroutineScope
): CallManager {
    val webRtcClient = remember { createWebRtcClient() }
    val callManager = remember { CallManager(webRtcClient, scope) }
    
    val orbitZeroEnabled by settingsManager.orbitZeroAiNoiseSuppression.collectAsState(true)
    val attenuation by settingsManager.attenuation.collectAsState(0f)
    val inputDevice by settingsManager.inputDevice.collectAsState("Default")
    val outputDevice by settingsManager.outputDevice.collectAsState("Default")
    val cameraDevice by settingsManager.cameraDevice.collectAsState("Default")


    LaunchedEffect(orbitZeroEnabled) {
        webRtcClient.setOrbitZeroEnabled(orbitZeroEnabled)
    }
    LaunchedEffect(attenuation) {
        webRtcClient.setAttenuationStrength(attenuation / 100f)
    }
    LaunchedEffect(inputDevice) {
        callManager.setAudioInputDevice(inputDevice)
    }
    LaunchedEffect(outputDevice) {
        callManager.setAudioOutputDevice(outputDevice)
    }
    LaunchedEffect(cameraDevice) {
        callManager.setVideoCaptureDevice(cameraDevice)
    }

    LaunchedEffect(callManager, repository) {
        callManager.refreshIceServers = {
            repository.getTurnServers()
                .onSuccess { servers ->
                    val urls = servers.flatMap { it.uris }
                    println("DEBUG: [WebRTC] Matrix ICE discovery returned ${urls.size} urls: ${urls.joinToString { it.redactedIceUrl() }}")
                }
                .onFailure { error ->
                    println("DEBUG: [WebRTC] Matrix ICE discovery failed: ${error.message}")
                }
                .getOrElse { emptyList() }
        }

        // Warm the TURN/STUN config early, and CallManager refreshes it again before each call.
        scope.launch {
            callManager.iceServers = callManager.refreshIceServers?.invoke().orEmpty()
        }

        // Listen for signaling events
        scope.launch {
            repository.observeCallEvents().collect { event ->
                println("DEBUG: [WebRTC] observeCallEvents received: ${event::class.simpleName} (callId=${event.callId})")
                val ownPartyId = CredentialManager.deviceId
                if (ownPartyId != null && event.partyId == ownPartyId) return@collect
                
                val user = repository.getCurrentUser()
                if (user != null && event.senderId == user.id) return@collect

                when (event) {
                    is repository.CallEvent.Invite -> callManager.handleCallInvite(event.roomId, event.callId, event.sdp)
                    is repository.CallEvent.Answer -> {
                        callManager.handleCallAnswer(event.sdp)
                        event.partyId?.let { selectedPartyId ->
                            repository.sendCallEvent(event.roomId, "m.call.select_answer", buildJsonObject {
                                put("call_id", event.callId)
                                put("selected_party_id", selectedPartyId)
                                put("version", "1")
                            })
                        }
                    }
                    is repository.CallEvent.Negotiate -> {
                        callManager.handleCallNegotiate(event.roomId, event.callId, event.type, event.sdp)
                    }
                    is repository.CallEvent.Candidate -> callManager.handleIceCandidate(
                        webrtc.IceCandidateModel(event.sdp, event.sdpMid, event.sdpMLineIndex)
                    )
                    is repository.CallEvent.Hangup -> {
                        if (callManager.callId == event.callId) {
                            callManager.endCall(notifyRemote = false)
                        }
                    }
                }
            }
        }

        callManager.sendInvite = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.invite", buildJsonObject {
                put("call_id", callId)
                put("offer", buildJsonObject {
                    put("type", "offer")
                    put("sdp", sdp)
                })
                put("version", "1")

                put("lifetime", 30000)
            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call invite: ${error.message}")
            }
        }

        callManager.sendAnswer = { roomId, callId, sdp ->
            repository.sendCallEvent(roomId, "m.call.answer", buildJsonObject {
                put("call_id", callId)
                put("answer", buildJsonObject {
                    put("type", "answer")
                    put("sdp", sdp)
                })
                put("version", "1")

            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call answer: ${error.message}")
            }
        }

        callManager.sendNegotiate = { roomId, callId, type, sdp ->
            repository.sendCallEvent(roomId, "m.call.negotiate", buildJsonObject {
                put("call_id", callId)
                put("description", buildJsonObject {
                    put("type", type)
                    put("sdp", sdp)
                })
                put("version", "1")

                put("lifetime", 30000)
            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call negotiate: ${error.message}")
            }
        }

        callManager.sendHangup = { roomId, callId ->
            repository.sendCallEvent(roomId, "m.call.hangup", buildJsonObject {
                put("call_id", callId)
                put("version", "1")

            }).onFailure { error ->
                println("DEBUG: [WebRTC] Failed to send call hangup: ${error.message}")
            }
        }

        callManager.onLeaveGroupCall = { roomId, callId ->
            repository.endGroupCall(roomId, callId)
            repository.leaveGroupCall(roomId, callId)
        }

        callManager.onUpdateGroupCallMembershipCapabilities = { roomId, callId, audio, video ->
            repository.updateGroupCallMembershipCapabilities(roomId, callId, audio, video)
        }

        // --- ICE Candidate Batching Logic ---
        val candidateBatch = mutableListOf<webrtc.IceCandidateModel>()
        var batchJob: kotlinx.coroutines.Job? = null

        callManager.sendCandidate = { roomId, callId, candidate ->
            candidateBatch.add(candidate)

            if (batchJob?.isActive != true) {
                batchJob = scope.launch {
                    kotlinx.coroutines.delay(500) // Debounce window to catch burst

                    val toSend = candidateBatch.toList()
                    candidateBatch.clear()

                    if (toSend.isNotEmpty()) {
                        println("DEBUG: [WebRTC] Sending ${toSend.size} local ICE candidates")
                        repository.sendCallEvent(roomId, "m.call.candidates", buildJsonObject {
                            put("call_id", callId)
                            put("version", 0)
            
                            put("candidates", buildJsonArray {
                                toSend.forEach { cand ->
                                    add(buildJsonObject {
                                        put("candidate", cand.sdp)
                                        put("sdpMid", cand.sdpMid)
                                        put("sdpMLineIndex", cand.sdpMLineIndex)
                                    })
                                }
                            })
                        }).onFailure { error ->
                            println("DEBUG: [WebRTC] Failed to send ICE candidates: ${error.message}")
                        }
                    }
                }
            }
        }
    }


    return callManager
}
