package repository

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the `m.room.banner` state event out of a `GET /rooms/{id}/state` array.
 *
 * ## Why this is a separate pure function
 *
 * The obvious place for this is [MatrixContext.cachedRoomBanner], which is where it
 * runs -- but that reads a private cache populated by a network call, so a test
 * could only ever assert it returns null for a room it never fetched. Splitting out
 * the parse means the part with actual logic can be pinned directly, and the cache
 * lookup stays the two lines it should be.
 *
 * ## The shape of the thing being parsed
 *
 * `m.state` returns a flat array of full state events, each with its own `type` and
 * `content`. A room with no banner simply has no such event, which is the common
 * case and must be null rather than an error.
 *
 * ## Why it is as defensive as it is
 *
 * `jsonObject` throws on anything that is not an object, and this array is
 * untrusted input: a homeserver is free to return whatever it likes. The same
 * assertion threw a real crash in `isVoiceRoom` and took the whole drawer down with
 * an error dialog, which is why [MatrixContext] parses these with `as?` and a catch
 * throughout. A banner is decoration. It must never be able to cost anyone their
 * window.
 */
internal fun roomBannerMxcFrom(events: Array<JsonElement>): String? =
    try {
        events.firstNotNullOfOrNull { element ->
            val raw = element as? JsonObject ?: return@firstNotNullOfOrNull null
            if ((raw["type"] as? JsonPrimitive)?.contentOrNull != "m.room.banner") {
                return@firstNotNullOfOrNull null
            }
            (raw["content"] as? JsonObject)?.get("url") as? JsonPrimitive
        }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }