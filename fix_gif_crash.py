import os
import re

file_path = 'd:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target1 = '''private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val url = content.thumbnailUrl ?: content.url
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {'''

replacement1 = '''private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val url = content.thumbnailUrl ?: content.url
    val isGif = url.lowercase().endsWith(".gif") || content.mimeType == "image/gif"
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {
        if (isGif) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable {
                        uriHandler.openUri(url)
                    },
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "GIF", tint = Color.White, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Click to view GIF in browser", color = Color.White, fontSize = 12.sp)
                }
            }
        } else {'''

target2 = '''                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        // Download button overlay'''

replacement2 = '''                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        }
        // Download button overlay'''

content = content.replace(target1, replacement1)
content = content.replace(target2, replacement2)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("GIF fixed.")
