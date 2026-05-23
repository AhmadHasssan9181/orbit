package util

import com.noobdev.orbit.OrbitApp
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

actual fun getMediaStorePath(): Path {
    val mediaDir = File(OrbitApp.instance.filesDir, "orbit_media")
    mediaDir.mkdirs()
    return mediaDir.absolutePath.toPath()
}