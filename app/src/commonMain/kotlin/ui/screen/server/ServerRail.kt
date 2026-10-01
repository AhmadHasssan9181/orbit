package ui.screen.server

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ui.assets.OrbitBrandMark
import ui.assets.OrbitServerAssetIcon
import ui.components.HoverTooltip
import ui.components.OrbitCountBadge
import ui.components.OrbitScrollbar
import ui.model.OrbitServer
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * The server rail.
 *
 * Rebuilt against the concept's `.server-rail`. The previous version was a
 * Discord-flavoured rail rather than Discord's, and the differences were all
 * visible:
 *
 *  - Hovering a tile animated only the corner radius. Discord also floods the
 *    whole tile with blurple and turns the glyph white. That colour flood is the
 *    single most recognisable thing about the rail and it was simply missing.
 *  - The pill was inset 3dp from the edge, 3dp wide, and rounded on all corners.
 *    Discord's is flush to the edge at 4dp, rounded only on its right side, so it
 *    reads as a tab rather than a floating stick.
 *  - Pill heights were 30/16/0 against the concept's 40/20/0, and there was no
 *    8px unread state at all -- an unread space looked identical to a read one
 *    until you opened it.
 *  - The pill was accent-coloured when selected. Discord's is white in every
 *    state, which is what keeps it from competing with the tile fill.
 *  - Tiles were 42dp against the concept's 48dp, in a 68dp rail against 72dp.
 *  - Unselected tiles were dimmed to 82% alpha. There is no dimming in the
 *    concept; the pill alone indicates selection.
 *  - Action tiles were grey with an accent hover. The concept's are green, so
 *    "add" and "discover" read as creation rather than as another destination.
 *  - The tooltip was mounted TWICE, nested, with the outer one wrapping the inner
 *    one. The inner popup resolved its offset against the outer's zero-content
 *    wrapper, which is why the tooltip was unreliable here.
 */
