package util

import okio.Path
import okio.Path.Companion.toPath
import java.io.File

actual fun getMediaStorePath(): Path {
    val mediaDir = File(System.getProperty("user.home"), ".orbit/media")
    mediaDir.mkdirs()
    return mediaDir.absolutePath.toPath()
}