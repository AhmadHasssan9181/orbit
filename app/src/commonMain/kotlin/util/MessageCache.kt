package util

import ui.model.CachedMessage

/**
 * On-disk store for recent messages, one file per room.
 *
 * ## Why this is expect/actual
 *
 * Everything that reads or writes it lives in `commonMain` -- the repository and
 * the UI both need to see the same cache -- but a filesystem only exists on the
 * JVM target. The split is the same one [FilePicker] already uses.
 */
internal expect fun messageCache(): MessageCacheStore

/**
 * A room's cached messages.
 *
 * Deliberately not a Flow: the cache is written by the sync path from a
 * background thread and read by the UI when a room opens, and neither side wants
 * to be coupled to the other's lifetime. A room opens once; a background refresh
 * that nobody watches is exactly what a cache does.
 */
internal interface MessageCacheStore {
    /**
     * Cached messages for [roomId], oldest first. Empty when nothing is cached,
     * when the room is unknown, or when the file cannot be read -- a cache that
     * fails to load must never be able to fail a room opening.
     */
    fun read(roomId: String): List<CachedMessage>

    /**
     * Replaces [roomId]'s cached messages with [messages], keeping the most recent
     * [maxPerRoom].
     *
     * Written off the caller's thread by the implementation, and it must never
     * throw: losing the cache is a performance problem, not a correctness one.
     */
    fun write(roomId: String, messages: List<CachedMessage>, maxPerRoom: Int = 500)

    /** Merges [messages] into [roomId]'s cache, de-duplicating by event id. */
    fun merge(roomId: String, messages: List<CachedMessage>, maxPerRoom: Int = 500)

    /** Drops [roomId]'s cache. Used on logout and on leaving a room for good. */
    fun clear(roomId: String)

    /** Drops everything. Used on logout. */
    fun clearAll()
}