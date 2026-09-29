package webrtc

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Extracts a bundled resource to a temp file, ONCE per resource name.
 *
 * ## Why this caches
 *
 * The selfie-segmentation ONNX model is several megabytes. `extractModelToTempFile`
 * used to copy it to a fresh temp file on every call, and it is called from
 * `LiveKitNativeVideoCapture.openForPublish` — which runs on every camera enable
 * mid-call — and from `WebRtcClientImpl`. So toggling the camera N times wrote N
 * copies of a multi-megabyte model to disk, none of which were deleted until JVM
 * exit.
 *
 * `deleteOnExit()` made it worse: the JVM keeps every path in a static set for the
 * lifetime of the process, so the list grows too.
 *
 * The native side only needs a readable PATH, not a path it owns, so handing back
 * the same file every time is correct. The files are intentionally not deleted --
 * a second camera enable would otherwise race the first one's deletion.
 */
private val extractedPaths = ConcurrentHashMap<String, String>()

fun extractModelToTempFile(resourceName: String): String? {
    // Fast path: already extracted. The native loader re-reads the file per call,
    // which is what we want -- only the copy is deduplicated.
    extractedPaths[resourceName]?.let { cached ->
        if (File(cached).exists()) return cached
        // The file vanished underneath us (temp dir cleaned, previous session).
        // Drop the entry and fall through to re-extract.
        extractedPaths.remove(resourceName)
    }

    return try {
        val inputStream: InputStream = object {}.javaClass.classLoader.getResourceAsStream(resourceName)
            ?: return null

        val tempFile = File.createTempFile("orbit_", "_$resourceName")
        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        inputStream.close()

        // deleteOnExit is deliberate but bounded to one entry per resource now,
        // rather than one per camera toggle.
        tempFile.deleteOnExit()

        val path = tempFile.absolutePath
        extractedPaths[resourceName] = path
        path
    } catch (e: Exception) {
        // Not printStackTrace: this runs on a media path and a stack trace in the
        // log tells you nothing the exception message does not.
        println("WARN [ResourceUtils] could not extract $resourceName: ${e.message}")
        null
    }
}
