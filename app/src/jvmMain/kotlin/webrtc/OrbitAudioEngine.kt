package webrtc

import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import java.io.File

/**
 * JVM Implementation for the custom WebRTC Audio Engine.
 * This bridges Kotlin to our custom C++ WASAPI JNI module.
 */
object OrbitAudioEngine {
    
    // The Java AudioDeviceModule instance that we have hooked natively
    private var hookedAudioModule: AudioDeviceModule? = null

    init {
        try {
            System.loadLibrary("OrbitAudioJNI")
            println("INFO: Successfully loaded OrbitAudioJNI from java.library.path")
        } catch (e: UnsatisfiedLinkError) {
            println("INFO: Could not load OrbitAudioJNI from library path, attempting to extract from resources...")
            try {
                val tempFile = File.createTempFile("OrbitAudioJNI", ".dll")
                tempFile.deleteOnExit()
                val inputStream = OrbitAudioEngine::class.java.getResourceAsStream("/desktop/OrbitAudioJNI.dll")
                if (inputStream == null) {
                    println("WARNING: Could not find /desktop/OrbitAudioJNI.dll in resources. C++ Loopback mixing disabled.")
                } else {
                    inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    System.load(tempFile.absolutePath)
                    println("INFO: Successfully loaded OrbitAudioJNI.dll from resources.")
                }
            } catch (ex: Exception) {
                println("WARNING: Failed to extract and load OrbitAudioJNI.dll: ${ex.message}")
            }
        }
    }

    /**
     * Injects our custom C++ ADM into the given Java AudioDeviceModule instance using JNI reflection.
     */
    fun injectInto(audioModule: AudioDeviceModule) {
        if (hookedAudioModule == null) {
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
     */
    fun setStreamAudioEnabled(enabled: Boolean) {
        hookedAudioModule?.let { adm ->
            setSystemAudioShareEnabled(adm, enabled)
        }
    }

    // --- Native JNI Methods ---
    
    @JvmStatic
    private external fun injectCustomAdm(javaAdm: AudioDeviceModule)

    @JvmStatic
    private external fun setSystemAudioShareEnabled(javaAdm: AudioDeviceModule, enabled: Boolean)
}
