package ui.screen

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
import ui.model.OrbitServer

import ui.theme.LocalIsLiteMode
import ui.theme.rememberCosmicTokens

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val Rail_Bg: Color @Composable get() = rememberCosmicTokens().Rail_Bg
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent
private val AccentLight: Color @Composable get() = rememberCosmicTokens().AccentLight
private val Pill_Color: Color @Composable get() = rememberCosmicTokens().Pill_Color
private val Text_White: Color @Composable get() = rememberCosmicTokens().Text_White
private val Text_Muted: Color @Composable get() = rememberCosmicTokens().Text_Muted
private val Separator: Color @Composable get() = rememberCosmicTokens().Separator

@Composable
fun ServerRail(
    servers: List<OrbitServer>,
    selectedServerId: String?,
    onServerClick: (String) -> Unit,
    onAddServerClick: () -> Unit = {},
    selectedWhatsAppTab: String = "Chats",
    onWhatsAppTabClick: (String) -> Unit = {},
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
            .width(72.dp)
            .background(Rail_Bg),
        contentPadding = PaddingValues(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top)
    ) {
        // DM / Home icon — always first, like Discord
        item(key = "home") {
            val isSelected = selectedServerId == servers.firstOrNull { it.isDM }?.id
            ServerRailItem(
                isSelected = isSelected,
                isHovered = false,
                onClick = { servers.firstOrNull { it.isDM }?.let { onServerClick(it.id) } }
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isSelected) 16.dp else 24.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "homeCorner"
                                ).value
                            )
                        )
                        .background(
                            if (isSelected) Brush.linearGradient(listOf(Accent, AccentLight))
                            else Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "Home",
                        tint = if (isSelected) Color.White else Text_Muted,
                        modifier = Modifier.size(22.dp)
                    )
                }
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
                onClick = { onServerClick(server.id) }
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isSelected || isHovered) 16.dp else 24.dp,
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
                    val initials = getInitials(server.name)
                    val fontSize: TextUnit = if (initials.length > 2) 11.sp else 15.sp
                    Text(
                        text = initials,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize,
                        maxLines = 1
                    )
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
                        .size(48.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isHovered) 16.dp else 24.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "addCorner"
                                ).value
                            )
                        )
                        .background(
                            if (isHovered) Accent.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Server",
                        tint = if (isHovered) Accent else Text_Muted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        // Discover button
        item(key = "discover") {
            ServerRailItem(
                isSelected = false,
                isHovered = false,
                onClick = {}
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .hoverable(interactionSource)
                        .clip(
                            RoundedCornerShape(
                                animateDpAsState(
                                    if (isHovered) 16.dp else 24.dp,
                                    spring(stiffness = Spring.StiffnessMedium),
                                    label = "discoverCorner"
                                ).value
                            )
                        )
                        .background(
                            if (isHovered) Accent.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Explore,
                        contentDescription = "Discover",
                        tint = if (isHovered) Accent else Text_Muted,
                        modifier = Modifier.size(22.dp)
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
    content: @Composable () -> Unit
) {
    // Left pill indicator — exactly like Discord/Revolt
    val pillHeight by animateDpAsState(
        targetValue = when {
            isSelected -> 36.dp
            isHovered -> 20.dp
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
            .height(52.dp)
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
            LiteRailItem(Icons.Default.Chat, "Chats", isSelected = selectedTab == "Chats", onClick = { onTabClick("Chats") })
            LiteRailItem(Icons.Default.Call, "Calls", isSelected = selectedTab == "Calls", onClick = { onTabClick("Calls") })
            LiteRailItem(Icons.Default.DonutLarge, "Status", isSelected = selectedTab == "Status", onClick = { onTabClick("Status") })
            LiteRailItem(Icons.Default.Groups, "Groups", isSelected = selectedTab == "Communities", onClick = { onTabClick("Communities") })
        }

        // Bottom section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LiteRailItem(Icons.Default.Archive, "Archive", isSelected = false, onClick = {})
            LiteRailItem(Icons.Default.Settings, "Settings", isSelected = false, onClick = {})
            // Profile avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, AccentLight))),
                contentAlignment = Alignment.Center
            ) {
                Text("YO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
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