package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.TablerIcons
import ui.icons.Search
import ui.icons.X
import ui.theme.OrbitMotion
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens
/**
 * Text field.
 *
 * Material's `OutlinedTextField` left a 1px border, a floating label gutter
 * and a 56dp minimum height inside our rounded 34dp rows, so every input in
 * the app was visually taller than the button sitting next to it. This is a
 * borderless field that the caller frames.
 *
 * ## Why the frame is focus-only
 *
 * A permanent outline is what made input-heavy screens look like forms
 * stapled together: the field drew a box, and so did the card it sat in. The
 * field keeps its background -- that is what makes it read as something you
 * can type into -- but the outline arrives with the caret and leaves when it
 * goes, so at rest there is exactly one edge per input instead of two.
 */
@Composable
fun OrbitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    textStyle: TextStyle? = null
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val focused by interaction.focusSource.collectIsFocusedAsState()
    // Keyed on the accent: unkeyed, the caret kept the previous theme's colour
    // after a theme switch because the SolidColor was never recomputed.
    val cursorColor = remember(t.Accent) { SolidColor(t.Accent) }

    val frame by animateColorAsState(
        targetValue = when {
            // Transparent at rest, in every state. The old `else -> t.Separator`
            // drew a permanent hairline around every field on screen, which is
            // most of the visible edge count in the settings panes and the login
            // form. Disabled loses the ring too: a field you cannot type in has
            // nothing to focus, and its greyed-out text already says so.
            !enabled -> Color.Transparent
            focused -> t.Accent
            else -> Color.Transparent
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "fieldFrame"
    )
    val frameWidth by animateFloatAsState(
        targetValue = if (focused && enabled) 1.5f else 0f,
        animationSpec = OrbitMotion.Snappy,
        label = "fieldFrameWidth"
    )
    val fieldStyle = (textStyle ?: MaterialTheme.typography.bodyMedium).copy(
        color = if (enabled) t.Text_White else t.Text_Subtle
    )

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 34.dp)
            .clip(OrbitRadius.mdShape)
            .background(t.Input_Bg)
            // 0.dp when unfocused, so the resting field is a filled shape with no
            // edge at all -- the fill is the affordance.
            .border(frameWidth.dp, frame, OrbitRadius.mdShape)
            .padding(horizontal = OrbitSpace.sm, vertical = OrbitSpace.xs),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OrbitSpace.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) t.Accent else t.Text_Muted,
                    modifier = Modifier.size(15.dp)
                )
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = fieldStyle.copy(color = t.Text_Subtle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    textStyle = fieldStyle,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    interactionSource = interaction.focusSource,
                    cursorBrush = cursorColor,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { onImeAction?.invoke() },
                        onSend = { onImeAction?.invoke() },
                        onGo = { onImeAction?.invoke() },
                        onSearch = { onImeAction?.invoke() }
                    )
                )
            }
            if (trailing != null) trailing()
        }
    }
}

/**
 * Search field with a leading glyph and an inline clear affordance.
 */
@Composable
fun OrbitSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    OrbitTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        leadingIcon = with(TablerIcons) { Search },
        imeAction = ImeAction.Search,
        onImeAction = onSubmit,
        trailing = {
            if (value.isNotEmpty()) {
                OrbitIconButton(
                    icon = with(TablerIcons) { X },
                    contentDescription = "Clear search",
                    onClick = { onValueChange("") },
                    size = 22.dp,
                    iconSize = 13.dp,
                    shape = CircleShape
                )
            }
        }
    )
}

/**
 * Segmented control. Replaces the row of Material `FilterChip`s that used to
 * mark the selected option with a colour change and nothing else.
 *
 * [fillWidth] exists because of a concrete, ugly failure. A `Row` measures its
 * non-weighted children against the FULL incoming width, and only then divides
 * what is left among `weight(1f)` children. This control's inner Row was
 * `fillMaxWidth()`, so inside a `PrefCard` -- which lays out
 * `Heading(weight 1f)` beside `content()` -- the control claimed every pixel and
 * the heading was measured at zero. A `Text` at zero-ish width wraps one glyph per
 * line, so the Appearance pane rendered "App Theme" vertically as A-p-p-T-h-e-m-e
 * with the description one letter per line below it. Ten segments do not fit in a
 * 600dp card, and `weight(1f)` per segment made each narrower than its own label,
 * which is how a chip that said "Cyberpunk" ended up showing "Cybe".
 *
 * So: segments are sized by their content, the row scrolls when it overflows
 * rather than crushing its labels, and callers that genuinely want the control to
 * span the space can still say so.
 */
