package livekit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Freezes the matrixRTC E2EE media-key wire shapes and rotation rules Orbit must
 * follow to interop with Element Call (deployed format, not MSC4143 future text).
 */
class CallMediaKeysCoreTest {

    private val sampleKey = ByteArray(16) { it.toByte() } // 000102..0f

    @Test
    fun payloadMirrorsVerifiedElementShape() {
        val payload = CallMediaKeys.buildEncryptionKeysPayload(
            keyIndex = 0,
            key = sampleKey,
            roomId = "!room:example.org",
            memberId = "@alice:example.org:DEV123",
            claimedDeviceId = "DEV123",
            sentTsMs = 1_724_400_000_000
        )

        val keys = payload["keys"]?.jsonObject ?: throw AssertionError("keys missing")
        assertEquals(0, keys["index"]?.jsonPrimitive?.int)
        // Element emits PADDED base64 of the raw 16 key bytes.
        assertEquals("AAECAwQFBgcICQoLDA0ODw==", keys["key"]?.jsonPrimitive?.content)
        assertEquals("!room:example.org", payload["room_id"]?.jsonPrimitive?.content)
        val member = payload["member"]?.jsonObject ?: throw AssertionError("member missing")
        assertEquals("@alice:example.org:DEV123", member["id"]?.jsonPrimitive?.content)
        assertEquals("DEV123", member["claimed_device_id"]?.jsonPrimitive?.content)
        val session = payload["session"]?.jsonObject ?: throw AssertionError("session missing")
        assertEquals("", session["call_id"]?.jsonPrimitive?.content)
        assertEquals("m.call", session["application"]?.jsonPrimitive?.content)
        assertEquals("m.room", session["scope"]?.jsonPrimitive?.content)
        assertEquals(1_724_400_000_000L, payload["sent_ts"]?.jsonPrimitive?.long)
    }

    @Test
    fun parseRoundTripsPaddedAndUnpaddedBase64() {
        val padded = CallMediaKeys.buildEncryptionKeysPayload(
            keyIndex = 255,
            key = sampleKey,
            roomId = "!r:s",
            memberId = "@a:s:D",
            claimedDeviceId = "D",
            sentTsMs = 42L
        )
        val parsed = assertNotNull(CallMediaKeys.parseEncryptionKeysPayload(padded))
        assertEquals(255, parsed.keyIndex)
        assertTrue(sampleKey.contentEquals(parsed.key))
        assertEquals("D", parsed.claimedDeviceId)

        // Element accepts unpadded base64 on read; so must we.
        val unpadded = kotlinx.serialization.json.buildJsonObject {
            padded.forEach { (k, v) -> put(k, v) }
            put("keys", kotlinx.serialization.json.buildJsonObject {
                put("index", 7)
                put("key", "AAECAwQFBgcICQoLDA0ODw")
            })
        }
        val parsedUnpadded = assertNotNull(CallMediaKeys.parseEncryptionKeysPayload(unpadded))
        assertEquals(7, parsedUnpadded.keyIndex)
        assertTrue(sampleKey.contentEquals(parsedUnpadded.key))
    }

