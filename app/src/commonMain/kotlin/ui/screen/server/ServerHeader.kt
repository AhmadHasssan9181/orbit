package ui.screen.server

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.assets.OrbitVerifiedBadge
import ui.assets.isOrbitVerifiedUser
import ui.model.OrbitServer
import ui.screen.drawer.SidebarConcept
import ui.screen.drawer.animateColorAsStateCompat
import ui.screen.drawer.conceptBorderSubtle
import ui.screen.drawer.conceptBrand
import ui.screen.drawer.conceptFloatingSurface
import ui.screen.drawer.conceptHeaderPrimary
import ui.screen.drawer.conceptHoverOverlay
import ui.screen.drawer.conceptInteractiveNormal
import ui.screen.drawer.conceptTextNormal
import ui.theme.OrbitConceptMetrics
import ui.theme.OrbitConceptStatus
import ui.theme.OrbitMotion

/**
 * One row of the server dropdown.
 *
 * The concept fills its menu with Discord product actions — Server Boost, Server
 * Settings, Notification Settings, Leave Server — and Orbit has none of those.
 * The list is therefore a parameter rather than a hard-coded block: a menu of
 * five rows where four cannot do anything is worse than a short menu, because
 * four of them are lying about being available.
 */
internal data class ServerMenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

/**
 * The sidebar's header. The concept's `.server-header` and
 * `.server-dropdown-menu`.
 *
 * ## What this is now
 *
 * The concept's header is a flat 48dp bar: `--header-height`, `padding: 0 16px`,
 * a 1px `--border-subtle` bottom rule, `--elevation-low`, and
 * `--bg-modifier-hover` on hover. It holds the server name, an optional
 * verified badge, and a chevron that rotates 180° while the menu below it is
 * open.
 *
 * The previous version of this file was not that. It grew to 116dp whenever the
 * space had a banner, and it drew no bottom rule at all — the drawer drew one
 * separately, so the two edges were 1px apart and read as a seam rather than as
 * the header's own boundary. The banner case went with it: the concept has no
 * banner case, and `height: var(--header-height)` is the rule being matched. A
 * space's picture, if it has one, is still shown on its rail tile.
 *
 * ## Not clickable at all
 *
 * The concept's DM variant of this bar is `style={{ cursor: 'default' }}` — it
 * carries the CSS `:hover` fill but has no menu behind it, because the DM
 * sidebar has nothing to put in one. So [onHeaderClick] is nullable: null means
 * no menu, and the row is then a label with a hover wash and no click semantics,
 * rather than a button that opens an empty popover.
 */
@Composable
internal fun ServerHeader(
    server: OrbitServer,
    showArrow: Boolean,
    menuEntries: List<ServerMenuEntry> = emptyList()
) {
    // An empty list is what makes the bar inert: the concept's DM variant of this
    // header is `style={{ cursor: 'default' }}`, so with nothing to put in the
    // dropdown there is no click target and no chevron rotation either. It still
    // keeps the CSS `:hover` fill, because that rule is on `.server-header`
    // itself and not on the button inside it.
    val interactive = menuEntries.isNotEmpty()

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember(server.id) { mutableStateOf(false) }

    // `.server-header:hover { background-color: var(--bg-modifier-hover) }`.
    // Kept even when there is no menu: the concept's DM bar keeps it too, and a
    // title bar that never acknowledges the pointer reads as a dead strip.
    val hoverFill by animateColorAsStateCompat(
        if (hovered) conceptHoverOverlay() else Color.Transparent,
        label = "serverHeaderHover"
    )
    // `transform: rotate(180deg)` when the menu is open,
    // `transition: transform 0.2s ease`.
    val chevronRotation by animateFloatAsState(
        targetValue = if (menuOpen) 180f else 0f,
        animationSpec = OrbitMotion.Snappy,
        label = "serverChevron"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(hoverFill)
            .hoverable(interaction)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(OrbitConceptMetrics.headerHeight)
                .then(
                    // Before the padding: `.server-header` is the clickable box
                    // in the concept, padding included. Applying `padding`
                    // first would inset the hit area by 16dp at each end, so
                    // the outer 16dp of the bar would do nothing.
                    if (!interactive) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClickLabel = "Open the ${server.name} menu",
                            onClick = { menuOpen = !menuOpen }
                        )
                    }
                )
                // `padding: 0 16px` and `justify-content: space-between`.
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // `.server-header-info { display: flex; align-items: center; gap: 8px;
            // overflow: hidden }`.
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // `.server-header-badge { color: #5865f2 }` — the verified tick.
                // The concept paints it in the brand colour rather than a muted
                // one because it is a mark of the thing itself, not chrome.
                if (isOrbitVerifiedUser(server.name)) {
                    OrbitVerifiedBadge(
                        tint = conceptBrand,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text = server.name,
                    // `.server-header-title { font-size: 15px; font-weight: 700;
                    // color: var(--header-primary) }`.
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = conceptHeaderPrimary(),
                        fontSize = 15.sp,
                        letterSpacing = 0.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            if (showArrow && interactive) {
                Spacer(Modifier.size(8.dp))
                // `color: var(--interactive-normal)` — the concept does NOT
                // lighten the chevron on hover, only the row behind it.
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = conceptInteractiveNormal(),
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation)
                )
            }
        }

        // The menu. Anchored to the header column so its left inset is measured
        // from the panel edge, which is what `.server-dropdown-menu { left: 10px }`
        // asks for. The vertical offset is the difference between the CSS's
        // `top: 54px` and the 48dp bar the popup already sits below.
        if (interactive) {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier
                    .width(SidebarConcept.dropdownWidth)
                    .padding(SidebarConcept.dropdownPadding),
                offset = serverMenuOffset(),
                shape = SidebarConcept.dropdownShape,
                containerColor = conceptFloatingSurface(),
                border = BorderStroke(1.dp, conceptBorderSubtle()),
                // `--elevation-high: 0 8px 16px rgba(0, 0, 0, 0.24)`.
                shadowElevation = 16.dp,
                tonalElevation = 0.dp
            ) {
                menuEntries.forEach { entry ->
                    ServerMenuItem(
                        label = entry.label,
                        icon = entry.icon,
                        danger = entry.danger,
                        onClick = {
                            menuOpen = false
                            entry.onClick()
                        }
                    )
                }
            }
        }

        // `.server-header { border-bottom: 1px solid var(--border-subtle) }`.
        // `--elevation-low` on top of it is a 1px black hairline at 20%, which is
        // indistinguishable from the border rule on every surface in the concept,
        // so only the rule is drawn.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(conceptBorderSubtle())
        )
    }
}

