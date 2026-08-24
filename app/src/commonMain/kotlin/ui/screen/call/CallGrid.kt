package ui.screen.call

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.CosmicTokens
import ui.icons.OrbitIcons

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
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Waiting for participants...",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 14.sp
            )
        }
        return
    }

    // Multiple people can share at once: every screen share competes for ONE
    // spotlight slot and the pager arrows shift between them. Extra shares are
    // no longer demoted to tiny thumbnails.
    val screenShareNodes = nodes.filter { it.isScreenShare }
    val nonShareNodes = nodes.filter { !it.isScreenShare }
    var sharePage by remember { mutableStateOf(0) }
    val clampedPage = if (screenShareNodes.isEmpty()) 0 else sharePage.coerceIn(0, screenShareNodes.lastIndex)
    val spotlightShareNode = screenShareNodes.getOrNull(clampedPage)

    // ── Focus Mode: spotlight one participant, thumbnails on side ──
    if (layoutMode == CallLayoutMode.Focus && nodes.size >= 2) {
        // Spotlight = paged screen share, else remote camera, else first node
        val spotlightNode = spotlightShareNode ?: nodes.find { it.id == "remote" } ?: nodes.first()

        val thumbnailNodes = nodes.filter { it.id != spotlightNode.id }

        Row(
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Main spotlight
            ShareSpotlightBox(
                node = spotlightNode,
                shareCount = screenShareNodes.size,
                selectedPage = clampedPage,
                onPageChange = { sharePage = it },
                tokens = tokens,
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
            if (screenShareNodes.isNotEmpty()) {
                // Screen share gets more space (pager shifts between sharers;
                // two simultaneous shares with no camera used to crash here).
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShareSpotlightBox(
                        node = spotlightShareNode ?: nodes.first(),
                        shareCount = screenShareNodes.size,
                        selectedPage = clampedPage,
                        onPageChange = { sharePage = it },
                        tokens = tokens,
                        modifier = Modifier
                            .weight(if (nonShareNodes.isEmpty()) 1f else 2.5f)
                            .fillMaxWidth()
                    )
                    nonShareNodes.firstOrNull()?.let { other ->
                        VideoNodeCard(other, tokens, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
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
            if (screenShareNodes.isNotEmpty()) {
                // Big screen share on top (paged), thumbnails below
                Column(
                    modifier = modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShareSpotlightBox(
                        node = spotlightShareNode ?: nodes.first(),
                        shareCount = screenShareNodes.size,
                        selectedPage = clampedPage,
                        onPageChange = { sharePage = it },
                        tokens = tokens,
                        modifier = Modifier.weight(2.5f).fillMaxWidth()
                    )
                    if (nonShareNodes.isNotEmpty()) {
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            nonShareNodes.forEach { node ->
                                VideoNodeCard(node, tokens, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
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

/**
 * Spotlight slot for a screen-share node with prev/next arrows overlaid when
 * more than one person is sharing. Wraps around so the arrows always work.
 */
@Composable
private fun ShareSpotlightBox(
    node: RenderNode,
    shareCount: Int,
    selectedPage: Int,
    onPageChange: (Int) -> Unit,
    tokens: CosmicTokens,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        VideoNodeCard(node, tokens, modifier = Modifier.fillMaxSize())
        if (shareCount > 1) {
            SharePagerArrows(
                page = selectedPage,
                count = shareCount,
                onPageChange = onPageChange,
                modifier = Modifier.matchParentSize()
            )
        }
    }
}

@Composable
private fun SharePagerArrows(
    page: Int,
    count: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        PagerArrowButton(
            icon = OrbitIcons.ChevronLeft,
            contentDescription = "Previous shared screen",
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            onClick = { onPageChange((page - 1 + count) % count) }
        )
        PagerArrowButton(
            icon = OrbitIcons.ChevronRight,
            contentDescription = "Next shared screen",
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            onClick = { onPageChange((page + 1) % count) }
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .background(Color(0xFF111214).copy(alpha = 0.75f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "${page + 1} / $count",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PagerArrowButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    IconButton(
        onClick = onClick,
        modifier = modifier
            .hoverable(interaction)
            .size(36.dp)
            .background(Color(0xFF111214).copy(alpha = if (isHovered) 0.85f else 0.55f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(18.dp)
        )
    }
}
