package livekit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure logic for the matrixRTC per-device E2EE media-key exchange (Element Call
 * deployed wire format). No trixnity/JVM types here so the wire shapes and the
 * rotation rules stay unit-testable in isolation.
 *
 * Wire event: to-device `io.element.call.encryption_keys`, olm-wrapped per device:
 * `{"keys":{"index":0,"key":"<padded base64 of 16 raw bytes>"},
 *   "room_id":"!r:s","member":{"id":"@u:s:DEV","claimed_device_id":"DEV"},
 *   "session":{"call_id":"","application":"m.call","scope":"m.room"},"sent_ts":ms}`
 */
data class CallDevice(val userId: String, val deviceId: String)

/**
 * LiveKit participant identities are `${userId}:${deviceId}` where userId
 * itself contains colons (and the server part may carry a port), so the device
 * id is everything after the LAST colon.
 */
fun callDeviceFromParticipantIdentity(identity: String): CallDevice? {
    if (!identity.startsWith("@")) return null
    val lastColon = identity.lastIndexOf(':')
    if (lastColon <= 1 || lastColon == identity.length - 1) return null
    // A bare "user:server" without a device segment must not pass.
    if (identity.indexOf(':') == lastColon) return null
    return CallDevice(userId = identity.substring(0, lastColon), deviceId = identity.substring(lastColon + 1))
}

/**
 * Us→them delivery ledger: the highest key index each peer device has CONFIRMED
 * received (marked only after a successful olm sendToDevice). Any live device
 * whose entry lags our current index is still decrypting nothing we send and
 * belongs in every resend pass — diff-only distribution (Element semantics)
 * structurally misses peers that drop out of the roster between emissions or
 * reset mid-call, which orphaned real calls in the field.
 */
class DeliveredKeyIndex {
    private val delivered = mutableMapOf<String, Int>()

    @Synchronized
    fun markDelivered(identity: String, keyIndex: Int) {
        if ((delivered[identity] ?: -1) < keyIndex) delivered[identity] = keyIndex
    }

    @Synchronized
    fun invalidate(identity: String) {
        delivered.remove(identity)
    }

    @Synchronized
    fun isUnderdelivered(identity: String, currentIndex: Int): Boolean =
        (delivered[identity] ?: -1) < currentIndex

    @Synchronized
    fun snapshot(): Map<String, Int> = delivered.toMap()
}

/**
 * RX-dead latecomer bookkeeping: stamps when each LiveKit participant identity
 * FIRST appeared in the room, prunes identities that left, and reports the ones
 * still present but that have never delivered a media key past a grace window.
 * Throttled Element clients (background tabs, mobile) reliably ignore plain
 * joins — they only respond to membership DIFFS — so an overdue identity is the
 * signal to fire our own leave+rejoin nudge instead of staying deaf for the
 * full reactive-watcher grace.
 */
class LatecomerTracker {
    private val firstSeenMs = mutableMapOf<String, Long>()

    @Synchronized
    fun observe(livekitIdentities: Collection<String>, ownIdentity: String, nowMs: Long, onNewParticipant: ((String) -> Unit)? = null) {
        val live = livekitIdentities.toSet()
        firstSeenMs.keys.retainAll(live)
        for (identity in live) {
            if (identity == ownIdentity) continue
            if (!firstSeenMs.containsKey(identity)) {
                firstSeenMs[identity] = nowMs
                onNewParticipant?.invoke(identity)
            }
        }
    }

    @Synchronized
    fun overdue(receivedIdentities: Set<String>, nowMs: Long, graceMs: Long): List<String> =
        firstSeenMs.entries
            .filter { (identity, seen) -> identity !in receivedIdentities && nowMs - seen >= graceMs }
            .map { it.key }
            .sorted()

    @Synchronized
    fun snapshot(): Map<String, Long> = firstSeenMs.toMap()
}

expect fun secureRandomBytes(count: Int): ByteArray

object CallMediaKeys {
    const val EVENT_TYPE = "io.element.call.encryption_keys"
    const val KEY_BYTES = 16
    const val KEY_INDEX_MODULUS = 256
    const val USE_KEY_DELAY_MS = 1000L

    /**
     * Element's joiner grace period, kept only so the divergence from it is
     * explicit rather than silent. Orbit no longer rotates on this boundary; a
     * joiner is always handed the current index instead. See
     * [decideMembershipReaction] for why.
     */
    const val RESEND_GRACE_PERIOD_MS = 10_000L
    const val SESSION_APPLICATION = "m.call"
    const val SESSION_SCOPE = "m.room"

    fun participantIdentity(userId: String, deviceId: String): String = "$userId:$deviceId"

    fun generateKey(): ByteArray = secureRandomBytes(KEY_BYTES).also { require(it.size == KEY_BYTES) }

