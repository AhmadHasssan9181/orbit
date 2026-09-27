package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
    textStyle: TextStyle? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    val t = rememberCosmicTokens()
    val interaction = rememberOrbitInteraction()
    val focused by interaction.focusSource.collectIsFocusedAsState()
    val cursorColor = remember { SolidColor(t.Accent) }

    val frame by animateColorAsState(
        targetValue = when {
            !enabled -> t.Separator
            focused -> t.Accent
            else -> t.Separator
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "fieldFrame"
    )
    val frameWidth by animateFloatAsState(
        targetValue = if (focused) 1.5f else 1f,
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
        leadingIcon = Icons.Default.Search,
        imeAction = ImeAction.Search,
        onImeAction = onSubmit,
        trailing = {
            if (value.isNotEmpty()) {
                OrbitIconButton(
                    icon = Icons.Default.Close,
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
 */
@Composable
fun <T> OrbitSegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String,
    height: Dp = 32.dp
) {
    val t = rememberCosmicTokens()
    Box(
        modifier = modifier
            .clip(OrbitRadius.smShape)
            .background(t.Sunken)
            .border(1.dp, t.Separator, OrbitRadius.smShape)
            .padding(2.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
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
                        .weight(1f)
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Switch, sized and coloured to match the rest of the app. */
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
