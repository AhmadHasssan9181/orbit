package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

/**
 * Menu.
 *
 * Every popup in the app used Material's DropdownMenu with a hard-coded
 * `Color(0xFF262C36)` container, so menus stayed Discord-grey in the light
 * theme and in every custom theme. This routes through the token set and adds
 * the two things a desktop menu needs: a labelled section header and a
 * keyboard-friendly item shape.
 */
@Composable
fun OrbitMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 208.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = rememberCosmicTokens()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.width(width),
        shape = OrbitRadius.mdShape,
        containerColor = t.Elevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, t.SeparatorStrong),
        shadowElevation = 16.dp,
        tonalElevation = 0.dp
    ) {
        content()
    }
}

@Composable
fun OrbitMenuHeader(text: String, modifier: Modifier = Modifier) {
    val t = rememberCosmicTokens()
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(
            color = t.Text_Subtle,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            letterSpacing = 0.8.sp
        ),
        maxLines = 1,
        modifier = modifier.padding(start = OrbitSpace.md, end = OrbitSpace.md, top = OrbitSpace.md, bottom = OrbitSpace.xs)
    )
}

@Composable
fun OrbitMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        color = rememberCosmicTokens().Separator,
        thickness = 1.dp,
        modifier = modifier.padding(vertical = OrbitSpace.xs)
    )
}

@Composable
fun OrbitMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    danger: Boolean = false,
    disabled: Boolean = false,
    selected: Boolean = false
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()

    val bg by animateColorAsState(
        targetValue = when {
            disabled -> Color.Transparent
            hovered -> if (danger) t.DangerGhost else t.Hover
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "menuItemBg"
    )
    val fg by animateColorAsState(
        targetValue = when {
            disabled -> t.Text_Subtle
            danger -> t.Danger
            selected || hovered -> t.Text_White
            else -> t.Text_Secondary
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "menuItemFg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.xs, vertical = 1.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction.hoverSource, !disabled)
            .orbitClickable(interaction, enabled = !disabled, onClick = onClick)
            .padding(horizontal = OrbitSpace.sm, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        when {
            leading != null -> leading()
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(15.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                color = fg,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelSmall,
                color = t.Text_Subtle,
                maxLines = 1
            )
        }
    }
}

/** Menu row with a trailing switch, for toggles that live in a menu. */
@Composable
fun OrbitMenuSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val bg by animateColorAsState(
        targetValue = if (hovered) t.Hover else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "menuSwitchBg"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OrbitSpace.xs, vertical = 1.dp)
            .clip(OrbitRadius.xsShape)
            .background(bg)
            .hoverable(interaction.hoverSource)
            .orbitClickable(interaction, onClick = { onCheckedChange(!checked) })
            .padding(start = OrbitSpace.sm, end = OrbitSpace.xs, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = t.Text_Muted, modifier = Modifier.size(15.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(color = t.Text_Secondary, fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.height(26.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = t.Accent,
                uncheckedThumbColor = t.Text_Muted,
                uncheckedTrackColor = t.Sunken,
                uncheckedBorderColor = t.Separator
            )
        )
    }
}

/** Menu row with a trailing check, for single-select menus. */
@Composable
fun OrbitMenuCheck(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    OrbitMenuItem(
        label = label,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        selected = checked,
        trailingText = if (checked) "✓" else null
    )
}
