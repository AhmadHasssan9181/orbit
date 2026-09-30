package repository

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `m.room.topic` for a room, so the channel header has a subtitle.
 *
 * ## Why this exists
 *
 * `createRoom` writes the topic (its `createRoom` request carries `topic = ...`)
 * and `ChatHeader` renders `channel.topic` as a subtitle. Nothing in between ever
 * parsed it back out of room state, so `OrbitChannel.topic` was always the `""`
 * default and the subtitle was always empty.
 *
 * That is the shape of defect this file exists to end: two halves of a feature
 * wired to each other with the connecting piece missing, so the feature could
 * never be seen working. It presented as a UI bug ("the topic line never shows")
 * and it is a repository gap.
 *
 * ## Why it costs nothing extra
 *
 * This does not fetch anything of its own. It reads `m.room.topic` out of
 * [MatrixContext.roomStateEvents], which is the same `GET /rooms/{id}/state`
 * response `isVoiceRoom` already retrieves and caches for every room the drawer
 * lists. Before this existed, that response was parsed for the voice probe and
 * discarded; now two features read one response, so the request count goes DOWN
 * relative to either feature fetching its own.
 *
 * ## Encrypted rooms
 *
 * `m.state` over plain HTTP returns ciphertext for encrypted event types, so in
 * an encrypted room the topic is not readable this way and resolves to `""` --
 * the same value the field defaulted to before, so this can only ever improve.
 * Reading it properly needs the decrypted store. That is a separate piece of
 * work, and it is recorded as such rather than papered over.
 */
internal class MatrixRoomTopics(private val context: MatrixContext) {

    /**
     * The topic for [roomId], or `""` when there is none or it cannot be read.
     *
     * Non-suspending by design: it reads the shared state cache the drawer has
     * already populated, and returns `""` if it has not, rather than blocking a
     * compose pass on a network call. The next drawer open picks it up.
     */
    fun topicOf(roomId: String): String = context.cachedRoomTopic(roomId)

    /** Drop a room's cached state so the next read re-fetches. */
    fun invalidate(roomId: String) = context.invalidateRoomState(roomId)

    fun clear() = context.clearRoomStateCache()
}
