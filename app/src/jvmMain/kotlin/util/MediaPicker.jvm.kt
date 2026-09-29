package util

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.suspendCoroutine
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.resume

/** Chat attachments above this size are rejected rather than loaded into the heap. */
private const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024

@Composable
actual fun rememberFilePickerLauncher(
    onFileSelected: (bytes: ByteArray, filename: String, mimeType: String) -> Unit
): () -> Unit {
    return {
        // A named scope so the work is not tied to a transient UI coroutine; the
        // previous code created a throwaway scope per click with no handle.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val picked = showFileDialogOnEdt() ?: run {
                println("DEBUG [MediaPicker] file dialog cancelled by the user")
                return@launch
            }
            if (!picked.exists()) {
                println("WARN [MediaPicker] selected file no longer exists: ${picked.absolutePath}")
                return@launch
            }
            // Guard BEFORE readBytes(): an unbounded read of a multi-GB file OOMs
            // the JVM, and the previous code had no size check at all.
            val size = picked.length()
            if (size <= 0) {
                println("WARN [MediaPicker] refusing empty file ${picked.absolutePath}")
                return@launch
            }
            if (size > MAX_ATTACHMENT_BYTES) {
                println(
                    "WARN [MediaPicker] refusing ${picked.name}: ${size / (1024 * 1024)} MB " +
                        "exceeds the ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB attachment limit"
                )
                return@launch
            }
            val bytes = runCatching { picked.readBytes() }.getOrElse { error ->
                println("ERROR [MediaPicker] failed to read ${picked.absolutePath}: ${error.message}")
                return@launch
            }
            val mimeType = Files.probeContentType(picked.toPath()) ?: "application/octet-stream"
            onFileSelected(bytes, picked.name, mimeType)
        }
    }
}

/**
 * Shows the AWT file dialog ON THE EDT. Constructing and showing a heavyweight
 * AWT component off the EDT is undefined behaviour and intermittently produced
 * an invisible dialog whose `file` stayed null. Returns null when the user
 * cancels or the dialog fails.
 */
private suspend fun showFileDialogOnEdt(): File? = suspendCoroutine { continuation ->
    EventQueue.invokeLater {
        val result = try {
            val dialog = FileDialog(null as Frame?, "Select Attachment", FileDialog.LOAD)
            dialog.isVisible = true
            val name = dialog.file
            val dir = dialog.directory
            if (name == null || dir == null) null else File(dir, name)
        } catch (e: Exception) {
            System.err.println("ERROR [MediaPicker] file dialog failed: ${e.message}")
            null
        }
        continuation.resume(result)
    }
}
