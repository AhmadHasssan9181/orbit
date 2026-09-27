package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitChatDoodleBackground
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.icons.OrbitIcons
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * What you see when a space is selected but no room is.
 *
 * The headline used to be `headlineMedium` + `ExtraBold` — 28sp at weight 800,
 * which shouted at a screen the user is passing through on the way somewhere
 * else. This is a quieter greeting with a calmer scale, and the room shortcuts
 * are real `OrbitListRow`s so they look like the rest of the app.
 */
@Composable
internal fun ChatEmptyState(
    serverName: String,
    channels: List<OrbitChannel>,
    onChannelClick: (String) -> Unit
) {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        OrbitChatDoodleBackground(
            selectedId = ui.theme.LocalChatWallpaper.current,
            modifier = Modifier.matchParentSize(),
            intensity = 0.78f
        )
        Column(
            modifier = Modifier.widthIn(max = 460.dp).padding(OrbitSpace.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.md)
        ) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.Chat,
                modifier = Modifier.width(232.dp).height(144.dp)
            )

            Spacer(Modifier.height(OrbitSpace.xs))

            Text(
                text = "Welcome to $serverName",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    fontSize = 19.sp,
                    letterSpacing = 0.sp
                )
            )

            Text(
                text = if (channels.isEmpty()) {
                    "There are no rooms here yet. Create one from the sidebar to get started."
                } else {
                    "Pick a room to start talking."
                },
                style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Muted, fontSize = 13.5.sp),
                modifier = Modifier.widthIn(max = 340.dp)
            )

            val textChannels = channels.filter { it.type == ChannelType.TEXT }
            if (textChannels.isNotEmpty()) {
                Spacer(Modifier.height(OrbitSpace.sm))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    textChannels.take(6).forEach { ch ->
                        OrbitListRow(
                            title = ch.name,
                            subtitle = ch.topic.ifBlank { null },
                            onClick = { onChannelClick(ch.id) },
                            height = 36.dp,
                            showSelectionBar = false,
                            leading = OrbitRowLeading(icon = OrbitIcons.TextChannel),
                            titleStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = t.Text_Secondary,
                                fontSize = 13.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
