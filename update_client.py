import os

file_path = 'd:/Onichat/app/src/commonMain/kotlin/webrtc/WebRtcClient.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    'fun setOrbitZeroEnabled(enabled: Boolean)',
    'fun setAudioOptions(noiseSuppression: Boolean, echoCancellation: Boolean, autoGainControl: Boolean)'
)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated WebRtcClient.kt")
