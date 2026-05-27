package webrtc

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

fun extractModelToTempFile(resourceName: String): String? {
    return try {
        val inputStream: InputStream = object {}.javaClass.classLoader.getResourceAsStream(resourceName)
            ?: return null
        
        val tempFile = File.createTempFile("orbit_", "_$resourceName")
        tempFile.deleteOnExit()
        
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        
        tempFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
