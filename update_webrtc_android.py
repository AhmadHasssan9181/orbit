import os

file_path = 'd:/Onichat/app/src/androidMain/kotlin/webrtc/WebRtcClientImpl.android.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target1 = '''    override fun setOrbitZeroEnabled(enabled: Boolean) {
        // Handle android orbit zero here
    }'''

replacement1 = '''    override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean) {
        // Apply android specific audio options
    }'''

if target1 in content:
    content = content.replace(target1, replacement1)
else:
    # Just replace the fun name if the body is different
    content = content.replace('override fun setOrbitZeroEnabled(enabled: Boolean)', 'override fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean)')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated WebRtcClientImpl.android.kt")
