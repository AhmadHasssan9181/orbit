package ui.screen.call

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun CallGrid(
    nodes: List<RenderNode>,
    tokens: CosmicTokens,
    layoutMode: CallLayoutMode = CallLayoutMode.Grid,
    modifier: Modifier = Modifier
) {
    if (nodes.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Text(
                "Waiting for participants...",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp
            )
        }
        return
    }

    // ── Focus Mode: spotlight one participant, thumbnails on side ──
    if (layoutMode == CallLayoutMode.Focus && nodes.size >= 2) {
        // Spotlight = first screen share, else remote, else first node
        val spotlightNode = nodes.find { it.isScreenShare }
            ?: nodes.find { it.id == "remote" }
            ?: nodes.first()

        val thumbnailNodes = nodes.filter { it.id != spotlightNode.id }

        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main spotlight
            VideoNodeCard(
                spotlightNode,
                tokens,
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
            )

            // Side thumbnails
            if (thumbnailNodes.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    thumbnailNodes.forEach { node ->
                        VideoNodeCard(
                            node,
                            tokens,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
        return
    }

    // ── Grid Mode ──
    when {
        nodes.size == 1 -> {
            // Single participant — centered large card with empty panel below
            Column(
                modifier = modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                VideoNodeCard(nodes.first(), tokens, modifier = Modifier.weight(1f).fillMaxWidth())
                EmptyVoicePanel(modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }

        nodes.size == 2 -> {
            // Two participants — side by side or stacked based on screen share
            val hasScreenShare = nodes.any { it.isScreenShare }

            if (hasScreenShare) {
                // Screen share gets more space
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val screenNode = nodes.find { it.isScreenShare }!!
                    val otherNode = nodes.find { !it.isScreenShare }!!
                    VideoNodeCard(screenNode, tokens, modifier = Modifier.weight(2.5f).fillMaxWidth())
                    VideoNodeCard(otherNode, tokens, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            } else {
                // Equal side-by-side
                Row(
                    modifier = modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    nodes.forEach { node ->
                        VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        else -> {
            // 3+ participants
            val screenShareNode = nodes.find { it.isScreenShare }

            if (screenShareNode != null) {
                // Big screen share on top, thumbnails below
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    VideoNodeCard(screenShareNode, tokens, modifier = Modifier.weight(2.5f).fillMaxWidth())
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        nodes.filter { !it.isScreenShare }.forEach { node ->
                            VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            } else {
                // 2x2 or 2xN grid
                val rows = nodes.chunked(2)
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rows.forEach { rowNodes ->
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowNodes.forEach { node ->
                                VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            // If odd number, add empty space
                            if (rowNodes.size < 2) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
