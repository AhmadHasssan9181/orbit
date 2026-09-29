package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

enum class OrbitButtonTone { Primary, Neutral, Ghost, Danger, Success }

/**
 * The app's button.
 *
 * Previously every screen called Material's `Button` with hand-picked
 * container colours, which meant the same action looked different in the
 * settings pane, the call screen and the join-server dialog.
 *
 * ## Why most buttons draw nothing at rest
 *
 * A neutral button used to be a filled rounded rectangle with a 1dp border,
 * and it almost always lived inside a card that is *also* a filled rounded
 * rectangle with a 1dp border. Stacked, that is three outlines for one action
 * and a screen that reads as boxy rather than calm. Discord's calm comes from
 * having very few visible edges: quiet controls are text plus an icon until
 * the cursor arrives, and only the accent-filled primary action keeps a
 * resting surface.
 *
 * So `Neutral` and `Ghost` are quiet at rest (no fill, no outline), `Primary`,
 * `Danger` and `Success` keep their fill because that fill *is* the emphasis,
 * and the only outline a resting button ever draws is the keyboard focus ring.
 */
@Composable
fun OrbitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: OrbitButtonTone = OrbitButtonTone.Neutral,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    size: Dp = 34.dp,
    shape: Shape = OrbitRadius.smShape,
    /**
     * Stretch to the full width offered.
     *
     * DANGER: never pass this for a child of a `Row`/`Column` that has a weighted
     * sibling. A Row measures non-weighted children against the FULL width first
     * and only then shares the remainder, so `fillMaxWidth()` here wins outright
     * and the weighted sibling collapses to zero. To share a Row evenly, pass
     * `modifier = Modifier.weight(1f)` and leave this false.
     */
    fullWidth: Boolean = false
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val focused by interaction.focusSource.collectIsFocusedAsState()

    // Quiet tones have no resting surface at all; the hovered colour is the
    // first time they draw one. Filled tones keep their fill at rest because
    // that fill is what separates the primary action from everything else.
    val quiet = tone == OrbitButtonTone.Neutral || tone == OrbitButtonTone.Ghost

    val resting = when (tone) {
        OrbitButtonTone.Primary -> t.Accent
        OrbitButtonTone.Neutral, OrbitButtonTone.Ghost -> Color.Transparent
        OrbitButtonTone.Danger -> t.Danger
        OrbitButtonTone.Success -> t.Success
    }
    val hoveredColor = when (tone) {
        OrbitButtonTone.Primary -> t.AccentLight
        // A quiet button reveals a plain surface, not a brighter box: the hover
        // is a hint that the target is live, not a second resting style.
        OrbitButtonTone.Neutral, OrbitButtonTone.Ghost -> t.Hover
        OrbitButtonTone.Danger -> t.Danger.copy(alpha = 0.86f)
        OrbitButtonTone.Success -> t.Success.copy(alpha = 0.88f)
    }
    // A quiet button is secondary by TEXT alone -- no fill, no ring -- which is
    // the whole difference between it and the accent-filled primary.
    val content = when (tone) {
        OrbitButtonTone.Primary, OrbitButtonTone.Danger, OrbitButtonTone.Success -> onColorFor(resting)
        OrbitButtonTone.Neutral, OrbitButtonTone.Ghost -> if (hovered || pressed) t.Text_White else t.Text_Secondary
    }

    val bg by animateColorAsState(
        targetValue = when {
            // A disabled quiet button is text, not a grey box. Dimming a fill
            // that no longer exists would leave a phantom surface behind the
            // label.
            !enabled -> if (quiet) Color.Transparent else t.CardBg.copy(alpha = 0.5f)
            pressed -> t.Pressed
            hovered -> hoveredColor
            else -> resting
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "btnBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (enabled) content else t.Text_Subtle,
        animationSpec = OrbitMotion.SnappyColor,
        label = "btnFg"
    )
    // The focus ring is the only outline a button ever draws. The old
    // `Neutral -> Separator` outline ringed every secondary action in the app,
    // and the `Ghost` hover outline ringed them again the moment the cursor
    // passed over; this one carries information instead of decoration.
    val borderColor by animateColorAsState(
        targetValue = if (focused && enabled) t.FocusRing else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "btnOutline"
    )

    Box(
        modifier = modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = size)
            .clip(shape)
            .background(bg)
            .border(if (borderColor == Color.Transparent) 0.dp else 1.dp, borderColor, shape)
            .hoverable(interaction.hoverSource, enabled)
            .scale(if (pressed) 0.975f else 1f)
            .orbitClickable(interaction, enabled, Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OrbitSpace.md, vertical = OrbitSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = contentColor,
                    strokeWidth = 2.dp
                )
            } else if (icon != null) {
                Icon(icon, null, tint = contentColor, modifier = Modifier.size(15.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (trailingIcon != null) {
                Icon(trailingIcon, null, tint = contentColor, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Square icon button. Used for every header action, hover toolbar button and
 * rail control, so hover/active/tooltip behaviour only has to be right once.
 */@Composable
fun OrbitIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    accent: Color? = null,
    tooltip: String? = null,
    size: Dp = 32.dp,
    iconSize: Dp = 17.dp,
    shape: Shape = OrbitRadius.smShape,
    danger: Boolean = false,
    content: (@Composable () -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val focused by interaction.focusSource.collectIsFocusedAsState()

    val accentColor = accent ?: if (danger) t.Danger else t.Accent

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            active -> t.AccentGhostStrong
            pressed -> t.Pressed
            hovered -> if (active) t.AccentGhostStrong else t.Hover
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "iconBtnBg"
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> t.Text_Subtle
            danger -> accentColor
            active -> accentColor
            hovered -> t.Text_White
            else -> t.Text_Muted
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "iconBtnTint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (hovered) 1.06f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "iconBtnScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused && enabled) t.FocusRing else Color.Transparent,
        animationSpec = OrbitMotion.SnappyColor,
        label = "iconBtnOutline"
    )

    val core: @Composable () -> Unit = {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(bg)
                .border(if (borderColor == Color.Transparent) 0.dp else 1.5.dp, borderColor, shape)
                .hoverable(interaction.hoverSource, enabled)
                .scale(if (pressed) 0.92f else 1f)
                .orbitClickable(interaction, enabled, Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (content != null) {
                content()
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize).scale(iconScale)
                )
            }
        }
    }

    if (tooltip != null) OrbitTooltip(tooltip, core) else core()
}

/** Circular variant, for call controls and the scroll-to-bottom affordance. */
@Composable
fun OrbitIconButtonRound(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    accent: Color? = null,
    tooltip: String? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 19.dp,
    danger: Boolean = false,
    filled: Boolean = false
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val accentColor = accent ?: if (danger) t.Danger else t.Accent

    val bg by animateColorAsState(
        targetValue = when {
            // Quiet at rest, like every other button: this control floats over
            // the message list, so a filled circle plus a 1dp ring is two edges
            // drawn for one affordance. Hover reveals the surface instead.
            !enabled -> Color.Transparent
            filled -> accentColor
            active -> t.AccentGhostStrong
            pressed -> t.Pressed
            hovered -> t.Hover
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "roundBtnBg"
    )
    val tint by animateColorAsState(
        targetValue = when {
            !enabled -> t.Text_Subtle
            danger -> accentColor
            active -> accentColor
            filled -> onColorFor(bg)
            hovered -> t.Text_White
            else -> t.Text_Secondary
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "roundBtnTint"
    )
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.05f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "roundBtnScale"
    )

    val core: @Composable () -> Unit = {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .hoverable(interaction.hoverSource, enabled)
                .scale(if (pressed) 0.94f else scale)
                .orbitClickable(interaction, enabled, Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }

    if (tooltip != null) OrbitTooltip(tooltip, core) else core()
}