/**
 * `.server-dropdown-menu` is `position: absolute; top: 54px; left: 10px`.
 *
 * A `DropdownMenu` already sits flush below its anchor — here a 48dp column —
 * so the vertical part of that `top` is the 6px it has to travel past it, and
 * the horizontal part is the menu's own 10px inset. `DropdownMenu` takes a
 * `DpOffset`, so this is not converted to pixels here: the menu does that with
 * the right density.
 */
private fun serverMenuOffset(): DpOffset = DpOffset(10.dp, 6.dp)

/**
 * `.menu-item { display: flex; align-items: center; justify-content:
 * space-between; padding: 8px 10px; font-size: 13px; font-weight: 500; color:
 * var(--text-normal); border-radius: 4px }` with
 * `.menu-item:hover { background-color: var(--brand-blurple); color: #ffffff }`
 * and `.menu-item.danger:hover { background-color: var(--status-dnd) }`.
 *
 * Material's `DropdownMenuItem` is not used: its row is a 48dp minimum
 * interactive target with its own padding, so the concept's 8px/10px and 13px
 * type do not survive it, and a 48dp row inside a 220dp popover over a 240dp
 * panel is visibly the wrong shape. The row is built here instead. The
 * 48dp target is kept as a minimum so it is still comfortable to hit.
 */
@Composable
private fun ServerMenuItem(
    label: String,
    icon: ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val fill by animateColorAsStateCompat(
        when {
            !hovered -> Color.Transparent
            danger -> OrbitConceptStatus.dnd
            else -> conceptBrand
        },
        label = "serverMenuItemFill"
    )
    // `:hover { color: #ffffff }` — white, not the theme's text colour, because
    // the row is sitting on a saturated fill in both themes.
    val fg by animateColorAsStateCompat(
        if (hovered) Color.White else conceptTextNormal(),
        label = "serverMenuItemFg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(SidebarConcept.menuItemShape)
            .background(fill)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick
            )
            .padding(
                horizontal = SidebarConcept.menuItemPaddingH,
                vertical = SidebarConcept.menuItemPaddingV
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (icon != null) {
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * `.menu-divider { height: 1px; background-color: var(--border-subtle);
 * margin: 4px 0 }`.
 *
 * Currently unbuilt: the menu carries two entries and no divider between them.
 * It exists because the rule is part of the component being reimplemented and a
 * future third group needs it — but an unused private composable is dead code, so
 * it is `internal` and callable from the drawer the moment there is something to
 * separate.
 */
@Suppress("unused")
@Composable
internal fun ServerMenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SidebarConcept.menuDividerMargin)
            .height(1.dp)
            .background(conceptBorderSubtle())
    )
}
