package repository

import io.ktor.util.date.getTimeMillis
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The rules for "is this MatrixRTC membership event someone who is actually in
 * the call right now".
 *
 * This exists as a single shared object because there were TWO writers to
 * `activeCallMembers[roomId]` with different rules:
 *
 *  - the 3-second roster poller, which filtered properly (active membership,
 *    unexpired window);
 *  - the sync collector, which added a sender on ANY non-leave event.
 *
 * They wrote to the same flow and overwrote each other, so a stale event could
 * repopulate a roster the poller had just pruned -- which is how people who
 * were not in the call ended up listed as if they were.
 *
 * The subtlety is that an `m.call.member` event is a MEMBERSHIP RECORD, not a
 * presence signal: it asserts "this device was in a call from `created_ts` for
 * `expires` ms". Nobody publishes a leave when that window closes, the
 * membership simply lapses. So a non-leave event is only evidence of presence
 * while its window is still open, and a room can hold records for calls other
 * than the current one.
 */
internal object MatrixRtcMembership {

    /**
     * Slack allowed when comparing a membership's `created_ts` against the call's
     * start. Clock skew between the homeserver that stamped the two events is
     * normally sub-second, but a locally-generated membership can land a little
     * before the definition event is committed.
     */
    const val CALL_START_SKEW_MS = 120_000L

    private fun jsonString(content: JsonObject, key: String): String? =
        runCatching { (content[key] as? JsonPrimitive)?.content }.getOrNull()

    private fun jsonLong(content: JsonObject, key: String): Long? =
        runCatching { content[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    /**
     * When the CURRENT call began, taken from the `origin_server_ts` of the room's
     * `m.call` definition event.
     *
     * This is the second half of "is someone actually in this call", and the part
     * that was missing. `expires` on an `m.call.member` event is a LONG window --
     * Element publishes hours, not minutes -- so the expiry check alone happily
     * accepts membership records written by a call that ended an hour ago. A room
     * accumulates one `m.call.member` state event per device per call and nobody
     * deletes them at hangup, so the roster filled up with everyone who has ever
     * been in that room while a call was going on. Nothing you can do about a
     * lapsed window repairs this; you have to compare against the call.
     *
     * Null when the room has no live definition event, in which case callers keep
     * the expiry-only behaviour rather than blanking a roster we cannot date.
     */
    fun callStartFromDefinition(definitionContent: JsonObject, originServerTs: Long?): Long? =
        originServerTs?.takeIf { it > 0 }

    /**
     * Active, unexpired membership objects carried by the event.
     *
     * [minCreatedTs] is the current call's start time; memberships older than that
     * belong to a previous call in the same room and are rejected. Pass null when
     * the call start is unknown.
     */
    fun activeUnexpiredMemberships(content: JsonObject, minCreatedTs: Long? = null): List<JsonObject> {
        val now = getTimeMillis()
        val out = mutableListOf<JsonObject>()

        val memberships = content["memberships"] as? JsonArray
        if (memberships != null) {
            for (elem in memberships) {
                val obj = elem as? JsonObject ?: continue
                if (jsonString(obj, "membership") == "leave") continue
                val createdTs = jsonLong(obj, "created_ts")
                val expires = jsonLong(obj, "expires")
                if (createdTs != null && expires != null && createdTs + expires <= now) continue
                if (minCreatedTs != null && createdTs != null && createdTs + CALL_START_SKEW_MS < minCreatedTs) continue
                if (obj.containsKey("membershipID") || obj.containsKey("membership_id") ||
                    obj.containsKey("device_id") || obj.containsKey("application") ||
                    obj.containsKey("focus_active") || obj.containsKey("foci_active") ||
                    obj.containsKey("scope") || obj.containsKey("m.call.intent")
                ) {
                    out.add(obj)
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Legacy flat shape: the fields sit directly on the event content.
        if (jsonString(content, "membership") == "leave") return emptyList()
        val flatCreated = jsonLong(content, "created_ts")
        val flatExpires = jsonLong(content, "expires")
        if (flatCreated != null && flatExpires != null && flatCreated + flatExpires <= now) {
            return emptyList()
        }
        if (minCreatedTs != null && flatCreated != null && flatCreated + CALL_START_SKEW_MS < minCreatedTs) {
            return emptyList()
        }
        val looksLikeMembership = content.containsKey("membershipID") ||
            content.containsKey("membership_id") ||
            content.containsKey("device_id") ||
            content.containsKey("application") ||
            content.containsKey("focus_active") || content.containsKey("foci_active") ||
            content.containsKey("scope") ||
            content.containsKey("m.call.intent")
        return if (looksLikeMembership) listOf(content) else emptyList()
    }

    /** True when this event currently asserts live call membership. */
    fun isCurrentlyValid(content: JsonObject, minCreatedTs: Long? = null): Boolean =
        activeUnexpiredMemberships(content, minCreatedTs).isNotEmpty()

    /** Milliseconds until this membership lapses; 0 when already gone. */
    fun expiresRemainingMs(content: JsonObject): Long {
        val valid = activeUnexpiredMemberships(content)
        if (valid.isEmpty()) return 0L
        val now = getTimeMillis()
        return valid.mapNotNull { obj ->
            val createdTs = jsonLong(obj, "created_ts") ?: return@mapNotNull null
            val expires = jsonLong(obj, "expires") ?: return@mapNotNull null
            createdTs + expires - now
        }.maxOrNull() ?: 3_600_000L
    }
}
