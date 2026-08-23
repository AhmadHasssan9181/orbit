package webrtc

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H4: multi-device noise guards. Two devices of the same user must never ring
 * or handshake each other; our own relayed-back events must be dropped.
 */
class CallEventGateTest {

    private companion object {
        const val ownDevice = "DEVICE_ORBIT_1"
        const val otherDevice = "DEVICE_ELEMENT_9"
        const val ownUser = "@ahmad:example.org"
        const val friendUser = "@friend:example.org"
    }

    @Test
    fun ownDeviceEchoIsIgnored() {
        assertTrue(
            CallEventGate.shouldIgnore(
                senderId = friendUser,
                partyId = ownDevice,
                ownDeviceId = ownDevice,
                ownUserId = null
            )
        )
    }

    @Test
    fun sameUserOtherDeviceIsIgnoredViaSenderFilter() {
        // Different party_id (it really is another device) but same account.
        assertTrue(
            CallEventGate.shouldIgnore(
                senderId = ownUser,
                partyId = otherDevice,
                ownDeviceId = ownDevice,
                ownUserId = ownUser
            )
        )
    }

    @Test
    fun differentUserPassesThrough() {
        assertFalse(
            CallEventGate.shouldIgnore(
                senderId = friendUser,
                partyId = otherDevice,
                ownDeviceId = ownDevice,
                ownUserId = ownUser
            )
        )
    }

    @Test
    fun missingOwnDeviceIdStillFiltersBySender() {
        assertTrue(
            CallEventGate.shouldIgnore(
                senderId = ownUser,
                partyId = otherDevice,
                ownDeviceId = null,
                ownUserId = ownUser
            )
        )
        assertFalse(
            CallEventGate.shouldIgnore(
                senderId = friendUser,
                partyId = otherDevice,
                ownDeviceId = null,
                ownUserId = ownUser
            )
        )
    }

    @Test
    fun nullPartyIdNeverTriggersDeviceGuard() {
        assertFalse(
            CallEventGate.shouldIgnore(
                senderId = friendUser,
                partyId = null,
                ownDeviceId = ownDevice,
                ownUserId = ownUser
            )
        )
    }

    @Test
    fun noOwnIdentityMeansNothingIsIgnored() {
        assertFalse(
            CallEventGate.shouldIgnore(
                senderId = friendUser,
                partyId = otherDevice,
                ownDeviceId = null,
                ownUserId = null
            )
        )
    }
}
