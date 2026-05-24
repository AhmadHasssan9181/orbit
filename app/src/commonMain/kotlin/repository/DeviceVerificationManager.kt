package repository

import de.connect2x.trixnity.client.MatrixClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class VerificationState {
    IDLE,
    REQUESTED,
    WAITING_FOR_OTHER_USER,
    SHOW_EMOJIS,
    VERIFIED,
    CANCELLED,
    ERROR
}

data class EmojiRepresentation(
    val emoji: String,
    val description: String
)

class DeviceVerificationManager(
    private val client: MatrixClient?,
    private val scope: CoroutineScope
) {
    private val _verificationState = MutableStateFlow(VerificationState.IDLE)
    val verificationState: StateFlow<VerificationState> = _verificationState

    private val _emojis = MutableStateFlow<List<EmojiRepresentation>>(emptyList())
    val emojis: StateFlow<List<EmojiRepresentation>> = _emojis

    init {
        // TODO: Bind to client?.verification?.getVerificationRequests() when API is confirmed
    }

    fun requestVerification() {
        if (client == null) return
        _verificationState.value = VerificationState.WAITING_FOR_OTHER_USER
        
        scope.launch {
            try {
                // TODO: trigger client.verification.requestSelfVerification()
            } catch (e: Exception) {
                _verificationState.value = VerificationState.ERROR
            }
        }
    }

    fun approveEmojis(match: Boolean) {
        if (match) {
            // TODO: approve SAS
            _verificationState.value = VerificationState.VERIFIED
        } else {
            // TODO: decline SAS
            _verificationState.value = VerificationState.CANCELLED
        }
    }

    fun cancelVerification() {
        _verificationState.value = VerificationState.CANCELLED
        _emojis.value = emptyList()
    }
}
