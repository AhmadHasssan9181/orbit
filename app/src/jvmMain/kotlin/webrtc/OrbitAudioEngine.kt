package webrtc

import dev.onvoid.webrtc.media.audio.AudioDeviceModule

object OrbitAudioEngine {
    init {
        WebrtcNativeDepsLoader.load()
    }

    /**
     * Injects the custom ADM implementation into a Java AudioDeviceModule.
     */
    external fun injectCustomAdm(adm: AudioDeviceModule)

    /**
     * Toggles system audio sharing.
     */
    external fun setSystemAudioShareEnabled(adm: AudioDeviceModule, enabled: Boolean)

    /**
     * Toggles Orbit Zero (noise suppression/filtering).
     */
    external fun setOrbitZeroEnabled(adm: AudioDeviceModule, enabled: Boolean)
}
