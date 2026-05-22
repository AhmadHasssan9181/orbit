package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.model.OrbitServer

import ui.theme.LocalIsLiteMode

// ─── Cosmic Design Tokens ──────────────────────────────────────
private val Rail_Bg: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFFEAE6DF) else Color(0xFF080A0F)
private val Rail_Active: Color @Composable get() = if (LocalIsLiteMode.current) Color(0x2A075E54) else Color(0x2A7C6AF0)
private val Accent: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF075E54) else Color(0xFF7C6AF0)
private val AccentLight: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF128C7E) else Color(0xFFA78BFA)
private val Pill_Color: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF111B21) else Color(0xFFEAECF5)
private val Text_White: Color @Composable get() = if (LocalIsLiteMode.current) Color(0xFF111B21) else Color(0xFFEAECF5)

private const val RAIL_WIDTH     = 72f
private const val ICON_SIZE      = 46f
private const val PILL_W         = 4f
private const val CORNER_SEL     = 18f
private const val CORNER_UNSEL   = 14f

@Composable
fun ServerRail(
    servers: List<OrbitServer>,
    selectedServerId: String?,
    onServerClick: (String) -> Unit,
    onAddServerClick: () -> Unit = {},
    selectedWhatsAppTab: String = "Chats",
    onWhatsAppTabClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isLiteMode = ui.theme.LocalIsLiteMode.current
    if (isLiteMode) {
        WhatsAppRail(selectedWhatsAppTab, onWhatsAppTabClick, modifier)
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH.dp)
            .background(Rail_Bg),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top)
    ) {
        // DM / Home button
        item {
            ServerIconSlot(isSelected = false, onClick = onAddServerClick) {
                Box(
                    modifier = Modifier
                        .size(ICON_SIZE.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Accent, AccentLight))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Text_White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                }
            }
        }

        // Fancy divider
        item {
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0xFF252838), Color.Transparent)
                        )
                    )
            )
        }

        items(servers, key = { it.id }) { server ->
            val isSelected = server.id == selectedServerId
            ServerIconSlot(
                isSelected = isSelected,
                onClick = { onServerClick(server.id) }
            ) {
                ServerIcon(
                    colorStart  = Color(server.iconColor),
                    colorEnd    = Color(server.iconColor).copy(alpha = 0.7f),
                    initials    = getInitials(server.name),
                    size        = ICON_SIZE.dp,
                    isSelected  = isSelected
                )
            }
        }
    }
}

// ─── ServerIconSlot — pill + animated bg ─────────────────────

@Composable
private fun ServerIconSlot(
    isSelected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) CORNER_SEL.dp else CORNER_UNSEL.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "cornerAnim"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Rail_Active else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bgAnim"
    )
    val pillH by animateFloatAsState(
        targetValue = if (isSelected) 36f else 10f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pillH"
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Pill indicator on left edge
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(PILL_W.dp)
                .height(pillH.dp)
                .background(
                    Pill_Color,
                    RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp)
                )
        )
        content()
    }
}

// ─── ServerIcon — squircle gradient + KMP-safe radial glow ──

@Composable
private fun ServerIcon(
    colorStart: Color,
    colorEnd: Color,
    initials: String,
    size: Dp,
    isSelected: Boolean
) {
    val glowAlpha by animateFloatAsState(
        targetValue    = if (isSelected) 0.45f else 0f,
        animationSpec  = tween(300),
        label          = "glowAlpha"
    )
    val cornerRadius by animateDpAsState(
        targetValue   = if (isSelected) 18.dp else 14.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label         = "iconCorner"
    )

    Box(contentAlignment = Alignment.Center) {
        // KMP-safe glow: radial gradient halo behind icon
        if (glowAlpha > 0.01f) {
            Box(
                modifier = Modifier
                    .size(size + 20.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colorStart.copy(alpha = glowAlpha),
                                Color.Transparent
                            )
                        ),
                        shape = CircleShape
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Brush.linearGradient(listOf(colorStart, colorEnd))),
            contentAlignment = Alignment.Center
        ) {
            val fontSize: TextUnit = if (initials.length > 2) 12.sp else 16.sp
            Text(
                text       = initials,
                color      = Text_White,
                fontWeight = FontWeight.ExtraBold,
                fontSize   = fontSize,
                maxLines   = 1
            )
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────

private fun getInitials(name: String): String = when {
    name.isBlank() -> "?"
    name.length <= 2 -> name.uppercase()
    else -> name.split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
}

// ─── WhatsApp Style Rail ─────────────────────────────────────────

@Composable
private fun WhatsAppRail(selectedTab: String, onTabClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(60.dp)
            .background(Color(0xFFF0F2F5)) // light gray background for whatsapp rail
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WhatsAppRailIcon(Icons.Default.Chat, isSelected = selectedTab == "Chats", onClick = { onTabClick("Chats") })
            WhatsAppRailIcon(Icons.Default.Call, isSelected = selectedTab == "Calls", onClick = { onTabClick("Calls") })
            WhatsAppRailIcon(Icons.Default.DonutLarge, isSelected = selectedTab == "Status", onClick = { onTabClick("Status") }) // Status
            WhatsAppRailIcon(Icons.Default.Groups, isSelected = selectedTab == "Communities", onClick = { onTabClick("Communities") })
        }

        // Bottom section
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WhatsAppRailIcon(Icons.Default.Archive, isSelected = false, onClick = {})
            WhatsAppRailIcon(Icons.Default.Settings, isSelected = false, onClick = {})
            // Profile mock
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF7C6AF0)),
                contentAlignment = Alignment.Center
            ) {
                Text("YO", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun WhatsAppRailIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    val color = if (isSelected) Color(0xFF111B21) else Color(0xFF54656F)
    val bgColor = if (isSelected) Color(0xFFD1D7DB) else Color.Transparent
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}