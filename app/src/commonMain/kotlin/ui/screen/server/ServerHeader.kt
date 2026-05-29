package ui.screen.server

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ui.icons.OrbitIcons
import ui.model.OrbitServer

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun ServerHeader(
    server: OrbitServer,
    showArrow: Boolean,
    onHeaderClick: () -> Unit = {},
    onCreateRoomClick: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val headerHeight = if (server.bannerUrl != null) 100.dp else 46.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(headerHeight)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onHeaderClick)
            .background(if (isHovered && server.bannerUrl == null) ChannelHover_Bg else Color.Transparent)
    ) {
        if (server.bannerUrl != null) {
            coil3.compose.AsyncImage(
                model = server.bannerUrl,
                contentDescription = "Server Banner",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Gradient overlay to ensure text is readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = if (server.bannerUrl != null) 8.dp else 0.dp)
                .height(if (server.bannerUrl != null) 24.dp else 46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = server.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_White
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (onCreateRoomClick != null) {
                    IconButton(
                        onClick = onCreateRoomClick,
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = OrbitIcons.Add,
                            contentDescription = "Create Room",
                            tint = Text_Muted,
                            modifier = Modifier.size(17.dp).alpha(0.9f)
                        )
                    }
                }
                if (showArrow) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(17.dp).alpha(0.7f)
                    )
                }
            }
        }
    }
}
