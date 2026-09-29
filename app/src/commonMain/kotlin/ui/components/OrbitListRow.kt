package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
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
 * The shared interaction model.
 *
 * Every interactive surface in Orbit answers three questions the same way:
 * what does it look like at rest, on hover, and while held. Before this
 * existed each file invented its own `spring(StiffnessMediumLow)` colour
 * animation and half of them had no press state at all, so the whole app felt
 * inconsistent under the cursor.
 */
@Stable
class OrbitInteraction internal constructor(
    val hoverSource: MutableInteractionSource = MutableInteractionSource(),
    val pressSource: MutableInteractionSource = MutableInteractionSource(),
    val focusSource: MutableInteractionSource = MutableInteractionSource()
)

@Composable
fun rememberOrbitInteraction(): OrbitInteraction = remember { OrbitInteraction() }

/** Slight compression while held — physical without being cute. */
@Composable
fun OrbitInteraction.pressScale(): Float =
    if (pressSource.collectIsPressedAsState().value) 0.972f else 1f

/**
 * Click handling that drives the shared press/hover sources and draws a
 * visible focus ring. Keyboard users get the same affordance as mouse users,
 * which the stock `clickable` ripple hides behind a focus outline.
 */
fun Modifier.orbitClickable(
    interaction: OrbitInteraction,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = this.clickable(
    interactionSource = interaction.pressSource,
    indication = null,
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick
)

// ─────────────────────────────────────────────────────────────────────────
// List row
// ─────────────────────────────────────────────────────────────────────────

/**
 * The single navigation tile used by the channel list, DM list, member list,
 * settings sidebar and call participant list.
 *
 * One implementation is what stops those five surfaces from drifting apart:
 * they used to differ in height, padding, hover colour, and whether the
 * selected state was a bar or a fill.
 */
@Immutable
class OrbitRowLeading(
    val icon: ImageVector? = null,
    val avatar: (@Composable () -> Unit)? = null,
    val custom: (@Composable () -> Unit)? = null
)



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrbitListRow(
    title: String,
    // Nullable: passing `{}` made every row look interactive while doing
    // nothing (see the member list). With null the row renders no click
    // semantics and no clickable modifier at all.
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectedColor: Color? = null,
    enabled: Boolean = true,
    leading: OrbitRowLeading? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    subtitle: String? = null,
    accentColor: Color? = null,
    titleStyle: TextStyle? = null,
    height: Dp = OrbitMetricsRow,
    horizontalPadding: Dp = OrbitSpace.sm,
    showSelectionBar: Boolean = true,
    shape: Shape = OrbitRadius.smShape,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    // A row with no click and no long-click is not interactive, so it must not
    // light up on hover either: the hover tint and the selection bar are the
    // affordance, and animating them made a read-only row look pressable.
    val isInteractive = onClick != null || onLongClick != null
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val pressed by interaction.pressSource.collectIsPressedAsState()
    val focused by interaction.focusSource.collectIsFocusedAsState()

    val restBackground = selectedColor
        ?: if (selected) t.ChannelSelected_Bg else Color.Transparent

    val bg by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Transparent
            pressed -> t.Pressed
            hovered && isInteractive -> if (selected) t.AccentGhostStrong else t.ChannelHover_Bg
            else -> restBackground
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "rowBg"
    )
    val barHeight by animateDpAsState(
        targetValue = when {
            selected -> height - 12.dp
            // The hover bar is the row's "you can click this" affordance, so a
            // non-interactive row (onClick == null) must not grow it either,
            // only the pressed/selected states apply.
            hovered && isInteractive -> 10.dp
            else -> 0.dp
        },
        animationSpec = OrbitMotion.SnappyDp,
        label = "rowBar"
    )
    val titleAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.45f,
        animationSpec = OrbitMotion.Snappy,
        label = "rowAlpha"
    )

    val titleColor = when {
        !enabled -> t.Text_Subtle
        selected -> t.Text_White
        hovered -> t.Text_Secondary
        else -> t.Text_Muted
    }
    val iconColor = when {
        selected -> (accentColor ?: t.Accent)
        hovered -> t.Text_Secondary
        else -> t.Text_Muted
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(bg)
            // Focus-only, as the file's own docstring says. The old `else 1.dp`
            // kept drawing a 1dp outline in a 55%-alpha accent, so every channel,
            // DM, member, settings and participant row looked permanently boxed.
            .border(if (focused && enabled) 1.5.dp else 0.dp, t.FocusRing, shape)
            .hoverable(interaction.hoverSource, enabled && isInteractive)
            .scale(interaction.pressScale())
            .then(
                when {
                    onClick == null -> Modifier
                    onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction.pressSource,
                        indication = null,
                        enabled = enabled,
                        onClickLabel = onClickLabel,
                        role = role,
                        onLongClick = onLongClick,
                        onClick = onClick
                    )
                    else -> Modifier.orbitClickable(
                        interaction = interaction,
                        enabled = enabled,
                        role = role,
                        onClickLabel = onClickLabel,
                        onClick = onClick
                    )
                }
            )
    ) {
        if (showSelectionBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .width(3.dp)
                    .height(barHeight)
                    .clip(CircleShape)
                    .background(
                        when {
                            selected -> (accentColor ?: t.Accent)
                            hovered -> t.Text_Muted
                            else -> Color.Transparent
                        }
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm)
        ) {
            when {
                leading?.avatar != null -> leading.avatar()
                leading?.icon != null -> Icon(
                    imageVector = leading.icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(17.dp)
                )
                leading?.custom != null -> leading.custom()
            }

            if (subtitle == null) {
                Text(
                    text = title,
                    style = titleStyle ?: LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            letterSpacing = 0.1.sp,
                            lineHeight = 17.sp
                        )
                    ),
                    color = titleColor.copy(alpha = titleAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = titleStyle ?: LocalTextStyle.current.merge(
                            MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        ),
                        color = titleColor.copy(alpha = titleAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall.copy(lineHeight = 13.sp),
                        color = t.Text_Subtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // No spacer before `trailing`: the title column already takes
            // weight(1f), so a second weighted child split the leftover space
            // 50/50 and truncated the title at half the row width.
            if (trailing != null) {
                trailing.invoke(this)
            }
        }
    }
}

internal val OrbitMetricsRow: Dp = 34.dp

// ─────────────────────────────────────────────────────────────────────────
// Badges and pills
// ─────────────────────────────────────────────────────────────────────────

/** Count pill for the trailing edge of a row. */
@Composable
fun OrbitCountBadge(
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 13.sp
            ),
            maxLines = 1
        )
    }
}

