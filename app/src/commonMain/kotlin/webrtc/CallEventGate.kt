package webrtc

/**
 * H4: pure decision for dropping inbound VoIP call events that would echo back
 * to us. Two guards, mirroring js-sdk semantics:
 *  - party_id equality with OUR deviceId → the event is our own transmission
 *    relayed back (same-device echo);
 *  - sender == our own user id → the event came from another logged-in device
 *    of ours (different party_id, same account) and must never ring/handshake us.
 */
object CallEventGate {
    fun shouldIgnore(
        senderId: String?,
        partyId: String?,
        ownDeviceId: String?,
        ownUserId: String?
    ): Boolean {
        if (ownDeviceId != null && partyId != null && partyId == ownDeviceId) return true
        if (ownUserId != null && senderId != null && senderId == ownUserId) return true
        return false
    }
}
