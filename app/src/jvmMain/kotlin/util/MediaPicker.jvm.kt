package util

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files

@Composable
actual fun rememberFilePickerLauncher(
    onFileSelected: (bytes: ByteArray, filename: String, mimeType: String) -> Unit
): () -> Unit {
    return {
        // Run file dialog on a separate thread to avoid blocking Compose UI
        CoroutineScope(Dispatchers.IO).launch {
            val dialog = FileDialog(null as Frame?, "Select Attachment", FileDialog.LOAD)
            dialog.isVisible = true
            val fileStr = dialog.file
            val dirStr = dialog.directory
            if (fileStr != null && dirStr != null) {
                val file = File(dirStr, fileStr)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val mimeType = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                    onFileSelected(bytes, file.name, mimeType)
                }
            }
        }
    }
}
