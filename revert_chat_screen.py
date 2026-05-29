import os

file_path = 'd:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt'
with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

target = '''private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
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
        } else {
          coil3.compose.AsyncImage(
              model = url,
              onState = { state ->
                  if (state is coil3.compose.AsyncImagePainter.State.Error) {
                      println("DEBUG: [Coil] Error loading image: ")
                      println("DEBUG: [Coil] URL was: ")
                  }
              },
              contentDescription = content.body.ifBlank { "Image" },
              contentScale = androidx.compose.ui.layout.ContentScale.Fit,
              modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 280.dp)
                  .clip(RoundedCornerShape(8.dp))
          )
        }
        // Download button overlay'''

replacement = '''private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val url = content.thumbnailUrl ?: content.url
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {
          coil3.compose.AsyncImage(
              model = url,
              onState = { state ->
                  if (state is coil3.compose.AsyncImagePainter.State.Error) {
                      println("DEBUG: [Coil] Error loading image: ")
                      println("DEBUG: [Coil] URL was: ")
                  }
              },
              contentDescription = content.body.ifBlank { "Image" },
              contentScale = androidx.compose.ui.layout.ContentScale.Fit,
              modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 280.dp)
                  .clip(RoundedCornerShape(8.dp))
          )
        // Download button overlay'''

content = content.replace(target, replacement)

with open(file_path, 'w', encoding='utf-8') as f:
    f.write(content)
print("Reverted ChatScreen.kt GIF hack")
