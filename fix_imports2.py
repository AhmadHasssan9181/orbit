import os
import re
def fix_imports(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    extra_imports = [
        "import androidx.compose.material3.*",
        "import androidx.compose.material.icons.outlined.*"
    ]
    package_match = re.search(r'package[^\n]+', content)
    if package_match:
        end_idx = package_match.end()
        for imp in extra_imports:
            if imp not in content:
                content = content[:end_idx] + f'\n{imp}' + content[end_idx:]
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
base_path = r'D:\Onichat\app\src\commonMain\kotlin\ui'
for root, dirs, files in os.walk(base_path):
    for file in files:
        if file.endswith('.kt'):
            fix_imports(os.path.join(root, file))
