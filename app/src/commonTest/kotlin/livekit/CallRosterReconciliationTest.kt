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
        // This is the DEFAULT, and the only caller (the display path in
        // `CallManager`) opts out of it once the call is CONNECTED -- see
        // `authoritativeLiveKitDropsGhostsFromAnAloneCall`. Callers that render
        // this result before LiveKit has spoken are displaying a 12-hour-expiry
        // membership list as a live participant count; see the class doc on
        // `CallRosterReconciliation.visibleUserIds`.
        val matrixRoster = listOf(alice, bob, carol)
        assertEquals(
            matrixRoster,
            CallRosterReconciliation.visibleUserIds(matrixRoster, emptySet(), ownUserId = alice)
        )
    }

    @Test
    fun authoritativeLiveKitDropsGhostsFromAnAloneCall() {
        // A solo call in a room with a stale Matrix membership record.
        //
        // LiveKit is not silent here, it is accurate: `onRemoteParticipantConnected`
        // fires for every remote participant whether or not it publishes media, so
        // an empty set while CONNECTED means genuinely nobody else is in the room.
        // Falling back to `m.call.member` rendered all eight ghosts in the drawer
        // and "Voice - 8 here" on the channel row, directly beside a call stage
        // reading "No one else is here".
        val staleMatrixRoster = listOf(
            alice, bob, carol, dave, "@eve:example.org", "@frank:example.org",
            "@grace:example.org", "@heidi:example.org"
        )
        val visible = CallRosterReconciliation.visibleUserIds(
            matrixUserIds = staleMatrixRoster,
            livekitIdentities = emptySet(),
            ownUserId = alice,
            livekitIsAuthoritative = true
        )
        assertEquals(
            listOf(alice),
            visible,
            "an empty authoritative LiveKit view means alone in the call, not everybody who ever joined"
        )
    }

    @Test
    fun authoritativeLiveKitReportsAnEmptyRosterWithoutALocalUser() {
        // Same as above but with no local id known: the roster is empty rather
        // than the whole stale record. An empty roster is the honest answer when
        // we cannot even identify ourselves.
        val visible = CallRosterReconciliation.visibleUserIds(
            matrixUserIds = listOf(alice, bob, carol),
            livekitIdentities = emptySet(),
            ownUserId = null,
            livekitIsAuthoritative = true
        )
        assertEquals(emptyList(), visible)
    }

    @Test
    fun authoritativeLiveKitStillIntersectsWhenPeersArePresent() {
        // The flag only changes what an EMPTY LiveKit view means. A populated one
        // must reconcile exactly as before, or a departing member whose membership
        // record is still unexpired would reappear.
        val staleMatrixRoster = listOf(alice, bob, carol, dave)
        val visible = CallRosterReconciliation.visibleUserIds(
            matrixUserIds = staleMatrixRoster,
            livekitIdentities = setOf(id(bob, "BBB")),
            ownUserId = alice,
            livekitIsAuthoritative = true
        )
        assertEquals(listOf(alice, bob), visible)
    }

    @Test
    fun authoritativeLiveKitDropsOpaqueIdentitiesInsteadOfFallingBack() {
        // The default falls back when the LiveKit set is populated but nothing in
        // it parses. Once the caller has said the LiveKit view is the answer, an
        // identity we cannot map tells us nothing about who is in the room, and
        // guessing from the stale record would be worse than admitting the room
        // looks empty.
        val visible = CallRosterReconciliation.visibleUserIds(
            matrixUserIds = listOf(alice, bob, carol),
            livekitIdentities = setOf("opaque-abc123"),
            ownUserId = alice,
            livekitIsAuthoritative = true
        )
        assertEquals(listOf(alice), visible)
    }

    @Test
    fun unparseableIdentitiesFallBackRatherThanBlanking() {
        // A service-generated opaque identity is not parseable. Blanking the
        // roster here would be worse than showing the membership record -- for a
        // caller that has NOT claimed the LiveKit view is authoritative.
        val matrixRoster = listOf(alice, bob)
        assertEquals(
            matrixRoster,
            CallRosterReconciliation.visibleUserIds(matrixRoster, setOf("opaque-abc123"), ownUserId = alice)
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
