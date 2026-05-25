package ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.icons.OrbitIcons
import ui.model.OrbitUser


import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.rememberCosmicTokens

// ─── Cosmic Design Tokens ───────────────────────────────────────
private val Bg: Color @Composable get() = rememberCosmicTokens().Bg
private val CardBg: Color @Composable get() = rememberCosmicTokens().CardBg
private val CardBgHover: Color @Composable get() = rememberCosmicTokens().CardBgHover
private val Accent: Color @Composable get() = rememberCosmicTokens().Accent
private val AccentLight: Color @Composable get() = rememberCosmicTokens().AccentLight
private val AccentGhost: Color @Composable get() = rememberCosmicTokens().AccentGhost
private val Text_White: Color @Composable get() = rememberCosmicTokens().Text_White
private val Text_Secondary: Color @Composable get() = rememberCosmicTokens().Text_Secondary
private val Text_Muted: Color @Composable get() = rememberCosmicTokens().Text_Muted

// ─── SettingsScreen ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: repository.OrbitRepository,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        // ── Header ───────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth()) {
            // Background gradient stripe
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(
                        Brush.verticalGradient(listOf(AccentGhost, Color.Transparent))
                    )
            )
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Settings",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    brush = Brush.horizontalGradient(listOf(Text_White, AccentLight))
                                )
                            )
                            Text(
                                text = "Customize your Onichat experience",
                                style = MaterialTheme.typography.bodySmall.copy(color = Text_Muted)
                            )
                        }
                    },
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CardBg)
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                 imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Text_Muted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    modifier = Modifier.height(80.dp)
                )
            }
        }

        // ── Scrollable Content ───────────────────────────────────
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Section: Account ────────────────────────────────
            item { SectionHeader(icon = Icons.Default.AccountCircle, title = "Account") }
            item { LogoutCard(onLogout) }

            item { Spacer(Modifier.height(4.dp)) }

            // ── Section: Streaming ──────────────────────────────
            item { SectionHeader(icon = OrbitIcons.Video, title = "Streaming & Screen Share") }
            item { VideoQualityCard() }
            item { StreamCodecCard() }
            item { StreamLatencyCard() }

            item { Spacer(Modifier.height(4.dp)) }

            // ── Section: Server ─────────────────────────────────
            item { SectionHeader(icon = Icons.Default.Dns, title = "Server Customization") }
            item { BannerCard() }
            item { AccentColorCard() }
            item { RolesCard() }
            item { EmojiCard() }

            item { Spacer(Modifier.height(4.dp)) }

            // ── Section: Display ─────────────────────────────────
            item { SectionHeader(icon = Icons.Default.DisplaySettings, title = "Display & Accessibility") }
            item { MessageDensityCard() }
            item { FontSizeCard() }
            item { ReduceMotionCard() }

            item { Spacer(Modifier.height(4.dp)) }

            // ── Section: Notifications ───────────────────────────
            item { SectionHeader(icon = OrbitIcons.Notifications, title = "Notifications") }
            item { MentionsOnlyCard() }
            item { SoundPickerCard() }

            item { Spacer(Modifier.height(4.dp)) }

            // ── Section: Appearance ──────────────────────────────
            item { SectionHeader(icon = Icons.Default.ColorLens, title = "Appearance") }
            item { ThemeSelectorCard() }
            item { LiteModeCard() }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ─── Section Header ──────────────────────────────────────────────

@Composable
private fun SectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(AccentGhost),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                color = Accent,
                letterSpacing = 0.8.sp,
                fontSize = 10.sp
            )
        )
        // Gradient line fill
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Accent.copy(alpha = 0.3f), Color.Transparent)))
        )
    }
}

// ─── Preference Card base ────────────────────────────────────────

@Composable
private fun PrefCard(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) CardBgHover else CardBg,
        animationSpec = tween(150),
        label = "cardBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(
                1.dp,
                if (isHovered) Accent.copy(alpha = 0.25f) else Color(0xFF252838),
                RoundedCornerShape(12.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = {})
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = Text_White
                )
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Text_Muted,
                        fontSize = 12.sp
                    )
                )
            }
        }
        content()
    }
}

// ─── Individual Settings Cards ───────────────────────────────────

@Composable private fun VideoQualityCard() {
    var selected by remember { mutableStateOf("1080p (60fps)") }
    PrefCard("Video Quality", "Higher quality uses more bandwidth") {
        ChipGroup(
            options = listOf("720p", "1080p (60fps)", "1440p"),
            selected = selected,
            onSelect = { selected = it }
        )
    }
}

