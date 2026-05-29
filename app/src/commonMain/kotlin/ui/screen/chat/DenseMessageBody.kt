package ui.screen.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.model.OrbitMessage

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun DenseMessageBody(message: OrbitMessage) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val rowBg by animateColorAsState(
        targetValue = if (isHovered) MessageHover_Bg else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "denseMessageBg"
    )
    val rowOffset by animateDpAsState(
        targetValue = if (isHovered) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "denseMessageOffset"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = rowOffset)
                .hoverable(interactionSource)
                .background(rowBg)
                .padding(start = 72.dp, end = 16.dp, top = 1.dp, bottom = 1.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Show timestamp on hover, like Discord
            if (isHovered && ui.theme.LocalShowTimestamps.current) {
                Text(
                    text = formatTimestamp(message.timestamp).replace("Today at ", ""),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = Text_Muted,
                        fontSize = 9.sp
                    ),
                    modifier = Modifier
                        .width(48.dp)
                        .offset(x = (-56).dp)
                )
            }
            MessageContentView(
                content = message.content,
                isCompact = true
            )
        }

        // Floating action toolbar on hover
        AnimatedVisibility(
            visible = isHovered,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = (-4).dp, x = (-16).dp),
            enter = fadeIn() + scaleIn(initialScale = 0.94f),
            exit = fadeOut() + scaleOut(targetScale = 0.94f)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        1.dp,
                        Separator.copy(alpha = 0.3f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                MessageActionBtn(OrbitIcons.Emoji, "React")
                MessageActionBtn(OrbitIcons.Reply, "Reply")
                MessageActionBtn(Icons.Default.MoreHoriz, "More")
            }
        }
    }
}
