package test

import java.io.File

fun main() {
    val file = File("d:/Onichat/app/src/commonMain/kotlin/ui/screen/ChatScreen.kt")
    val content = file.readText()
    val newContent = content.replace(
        """private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
    val tokens = rememberCosmicTokens()
    val url = content.thumbnailUrl ?: content.url
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tokens.CardBg)
            .widthIn(max = 360.dp)
    ) {""",
        """private fun ImageMessageCard(content: ui.model.MessageContent.Image) {
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
        } else {"""
    ).replace(
        """                .clip(RoundedCornerShape(8.dp))
        )
        // Download button overlay""",
        """                .clip(RoundedCornerShape(8.dp))
        )
        }
        // Download button overlay"""
    )
    file.writeText(newContent)
}
