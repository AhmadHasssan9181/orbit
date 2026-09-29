package ui.screen.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
import ui.components.OrbitSegmentedControl
import ui.components.OrbitSwitch
import ui.components.orbitClickable
import ui.components.rememberOrbitInteraction
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Settings primitives.
 *
 * `PrefCard` used to make the *whole card* clickable with an empty `onClick`,
 * which meant a slider inside a card swallowed every click on the card and
 * hovering a static information row lit it up like a button. Cards are now
 * inert; only the actual control is interactive. That single change removes
 * most of the ways the settings screen used to feel broken.
 *
 * ## Why the card keeps its ring
 *
 * The card is the one surface in the app that is *supposed* to draw a
 * permanent edge. A settings page is a long scroll of unrelated rows, and
 * without the boundary a switch at the end of "Minimize to tray" reads as
 * belonging to whatever came before it. The borderless pass is therefore
 * directional: cards keep the fill and the hairline, and everything placed
 * *inside* a card gives its own border up -- buttons, the segmented control
 * and the text fields all do. That is what stops the old stack of
 * border-in-border-in-border.
 */
@Composable
internal fun PrefCard(
    title: String,
    description: String? = null,
    vertical: Boolean = false,
    icon: ImageVector? = null,
    content: @Composable () -> Unit
) {
    val t = rememberCosmicTokens()

    val surface = Modifier
        .fillMaxWidth()
        .clip(OrbitRadius.mdShape)
        .background(t.CardBg)
        .border(1.dp, t.Separator, OrbitRadius.mdShape)

    if (vertical) {
        Column(modifier = surface.padding(OrbitSpace.lg)) {
            PrefCardHeading(title, description, icon)
            Spacer(Modifier.height(OrbitSpace.md))
            content()
        }
    } else {
        Row(
            modifier = surface.padding(horizontal = OrbitSpace.lg, vertical = OrbitSpace.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PrefCardHeading(
                title,
                description,
                icon,
                // The floor matters as much as the weight. `weight(1f)` is a
                // REQUEST for leftover space, not a guarantee: any sibling that
                // measures wide takes the remainder first and this one collapses
                // to nothing. A zero-width Text wraps one glyph per line, which is
                // how "App Theme" turned into a vertical A-p-p-T-h-e-m-e. 120dp is
                // below any real label but well above one character, so the worst
                // case is a clipped word instead of a broken layout.
                Modifier
                    .weight(1f, fill = false)
                    .widthIn(min = 120.dp)
                    .padding(end = OrbitSpace.lg)
            )
            content()
        }
    }
}

@Composable
private fun PrefCardHeading(
    title: String,
    description: String?,
    icon: ImageVector?,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(OrbitRadius.smShape)
                    .background(t.Sunken),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = t.Text_Muted,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(OrbitSpace.md))
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    fontSize = 13.5.sp
                )
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = t.Text_Muted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                )
            }
        }
    }
}

@Composable
internal fun SectionHeader(text: String, subtitle: String? = null) {
    val t = rememberCosmicTokens()
    Column(modifier = Modifier.padding(top = OrbitSpace.xl, bottom = OrbitSpace.sm)) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                color = t.Text_Muted,
                letterSpacing = 0.8.sp,
                fontSize = 10.sp
            )
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(color = t.Text_Subtle, fontSize = 12.sp)
            )
        }
    }
}

/**
 * Segmented selector, themed to match the rest of the app.
 *
 * [fillWidth] defaults to false here even though the underlying control defaults
 * to true. Every `ChipGroup` sits in a `PrefCard` next to a heading, which is
 * exactly the arrangement that starves a `fillMaxWidth()` sibling. Content-width
 * segments plus the control's own horizontal scroll means the chips never eat the
 * heading's space, however many options a theme list grows to have.
 */
@Composable
internal fun ChipGroup(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    fillWidth: Boolean = false
) {
    OrbitSegmentedControl(
        options = options,
        selected = selected,
        onSelect = onSelect,
        label = { it },
        fillWidth = fillWidth
    )
}

@Composable
internal fun CosmicSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color? = null
) {
    val t = rememberCosmicTokens()
    OrbitSwitch(checked = checked, onCheckedChange = onCheckedChange)
}
