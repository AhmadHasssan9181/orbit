package util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun pickImageFile(): ByteArray? = withContext(Dispatchers.IO) {
    var result: ByteArray? = null
    try {
        val dialog = FileDialog(null as Frame?, "Select Profile Picture", FileDialog.LOAD)
        dialog.setFile("*.png;*.jpg;*.jpeg;*.gif")
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
            val f = File(dir, file)
            if (f.exists()) {
                result = f.readBytes()
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    result
}
