package ui.screen.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ui.theme.LocalReduceMotion
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens

/**
 * The two circles that sit at the bottom-right of a stage: pop the stream out,
 * and maximise it.
 *
 * These are `.stream-action-circle-btn` and `.maximize-btn-highlight` from the
 * concept, which is the only place in the whole stylesheet where a control
 * hovers to the brand colour. That is not decoration: the maximise button is the
 * one control on the stage whose absence nobody would notice until they wanted
 * it, so it is the one that has to announce itself.
 */
@Composable
fun CallStreamActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    val t = rememberCosmicTokens()
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        targetValue = when {
            highlighted && hovered -> t.Accent
            highlighted -> CallMaximizePlate
            hovered -> CallActionScrimHover
            else -> CallActionScrim
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "streamActionBg"
    )
    val ring by animateColorAsState(
        targetValue = when {
            highlighted && hovered -> t.Accent
            else -> CallActionRing
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "streamActionRing"
    )
    // `.stream-action-circle-btn:hover { transform: scale(1.08) }`. The plain
    // circle does not scale, the maximise plate does; the concept states it
    // once on the shared rule so both inherit it.
    val scale by animateFloatAsState(
        targetValue = if (hovered && !reduce) CALL_ACTION_HOVER_SCALE else 1f,
        animationSpec = OrbitMotion.Snappy,
        label = "streamActionScale"
    )
    // `.stream-action-circle-btn { color: #dbdee1 }` -> `--text-normal`;
    // `:hover { color: #ffffff }`; `.maximize-btn-highlight { color: #ffffff }`.
    val tint = when {
        highlighted -> Color.White
        hovered -> t.Text_White
        else -> t.Text_Secondary
    }

    Box(
        modifier = modifier
            .size(CallActionCircleSize)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .border(1.dp, ring, CircleShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(if (highlighted) 18.dp else 16.dp)
        )
    }
}

/**
 * `.header-action-btn` — transparent, no border, 4px padding, 4px radius,
 * `--interactive-normal`; hover `--interactive-hover`; `.active`
 * `--interactive-active`.
 *
 * The only hover rule the concept gives a header button is a *colour* change, with
 * no fill, so that is what this does: a ghost icon that brightens, the way every
 * other header control in the app behaves.
 */
@Composable
fun CallHeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    val t = rememberCosmicTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val tint = when {
        active -> callInteractiveActive(t)         // --interactive-active
        hovered -> callInteractiveHover(t)         // --interactive-hover (#dbdee1)
        else -> callInteractiveNormal(t)           // --interactive-normal (#b5bac1)
    }

    Box(
        modifier = modifier
            .size(28.dp)
            .clip(CallIconPillShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

/**
 * `.stream-bottom-right-actions`: `bottom: 24px; right: 28px; gap: 10px`.
 *
 * Fills whatever it is given and pins itself to that box's bottom-right. In the
 * concept its parent is the stream canvas, which is the whole stage; here the
 * caller hands it the presentation area — the space above the participant strip,
 * or the whole stage once the call is maximised — so the two circles never land
 * on top of somebody's face.
 */
@Composable
fun CallStreamBottomActions(
    isMaximized: Boolean,
    onToggleMaximized: () -> Unit,
    modifier: Modifier = Modifier,
    onPopout: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = CallStreamActionsBottom, end = CallStreamActionsEnd),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onPopout != null) {
                CallStreamActionButton(
                    icon = Icons.Filled.OpenInNew,
                    contentDescription = "Pop the stream out",
                    onClick = onPopout
                )
            }
            CallStreamActionButton(
                icon = if (isMaximized) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = if (isMaximized) {
                    "Exit full screen (Escape)"
                } else {
                    "Maximize the stream"
                },
                onClick = onToggleMaximized,
                highlighted = true
            )
        }
    }
}
