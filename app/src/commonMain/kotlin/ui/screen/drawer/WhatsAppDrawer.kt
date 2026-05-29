package ui.screen.drawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun WhatsAppDrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(rememberCosmicTokens().Header_Bg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Orbit",
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White,
                fontSize = 20.sp
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "New Chat", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun WhatsAppSearchBar() {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(rememberCosmicTokens().Input_Bg)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = Text_Muted, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Search conversations...",
                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 13.sp)
            )
        }
    }
}

@Composable
internal fun WhatsAppFilterChips() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LiteChip("All", isSelected = true)
        LiteChip("Unread", isSelected = false)
        LiteChip("Favourites", isSelected = false)
        LiteChip("Groups", isSelected = false)
    }
}

@Composable
internal fun LiteChip(text: String, isSelected: Boolean) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) Accent.copy(alpha = 0.15f)
        else rememberCosmicTokens().CardBg,
        label = "chipBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected) Accent else Text_Muted,
        label = "chipText"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
internal fun WhatsAppCallsDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Calls") {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(OrbitIcons.Add, contentDescription = "New Call", tint = Accent, modifier = Modifier.size(20.dp))
            }
        }
        WhatsAppSearchBar()

        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            val names = listOf("Nova", "Phoenix", "Stella", "Raven", "Orbit Bot", "Alice", "Bob", "Charlie", "Diana", "Eve")
            items(names.size) { i ->
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .hoverable(interactionSource)
                        .background(if (isHovered) rememberCosmicTokens().ChannelHover_Bg else Color.Transparent)
                        .clickable {}
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hue = (i * 47) % 360
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(
                                hslToColorLocal(hue.toFloat()),
                                hslToColorLocal((hue + 30f) % 360)
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(names[i].take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(names[i], style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (i % 2 == 0) Icons.Default.CallMade else Icons.Default.CallReceived,
                                contentDescription = null,
                                tint = if (i % 2 == 0) Accent else Color(0xFFF87171),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Today, ${10 + i}:${10 + i * 3} AM", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
                        }
                    }
                    IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                        Icon(OrbitIcons.Call, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun WhatsAppStatusDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Status") {
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(OrbitIcons.Add, contentDescription = "New Status", tint = Accent, modifier = Modifier.size(20.dp))
            }
        }

        // My status
        Row(
            modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(48.dp)) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Accent, AccentLight))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
                Box(
                    modifier = Modifier.align(Alignment.BottomEnd).size(16.dp).clip(CircleShape)
                        .background(Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(OrbitIcons.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("My status", style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                Text("Tap to add status update", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
            }
        }

        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 14.dp))

        Text(
            text = "RECENT",
            style = MaterialTheme.typography.labelSmall.copy(color = Accent, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.weight(1f)) {
            val names = listOf("Phoenix", "Stella", "Nova")
            items(names.size) { i ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val hue = ((i + 3) * 67) % 360
                    Box(
                        modifier = Modifier.size(48.dp)
                            .border(2.dp, Accent, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(
                                hslToColorLocal(hue.toFloat()),
                                hslToColorLocal((hue + 25f) % 360)
                            ))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(names[i].take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(names[i], style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
                        Text("Today, 9:${40 + i * 7} AM", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted, fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun WhatsAppCommunitiesDrawer() {
    Column(modifier = Modifier.fillMaxSize().background(Drawer_Bg)) {
        LiteDrawerHeader("Communities")

        // New community
        Row(
            modifier = Modifier.fillMaxWidth().clickable {}.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                    .background(Accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(OrbitIcons.Members, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("New community", style = MaterialTheme.typography.bodyMedium.copy(color = Text_White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp))
        }

        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 14.dp))

        // Empty state
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(OrbitIcons.TextChannel, contentDescription = null, tint = Text_Muted.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No communities yet", style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted))
                Text("Create or join a community to get started", style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted.copy(alpha = 0.6f), fontSize = 12.sp))
            }
        }
    }
}

@Composable
internal fun LiteDrawerHeader(title: String, actions: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(rememberCosmicTokens().Header_Bg)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = Text_White
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            actions()
            IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Text_Muted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

internal fun hslToColorLocal(hue: Float): Color {
    val s = 0.65f; val l = 0.55f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun h2r(t: Float): Float {
        var x = t; if (x < 0) x += 1f; if (x > 1) x -= 1f
        return when {
            x < 1f / 6f -> p + (q - p) * 6f * x
            x < 0.5f -> q
            x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
            else -> p
        }
    }
    return Color(
        h2r(hue / 360f + 1f / 3f).coerceIn(0f, 1f),
        h2r(hue / 360f).coerceIn(0f, 1f),
        h2r(hue / 360f - 1f / 3f).coerceIn(0f, 1f)
    )
}
