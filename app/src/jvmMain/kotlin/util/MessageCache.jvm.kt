package util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ui.model.CachedMessage
import java.io.File

/**
 * JSON-file message cache, one file per room, under `~/.orbit/cache/rooms/`.
 *
 * ## Why files and not SQLite
 *
 * The project already has SQLite on the classpath, but only through Room and the
 * AndroidX bundled driver, which are shaped for an annotated DAO rather than
 * hand-written SQL. Adding a second schema to someone else's database to store
 * "the last few hundred messages" would be a worse trade than a directory of JSON:
 *
 *  - The cache is genuinely small and bounded. The per-room cap keeps a file to a
 *    few hundred KB, so a full rewrite is cheap.
 *  - It is disposable. If the format ever breaks, deleting the directory is a
 *    complete recovery, and nothing else in the app depends on it.
 *  - It is inspectable, which has already been worth something: the file can be
 *    read with a text editor when something looks wrong.
 *
 * ## Why it cannot break a room opening
 *
 * Every read is wrapped. A malformed file, a missing directory, an unreadable
 * cache after an upgrade -- all of them produce an empty list, which is exactly
 * what the app did before the cache existed. The cache is allowed to be useless;
 * it is not allowed to be a new way for chat to fail.
 */
internal actual fun messageCache(): MessageCacheStore = JvmMessageCacheStore

private object JvmMessageCacheStore : MessageCacheStore {

    private val json = Json {
        // Tolerant on purpose: a cache written by an older build may be missing a
        // field that has since gained a default, and that should cost one message,
        // not the whole room.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val serializer = ListSerializer(CachedMessage.serializer())

    /**
     * Writes happen off the caller's thread. A cache write is disk I/O on a path
     * the UI is not waiting for, and `sync` exists so that a crash cannot leave a
     * half-written file where a good one used to be.
     */
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun cacheDir(): File =
        OrbitPaths.cacheDir

    /**
     * SECURITY: this cache holds **decrypted** E2EE message bodies.
     *
     * `CachedMessage.body` is plaintext, for every room, written from the sync path
     * as messages arrive. On Windows `%USERPROFILE%` is per-user ACL'd so the blast
     * radius is limited to processes running as that user; on a POSIX host -- and
     * the app is not gated to Windows, `Browser.jvm.kt` still has `open` and
     * `xdg-open` branches -- the default umask gives 0644, i.e. world-readable.
     *
     * So the directory and every file in it are owner-only. This mirrors what
     * `Settings.jvm.kt` already does for `.credkey`; the asymmetry was the bug, not
     * the absence of a fix.
     *
     * Best-effort by design: a filesystem with no POSIX permissions must not stop
     * the cache from working. The cache is allowed to be useless; it is not allowed
     * to be a new way for chat to fail, which is the same rule this class already
     * applies to every read.
     */
    private fun restrictToOwner(target: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                target.toPath(),
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            )
        }.onFailure {
            // Windows, or a non-POSIX mount. Nothing to do; the ACL model there is
            // per-user already. Deliberately not logged per call -- this runs on
            // every cache write and would flood the log.
        }
    }

    /**
     * Room ids contain `!`, `:`, `/` and can be very long, so they cannot be a
     * filename. A hash of the id is: stable across runs, filesystem-safe, and two
     * different rooms cannot collide in any way that matters -- and if one ever
     * did, the result would be a room showing another room's recent messages
     * rather than a crash, which is survivable and rare.
     */
    private fun fileFor(roomId: String): File {
        // A positive hex digest so the name is never mistaken for a flag.
        val safe = roomId.fold(17L) { acc, c -> acc * 31 + c.code } and 0x7FFFFFFFFFFFFFFF
        return File(cacheDir(), "r-$safe.json")
    }

    override fun read(roomId: String): List<CachedMessage> = try {
        val file = fileFor(roomId)
        if (!file.isFile) emptyList()
        else json.decodeFromString(serializer, file.readText())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Includes JsonDecodingException and IOException. A cache that cannot be
        // read must behave exactly like a cache that does not exist.
        emptyList()
    }

    override fun write(roomId: String, messages: List<CachedMessage>, maxPerRoom: Int) {
        writerScope.launch {
            try {
                writeNow(roomId, messages, maxPerRoom)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Losing the cache costs a slower room next launch. Never worth
                // propagating out of a background write.
            }
        }
    }

    override fun merge(roomId: String, messages: List<CachedMessage>, maxPerRoom: Int) {
        if (messages.isEmpty()) return
        writerScope.launch {
            try {
                val existing = read(roomId)
                // Replace in place, append if new.
                //
                // Replacing in place is the part that matters: a message arriving
                // again with a new reaction or a fresh read receipt must overwrite
                // the old copy rather than sit beside it, AND must keep its position.
                // Appending unconditionally would put an edited message at the end
                // of the room every time somebody reacted to it.
                val byId = LinkedHashMap<String, CachedMessage>(existing.size + messages.size)
                existing.forEach { byId[it.id] = it }
                messages.forEach { incoming -> byId[incoming.id] = incoming }
                writeNow(roomId, byId.values.toList(), maxPerRoom)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
            }
        }
    }

    /**
     * Write-then-move.
     *
     * A crash or a full disk partway through a write would otherwise leave a
     * truncated JSON file, and every later read of that room would fail to decode
     * -- so the room would lose its cache permanently because of one bad write.
     * The rename is atomic on the filesystems this runs on, so the previous good
     * file survives instead.
     *
     * The rename fallback exists for the case where it is not (an antivirus
     * holding the target open on Windows is the realistic one): copy the bytes
     * over, then drop the temp.
     */
    private fun writeNow(roomId: String, messages: List<CachedMessage>, maxPerRoom: Int) {
        val file = fileFor(roomId)
        file.parentFile?.mkdirs()
        // Restrict the directory too: a file can be 0600 inside a world-traversable
        // directory, and the directory is what a reader enumerates.
        file.parentFile?.let { restrictToOwner(it) }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Create the temp file owner-only BEFORE the message bodies reach disk. A
        // plain `writeText` applies the umask, so the plaintext would exist
        // world-readable for the duration of the write.
        val bytes = json.encodeToString(serializer, messages.takeLast(maxPerRoom))
        runCatching {
            java.nio.file.Files.createFile(
                tmp.toPath(),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
                )
            )
        }
        tmp.writeText(bytes)
        // Also narrow a pre-existing temp file left by an older build.
        restrictToOwner(tmp)
        if (!tmp.renameTo(file)) {
            // rename failed (antivirus holding the target on Windows). Copy over,
            // then drop the temp -- and make sure the destination is restricted too,
            // since it may have been created by an older build with loose modes.
            file.writeText(tmp.readText())
            restrictToOwner(file)
            tmp.delete()
        } else {
            // rename preserves the temp file's mode, but an older `file` may still
            // exist with looser permissions if the rename replaced it on some
            // filesystems, so normalise both ends.
            restrictToOwner(file)
        }
    }

    override fun clear(roomId: String) {
        writerScope.launch {
            runCatching { fileFor(roomId).delete() }
        }
    }

    override fun clearAll() {
        writerScope.launch {
            runCatching { cacheDir().listFiles()?.forEach { it.delete() } }
        }
    }
}