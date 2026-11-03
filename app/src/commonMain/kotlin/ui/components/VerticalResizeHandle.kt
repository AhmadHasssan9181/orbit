package ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import ui.theme.rememberCosmicTokens
import util.horizontalResizeCursor

/**
 * An interactive vertical split divider that allows horizontal resizing between panels.
 * Provides a comfortable hit target, visual feedback on hover/drag, and native `<->` resize cursor.
 */
@Composable
fun VerticalResizeHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color? = null
) {
    val tokens = rememberCosmicTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }

    val isActive = isHovered || isDragging
    val highlightColor = activeColor ?: tokens.Accent.copy(alpha = 0.85f)
    val lineColor by animateColorAsState(
        targetValue = if (isActive) highlightColor else tokens.Separator.copy(alpha = 0.2f),
        animationSpec = tween(durationMillis = 150),
        label = "resizeLineColor"
    )
    val lineWidth by animateDpAsState(
        targetValue = if (isActive) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 150),
        label = "resizeLineWidth"
    )

    Box(
        modifier = modifier
            .width(2.dp)
            .fillMaxHeight()
            .pointerHoverIcon(horizontalResizeCursor())
            .hoverable(interactionSource)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(lineWidth)
                .fillMaxHeight()
                .background(lineColor)
        )
    }
}