@Composable
fun ServerRail(
    servers: List<OrbitServer>,
    selectedServerId: String?,
    onServerClick: (String) -> Unit,
    onAddServerClick: () -> Unit = {},
    onExploreClick: () -> Unit = {},
    unreadByServer: Map<String, Int> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()
    val spaces = servers.filter { !it.isDM }
    val dmServer = servers.firstOrNull { it.isDM }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(OrbitConceptMetrics.railWidth)
            .background(t.Rail_Bg)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxHeight(),
            contentPadding = PaddingValues(
                top = OrbitSpace.md,
                bottom = OrbitSpace.md
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            if (dmServer != null) {
                item(key = "home") {
                    ServerRailItem(
                        selected = dmServer.id == selectedServerId,
                        unreadCount = unreadByServer[dmServer.id] ?: 0,
                        onClick = { onServerClick(dmServer.id) },
                        label = dmServer.name
                    ) { hovered, shape ->
                        OrbitBrandMark(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(shape),
                            showGlow = dmServer.id == selectedServerId
                        )
                    }
                }
            }

            item(key = "divider") { RailDivider() }

            items(spaces, key = { it.id }, contentType = { "space" }) { server ->
                val isSelected = server.id == selectedServerId
                ServerRailItem(
                    selected = isSelected,
                    unreadCount = unreadByServer[server.id] ?: 0,
                    onClick = { onServerClick(server.id) },
                    label = server.name
                ) { hovered, shape ->
                    if (server.avatarUrl != null) {
                        coil3.compose.AsyncImage(
                            model = server.avatarUrl,
                            contentDescription = server.name,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(shape)
                        )
                    } else {
                        OrbitServerAssetIcon(
                            label = initialsOf(server.name),
                            color = Color(server.iconColor),
                            modifier = Modifier.size(48.dp).clip(shape)
                        )
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
                    onClick = onExploreClick
                )
            }
        }

        OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}

/** 32x2 hairline, `--border-subtle`, per the concept's `.server-divider`. */
@Composable
private fun RailDivider() {
    val t = rememberCosmicTokens()
    Box(
        modifier = Modifier
            .width(32.dp)
            .height(2.dp)
            .clip(CircleShape)
            // The concept's .server-divider is a white@8% hairline, not a ramp
            // step. t.Separator is opaque #3F4147, so the divider read as a hard
            // rule rather than a hairline between sections.
            .background(t.ServerDivider)
    )
}

/**
 * A rail tile: the selection pill, and the tile that floods on hover.
 *
 * One composable rather than a wrapper with an empty content lambda. The previous
 * version nested the tooltip inside itself, so the popup measured against a
 * zero-content parent and landed in the wrong place.
 */
@Composable
private fun ServerRailItem(
    selected: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    label: String,
    content: @Composable (hovered: Boolean, shape: androidx.compose.ui.graphics.Shape) -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Concept heights: 40 selected, 20 hover, 8 unread-and-idle, 0 otherwise.
    // The 8dp state is the one Orbit had no equivalent for at all, so an unread
    // space was indistinguishable from a read one until opened.
    val pillHeight by animateDpAsState(
        targetValue = when {
            selected -> 40.dp
            hovered -> 20.dp
            unreadCount > 0 -> 8.dp
            else -> 0.dp
        },
        animationSpec = OrbitMotion.BouncyDp,
        label = "railPillHeight"
    )

    // White in every state. The old version tinted it with the accent when
    // selected, which made it compete with the tile fill it is meant to sit beside.
    val pillVisible = pillHeight > 0.dp

    HoverTooltip(
        text = label,
        visible = hovered,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Flush to the rail's left edge, rounded only on the right, so it reads
            // as a tab cut into the rail rather than a stick floating in it.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(4.dp)
                    .height(pillHeight)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(if (pillVisible) Color.White else Color.Transparent)
            )

            // Discord's signature shape change: a full circle at rest that squares
            // off to 16dp on hover or selection. It is the one place in the app
            // where animating the shape is the point.
            val shape = RoundedCornerShape(
                animateDpAsState(
                    targetValue = if (selected || hovered) 16.dp else 24.dp,
                    animationSpec = OrbitMotion.BouncyDp,
                    label = "railCorner"
                ).value
            )

            val fill by animateColorAsState(
                // The flood. This is the change that was missing: hover fills the
                // whole tile with blurple rather than only reshaping it.
                targetValue = if (selected || hovered) t.Accent else t.Rail_Idle,
                animationSpec = OrbitMotion.SnappyColor,
                label = "railTileFill"
            )

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(fill)
                    .hoverable(interaction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                content(hovered, shape)

                if (unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 2.dp, bottom = 2.dp)
                    ) {
                        OrbitCountBadge(count = unreadCount, color = OrbitConceptStatus.dnd)
                    }
                }
            }
        }
    }
}

@Composable
private fun RailActionTile(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // Green at rest, per the concept's `.action-server-btn`. The previous tile was
    // grey with an accent hover, so "add a server" read as just another place to
    // go rather than as an action.
    val tint by animateColorAsState(
        targetValue = if (hovered) Color.White else OrbitConceptStatus.online,
        animationSpec = OrbitMotion.SnappyColor,
        label = "railActionTint"
    )

    val fill by animateColorAsState(
        targetValue = if (hovered) t.Accent else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "railActionFill"
    )

    val shape = RoundedCornerShape(
        animateDpAsState(
            targetValue = if (hovered) 16.dp else 24.dp,
            animationSpec = OrbitMotion.BouncyDp,
            label = "railActionCorner"
        ).value
    )

    Box(modifier = Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
        HoverTooltip(
            text = description,
            visible = hovered,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(fill)
                    .hoverable(interaction)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

/** Word-boundary split for initials; compiled once instead of per call. */
private val WhitespaceRun = Regex("\\s+")

internal fun initialsOf(name: String): String = when {
    name.isBlank() -> "?"
    name.length <= 2 -> name.uppercase()
    else -> name.split(WhitespaceRun)
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}