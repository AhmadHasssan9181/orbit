package webrtc

import dev.onvoid.webrtc.media.audio.AudioDeviceModule

class OrbitAudioDeviceModule : AudioDeviceModule() {
    external fun setSystemAudioShareEnabled(enabled: Boolean)
}
