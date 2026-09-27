package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitBrandMark
import ui.assets.OrbitServerAssetIcon
import ui.components.OrbitCountBadge
import ui.components.OrbitScrollbar
import ui.model.OrbitServer
import ui.theme.LocalIsLiteMode
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * The server rail.
 *
 * Discord's rail is good and worth keeping: a 68px column of squircles with a
 * selection pill. What was wrong here was the execution — the pill was
 * hard-coded to a neon red for unread counts, the "add" and "discover" tiles
 * used a background that did not exist in the token set, hovering animated the
 * corner radius of a shape the user could not see, and there was no way to
 * find out which space a tile was without clicking it.
 *
 * Now: labelled on hover, one accent, a real token for every surface, and a
 * scrollbar that only shows up when there are more spaces than fit.
 */
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

    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()
    val spaces = servers.filter { !it.isDM }
    val dmServer = servers.firstOrNull { it.isDM }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(72.dp)
            .background(t.Rail_Bg)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = OrbitSpace.md,
                bottom = OrbitSpace.md
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.xs)
        ) {
            // ── Home / DMs ─────────────────────────────────────────
            if (dmServer != null) {
                item(key = "home") {
                    ServerRailItem(
                        selected = dmServer.id == selectedServerId,
                        unreadCount = unreadByServer[dmServer.id] ?: 0,
                        onClick = { onServerClick(dmServer.id) },
                        label = dmServer.name
                    ) { hovered ->
                        OrbitBrandMark(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(
                                    RoundedCornerShape(
                                        animateRailCorner(selected = dmServer.id == selectedServerId, hovered = hovered)
                                    )
                                ),
                            showGlow = dmServer.id == selectedServerId
                        )
                    }
                }
            }

            item(key = "divider") {
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .height(1.5.dp)
                        .clip(CircleShape)
                        .background(t.Separator)
                )
            }

            items(spaces, key = { it.id }) { server ->
                val isSelected = server.id == selectedServerId
                ServerRailItem(
                    selected = isSelected,
                    unreadCount = unreadByServer[server.id] ?: 0,
                    onClick = { onServerClick(server.id) },
                    label = server.name
                ) { hovered ->
                    val shape = RoundedCornerShape(
                        animateRailCorner(selected = isSelected, hovered = hovered)
                    )
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(shape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Color(server.iconColor),
                                        Color(server.iconColor).copy(alpha = 0.78f)
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
                                modifier = Modifier.fillMaxWidth().size(42.dp)
                            )
                        } else {
                            OrbitServerAssetIcon(
                                label = initialsOf(server.name),
                                color = Color(server.iconColor),
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                }
            }

            item(key = "add") {
                RailActionTile(
                    icon = Icons.Default.Add,
                    description = "Create or join a server",
                    onClick = onAddServerClick
                )
            }
            item(key = "discover") {
                RailActionTile(
                    icon = Icons.Default.Explore,
                    description = "Discover servers",
                    onClick = onAddServerClick
                )
            }
        }

        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * The rail's signature shape change: a rounded square at rest that tightens
 * toward a squircle when the tile is selected or hovered. It is the one place
 * in the app where animating the *shape* is the point, so it gets its own
 * spec rather than a generic duration.
 */
@Composable
private fun animateRailCorner(selected: Boolean, hovered: Boolean) =
    animateDpAsState(
        targetValue = if (selected || hovered) 14.dp else 18.dp,
        animationSpec = OrbitMotion.BouncyDp,
        label = "railCorner"
    ).value

@Composable
private fun ServerRailItem(
    selected: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    label: String,
    content: @Composable (hovered: Boolean) -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Left selection pill.
    val pillHeight by animateDpAsState(
        targetValue = when {
            selected -> 30.dp
            hovered -> 16.dp
            else -> 0.dp
        },
        animationSpec = OrbitMotion.BouncyDp,
        label = "railPillHeight"
    )
    val pillColor by animateColorAsState(
        targetValue = when {
            selected -> t.Accent
            hovered -> t.Text_Muted
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "railPillColor"
    )
    // Mute a non-selected tile slightly so the active one carries the eye.
    val tileAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.82f,
        animationSpec = OrbitMotion.Snappy,
        label = "railTileAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 3.dp)
                .width(3.dp)
                .height(pillHeight)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(pillColor)
        )

        Box(
            modifier = Modifier
                .size(50.dp)
                .alpha(tileAlpha)
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            content(hovered)

            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 3.dp, top = 2.dp)
                ) {
                    OrbitCountBadge(count = unreadCount, color = t.Danger)
                }
            }
        }

        // Name, revealed on hover — the only way to know a space without opening it.
        ui.components.HoverTooltip(
            text = label,
            visible = hovered,
            modifier = Modifier
        ) { }
    }
}

@Composable
private fun RailActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = if (hovered) t.CardBgHover else t.CardBg,
        animationSpec = OrbitMotion.SnappyColor,
        label = "railActionBg"
    )
    val tint by animateColorAsState(
        targetValue = if (hovered) t.AccentLight else t.Text_Muted,
        animationSpec = OrbitMotion.SnappyColor,
        label = "railActionTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.06f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "railActionScale"
    )

    Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .scale(scale)
                .clip(RoundedCornerShape(animateRailCorner(selected = false, hovered = hovered)))
                .background(bg)
                .border(1.dp, if (hovered) t.AccentBorder else t.Separator, RoundedCornerShape(18.dp))
                .hoverable(interaction)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
        }
        ui.components.HoverTooltip(text = description, visible = hovered) { }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────

internal fun initialsOf(name: String): String = when {
    name.isBlank() -> "?"
    name.length <= 2 -> name.uppercase()
    else -> name.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}

// ─── Lite Mode Messenger Rail ────────────────────────────────────────────

@Composable
private fun WhatsAppRail(selectedTab: String, onTabClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(60.dp)
            .background(ui.theme.rememberCosmicTokens().Rail_Bg)
            .padding(vertical = OrbitSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.xs)
        ) {
            LiteRailItem(ui.icons.OrbitIcons.TextChannel, "Chats", isSelected = selectedTab == "Chats", onClick = { onTabClick("Chats") })
            LiteRailItem(ui.icons.OrbitIcons.Call, "Calls", isSelected = selectedTab == "Calls", onClick = { onTabClick("Calls") })
            LiteRailItem(Icons.Default.DonutLarge, "Status", isSelected = selectedTab == "Status", onClick = { onTabClick("Status") })
            LiteRailItem(ui.icons.OrbitIcons.Members, "Groups", isSelected = selectedTab == "Communities", onClick = { onTabClick("Communities") })
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
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> t.ChannelSelected_Bg
            hovered -> t.ChannelHover_Bg
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "liteRailBg"
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            isSelected -> t.Accent
            hovered -> t.Text_White
            else -> t.Text_Muted
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "liteRailTint"
    )

    Column(
        modifier = Modifier
            .size(50.dp)
            .clip(OrbitRadius.mdShape)
            .background(bgColor)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(19.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = iconTint,
                fontSize = 8.5.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}
