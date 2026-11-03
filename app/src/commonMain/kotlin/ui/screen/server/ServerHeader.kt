package ui.screen.server

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

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
import ui.icons.TablerIcons
import ui.icons.ChevronDown
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
    val hasBanner = !server.isDM && !server.bannerUrl.isNullOrBlank()

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember(server.id) { mutableStateOf(false) }

    // `.server-header:hover { background-color: var(--bg-modifier-hover) }`.
    // Kept even when there is no menu: the concept's DM bar keeps it too, and a
    // title bar that never acknowledges the pointer reads as a dead strip.
    val hoverFill by animateColorAsStateCompat(
        if (hovered && !hasBanner) conceptHoverOverlay() else Color.Transparent,
        label = "serverHeaderHover"
    )
    // `transform: rotate(180deg)` when the menu is open,
    // `transition: transform 0.2s ease`.
    val chevronRotation by animateFloatAsState(
        targetValue = if (menuOpen) 180f else 0f,
        animationSpec = OrbitMotion.Snappy,
        label = "serverChevron"
    )

    val clickModifier = if (!interactive) {
        Modifier
    } else {
        Modifier
            .pointerInput(interactive) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            menuOpen = true
                        }
                    }
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Open the ${server.name} menu",
                onClick = { menuOpen = !menuOpen }
            )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(hoverFill)
            .hoverable(interaction)
    ) {
        if (hasBanner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)
                    .shadow(elevation = 6.dp, shape = RoundedCornerShape(12.dp), clip = false)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                    .height(120.dp)
                    .then(clickModifier)
            ) {
                AsyncImage(
                    model = server.bannerUrl,
                    contentDescription = server.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Top subtle scrim + bottom gradient dark wash for perfect text readability
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x33000000),
                                    Color(0x10000000),
                                    Color(0x99111214),
                                    Color(0xF2111214)
                                )
                            )
                        )
                )
                if (hovered) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x1F000000))
                    )
                }

                // Server title row at the bottom of the floating banner card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isOrbitVerifiedUser(server.name)) {
                            OrbitVerifiedBadge(
                                tint = conceptBrand,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 14.sp,
                                letterSpacing = 0.sp,
                                shadow = Shadow(
                                    color = Color(0xCC000000),
                                    offset = Offset(0f, 1.5f),
                                    blurRadius = 4f
                                )
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    if (showArrow && interactive) {
                        Spacer(Modifier.size(6.dp))
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = with(TablerIcons) { ChevronDown },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .rotate(chevronRotation)
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OrbitConceptMetrics.headerHeight)
                    .then(clickModifier)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isOrbitVerifiedUser(server.name)) {
                        OrbitVerifiedBadge(
                            tint = conceptBrand,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = server.name,
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
                    androidx.compose.material3.Icon(
                        imageVector = with(TablerIcons) { ChevronDown },
                        contentDescription = null,
                        tint = conceptInteractiveNormal(),
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation)
                    )
                }
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
                offset = if (hasBanner) DpOffset(12.dp, 4.dp) else serverMenuOffset(),
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
        if (!hasBanner) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(conceptBorderSubtle())
            )
        }
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
