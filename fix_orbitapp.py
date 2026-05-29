import re

file_path = r'd:\Onichat\app\src\commonMain\kotlin\ui\screen\OrbitApp.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Replace ChannelDrawer invocations inside the layouts
# We need to insert activeGroupCalls
pattern1 = r'(\s*)ChannelDrawer\(\s*server = server,\s*categories = emptyList\(\),'
replacement1 = r'\1val activeCallMap by (repository as? MatrixOrbitRepository)?.activeCallMap?.collectAsState() ?: mutableStateOf(emptyMap())\1ChannelDrawer(\1    activeGroupCalls = activeCallMap,\n\1    server = server,\1    categories = emptyList(),'
content = re.sub(pattern1, replacement1, content)

pattern2 = r'(\s*)ChannelDrawer\(\s*server = selectedServer,\s*categories = categories,'
replacement2 = r'\1val activeCallMap by (repository as? MatrixOrbitRepository)?.activeCallMap?.collectAsState() ?: mutableStateOf(emptyMap())\1ChannelDrawer(\1    activeGroupCalls = activeCallMap,\n\1    server = selectedServer,\1    categories = categories,'
content = re.sub(pattern2, replacement2, content)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)

print("Done")
