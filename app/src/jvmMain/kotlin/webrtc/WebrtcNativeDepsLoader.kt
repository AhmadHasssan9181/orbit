package webrtc


import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object WebrtcNativeDepsLoader {
    // load() is reachable from four+ independent entry points (audio bridge init,
    // media session, P2P factory, group screen-share factory), so the guard must
    // be atomic AND must only be set once the work succeeded. Two threads racing
    // here each create a temp dir and System.load() a SECOND copy of livekit.dll /
    // orbit_livekit_*.dll / the webrtc DLL: two copies means two sets of C++
    // globals, so one copy's session is invisible to the other and the
    // UnsatisfiedLinkError is swallowed.
    private val loadLock = Object()
    @Volatile private var loaded = false

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
        // Concurrent callers WAIT here for the first load instead of returning
        // early and racing it.
        synchronized(loadLock) {
            if (loaded) return
            // Only now, and only on success: a failed extraction must stay
            // retryable instead of leaving `loaded` set with nothing actually
            // loaded. loadInternal previously swallowed its own exception, so
            // `loaded = true` ran on every path and one transient failure
            // (temp dir blocked by AV, disk full) poisoned the loader for the
            // whole JVM lifetime -- every later native call threw
            // UnsatisfiedLinkError with two println lines as the only evidence.
            if (loadInternal()) {
                loaded = true
            } else {
                System.err.println(
                    "WARN [WebrtcNativeDepsLoader] native dependency load failed; will retry on next call"
                )
            }
        }
    }

    /** @return true only if the dependency directory was extracted successfully. */
    private fun loadInternal(): Boolean {
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
                // livekit.dll links against livekit_ffi.dll and it must be a LOADED
                // module at that point — the temp dir isn't on the real Windows DLL
                // search path, so extraction alone doesn't resolve the import. J1
                // dropped this entry and broke every orbit_livekit_* JNI call.
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
            return true
        } catch (e: Exception) {
            System.err.println("WARN: Failed to extract WebRTC dependencies: ${e.message}")
            return false
        }
    }
}

