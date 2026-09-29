package livekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Freezes the DISPLAY-side roster reconciliation.
 *
 * The bug these guard against is specific and was expensive: `m.call.member` is a
 * membership record with a multi-hour expiry, so a room that had hosted a few
 * calls listed everybody who ever joined. The E2EE media-key exchange already
 * intersected that roster with the LiveKit room
 * (`CallMediaKeyExchange.nudgeMissingPeers`); the sidebar and the member panel
 * did not, which is why a 2-person call rendered 4-8 ghosts.
 */
class CallRosterReconciliationTest {

    private val alice = "@alice:example.org"
    private val bob = "@bob:example.org"
    private val carol = "@carol:example.org"
    private val dave = "@dave:example.org"

    private fun id(user: String, device: String) = "$user:$device"

    @Test
    fun identitiesParseToUserIdsNotTrackIds() {
        val live = setOf(id(alice, "AAA"), id(bob, "BBB"))
        assertEquals(setOf(alice, bob), CallRosterReconciliation.userIdsInRoom(live))
    }

    @Test
    fun departedMembersAreExcludedWhileTheirRecordIsStillUnexpired() {
        // Matrix still lists four people from earlier calls...
        val matrixRoster = listOf(alice, bob, carol, dave)
        // ...but only two are actually connected right now.
        val liveKit = setOf(id(alice, "AAA"), id(bob, "BBB"))
        assertEquals(
            listOf(alice, bob),
            CallRosterReconciliation.visibleUserIds(matrixRoster, liveKit, ownUserId = alice)
        )
    }

    @Test
    fun emptyLiveKitViewFallsBackToTheMatrixRoster() {
        // A native-wiring regression. Mirrors the `livekitIds.isEmpty() ||` guard
        // in nudgeMissingPeers: a regression must never blank the list.
        //
        // It is NOT the pre-join case, and that distinction is the whole reason
        // the display path gates on CallState.CONNECTED upstream. This function
        // cannot tell the two apart -- in both, LiveKit has said nothing -- so a
        // caller that renders its result before it has connected is displaying a
        // 12-hour-expiry membership list as a live participant count. See the
        // class doc on `CallRosterReconciliation.visibleUserIds`.
        val matrixRoster = listOf(alice, bob, carol)
        assertEquals(
            matrixRoster,
            CallRosterReconciliation.visibleUserIds(matrixRoster, emptySet(), ownUserId = alice)
        )
    }

    @Test
    fun onlyRemoteIdentitiesConnectedYieldsExactlyTwoForATwoPersonCall() {
        // The reported bug, in the shape it actually reached the UI.
        //
        // A two-person call (you + one remote) in a room whose Matrix state still
        // carried six membership records from earlier calls. The drawer rendered
        // all six; the call surface rendered two tiles.
        //
        // This asserts the intersection half, which is what the drawer needed and
        // was not getting. The other half -- that nothing is rendered before the
        // call is CONNECTED -- is enforced by the state gate in
        // `CallManager`'s reconciliation collector rather than here, because call
        // state is not an input to this function.
        val staleMatrixRoster = listOf(alice, bob, carol, dave, "@eve:example.org", "@frank:example.org")
        val twoActuallyConnected = setOf(id(bob, "BBB"))
        val visible = CallRosterReconciliation.visibleUserIds(
            matrixUserIds = staleMatrixRoster,
            livekitIdentities = twoActuallyConnected,
            ownUserId = alice
        )
        assertEquals(
            listOf(alice, bob),
            visible,
            "only the two connected identities may survive; the four stale records must not"
        )
    }

    @Test
    fun unparseableIdentitiesFallBackRatherThanBlanking() {
        // A service-generated opaque identity is not parseable. Blanking the
        // roster here would be worse than showing the membership record.
        val matrixRoster = listOf(alice, bob)
        assertEquals(
            matrixRoster,
            CallRosterReconciliation.visibleUserIds(matrixRoster, setOf("opaque-abc123"), ownUserId = alice)
        )
    }

    @Test
    fun localUserIsNeverDroppedByTheIntersection() {
        // remoteParticipants only ever holds REMOTE identities, so the local user
        // would vanish from their own roster without the ownUserId carve-out.
        val matrixRoster = listOf(alice, bob)
        val liveKit = setOf(id(bob, "BBB"))
        val visible = CallRosterReconciliation.visibleUserIds(matrixRoster, liveKit, ownUserId = alice)
        assertTrue(alice in visible, "the local user must stay visible")
        assertTrue(bob in visible, "the connected remote must stay visible")
        assertEquals(2, visible.size)
    }

    @Test
    fun matrixRosterOrderAndDeduplicationArePreserved() {
        val matrixRoster = listOf(bob, alice, bob, "", alice)
        val liveKit = setOf(id(alice, "AAA"), id(bob, "BBB"))
        assertEquals(
            listOf(bob, alice),
            CallRosterReconciliation.visibleUserIds(matrixRoster, liveKit, ownUserId = null)
        )
    }

    @Test
    fun multiDeviceUserCountsOnceAndStaysVisible() {
        // One person, two devices, two LiveKit identities: still one roster row.
        val matrixRoster = listOf(alice, bob)
        val liveKit = setOf(id(bob, "PHONE"), id(bob, "LAPTOP"), id(alice, "AAA"))
        assertEquals(
            listOf(alice, bob),
            CallRosterReconciliation.visibleUserIds(matrixRoster, liveKit, ownUserId = null)
        )
    }

    @Test
    fun userIdOfHandlesBareAndOpaqueIdentities() {
        // Canonical ${userId}:${deviceId}.
        assertEquals(alice, CallRosterReconciliation.userIdOf("@alice:example.org:DEV"))
        // Bare ${userId}:${host} with no device segment: still resolvable.
        assertEquals(alice, CallRosterReconciliation.userIdOf("@alice:example.org"))
        // No mxid at all: nothing we could ask the homeserver about.
        assertEquals(null, CallRosterReconciliation.userIdOf("opaque-abc123"))
        assertEquals(null, CallRosterReconciliation.userIdOf(""))
        assertEquals(null, CallRosterReconciliation.userIdOf(null))
    }
}
