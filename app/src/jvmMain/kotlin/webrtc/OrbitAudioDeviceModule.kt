package webrtc

/**
 * Thin Kotlin wrapper for controlling WASAPI system-audio sharing via OrbitAudioJNI.
 *
 * This is NO LONGER an AudioDeviceModule subclass. Previously it extended AudioDeviceModule
 * and was passed to PeerConnectionFactory, which caused the WebRTC native stack to use our
 * uninitialized custom C++ ADM for ALL calls — crashing on friend's machines.
 *
 * Now it simply delegates WASAPI toggle calls to OrbitAudioEngine, which lazy-loads the
 * native DLL only when screen-share audio is actually requested.
 */
class OrbitAudioDeviceModule {
    fun setSystemAudioShareEnabled(enabled: Boolean) {
        OrbitAudioEngine.setStreamAudioEnabled(enabled)
    }

    fun dispose() {
        // No native resources to release — OrbitAudioEngine handles its own lifecycle
    }
}
