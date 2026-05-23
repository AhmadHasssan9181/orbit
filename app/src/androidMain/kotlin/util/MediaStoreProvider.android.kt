package util

import com.noobdev.orbit.OrbitApp
import de.connect2x.trixnity.client.media.MediaStore
import de.connect2x.trixnity.client.media.okio.OkioMediaStore
import okio.FileSystem
import ui.screen.OrbitApp
import java.io.File

actual fun createMediaStore(): MediaStore {
    val mediaDir = File(OrbitApp.instance.filesDir, "orbit_media")
    mediaDir.mkdirs()
    return OkioMediaStore(FileSystem.SYSTEM, mediaDir.absolutePath)
}