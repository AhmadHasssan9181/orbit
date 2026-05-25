package webrtc

import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import java.io.File

/**
 * JVM Implementation for the custom WebRTC Audio Engine.
 * This bridges Kotlin to our custom C++ WASAPI JNI module.
 *
 * NOTE: The native DLL is loaded LAZILY only when WASAPI audio sharing is first
 * requested. It must NOT be loaded at class init time because loading OrbitAudioJNI.dll
 * at startup alongside webrtc-java-windows-x86_64.dll causes a duplicate static
 * JavaFactories map which results in an EXCEPTION_UNCAUGHT_CXX_EXCEPTION crash
 * during WebRTC calls on machines that don't have vcpkg installed.
 */
object OrbitAudioEngine {

    // The Java AudioDeviceModule instance that we have hooked natively
    private var hookedAudioModule: AudioDeviceModule? = null
    private var dllLoaded = false
    private var dllLoadAttempted = false

    /**
     * Lazily load OrbitAudioJNI.dll only when first needed for WASAPI mixing.
     * Returns true if the DLL is available, false otherwise.
     */
    private fun ensureDllLoaded(): Boolean {
        if (dllLoaded) return true
        if (dllLoadAttempted) return false
        dllLoadAttempted = true

        return try {
            System.loadLibrary("OrbitAudioJNI")
            println("INFO: Successfully loaded OrbitAudioJNI from java.library.path")
            dllLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            println("INFO: Could not load OrbitAudioJNI from library path, attempting to extract from resources...")
            try {
                val tempFile = File.createTempFile("OrbitAudioJNI", ".dll")
                tempFile.deleteOnExit()
                val inputStream = OrbitAudioEngine::class.java.getResourceAsStream("/desktop/OrbitAudioJNI.dll")
                if (inputStream == null) {
                    println("WARNING: Could not find /desktop/OrbitAudioJNI.dll in resources. C++ Loopback mixing disabled.")
                    false
                } else {
                    inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    System.load(tempFile.absolutePath)
                    println("INFO: Successfully loaded OrbitAudioJNI.dll from resources.")
                    dllLoaded = true
                    true
                }
            } catch (ex: Exception) {
                println("WARNING: Failed to extract and load OrbitAudioJNI.dll: ${ex.message}")
                false
            }
        }
    }

    /**
     * Injects our custom C++ ADM into the given Java AudioDeviceModule instance using JNI reflection.
     * Only called when WASAPI audio sharing is first enabled.
     */
    fun injectInto(audioModule: AudioDeviceModule) {
        if (hookedAudioModule == null && ensureDllLoaded()) {
            runCatching {
                injectCustomAdm(audioModule)
                hookedAudioModule = audioModule
                println("Successfully injected custom C++ Audio Device Module.")
            }.onFailure {
                println("Failed to inject custom C++ ADM: ${it.message}")
            }
        }
    }

    /**
     * Toggles whether the C++ mixer should include WASAPI loopback audio.
     * Lazily loads the native library on first call.
     */
    fun setStreamAudioEnabled(enabled: Boolean) {
        if (enabled && hookedAudioModule == null) {
            println("INFO: [OrbitAudio] WASAPI sharing requested but no ADM hooked yet. DLL not injected.")
            return
        }
        hookedAudioModule?.let { adm ->
            if (ensureDllLoaded()) {
                runCatching { setSystemAudioShareEnabled(adm, enabled) }
                    .onFailure { println("WARNING: [OrbitAudio] setSystemAudioShareEnabled failed: ${it.message}") }
            }
        }
    }

    // --- Native JNI Methods ---

    @JvmStatic
    private external fun injectCustomAdm(javaAdm: AudioDeviceModule)

    @JvmStatic
    private external fun setSystemAudioShareEnabled(javaAdm: AudioDeviceModule, enabled: Boolean)
}
