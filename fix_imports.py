import os
import re
def fix_imports(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    # Common Missing Imports Toolkit
    extra_imports = [
        "import androidx.compose.foundation.layout.*",
        "import androidx.compose.material.*",
        "import androidx.compose.material.icons.*",
        "import androidx.compose.material.icons.filled.*",
        "import androidx.compose.material.icons.automirrored.filled.*",
        "import androidx.compose.runtime.*",
        "import androidx.compose.ui.*",
        "import androidx.compose.ui.unit.*",
        "import androidx.compose.ui.graphics.*",
        "import androidx.compose.foundation.shape.*",
        "import ui.model.*", # covers ChannelType, OrbitChannel, etc
        "import kotlin.random.Random"
    ]
    # Remove all incorrect imports pattern import app.orbit... just in case
    content = re.sub(r'import app\.orbit\.[^\n]+\n', '', content)
    # Simple strategy: append all extra imports before the first existing import or after package declaration
    package_match = re.search(r'package[^\n]+', content)
    if package_match:
        end_idx = package_match.end()
        # insert imports
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
