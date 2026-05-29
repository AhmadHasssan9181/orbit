package ui.screen.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.layout.LocalWindowSizeClass
import ui.model.ChannelType
import ui.model.OrbitChannel
import ui.model.OrbitUser

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun ChatHeader(
    channel: OrbitChannel,
    directProfile: OrbitUser? = null,
    onBack: (() -> Unit)? = null,
    onVideoCall: (() -> Unit)? = null,
    onVoiceCall: (() -> Unit)? = null
) {
    val windowSize = LocalWindowSizeClass.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Header_Bg)
            .height(if (windowSize.isCompact) 56.dp else 48.dp)
            .padding(horizontal = if (windowSize.isCompact) 4.dp else 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Back button for compact (phone) and medium (tablet) layouts
            if (onBack != null && !windowSize.isExpanded) {
                IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Text_White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            val isLiteMode = ui.theme.LocalIsLiteMode.current
            if (directProfile != null) {
                SmallUserAvatar(directProfile, size = 28.dp)
            } else if (isLiteMode) {
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(AccentGhost),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.name.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                    )
                }
            } else {
                // Channel icon — Discord uses grey hash, Revolt uses coloured
                when (channel.type) {
                    ChannelType.TEXT -> Icon(
                        imageVector = OrbitIcons.TextChannel,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                    ChannelType.VOICE -> Icon(
                        imageVector = OrbitIcons.VoiceChannel,
                        contentDescription = null,
                        tint = Text_Muted,
                        modifier = Modifier.size(20.dp).alpha(0.7f)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Text(
                text = directProfile?.displayName ?: channel.name,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = Text_White
                ),
                maxLines = 1
            )

            if (channel.topic.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .height(16.dp)
                        .background(Text_Muted.copy(alpha = 0.3f))
                )
                Text(
                    text = channel.topic,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Text_Muted,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (directProfile != null) {
                HeaderIconBtn(OrbitIcons.Call, "Voice Call", onClick = { onVoiceCall?.invoke() })
                HeaderIconBtn(OrbitIcons.Video, "Video Call", onClick = { onVideoCall?.invoke() })
            }
            HeaderIconBtn(Icons.Default.Search, "Search")
            HeaderIconBtn(OrbitIcons.Notifications, "Notifications")
            HeaderIconBtn(OrbitIcons.Pin, "Pins")
            HeaderIconBtn(OrbitIcons.Members, "Members")
        }
    }
}

@Composable
internal fun HeaderIconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val tint by animateColorAsState(
        targetValue = if (isHovered) Text_White else Text_Muted,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headerIconTint"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) AccentGhost.copy(alpha = 0.40f) else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headerIconBg"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (isHovered) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "headerIconScale"
    )
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor),
        interactionSource = interactionSource
    ) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp).scale(iconScale))
    }
}