    fun buildEncryptionKeysPayload(
        keyIndex: Int,
        key: ByteArray,
        roomId: String,
        memberId: String,
        claimedDeviceId: String,
        sentTsMs: Long
    ): JsonObject = buildJsonObject {
        putJsonObject("keys") {
            put("index", keyIndex)
            put("key", encodePaddedBase64(key))
        }
        put("room_id", roomId)
        putJsonObject("member") {
            put("id", memberId)
            put("claimed_device_id", claimedDeviceId)
        }
        putJsonObject("session") {
            put("call_id", "")
            put("application", SESSION_APPLICATION)
            put("scope", SESSION_SCOPE)
        }
        put("sent_ts", sentTsMs)
    }

    data class ParsedKeys(
        val keyIndex: Int,
        val key: ByteArray,
        val claimedDeviceId: String?,
        val roomId: String?,
        val sentTsMs: Long?
    )

    fun parseEncryptionKeysPayload(raw: JsonObject): ParsedKeys? {
        val keysObj = raw["keys"] as? JsonObject ?: return null
        val keyIndex = keysObj["index"]?.jsonPrimitive?.intOrNull ?: return null
        if (keyIndex !in 0 until KEY_INDEX_MODULUS) return null
        val keyB64 = keysObj["key"]?.jsonPrimitive?.contentOrNull ?: return null
        val key = runCatching { decodeBase64(keyB64) }.getOrNull() ?: return null
        if (key.size != KEY_BYTES) return null
        val claimedDeviceId = (raw["member"] as? JsonObject)
            ?.get("claimed_device_id")?.jsonPrimitive?.contentOrNull
        val roomId = raw["room_id"]?.jsonPrimitive?.contentOrNull
        val sentTsMs = raw["sent_ts"]?.jsonPrimitive?.longOrNull
        return ParsedKeys(keyIndex, key, claimedDeviceId, roomId, sentTsMs)
    }

    /**
     * Receiver-side dedup mirroring Element's OutdatedKeyFilter: for each
     * (sender, device, keyIndex) only the newest sent_ts is accepted; older or
     * equal replays are dropped. Device is part of the slot so two devices of
     * the same user (both emitting index 0) never evict each other's keys.
     */
    class OutdatedKeyFilter {
        private val newestSentTs = mutableMapOf<String, Long>()

        @Synchronized
        fun accept(sender: String, deviceId: String?, keyIndex: Int, sentTsMs: Long): Boolean {
            val slot = "$sender\u0000${deviceId ?: ""}\u0000$keyIndex"
            val known = newestSentTs[slot]
            if (known != null && sentTsMs <= known) return false
            newestSentTs[slot] = sentTsMs
            return true
        }
    }

    /** Own sending device's current media key + index + creation time. */
    class OwnKeyRing(private val nowMs: () -> Long) {
        var currentKey: ByteArray = generateKey(); private set
        var currentIndex: Int = 0; private set
        var createdAtMs: Long = nowMs(); private set

        /** New random key, index+1 mod 256. Returns the new index. */
        fun rotate(): Int {
            currentKey = generateKey()
            currentIndex = (currentIndex + 1) % KEY_INDEX_MODULUS
            createdAtMs = nowMs()
            return currentIndex
        }

        fun ageMs(): Long = nowMs() - createdAtMs
    }

    /**
     * Devices that published a REAL `membership: leave` for their call.
     *
     * The roster deliberately drops leave events (MatrixRtcMembership skips them), so
     * a vanished device and a departed device are indistinguishable from the roster
     * alone. Every disappearance was therefore treated as a leaver -- including a
     * participant whose connection merely dropped, who leaves a ghost membership
     * behind and vanishes from the roster when it reconciles.
     *
     * That inference is what made Orbit rotate on a cut call. Rotation is a shared
     * mutation: every peer must learn the new index before audio resumes, and a peer
     * that misses it is deaf with no recovery. It is worth doing for a real departure,
     * where it revokes a departed member's ability to decrypt anything sent later. It
     * is not worth doing for a network blip, where nothing about the key material
     * changed and there is nothing to revoke.
     *
     * This records the positive evidence that Element itself acts on: an explicit
     * leave event. Rotation is then gated on that rather than on absence.
     */
    class ConfirmedLeavers(private val nowMs: () -> Long) {
        private val left = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())

        /** Record that [membershipId] published a leave event. */
        fun record(membershipId: String) {
            left[membershipId] = nowMs()
        }

        /** Whether [membershipId] was seen leaving, within a recent window. */
        fun isConfirmedLeaver(membershipId: String): Boolean {
            val at = left[membershipId] ?: return false
            if (nowMs() - at > LEAVE_MEMORY_MS) {
                left.remove(membershipId)
                return false
            }
            return true
        }

