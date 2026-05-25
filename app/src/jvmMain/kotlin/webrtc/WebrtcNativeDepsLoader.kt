package webrtc

import com.sun.jna.platform.win32.Kernel32
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WebrtcNativeDepsLoader {
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val dlls = listOf(
                "abseil_dll.dll",
                "aom.dll",
                "jpeg62.dll",
                "jsoncpp.dll",
                "legacy.dll",
                "libcrypto-3-x64.dll",
                "libssl-3-x64.dll",
                "libyuv.dll",
                "opus.dll",
                "pkgconf-7.dll",
                "srtp2.dll",
                "turbojpeg.dll"
            )

            val tempDir = Files.createTempDirectory("webrtc-deps").toFile()
            tempDir.deleteOnExit()

            for (dll in dlls) {
                val stream = WebrtcNativeDepsLoader::class.java.getResourceAsStream("/webrtc-deps/$dll")
                if (stream != null) {
                    val file = File(tempDir, dll)
                    file.deleteOnExit()
                    Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }

            // Tell Windows to look for DLL dependencies in our temp directory!
            Kernel32.INSTANCE.SetDllDirectory(tempDir.absolutePath)
            println("DEBUG: Set DLL directory to ${tempDir.absolutePath}")
            
        } catch (e: Exception) {
            println("DEBUG: Failed to extract WebRTC dependencies: ${e.message}")
        }
    }
}
