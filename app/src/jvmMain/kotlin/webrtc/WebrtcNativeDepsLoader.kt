package webrtc


import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WebrtcNativeDepsLoader {
    private var loaded = false
    var livekitFfiPath: String? = null

    // VC++ runtime DLLs that are already loaded by the JVM process.
    // Loading a second (different-version) copy causes DllMain init failures
    // in downstream DLLs like opencv_core4. These are extracted to the temp dir
    // so they are available for implicit dependency resolution via PATH, but
    // they are NOT explicitly loaded via System.load().
    private val vcRuntimeDlls = setOf(
        "msvcp140.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "concrt140.dll"
    )

    fun load() {
        if (loaded) return
        loaded = true
        try {
            val dlls = listOf(
                "msvcp140.dll",
                "vcruntime140.dll",
                "vcruntime140_1.dll",
                "concrt140.dll",
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
                "webrtc-java-orbit-windows-x86_64.dll",
                "livekit_ffi.dll",
                "livekit.dll",
                "orbit_livekit_video.dll",
                "orbit_livekit_audio.dll"
            )

            val tempDir = Files.createTempDirectory("webrtc-deps").toFile()
            tempDir.deleteOnExit()

            // Extract ALL DLLs first (including VC++ runtime) so they are on
            // disk for implicit dependency resolution.
            for (dll in dlls) {
                val stream = WebrtcNativeDepsLoader::class.java.getResourceAsStream("/webrtc-deps/$dll")
                if (stream != null) {
                    val file = File(tempDir, dll)
                    file.deleteOnExit()
                    Files.copy(stream, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    if (dll == "livekit_ffi.dll") {
                        livekitFfiPath = file.absolutePath
                    }
                } else {
                    println("DEBUG: Resource /webrtc-deps/$dll not found")
                }
            }

            // Add temp dir to process PATH so Windows can resolve implicit DLL
            // dependencies. Without this, System.load() finds each DLL by absolute
            // path but the DLL's own native dependencies are not found because the
            // temp directory is not on the Windows DLL search path.
            try {
                val peClass = Class.forName("java.lang.ProcessEnvironment")
                val envField = peClass.getDeclaredField("theCaseInsensitiveEnvironment")
                envField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val env = envField.get(null) as MutableMap<String, String>
                val currentPath = env["PATH"] ?: ""
                env["PATH"] = tempDir.absolutePath + File.pathSeparator + currentPath
                println("DEBUG: Added ${tempDir.absolutePath} to process PATH")
            } catch (e: Exception) {
                System.err.println("DEBUG: Could not add temp dir to PATH: ${e.message}")
            }

            // Now load each DLL, skipping VC++ runtime DLLs that are already in
            // the JVM process (loading a mismatched version crashes opencv_core4).
            for (dll in dlls) {
                if (dll in vcRuntimeDlls) {
                    println("DEBUG: Skipping $dll (VC++ runtime already loaded by JVM)")
                    continue
                }
                val file = File(tempDir, dll)
                if (!file.exists()) continue
                try {
                    System.load(file.absolutePath)
                    println("DEBUG: Loaded $dll successfully")
                } catch (e: UnsatisfiedLinkError) {
                    System.err.println("DEBUG: Failed to load $dll: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            println("DEBUG: Failed to extract WebRTC dependencies: ${e.message}")
        }
    }
}