@Composable
fun <T> OrbitSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
    height: Dp = 32.dp,
    fillWidth: Boolean = true
) {
    val t = rememberCosmicTokens()
    Box(
        modifier = modifier
            .clip(OrbitRadius.smShape)
            // The sunken track stays -- it is what makes the control read as one
            // group rather than a row of loose labels -- but the 1dp ring around
            // it is gone. This control almost always lives inside a PrefCard, and
            // a bordered control inside a bordered card is the box-in-a-box the
            // whole app is trying to shed. Selected segments are marked by their
            // fill, so no information is lost.
            .background(t.Sunken)
            .padding(2.dp)
    ) {
        Row(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = rememberOrbitInteraction()
                val hovered by interaction.hoverSource.collectIsHoveredAsState()
                val bg by animateColorAsState(
                    targetValue = when {
                        isSelected -> t.CardBgHover
                        hovered -> t.Hover
                        else -> Color.Transparent
                    },
                    animationSpec = OrbitMotion.SnappyColor,
                    label = "segBg"
                )
                val fg by animateColorAsState(
                    targetValue = when {
                        isSelected -> t.Text_White
                        hovered -> t.Text_Secondary
                        else -> t.Text_Muted
                    },
                    animationSpec = OrbitMotion.SnappyColor,
                    label = "segFg"
                )
                Box(
                    modifier = Modifier
                        // Content width, floored at a legible minimum, instead of
                        // weight(1f). A segment can now never be narrower than the
                        // word inside it.
                        .widthIn(min = 56.dp)
                        .height(height)
                        .clip(OrbitRadius.xsShape)
                        .background(bg)
                        .hoverable(interaction.hoverSource)
                        .orbitClickable(interaction, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge.copy(
                            color = fg,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            fontSize = 12.sp
                        ),
                        // No ellipsis: if it still does not fit, scrolling shows the
                        // whole word, which beats showing "Cybe".
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

/**
 * Switch, sized and coloured to match the rest of the app.
 *
 * This is the one control whose resting outline is kept. The unchecked track is
 * `t.Sunken`, which in the light theme is the *same value* as the `t.CardBg` of
 * the `PrefCard` it sits in, so without the hairline an off switch inside a
 * settings card would be an invisible 40x22 hole. The ring is load-bearing here
 * rather than decorative, which is exactly the test the borderless pass applies
 * everywhere else.
 */
@Composable
fun OrbitSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val hovered by interaction.hoverSource.collectIsHoveredAsState()
    val track by animateColorAsState(
        targetValue = when {
            !enabled -> t.Sunken
            checked -> t.Accent
            hovered -> t.CardBgHover
            else -> t.Sunken
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "switchTrack"
    )
    val knob by animateColorAsState(
        targetValue = if (checked) Color.White else t.Text_Muted,
        animationSpec = OrbitMotion.SnappyColor,
        label = "switchKnob"
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = OrbitMotion.SnappyDp,
        label = "switchOffset"
    )
    val trackScale by animateFloatAsState(
        targetValue = if (hovered && enabled) 1.04f else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "switchScale"
    )

    Box(
        modifier = modifier
            .width(40.dp)
            .height(22.dp)
            .scale(trackScale)
            .clip(CircleShape)
            .background(track)
            .border(
                if (checked) 0.dp else 1.dp,
                if (checked) Color.Transparent else t.Separator,
                CircleShape
            )
            .hoverable(interaction.hoverSource, enabled)
            // toggleableState/stateDescription: without them a screen reader
            // announces a switch that never says whether it is on.
            .semantics {
                stateDescription = if (checked) "On" else "Off"
                toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            }
            .orbitClickable(interaction, enabled, Role.Switch, onClick = { onCheckedChange(!checked) })
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = knobOffset)
                .size(18.dp)
                .clip(CircleShape)
                .background(knob)
        )
    }
}
