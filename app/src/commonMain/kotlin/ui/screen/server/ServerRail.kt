package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitBrandMark
import ui.assets.OrbitServerAssetIcon
import ui.icons.OrbitIcons
import ui.model.OrbitServer

import ui.theme.LocalIsLiteMode
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// ─── Cosmic Design Tokens ──────────────────────────────────────

@Composable
fun ServerRail(
    servers: List<OrbitServer>,
    selectedServerId: String?,
    onServerClick: (String) -> Unit,
    onAddServerClick: () -> Unit = {},
    selectedWhatsAppTab: String = "Chats",
    onWhatsAppTabClick: (String) -> Unit = {},
    unreadByServer: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppRail(selectedWhatsAppTab, onWhatsAppTabClick, modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .width(68.dp)
            .background(Rail_Bg),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top)
    ) {
        // DM / Home icon — always first, like Discord
        item(key = "home") {
            val isSelected = selectedServerId == servers.firstOrNull { it.isDM }?.id
            val homeUnread = servers.firstOrNull { it.isDM }?.id?.let { unreadByServer[it] } ?: 0
            ServerRailItem(
                isSelected = isSelected,
                isHovered = false,
                unreadCount = homeUnread,
                onClick = { servers.firstOrNull { it.isDM }?.let { onServerClick(it.id) } }
            ) {
                OrbitBrandMark(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isSelected) 15.dp else 22.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "homeCorner"
                                ).value
                            )
                        ),
                    showGlow = isSelected
                )
            }
        }

        // Divider like Discord/Revolt
        item(key = "divider") {
            HorizontalDivider(
                modifier = Modifier
                    .width(32.dp)
                    .padding(vertical = 2.dp),
                color = Separator,
                thickness = 2.dp
            )
        }

        // Server icons
        items(servers.filter { !it.isDM }, key = { it.id }) { server ->
            val isSelected = server.id == selectedServerId
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()

            ServerRailItem(
                isSelected = isSelected,
                isHovered = isHovered,
                unreadCount = unreadByServer[server.id] ?: 0,
                onClick = { onServerClick(server.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isSelected || isHovered) 15.dp else 22.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "srvCorner"
                                ).value
                            )
                        )
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(server.iconColor),
                                    Color(server.iconColor).copy(alpha = 0.7f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (server.avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = server.avatarUrl,
                            contentDescription = server.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val initials = getInitials(server.name)
                        OrbitServerAssetIcon(
                            label = initials,
                            color = Color(server.iconColor),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Add Server button
        item(key = "add") {
            ServerRailItem(
                isSelected = false,
                isHovered = false,
                onClick = onAddServerClick
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isHovered) 15.dp else 22.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "addCorner"
                                ).value
                            )
                        )
                        .background(
                            if (isHovered) Accent.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = OrbitIcons.Add,
                        contentDescription = "Add Server",
                        tint = if (isHovered) AccentLight else Text_Muted,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }

        // Discover button
        item(key = "discover") {
            ServerRailItem(
                isSelected = false,
                isHovered = false,
                onClick = onAddServerClick
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isHovered) 15.dp else 22.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "discoverCorner"
                                ).value
                            )
                        )
                        .background(
                            if (isHovered) Accent.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surfaceContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "Join a server",
                        tint = if (isHovered) AccentLight else Text_Muted,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

// ─── ServerRailItem — Discord-style pill + squircle ──────────

@Composable
private fun ServerRailItem(
    isSelected: Boolean,
    isHovered: Boolean,
    onClick: () -> Unit,
    unreadCount: Int = 0,
    content: @Composable () -> Unit
) {
    // Left pill indicator — exactly like Discord/Revolt
    val pillHeight by animateDpAsState(
        targetValue = when {
            isSelected -> 34.dp
            isHovered -> 18.dp
            else -> 0.dp
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pillH"
    )
    val pillColor by animateColorAsState(
        targetValue = when {
            isSelected -> Accent
            isHovered -> Text_White
            else -> Color.Transparent
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pillColor"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Left pill
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (-2).dp)
                .width(4.dp)
                .height(pillHeight)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(pillColor)
        )
        content()
        if (unreadCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-1).dp, y = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF23F42))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────

private fun getInitials(name: String): String = when {
    name.isBlank() -> "?"
    name.length <= 2 -> name.uppercase()
    else -> name.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}

// ─── Lite Mode Messenger Rail ────────────────────────────────────

@Composable
private fun WhatsAppRail(selectedTab: String, onTabClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(60.dp)
            .background(Rail_Bg)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top nav items
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiteRailItem(OrbitIcons.TextChannel, "Chats", isSelected = selectedTab == "Chats", onClick = { onTabClick("Chats") })
            LiteRailItem(OrbitIcons.Call, "Calls", isSelected = selectedTab == "Calls", onClick = { onTabClick("Calls") })
            LiteRailItem(Icons.Default.DonutLarge, "Status", isSelected = selectedTab == "Status", onClick = { onTabClick("Status") })
            LiteRailItem(OrbitIcons.Members, "Groups", isSelected = selectedTab == "Communities", onClick = { onTabClick("Communities") })
        }
    }
}

@Composable
private fun LiteRailItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> rememberCosmicTokens().ChannelSelected_Bg
            isHovered -> rememberCosmicTokens().ChannelHover_Bg
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "liteRailBg"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isSelected -> Accent
            isHovered -> Text_White
            else -> Text_Muted
        },
        label = "liteRailTint"
    )

    Column(
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = iconTint,
                fontSize = 8.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

