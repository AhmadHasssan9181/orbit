import os

file_path = 'd:/Onichat/app/src/commonMain/kotlin/ui/screen/OrbitApp.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''    LaunchedEffect(orbitZeroEnabled) {
        webRtcClient.setOrbitZeroEnabled(orbitZeroEnabled)
    }'''

content = content.replace(target, '')

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Updated OrbitApp.kt")
