package webrtc

import dev.onvoid.webrtc.media.audio.AudioDeviceModule

class OrbitAudioDeviceModule : AudioDeviceModule() {
    fun setSystemAudioShareEnabled(enabled: Boolean) {
        OrbitAudioEngine.setSystemAudioShareEnabled(this, enabled)
    }

    fun setOrbitZeroEnabled(enabled: Boolean) {
        OrbitAudioEngine.setOrbitZeroEnabled(this, enabled)
    }
}