        /** Drop anything older than the window so the map cannot grow unbounded. */
        fun prune() {
            val cutoff = nowMs() - LEAVE_MEMORY_MS
            left.entries.removeAll { it.value < cutoff }
        }

        private companion object {
            /**
             * How long a leave stays authoritative. Long enough to cover a slow
             * roster reconciliation landing after the leave, short enough that a
             * device which rejoins and leaves again is judged on its latest event.
             */
            const val LEAVE_MEMORY_MS = 120_000L
        }
    }

    sealed class MembershipReaction {
        object None : MembershipReaction()
        object Rotate : MembershipReaction()
        object ResendCurrent : MembershipReaction()
    }

    /**
     * Rotation rules.
     *
     * A confirmed leaver rotates immediately: it revokes a departed member's ability
     * to decrypt anything sent afterwards, which is the one reason rotation exists.
     *
     * A joiner never rotates. Element Call resends the current key while it is
     * younger than [RESEND_GRACE_PERIOD_MS] and rotates once it is older, on the
     * reasoning that a long-lived key has been held by many participants including
     * some who have since left, so a newcomer should not be handed it.
     *
     * That reasoning does not transfer here, and following it literally made rejoin
     * impossible. The grace period is 10s, so in any call older than ten seconds --
     * that is, every real call -- the condition can never be satisfied and
     * "joiner within grace" is unreachable. Every rejoin therefore rotated.
     *
     * Which is precisely the reported failure. Run 73, after the drop-vs-departure
     * fix removed the other trigger, left every remaining rotation reading
     * `confirmedLeavers=0 joiners=1`:
     *
     *     rotating key: confirmedLeavers=0 joiners=1 keyAgeMs=27381
     *     rotating key: confirmedLeavers=0 joiners=1 keyAgeMs=13459
     *     rotating key: confirmedLeavers=0 joiners=1 keyAgeMs=25484
     *
     * One rotation per rejoin, every time. hellishsaa cut and rejoined three times
     * and Orbit's index went 0 -> 1 -> 2 -> 3, matching the "first rejoin worked,
     * the second and third did not" report exactly.
     *
     * The security argument for rotating does not actually apply. Rotation is
     * load-bearing only for revoking someone who has LEFT, and that case is
     * already covered above by the leaver rule -- when a participant departs, the
     * key rotates and the departed member is left holding a key nothing further is
     * encrypted under. A joiner who arrives afterwards is handed the current index
     * by the ordinary delivery paths, and nobody who should be locked out gains
     * anything from that index changing as well.
     *
     * Meanwhile the cost is the whole call. Rotation desynchronises every
     * participant at once: each must learn the new index before audio resumes, and
     * a peer that misses the window is deaf with no recovery of its own. So a rule
     * whose only function was to hand a newcomer a fresher key was making rejoins
     * -- a completely ordinary event, someone dropping and coming back -- the most
     * dangerous thing that could happen in a call.
     */
    fun decideMembershipReaction(leaverCount: Int, joinerCount: Int, currentKeyAgeMs: Long): MembershipReaction =
        when {
            leaverCount > 0 -> MembershipReaction.Rotate
            // A joiner gets the current index. Never a rotation -- see the KDoc for
            // why that branch was unreachable in practice and what it cost.
            joinerCount > 0 -> MembershipReaction.ResendCurrent
            else -> MembershipReaction.None
        }

    // --- padded base64 (RFC 4648, '+' '/'), decoder tolerates unpadded input ---

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encodePaddedBase64(bytes: ByteArray): String {
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[n ushr 18 and 63])
                .append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63])
                .append(ALPHABET[n and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest == 1) {
            val n = bytes[i].toInt() and 0xFF
            out.append(ALPHABET[n ushr 2 and 63])
                .append(ALPHABET[n shl 4 and 63])
                .append("==")
        } else if (rest == 2) {
            val n = (bytes[i].toInt() and 0xFF shl 8) or (bytes[i + 1].toInt() and 0xFF)
            out.append(ALPHABET[n ushr 10 and 63])
                .append(ALPHABET[n ushr 4 and 63])
                .append(ALPHABET[n shl 2 and 63])
                .append('=')
        }
        return out.toString()
    }

    fun decodeBase64(value: String): ByteArray? {
        val clean = value.filterNot { it == '=' || it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (clean.isEmpty()) return emptyArray<Byte>().toByteArray()
        if (clean.length % 4 == 1) return null
        val rev = IntArray(128) { -1 }
        for (idx in ALPHABET.indices) rev[ALPHABET[idx].code] = idx
        val out = ByteArray(clean.length * 3 / 4)
        var acc = 0
        var bits = 0
        var o = 0
        for (ch in clean) {
            val v = if (ch.code < 128) rev[ch.code] else -1
            if (v < 0) return null
            acc = acc shl 6 or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (acc ushr bits and 0xFF).toByte()
            }
        }
        return out
    }
}
