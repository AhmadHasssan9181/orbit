import os

file_path = 'd:/Onichat/app/src/commonMain/kotlin/webrtc/CallManager.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''        scope.launch {
            settingsManager.orbitZeroAiNoiseSuppression.collect { enabled ->
                webRtcClient.setOrbitZeroEnabled(enabled)
            }
        }'''

replacement = '''        scope.launch {
            kotlinx.coroutines.flow.combine(
                settingsManager.noiseSuppression,
                settingsManager.echoCancellation,
                settingsManager.autoGainControl,
                settingsManager.orbitZeroAiNoiseSuppression
            ) { ns, ec, agc, orbitZero ->
                val activeNs = ns || orbitZero
                webRtcClient.setAudioOptions(activeNs, ec, agc)
            }.collect { /* handled in combine */ }
        }'''

content = content.replace(target, replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated CallManager.kt")