// `OrbitPill` used to live here: a solid/tinted pill for short status words
// ("LIVE", "BETA", "NEW"). Zero call sites. `OrbitCountBadge` and the inline
// status pills in the channel rows cover both jobs, so it was a second way to
// draw the same thing with no consumers -- and a third one to pick between.
//
// `onColorFor`, immediately below, stays: it is what picks black-or-white text for
// a filled surface by perceived luminance, and it is still live.

/** Picks black or white text for a filled pill, by perceived luminance. */
fun onColorFor(background: Color): Color = onColorForWash(background, 1f)

/**
 * [onColorFor] for a colour drawn at [alpha] over the surface beneath it.
 *
 * The opaque threshold is wrong for a translucent wash: a 45 %-alpha accent over
 * a near-white light-theme surface composites to a LIGHT lavender whose raw
 * accent luminance is still low, so the opaque check returns white and lands at
 * ~1.8:1 -- white on lavender, the exact bug the mention pill was being fixed
 * for. Compositing first is what makes the decision correct.
 */
fun onColorForWash(background: Color, alpha: Float, over: Color = Color.White): Color {
    val composited = lerp(over, background, alpha.coerceIn(0f, 1f))
    return if (composited.luminance() > 0.45f) Color(0xFF14161B) else Color.White
}