@Composable private fun StreamCodecCard() {
    PrefCard("Stream Codec", "Encoding format for screen share") {
        Text(
            text = "H.264",
            style = MaterialTheme.typography.labelMedium.copy(
                color = Accent,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable private fun StreamLatencyCard() {
    PrefCard("Network Latency", "Real-time connection quality") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34D399))
            )
            Text(
                text = "~120ms",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color(0xFF34D399),
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable private fun BannerCard() {
    PrefCard("Server Banner", "Upload a custom banner image for your server") {
        GradientBtn("Upload") {}
    }
}

@Composable private fun AccentColorCard() {
    val colors = listOf(
        Color(0xFF7C6AF0), Color(0xFF10B981), Color(0xFFF59E0B),
        Color(0xFFEF4444), Color(0xFFEC4899), Color(0xFF3B82F6)
    )
    var selectedIdx by remember { mutableStateOf(0) }
    PrefCard("Server Accent Color", "Pick a color for your server's identity") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            colors.forEachIndexed { idx, color ->
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (idx == selectedIdx)
                                Modifier.border(2.dp, Color.White, CircleShape)
                            else Modifier
                        )
                        .clickable { selectedIdx = idx },
                    contentAlignment = Alignment.Center
                ) {
                    if (idx == selectedIdx) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun RolesCard() {
    PrefCard("Roles & Permissions", "Manage server roles and member access") {
        Icon(Icons.Default.ChevronRight, null, tint = Text_Muted, modifier = Modifier.size(14.dp))
    }
}

@Composable private fun EmojiCard() {
    PrefCard("Custom Emojis", "Upload and manage custom emojis for this server") {
        Text(
            text = "14 emojis",
            style = MaterialTheme.typography.labelMedium.copy(
                color = AccentLight,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

@Composable private fun MessageDensityCard() {
    var selected by remember { mutableStateOf("Comfortable") }
    PrefCard("Message Density", "How messages are visually spaced") {
        ChipGroup(
            options = listOf("Compact", "Comfortable", "Cozy"),
            selected = selected,
            onSelect = { selected = it }
        )
    }
}

@Composable private fun FontSizeCard() {
    var fontSize by remember { mutableStateOf(50f) }
    PrefCard("Font Size", "Adjust the size of message text") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${(8 + fontSize * 0.08).toInt()}sp",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Accent,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(Modifier.width(8.dp))
            Slider(
                value = fontSize,
                onValueChange = { fontSize = it },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor        = Accent,
                    activeTrackColor  = Accent,
                    inactiveTrackColor = Text_Muted.copy(alpha = 0.3f)
                ),
                modifier = Modifier.width(110.dp)
            )
        }
    }
}

@Composable private fun ReduceMotionCard() {
    var on by remember { mutableStateOf(false) }
    PrefCard("Reduce Motion", "Minimize animations for accessibility") {
        CosmicSwitch(checked = on, onCheckedChange = { on = it })
    }
}

@Composable private fun MentionsOnlyCard() {
    var on by remember { mutableStateOf(true) }
    PrefCard("Mentions Only", "Only get notified when you're @mentioned") {
        CosmicSwitch(checked = on, onCheckedChange = { on = it })
    }
}

@Composable private fun SoundPickerCard() {
    var selected by remember { mutableStateOf("Chime") }
    PrefCard("Notification Sound", "Sound played when you receive a notification") {
        ChipGroup(
            options = listOf("None", "Default", "Chime", "Bell"),
            selected = selected,
            onSelect = { selected = it }
        )
    }
}

@Composable private fun ThemeSelectorCard() {
    val activeTheme = ui.theme.LocalActiveTheme.current
    val onThemeChange = ui.theme.LocalOnThemeChange.current
    
    val themeMap = mapOf(
        "Default" to ui.theme.Theme.Default,
        "Light" to ui.theme.Theme.Light,
        "Amoled" to ui.theme.Theme.Amoled,
        "Ocean" to ui.theme.Theme.Ocean,
        "Forest" to ui.theme.Theme.Forest,
        "Sunset" to ui.theme.Theme.Sunset,
        "Cyberpunk" to ui.theme.Theme.Cyberpunk
    )
    
    val selectedLabel = themeMap.entries.find { it.value == activeTheme }?.key ?: "Default"

    PrefCard("App Theme", "Choose your visual theme") {
        ChipGroup(
            options = themeMap.keys.toList(),
            selected = selectedLabel,
            onSelect = { label ->
                themeMap[label]?.let { onThemeChange(it) }
            }
        )
    }
}

@Composable private fun LiteModeCard() {
    val isLite = LocalIsLiteMode.current
    val onLiteChange = LocalOnLiteModeChange.current
    PrefCard("Lite Mode", "Switch to a clean messenger-style interface") {
        CosmicSwitch(
            checked = isLite,
            onCheckedChange = onLiteChange
        )
    }
}

@Composable private fun LogoutCard(onLogout: () -> Unit) {
    PrefCard("Logout", "Sign out of your account") {
        Button(
            onClick = onLogout,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
            modifier = Modifier.height(34.dp)
        ) {
            Text(
                text = "Logout",
                style = MaterialTheme.typography.labelMedium.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

// ─── Reusable micro-components ───────────────────────────────────

@Composable
private fun ChipGroup(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.widthIn(max = 250.dp), // Prevent infinite width crash in row
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        items(options) { opt ->
            val isSelected = selected == opt
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) Accent else CardBg,
                label = "chipBg"
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor)
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = opt,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isSelected) Color.White else Text_Muted,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun CosmicSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color = Accent
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor   = Color.White,
            checkedTrackColor   = activeColor,
            uncheckedThumbColor = Text_Muted,
            uncheckedTrackColor = CardBg
        )
    )
}

@Composable
private fun GradientBtn(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(listOf(Accent, AccentLight)))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
