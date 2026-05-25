package webrtc

/**
 * Previously loaded vcpkg dependency DLLs before our custom webrtc-java-windows-x86_64.dll.
 * Now that we use the official upstream webrtc-java Maven artifact (which is self-contained
 * and statically links its dependencies), this loader is a no-op.
 *
 * OrbitAudioJNI.dll (for WASAPI screen-share audio) is loaded lazily by OrbitAudioEngine
 * only when the feature is explicitly activated.
 */
object WebrtcNativeDepsLoader {
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        // No-op: upstream webrtc-java DLL is self-contained.
        println("DEBUG: [WebRTC] Using upstream self-contained webrtc-java native library.")
    }
}