    @Test
    fun parseRejectsMalformedPayloads() {
        fun keysJson(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

        fun payloadWith(keys: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject { put("keys", keys) }

        val validKey = kotlinx.serialization.json.JsonPrimitive("AAECAwQFBgcICQoLDA0ODw==")
        val shortKey = kotlinx.serialization.json.JsonPrimitive("AAECAwQFBgcICQoLDA0O")

        assertNull(CallMediaKeys.parseEncryptionKeysPayload(payloadWith(keysJson("index" to kotlinx.serialization.json.JsonPrimitive(0)))))
        assertNull(
            CallMediaKeys.parseEncryptionKeysPayload(
                payloadWith(keysJson("index" to kotlinx.serialization.json.JsonPrimitive(-1), "key" to validKey))
            )
        )
        assertNull(
            CallMediaKeys.parseEncryptionKeysPayload(
                payloadWith(keysJson("index" to kotlinx.serialization.json.JsonPrimitive(256), "key" to validKey))
            )
        )
        // Wrong key size (15 bytes -> 20 unpadded chars) must be rejected.
        assertNull(
            CallMediaKeys.parseEncryptionKeysPayload(
                payloadWith(keysJson("index" to kotlinx.serialization.json.JsonPrimitive(0), "key" to shortKey))
            )
        )
        assertNull(CallMediaKeys.parseEncryptionKeysPayload(kotlinx.serialization.json.buildJsonObject { }))
    }

    @Test
    fun base64MatchesRfc4648Vectors() {
        fun b64(s: String) = CallMediaKeys.encodePaddedBase64(s.encodeToByteArray())
        assertEquals("", b64(""))
        assertEquals("Zg==", b64("f"))
        assertEquals("Zm8=", b64("fo"))
        assertEquals("Zm9v", b64("foo"))
        assertEquals("Zm9vYmFy", b64("foobar"))

        for (size in 0..24) {
            val bytes = ByteArray(size) { (it * 31 + size).toByte() }
            val encoded = CallMediaKeys.encodePaddedBase64(bytes)
            assertTrue(encoded.length % 4 == 0, "padded length for $size")
            assertTrue(bytes.contentEquals(CallMediaKeys.decodeBase64(encoded)), "round trip $size")
        }
        // Unpadded decode tolerance.
        assertTrue(
            byteArrayOf(1, 2, 3).contentEquals(
                CallMediaKeys.decodeBase64("AQID")
            )
        )
        assertNull(CallMediaKeys.decodeBase64("A"))
        assertNull(CallMediaKeys.decodeBase64("!!!!"))
    }

    @Test
    fun outdatedKeyFilterKeepsNewestSentTsPerSenderAndIndex() {
        val filter = CallMediaKeys.OutdatedKeyFilter()
        assertTrue(filter.accept("@a:s", "D1", 0, 100))
        assertFalse(filter.accept("@a:s", "D1", 0, 50))   // older replay
        assertFalse(filter.accept("@a:s", "D1", 0, 100))  // equal replay
        assertTrue(filter.accept("@a:s", "D1", 0, 200))   // newer wins
        assertTrue(filter.accept("@a:s", "D1", 1, 10))    // different index independent
        assertTrue(filter.accept("@b:s", "D1", 0, 10))    // different sender independent
        // Two devices of the same user both emit index 0 — neither evicts the other.
        assertTrue(filter.accept("@a:s", "D2", 0, 150))
        assertFalse(filter.accept("@a:s", "D2", 0, 140))
        assertTrue(filter.accept("@a:s", "D1", 0, 250))
    }

    @Test
    fun membershipReactionRotatesOnConfirmedLeaverOnly() {
        val freshKeyAgeMs = 500L
        val staleKeyAgeMs = 30_000L

        // Rotation is reserved for a departure. It is the only case where it
        // revokes anything, and it desynchronises every participant at once.
        assertTrue(CallMediaKeys.decideMembershipReaction(1, 0, freshKeyAgeMs) is CallMediaKeys.MembershipReaction.Rotate)
        assertTrue(CallMediaKeys.decideMembershipReaction(2, 3, freshKeyAgeMs) is CallMediaKeys.MembershipReaction.Rotate)

        // A joiner never rotates, whatever the key age. The previous rule
        // rotated once the key was older than the 10s grace period, which in any
        // call older than ten seconds meant every single rejoin rotated -- the
        // exact cause of "first rejoin works, the second and third do not".
        assertTrue(
            CallMediaKeys.decideMembershipReaction(0, 1, freshKeyAgeMs) is
                CallMediaKeys.MembershipReaction.ResendCurrent
        )
        assertTrue(
            CallMediaKeys.decideMembershipReaction(0, 2, staleKeyAgeMs) is
                CallMediaKeys.MembershipReaction.ResendCurrent,
            "a stale key must not make a joiner rotate; that made rejoin impossible"
        )
        assertTrue(
            CallMediaKeys.decideMembershipReaction(0, 1, 60_000L) is
                CallMediaKeys.MembershipReaction.ResendCurrent
        )

        assertTrue(CallMediaKeys.decideMembershipReaction(0, 0, freshKeyAgeMs) is CallMediaKeys.MembershipReaction.None)
    }

    @Test
    fun aJoinerNeverRotatesAtAnyKeyAge() {
        // The grace period no longer gates anything, so a key of any age must
        // behave identically for a joiner. Swept rather than sampled: this is the
        // property the bug lived in, and a boundary case is not enough to pin it.
        var ageMs = 0L
        while (ageMs <= 120_000L) {
            assertTrue(
                CallMediaKeys.decideMembershipReaction(0, 1, ageMs) is
                    CallMediaKeys.MembershipReaction.ResendCurrent,
                "a joiner rotated at keyAgeMs=$ageMs"
            )
            ageMs += 250L
        }
    }

    @Test
    fun aConfirmedLeaverStillRotatesRegardlessOfKeyAge() {
        // The guard must not become "never rotate", which would drop forward
        // secrecy: a departed member holds the old key and could decrypt anything
        // still encrypted under it.
        var ageMs = 0L
        while (ageMs <= 120_000L) {
            assertTrue(
                CallMediaKeys.decideMembershipReaction(1, 0, ageMs) is
                    CallMediaKeys.MembershipReaction.Rotate,
                "a departure failed to rotate at keyAgeMs=$ageMs"
            )
            ageMs += 250L
        }
    }

    @Test
    fun aLeaverTakesPrecedenceOverAJoiner() {
        // Both present: the departure wins, because that is the case where
        // rotation has a security purpose and must not be skipped.
        assertTrue(
            CallMediaKeys.decideMembershipReaction(1, 5, 60_000L) is
                CallMediaKeys.MembershipReaction.Rotate
        )
    }

    @Test
    fun ownKeyRingRotatesWithWrappedIndex() {
        var now = 1_000L
        val ring = CallMediaKeys.OwnKeyRing(nowMs = { now })
        assertEquals(0, ring.currentIndex)
        assertEquals(16, ring.currentKey.size)
        assertEquals(0L, ring.ageMs())
        val firstKey = ring.currentKey.toList()

        now += 5_000
        // Age tracks the CURRENT key: full old-key age before rotating, reset after.
        assertEquals(5_000L, ring.ageMs())
        ring.rotate()
        assertEquals(1, ring.currentIndex)
        assertEquals(0L, ring.ageMs())
        assertFalse(firstKey == ring.currentKey.toList())

        repeat(CallMediaKeys.KEY_INDEX_MODULUS - 1) { ring.rotate() }
        assertEquals(0, ring.currentIndex) // (255 + 1) % 256 wraps
    }

    @Test
    fun participantIdentityConcatenatesUserIdAndDevice() {
        assertEquals("@alice:example.org:JLAFKJWSCS", CallMediaKeys.participantIdentity("@alice:example.org", "JLAFKJWSCS"))
    }

    @Test
    fun participantIdentityParsesBackToDevice() {
        assertEquals(
            CallDevice("@alice:example.org", "JLAFKJWSCS"),
            callDeviceFromParticipantIdentity("@alice:example.org:JLAFKJWSCS")
        )
        // Server part with a port — device id still resolves after the LAST colon.
        assertEquals(
            CallDevice("@alice:example.org:8448", "DEV1"),
            callDeviceFromParticipantIdentity("@alice:example.org:8448:DEV1")
        )
        assertNull(callDeviceFromParticipantIdentity("alice:example.org:D"))  // no leading @
        assertNull(callDeviceFromParticipantIdentity("@alice:example.org"))   // user:server only
        assertNull(callDeviceFromParticipantIdentity("@alice:example.org:"))  // empty device
        assertNull(callDeviceFromParticipantIdentity(""))
    }

    @Test
    fun deliveredKeyIndexTracksHighestConfirmedPerDevice() {
        val ledger = DeliveredKeyIndex()
        assertTrue(ledger.isUnderdelivered("@a:s:D1", 0)) // nothing delivered yet
        ledger.markDelivered("@a:s:D1", 0)
        assertFalse(ledger.isUnderdelivered("@a:s:D1", 0))
        assertTrue(ledger.isUnderdelivered("@a:s:D1", 1)) // rotation moved ahead
        ledger.markDelivered("@a:s:D1", 1)
        assertFalse(ledger.isUnderdelivered("@a:s:D1", 1))
        ledger.markDelivered("@a:s:D1", 0)                // stale mark never regresses
        assertFalse(ledger.isUnderdelivered("@a:s:D1", 0))
        assertTrue(ledger.snapshot() == mapOf("@a:s:D1" to 1))
        // Devices are independent slots.
        assertTrue(ledger.isUnderdelivered("@a:s:D2", 0))
    }

    @Test
    fun latecomerTrackerFlagsOnlyKeylessParticipantsPastGrace() {
        val tracker = LatecomerTracker()
        tracker.observe(listOf("@a:s:D1", "@b:s:D2"), ownIdentity = "@me:s:ME", nowMs = 1_000L)
        // Own identity is never tracked even if it appears in the room view.
        tracker.observe(listOf("@a:s:D1", "@b:s:D2", "@me:s:ME"), ownIdentity = "@me:s:ME", nowMs = 2_000L)
        // D2 appeared at 1000; before firstSeen+grace it is not yet overdue.
        assertEquals(emptyList(), tracker.overdue(setOf("@a:s:D1"), nowMs = 8_999L, graceMs = 8_000L))
        // At exactly firstSeen+grace it is overdue.
        assertEquals(listOf("@b:s:D2"), tracker.overdue(setOf("@a:s:D1"), nowMs = 9_000L, graceMs = 8_000L))
        // Once its key arrives it drops out of the overdue list.
        assertEquals(emptyList(), tracker.overdue(setOf("@a:s:D1", "@b:s:D2"), nowMs = 9_000L, graceMs = 8_000L))
    }

    @Test
    fun latecomerTrackerRestampsRejoinersAndPrunesLeavers() {
        val tracker = LatecomerTracker()
        tracker.observe(listOf("@a:s:D1"), ownIdentity = "@me:s:ME", nowMs = 1_000L)
        // Participant leaves — pruning forgets their stamp...
        tracker.observe(emptyList(), ownIdentity = "@me:s:ME", nowMs = 2_000L)
        // ...so on rejoin the grace window restarts from the new appearance.
        tracker.observe(listOf("@a:s:D1"), ownIdentity = "@me:s:ME", nowMs = 10_000L)
        assertEquals(emptyList(), tracker.overdue(setOf(), nowMs = 17_999L, graceMs = 8_000L))
        assertEquals(listOf("@a:s:D1"), tracker.overdue(setOf(), nowMs = 18_000L, graceMs = 8_000L))
    }

    @Test
    fun generatedKeysAreUniqueAndCorrectSized() {
        val a = CallMediaKeys.generateKey()
        val b = CallMediaKeys.generateKey()
        assertEquals(16, a.size)
        assertEquals(16, b.size)
        assertFalse(a.contentEquals(b))
    }
}
