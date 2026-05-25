package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.theme.rememberCosmicTokens

/**
 * Bottom navigation bar for compact (phone) layouts.
 * Replaces the ServerRail on small screens.
 */
@Composable
fun BottomNavBar(
    selectedTab: BottomNavTab,
    onTabSelected: (BottomNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = rememberCosmicTokens()

    NavigationBar(
        modifier = modifier,
        containerColor = tokens.UserBar_Bg,
        tonalElevation = 0.dp
    ) {
        BottomNavTab.entries.forEach { tab ->
            val isSelected = tab == selectedTab
            val iconColor by animateColorAsState(
                targetValue = if (isSelected) tokens.Accent else tokens.Text_Muted,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "navIconColor_${tab.name}"
            )

            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        imageVector = if (isSelected) tab.selectedIcon else tab.icon,
                        contentDescription = tab.label,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = iconColor
                        )
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = tokens.Accent,
                    unselectedIconColor = tokens.Text_Muted,
                    indicatorColor = tokens.Accent.copy(alpha = 0.15f)
                )
            )
        }
    }
}

enum class BottomNavTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
) {
    DMs(
        label = "Messages",
        icon = OrbitIcons.TextChannel,
        selectedIcon = OrbitIcons.TextChannel
    ),
    Servers(
        label = "Servers",
        icon = Icons.Default.GridView,
        selectedIcon = Icons.Default.GridView
    ),
    Search(
        label = "Search",
        icon = Icons.Default.Search,
        selectedIcon = Icons.Default.Search
    ),
    Profile(
        label = "Profile",
        icon = OrbitIcons.Members,
        selectedIcon = OrbitIcons.Members
    );

    companion object {
        val entries = listOf(DMs, Servers, Search, Profile)
    }
}
