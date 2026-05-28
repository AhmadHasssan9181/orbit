package webrtc


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
                "z.dll",
                "libcrypto-3-x64.dll",
                "libssl-3-x64.dll",
                "abseil_dll.dll",
                "jsoncpp.dll",
                "libprotobuf.dll",
                "jpeg62.dll",
                "turbojpeg.dll",
                "libyuv.dll",
                "aom.dll",
                "opus.dll",
                "pkgconf-7.dll",
                "srtp2.dll",
                "opencv_core4.dll",
                "opencv_imgproc4.dll",
                "opencv_dnn4.dll",
                "legacy.dll",
                "webrtc-java-orbit-windows-x86_64.dll"
            )

            val tempDir = Files.createTempDirectory("webrtc-deps").toFile()
            tempDir.deleteOnExit()

            for (dll in dlls) {
                val stream = WebrtcNativeDepsLoader::class.java.getResourceAsStream("/webrtc-deps/$dll")
                if (stream != null) {
                    val file = File(tempDir, dll)
                    file.deleteOnExit()
                    Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    
                    try {
                        System.load(file.absolutePath)
                        println("DEBUG: Loaded $dll successfully")
                    } catch (e: UnsatisfiedLinkError) {
                        System.err.println("DEBUG: Failed to load $dll: ${e.message}")
                    }
                } else {
                    println("DEBUG: Resource /webrtc-deps/$dll not found")
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Failed to extract WebRTC dependencies: ${e.message}")
        }
    }
}
