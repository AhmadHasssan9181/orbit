package ui.screen.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ui.assets.OrbitChatDoodleBackground
import ui.assets.OrbitEmptyIllustration
import ui.assets.OrbitEmptyScene
import ui.icons.OrbitIcons
import ui.model.ChannelType
import ui.model.OrbitChannel

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun ChatEmptyState(
    serverName: String,
    channels: List<OrbitChannel>,
    onChannelClick: (String) -> Unit
) {
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OrbitEmptyIllustration(
                scene = OrbitEmptyScene.Chat,
                modifier = Modifier.width(260.dp).height(162.dp)
            )

            Text(
                text = "Welcome to $serverName",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    color = Text_White
                )
            )

            Text(
                text = "Pick a channel to start chatting",
                style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
            )

            Spacer(Modifier.height(4.dp))

            // Channel cards
            val textChannels = channels.filter { it.type == ChannelType.TEXT }
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                textChannels.take(6).forEach { ch ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isHovered) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .hoverable(interactionSource)
                            .clickable { onChannelClick(ch.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = OrbitIcons.TextChannel,
                            contentDescription = null,
                            tint = Text_Muted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ch.name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (isHovered) Text_White else Text_Secondary,
                                fontWeight = FontWeight.Medium
                            )
                        )
                        if (ch.topic.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = ch.topic,
                                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted),
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}
