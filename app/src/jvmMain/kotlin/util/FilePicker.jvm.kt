package util

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

/**
 * Modern Windows file dialog launcher using Windows Common Item Dialog (IFileOpenDialog)
 * via PowerShell, with automatic fallback to AWT FileDialog on other platforms or on failure.
 */
internal fun pickFileNative(
    title: String,
    filter: String = "Image Files (*.png;*.jpg;*.jpeg;*.gif;*.webp)|*.png;*.jpg;*.jpeg;*.gif;*.webp|All Files (*.*)|*.*",
    awtFilter: String = "*.png;*.jpg;*.jpeg;*.gif;*.webp"
): File? {
    val isWindows = System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true
    if (isWindows) {
        val picked = pickFileWindows(title, filter)
        if (picked != null) return picked.takeIf { it.exists() }
    }

    // Fallback for macOS, Linux, or if Windows script failed
    return pickFileAwt(title, awtFilter)
}

private fun pickFileWindows(title: String, filter: String): File? {
    return try {
        val script = """
            Add-Type -AssemblyName System.Windows.Forms
            ${'$'}dialog = New-Object System.Windows.Forms.OpenFileDialog
            ${'$'}dialog.Title = '$title'
            ${'$'}dialog.Filter = '$filter'
            ${'$'}dialog.RestoreDirectory = ${'$'}true
            ${'$'}dialog.AutoUpgradeEnabled = ${'$'}true
            ${'$'}form = New-Object System.Windows.Forms.Form
            ${'$'}form.TopMost = ${'$'}true
            ${'$'}form.Opacity = 0
            ${'$'}form.Show()
            ${'$'}res = ${'$'}dialog.ShowDialog(${'$'}form)
            ${'$'}form.Close()
            if (${'$'}res -eq [System.Windows.Forms.DialogResult]::OK) {
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                [Console]::WriteLine(${'$'}dialog.FileName)
            }
        """.trimIndent()

        val tempScript = File.createTempFile("orbit_file_picker_", ".ps1")
        try {
            tempScript.writeText(script, Charsets.UTF_8)
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-STA",
                "-WindowStyle", "Hidden",
                "-File", tempScript.absolutePath
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) {
                val lastLine = output.lines().map { it.trim() }.filter { it.isNotBlank() }.lastOrNull()
                lastLine?.let { File(it).takeIf { f -> f.exists() } }
            } else {
                null
            }
        } finally {
            tempScript.delete()
        }
    } catch (e: Exception) {
        println("WARN [FilePicker] Windows native picker failed, falling back to AWT: ${e.message}")
        null
    }
}

private fun pickFileAwt(title: String, filter: String? = null): File? {
    fun show(): File? = try {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file
        val dir = dialog.directory
        if (file != null && dir != null) {
            File(dir, file).takeIf { it.exists() }
        } else null
    } catch (e: Exception) {
        println("ERROR [FilePicker] AWT picker failed: ${e.message}")
        null
    }

    return if (EventQueue.isDispatchThread()) {
        show()
    } else {
        var res: File? = null
        EventQueue.invokeAndWait { res = show() }
        res
    }
}

actual suspend fun pickImageFile(): ByteArray? = withContext(Dispatchers.IO) {
    val file = pickFileNative(
        title = "Select Image",
        filter = "Image Files (*.png;*.jpg;*.jpeg;*.gif;*.webp)|*.png;*.jpg;*.jpeg;*.gif;*.webp|All Files (*.*)|*.*",
        awtFilter = "*.png;*.jpg;*.jpeg;*.gif;*.webp"
    ) ?: return@withContext null

    if (file.length() > MAX_IMAGE_BYTES) {
        println("WARN [FilePicker] Image file ${file.name} exceeds max size limit")
        return@withContext null
    }

    runCatching { file.readBytes() }.getOrNull()
}

actual fun compressImage(imageBytes: ByteArray, maxDimension: Int, quality: Float): ByteArray {
    if (imageBytes.isEmpty()) return imageBytes
    return try {
        val input = ByteArrayInputStream(imageBytes)
        val original = ImageIO.read(input) ?: return imageBytes
        val width = original.width
        val height = original.height
        if (width <= maxDimension && height <= maxDimension && imageBytes.size <= 250 * 1024) {
            return imageBytes
        }
        val scale = minOf(maxDimension.toDouble() / width, maxDimension.toDouble() / height, 1.0)
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)

        val hasAlpha = original.colorModel?.hasAlpha() ?: false
        val imageType = if (hasAlpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val scaled = BufferedImage(targetWidth, targetHeight, imageType)
        val g2d = scaled.createGraphics()
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.drawImage(original, 0, 0, targetWidth, targetHeight, null)
        } finally {
            g2d.dispose()
        }

        val out = ByteArrayOutputStream()
        if (hasAlpha) {
            ImageIO.write(scaled, "png", out)
        } else {
            val writers = ImageIO.getImageWritersByFormatName("jpeg")
            if (writers.hasNext()) {
                val writer = writers.next()
                val ios = ImageIO.createImageOutputStream(out)
                writer.output = ios
                val params = writer.defaultWriteParam
                params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                params.compressionQuality = quality.coerceIn(0.1f, 1.0f)
                writer.write(null, javax.imageio.IIOImage(scaled, null, null), params)
                ios.close()
                writer.dispose()
            } else {
                ImageIO.write(scaled, "jpg", out)
            }
        }
        val result = out.toByteArray()
        if (result.isNotEmpty()) result else imageBytes
    } catch (e: Exception) {
        imageBytes
    }
}

