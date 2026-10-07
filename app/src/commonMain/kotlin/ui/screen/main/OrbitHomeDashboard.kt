package ui.screen.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.model.OrbitChannel
import ui.model.OrbitServer
import ui.theme.OrbitMotion
import ui.theme.rememberCosmicTokens

@Composable
fun OrbitHomeDashboard(
    server: OrbitServer?,
    onCreateServer: () -> Unit,
    onDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
    onChannelClick: (String) -> Unit,
    channels: List<OrbitChannel> = emptyMap<String, String>().let { emptyList() },
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // Breadcrumb header matching Stoat: < Home
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = null,
                tint = t.Text_Muted,
                modifier = Modifier.size(18.dp)
            )
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = null,
                tint = t.Text_White,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Home",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    fontSize = 14.sp
                )
            )
        }

        // Center content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                // Centered Orbit logo and title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(t.Accent.copy(alpha = 0.18f))
                            .border(1.5.dp, t.Accent.copy(alpha = 0.40f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Orbit",
                            tint = t.Accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Orbit",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = t.Text_White,
                            fontSize = 32.sp
                        )
                    )
                }

                // 2x3 Grid of Action Cards
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.widthIn(max = 660.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActionCard(
                            icon = Icons.Default.Add,
                            title = "Create a group or server",
                            description = "Invite all of your friends, some cool bots, and throw a big party.",
                            onClick = onCreateServer,
                            modifier = Modifier.weight(1f)
                        )
                        ActionCard(
                            icon = Icons.Default.Explore,
                            title = "Discover Orbit",
                            description = "Find a community based on your hobbies or interests.",
                            onClick = onDiscover,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val loungeChannel = channels.firstOrNull {
                            it.name.contains("lounge", ignoreCase = true) ||
                            it.name.contains("general", ignoreCase = true)
                        }
                        ActionCard(
                            icon = Icons.Default.Groups,
                            title = "Join the Orbit Lounge",
                            description = "You can report issues and discuss improvements with us directly here.",
                            onClick = {
                                if (loungeChannel != null) {
                                    onChannelClick(loungeChannel.id)
                                } else {
                                    onDiscover()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ActionCard(
                            icon = Icons.AutoMirrored.Filled.Chat,
                            title = "Give feedback on Orbit",
                            description = "Let us know how we can improve our app by giving us feedback.",
                            onClick = {
                                runCatching {
                                    uriHandler.openUri("https://github.com/AhmadHasssan9181/orbit/issues")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActionCard(
                            icon = Icons.Default.VolunteerActivism,
                            title = "Donate to Orbit",
                            description = "Support the project by donating - thank you!",
                            isSpecial = true,
                            onClick = {
                                runCatching {
                                    uriHandler.openUri("https://github.com/sponsors/AhmadHasssan9181")
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        ActionCard(
                            icon = Icons.Default.Settings,
                            title = "Open settings",
                            description = "You can also click the gear icon in the bottom left.",
                            onClick = onOpenSettings,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    isSpecial: Boolean = false,
    modifier: Modifier = Modifier
) {
    val t = rememberCosmicTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val cardBg by animateColorAsState(
        targetValue = when {
            isSpecial && isHovered -> t.Accent.copy(alpha = 0.22f)
            isSpecial -> t.Accent.copy(alpha = 0.14f)
            isHovered -> t.CardBgHover
            else -> t.CardBg
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "actionCardBg"
    )

    val borderStroke by animateColorAsState(
        targetValue = when {
            isSpecial -> t.Accent.copy(alpha = 0.35f)
            isHovered -> t.SeparatorStrong
            else -> t.Separator.copy(alpha = 0.20f)
        },
        animationSpec = OrbitMotion.SnappyColor,
        label = "actionCardBorder"
    )

    val cardShape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .height(86.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(1.dp, borderStroke, cardShape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isSpecial) t.Accent.copy(alpha = 0.25f) else t.Hover),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSpecial) t.Accent else t.Text_White,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = t.Text_White,
                    fontSize = 13.5.sp
                ),
                maxLines = 1
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = t.Text_Muted,
                    fontSize = 11.5.sp,
                    lineHeight = 14.sp
                ),
                maxLines = 2
            )
        }
    }
}
