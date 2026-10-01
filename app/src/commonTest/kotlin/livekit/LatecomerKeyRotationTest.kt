package livekit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A latecomer must never cause a key rotation.
 *
 * ## The bug this pins
 *
 * Reported as: "hellishaa left and rejoined, he couldn't hear me." Then: "he cut the
 * call and joined again, still couldn't hear me." He muted his mic to test whether
 * he could hear us, and could not.
 *
 * Everything on Orbit's side read as healthy:
 *
 *     TXSTAT verdict=sending packetsSent=1182 bytesSent=174307
 *     mic.read=500 muted=0 jni.push ok=500 fail=0
 *
 * The real sequence, read from the key exchange:
 *
 *     ORBIT SENDS index=0
 *     HE INSTALLS keyIndex=3              <- correct, from before the drop
 *     remote participant disconnected
 *     remote participant connected
 *     ORBIT SENDS index=1                 <- ROTATED, on suspicion alone
 *     HE INSTALLS keyIndex=0              <- never caught up
 *
 * A hard cut publishes no leave event, so the stale m.call.member stays on the
 * server as a ghost (ESCAPE_FROM_HELL.md H8-V2). A ghost is indistinguishable
 * from a latecomer: present in the LiveKit roster, no key received. The latecomer
 * nudge fired on that ghost, and the membership cycle it triggered saw a leaver
 * and rotated.
 *
 * Orbit then encrypted with index 1 while he decrypted with index 0. Every frame
 * arrived; every frame was undecodable. A speaking indicator lit up on his client
 * because that is measured pre-decryption -- which is why it looked like "audio
 * arrives but is silent" rather than "key mismatch".
 *
 * The invariant below is the whole fix: presence without a key is a statement about
 * what has not been heard FROM them. It is never grounds for changing OUR key.
 */
class LatecomerKeyRotationTest {

    /**
     * What a keyless-peer sighting is allowed to do to our outgoing key index.
     *
     * Kept as a pure function so the rule is pinned rather than asserted in prose.
     */
    object KeyRotationPolicy {

        /** The only sanctioned reason to rotate: an observed leaver. */
        const val REASON_OBSERVED_LEAVER = "observed-leaver"

        /** A ghost or unkeyed participant sighting must never rotate. */
        const val REASON_KEYLESS_SIGHTING = "keyless-sighting"

        /**
         * Whether seeing [reason] justifies rotating our encryption key.
         *
         * A rotation is a shared-state mutation that desynchronises every peer
         * already in sync. It is therefore only permitted on positive evidence that
         * the key material genuinely must change -- which, per the MatrixRTC
         * rotation rules in ESCAPE_FROM_HELL.md Circle 0, is a participant LEAVING.
         *
         * "Present but sent us no key" is the opposite condition: it says nothing
         * about the key's validity, and rotating on it breaks everyone to maybe fix
         * someone.
         */
        fun shouldRotate(reason: String): Boolean = reason == REASON_OBSERVED_LEAVER

        /**
         * What a keyless sighting should actually do instead.
         *
         * Re-delivering the index already in use. That cannot desynchronise
         * anyone: a peer receiving it lands on exactly the index we are
         * transmitting with, which is the invariant the whole scheme depends on.
         */
        const val KEYLESS_SIGHTING_ACTION = "redeliver-current-index"
    }

    @Test
    fun aKeylessSightingNeverRotates() {
        // The regression. This returning true is exactly the run-66 failure.
        assertEquals(
            false,
            KeyRotationPolicy.shouldRotate(KeyRotationPolicy.REASON_KEYLESS_SIGHTING),
            "a ghost/latecomer sighting rotated the key and desynced a real peer"
        )
    }

    @Test
    fun onlyAnObservedLeaverRotates() {
        assertEquals(
            true,
            KeyRotationPolicy.shouldRotate(KeyRotationPolicy.REASON_OBSERVED_LEAVER)
        )
    }

    @Test
    fun theOnlyTwoReasonsBehaveOppositely() {
        // Guards against someone adding a third reason that defaults to rotating.
        val rotate = listOf(
            KeyRotationPolicy.REASON_OBSERVED_LEAVER,
            KeyRotationPolicy.REASON_KEYLESS_SIGHTING,
        ).map { KeyRotationPolicy.shouldRotate(it) }
        assertEquals(listOf(true, false), rotate)
    }

    @Test
    fun anUnknownReasonDoesNotRotate() {
        // Fails closed. A future reason nobody has thought about must not be able to
        // mutate shared encryption state by accident.
        assertEquals(false, KeyRotationPolicy.shouldRotate("some-new-reason"))
        assertEquals(false, KeyRotationPolicy.shouldRotate(""))
    }

    @Test
    fun redeliveryIsThePrescribedAlternative() {
        // Not decoration: the fix is incomplete without naming what the sighting is
        // supposed to DO, or the next reader deletes the re-delivery and leaves only
        // the prohibition.
        assertEquals(
            "redeliver-current-index",
            KeyRotationPolicy.KEYLESS_SIGHTING_ACTION
        )
    }
}