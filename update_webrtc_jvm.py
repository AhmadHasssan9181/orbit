import os

file_path = 'd:/Onichat/app/src/jvmMain/kotlin/webrtc/WebRtcClientImpl.jvm.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''    override fun setOrbitZeroEnabled(enabled: Boolean) {
        audioDeviceModule?.setOrbitZeroEnabled(enabled)
    }'''

replacement = '''    private var currentNs = true
    private var currentEc = true
    private var currentAgc = true

    override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        currentNs = noiseSuppression
        currentEc = echoCancellation
        currentAgc = autoGainControl
        
        try {
            val pConn = peerConnection ?: return
            val f = factory ?: return
            
            val options = dev.onvoid.webrtc.media.audio.AudioOptions()
            options.noiseSuppression = noiseSuppression
            options.echoCancellation = echoCancellation
            options.autoGainControl = autoGainControl
            options.highpassFilter = noiseSuppression
            
            val newAudioSource = f.createAudioSource(options)
            val newAudioTrack = f.createAudioTrack("audioTrack_", newAudioSource)
            newAudioTrack.setEnabled(true)
            
            if (localAudioSender != null) {
                localAudioSender?.replaceTrack(newAudioTrack)
            } else {
                localAudioSender = pConn.addTrack(newAudioTrack, listOf("mediaStream"))
            }
            
            // Clean up old track
            kotlin.runCatching { localAudioTrack?.dispose() }
            
            localAudioSource = newAudioSource
            localAudioTrack = newAudioTrack
            
            println("DEBUG: [WebRTC] AudioOptions updated. NS=, EC=")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }'''

content = content.replace(target, replacement)

target3 = '''val audioSource = f.createAudioSource(AudioOptions())'''
replacement3 = '''val options = dev.onvoid.webrtc.media.audio.AudioOptions()
                options.noiseSuppression = currentNs
                options.echoCancellation = currentEc
                options.autoGainControl = currentAgc
                options.highpassFilter = currentNs
                val audioSource = f.createAudioSource(options)'''
content = content.replace(target3, replacement3)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated WebRtcClientImpl.jvm.kt")
