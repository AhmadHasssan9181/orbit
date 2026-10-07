package util

import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/**
 * Locates the on-disk media store, and makes sure only the owner can read it.
 *
 * ## Why this exists
 *
 * `~/.orbit/media` is Trixnity's media cache: every image, voice note and video
 * Orbit has fetched is written here, including **decrypted** media from encrypted
 * rooms. It was created with a bare `mkdirs()`, so on a POSIX host it inherited the
 * process umask -- typically 0644, i.e. readable by every local account.
 *
 * That is the same class of problem as the message cache (see MessageCache.jvm.kt)
 * and the `.credkey` file (see Settings.jvm.kt): the file holds decrypted content and
 * was being created with default permissions.
 *
 * ## What is and is not done here
 *
 * This restricts the permissions of the directory and of the files already in it.
 * It does **not** encrypt the store. On Windows `%USERPROFILE%` is already per-user
 * ACL'd, so permissions are the whole story there; on POSIX, owner-only is a real
 * improvement but at-rest encryption would be the actual answer, and that is a
 * dependency decision rather than a two-line change (SQLCipher, with a migration
 * for an existing store).
 *
 * Best-effort throughout: a filesystem without POSIX permissions must not stop media
 * from loading.
 */
actual fun getMediaStorePath(): Path {
    val mediaDir = File(System.getProperty("user.home"), ".orbit/media")
    mediaDir.mkdirs()
    restrictDirectoryToOwner(mediaDir)
    return mediaDir.absolutePath.toPath()
}

/**
 * Narrow [dir] and everything already inside it to owner-only.
 *
 * Files are included because the directory's mode does not change the mode of files
 * created before this ran: an existing world-readable cache stays readable no matter
 * how tight the directory is. `setReadable(false, false)` / `setWritable(false, false)`
 * are used rather than the POSIX API so the call is portable -- they are no-ops on
 * Windows, where the ACL model already handles it, and effective on POSIX without
 * importing `java.nio.file.attribute` at every call site.
 */
private fun restrictDirectoryToOwner(dir: File) {
    runCatching {
        dir.setReadable(false, false)
        dir.setWritable(false, false)
        dir.setExecutable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(true, true)
        dir.setExecutable(true, true)
        dir.listFiles()?.forEach { file ->
            file.setReadable(false, false)
            file.setWritable(false, false)
            if (file.isDirectory) {
                file.setExecutable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
                file.setExecutable(true, true)
            } else {
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}