package ui.screen.call

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ui.theme.CosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*
import webrtc.*

@Composable
fun CallGrid(nodes: List<RenderNode>, tokens: CosmicTokens, modifier: Modifier = Modifier) {
    if (nodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text("Waiting for video...", color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.5f))
        }
    } else if (nodes.size == 1) {
        val node = nodes.first()
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxWidth())
            EmptyVoicePanel(modifier = Modifier.weight(1f).fillMaxWidth())
        }
    } else if (nodes.size == 2) {
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val weight0 = if (nodes[0].isScreenShare) 2f else 1f
            val weight1 = if (nodes[1].isScreenShare) 2f else 1f
            VideoNodeCard(nodes[0], tokens, modifier = Modifier.weight(weight0).fillMaxWidth())
            VideoNodeCard(nodes[1], tokens, modifier = Modifier.weight(weight1).fillMaxWidth())
        }
    } else {
        // Masonry style (3+ items)
        val screenShareNode = nodes.find { it.isScreenShare }
        if (screenShareNode != null) {
            // Big screen share, small thumbnails below
            Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                VideoNodeCard(screenShareNode, tokens, modifier = Modifier.weight(2f).fillMaxWidth())
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    nodes.filter { !it.isScreenShare }.forEach { node ->
                        VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        } else {
            // Equal grid
            Row(
                modifier = modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                nodes.forEach { node ->
                    VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}
