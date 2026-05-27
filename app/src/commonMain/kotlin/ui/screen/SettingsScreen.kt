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
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import repository.SettingsManager
import repository.OrbitRepository
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
private val Separator: Color @Composable get() = rememberCosmicTokens().Separator

enum class SettingsCategory(val title: String, val group: String) {
    // User Settings
    MyAccount("My Account", "USER SETTINGS"),
    Profiles("Profiles", "USER SETTINGS"),
    Privacy("Privacy & Safety", "USER SETTINGS"),
    FamilyCenter("Family Centre", "USER SETTINGS"),
    AuthorizedApps("Authorised Apps", "USER SETTINGS"),
    Connections("Connections", "USER SETTINGS"),
    
    // Perks (Replacing Billing)
    OrbitPlus("Orbit Plus", "COSMIC PERKS"),
    ServerOverdrive("Server Overdrive", "COSMIC PERKS"),
    ActivePerks("Active Perks", "COSMIC PERKS"),
    CosmicGifts("Cosmic Gifts", "COSMIC PERKS"),
    PerkStatus("Perk Status", "COSMIC PERKS"),

    // App Settings
    Appearance("Appearance", "APP SETTINGS"),
    Accessibility("Accessibility", "APP SETTINGS"),
    VoiceVideo("Voice & Video", "APP SETTINGS"),
    Chat("Chat", "APP SETTINGS"),
    Notifications("Notifications", "APP SETTINGS"),
    Keybinds("Keybinds", "APP SETTINGS"),
    Language("Language & Time", "APP SETTINGS"),
    Windows("Windows Settings", "APP SETTINGS"),
    Advanced("Advanced", "APP SETTINGS"),
    
    // Activity
    ActivityPrivacy("Activity Privacy", "ACTIVITY"),
    RegisteredGames("Registered Games", "ACTIVITY"),
    GameOverlay("Game Overlay", "ACTIVITY"),
    
    // Auth
    Logout("Log Out", "")
}

@Composable
fun SettingsScreen(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.MyAccount) }
    
    Row(modifier = modifier.fillMaxSize().background(Bg)) {
        // --- Left Sidebar (Categories) ---
        LazyColumn(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(CardBg)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 40.dp)
        ) {
            val categoriesByGroup = SettingsCategory.entries.groupBy { it.group }
            
            categoriesByGroup.forEach { (group, cats) ->
                if (group.isNotEmpty()) {
                    item {
                        Text(
                            text = group, 
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.ExtraBold, 
                                color = Text_Muted, 
                                letterSpacing = 0.8.sp,
                                fontSize = 10.sp
                            ), 
                            modifier = Modifier.padding(start = 12.dp, top = if (group == "USER SETTINGS") 0.dp else 16.dp, bottom = 8.dp)
                        )
                    }
                }
                
                items(cats) { cat ->
                    if (cat == SettingsCategory.Logout) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 12.dp))
                        Spacer(Modifier.height(16.dp))
                    }
                    
                    CategoryItem(cat, selectedCategory) { 
                        if (it == SettingsCategory.Logout) {
                            onLogout()
                        } else {
                            selectedCategory = it 
                        }
                    }
                }
                
                if (group.isNotEmpty() && group != "ACTIVITY") {
                    item {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = Separator, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        }
        
        // --- Right Content Pane ---
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(Bg)) {
            // A close button in the top right corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(40.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(2.dp, Text_Muted.copy(alpha = 0.5f), CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Text_Muted, modifier = Modifier.size(20.dp))
            }
            
            // Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 40.dp, top = 60.dp, end = 100.dp, bottom = 40.dp)
            ) {
                Text(
                    text = selectedCategory.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, color = Text_White),
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    when (selectedCategory) {
                        SettingsCategory.MyAccount -> item { MyAccountPane(repository) }
                        SettingsCategory.Profiles -> item { ProfilesPane(repository) }
                        SettingsCategory.Privacy -> item { PrivacyPane(repository) }
                        SettingsCategory.FamilyCenter -> item { FamilyCenterPane(repository) }
                        SettingsCategory.AuthorizedApps -> item { AuthorizedAppsPane() }
                        SettingsCategory.Connections -> item { ConnectionsPane(repository) }
                        
                        SettingsCategory.OrbitPlus -> item { OrbitPlusPane() }
                        SettingsCategory.ServerOverdrive -> item { ServerOverdrivePane() }
                        SettingsCategory.ActivePerks -> item { ActivePerksPane() }
                        SettingsCategory.CosmicGifts -> item { CosmicGiftsPane() }
                        SettingsCategory.PerkStatus -> item { PerkStatusPane() }
                        
                        SettingsCategory.Appearance -> {
                            item { ThemeSelectorCard() }
                            item { LiteModeCard() }
                            item { FontSizeCard(settingsManager) }
                            item { AppearanceExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Accessibility -> {
                            item { FontSizeCard(settingsManager) }
                            item { ReduceMotionCard(settingsManager) }
                            item { AccessibilityExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Chat -> {
                            item { FontSizeCard(settingsManager) }
                            item { MessageDensityCard(settingsManager) }
                            item { ChatSettingsPane(settingsManager) }
                        }
                        SettingsCategory.VoiceVideo -> {
                            item { VoiceVideoFullPane(settingsManager) }
                        }
                        SettingsCategory.Notifications -> {
                            item { MentionsOnlyCard(settingsManager) }
                            item { SoundPickerCard(settingsManager) }
                            item { NotificationsExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Keybinds -> item { KeybindsPane() }
                        SettingsCategory.Language -> item { LanguageTimePane(settingsManager) }
                        SettingsCategory.Windows -> item { WindowsSettingsPane(settingsManager) }
                        SettingsCategory.Advanced -> item { AdvancedPane(settingsManager) }
                        
                        SettingsCategory.ActivityPrivacy -> item { ActivityPrivacyPane(settingsManager) }
                        SettingsCategory.RegisteredGames -> item { RegisteredGamesPane(settingsManager) }
                        SettingsCategory.GameOverlay -> item { GameOverlayPane(settingsManager) }
                        
                        SettingsCategory.Logout -> {}
                        else -> {
                            item {
                                PlaceholderPane(selectedCategory.title)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryItem(
    category: SettingsCategory,
    selected: SettingsCategory,
    onClick: (SettingsCategory) -> Unit
) {
    val isSelected = category == selected
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) AccentGhost else Color.Transparent,
        label = "categoryBg"
    )
    
    val baseTextColor = if (category == SettingsCategory.Logout) Color(0xFFEF4444) else Text_Secondary
    val textColor = if (isSelected) Text_White else baseTextColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable { onClick(category) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = textColor
            )
        )
    }
}

// ─── Placeholder Pane ─────────────────────────────────────────────

@Composable
private fun PlaceholderPane(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Construction,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(48.dp).padding(bottom = 16.dp)
        )
        Text(
            text = "$title coming soon",
            style = MaterialTheme.typography.titleMedium.copy(color = Text_White, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "We are working hard to bring you these detailed configuration options very soon.",
            style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ─── My Account Pane ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyAccountPane(repository: OrbitRepository) {
    val coroutineScope = rememberCoroutineScope()
    var user by remember { mutableStateOf<OrbitUser?>(null) }
    var isEditingName by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    
    LaunchedEffect(Unit) {
        user = repository.getCurrentUser()
        newName = user?.displayName ?: ""
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardBgHover)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Bg)
                    .clickable { 
                        coroutineScope.launch {
                            val bytes = util.pickImageFile()
                            if (bytes != null) {
                                repository.uploadAvatar(bytes)
                                user = repository.getCurrentUser() // refresh
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AccountCircle, null, modifier = Modifier.fillMaxSize(), tint = Accent)
            }
            
            Spacer(Modifier.width(16.dp))
            
            // Name Info
            Column(modifier = Modifier.weight(1f)) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Bg,
                            unfocusedContainerColor = Bg,
                            focusedTextColor = Text_White,
                            unfocusedTextColor = Text_White
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    Row {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    repository.updateDisplayName(newName)
                                    user = repository.getCurrentUser()
                                    isEditingName = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Text("Save")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { isEditingName = false }) {
                            Text("Cancel", color = Text_Muted)
                        }
                    }
                } else {
                    Text(
                        text = user?.displayName ?: "Unknown",
                        style = MaterialTheme.typography.titleLarge.copy(color = Text_White, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = user?.id ?: "@unknown",
                        style = MaterialTheme.typography.bodyMedium.copy(color = Text_Muted)
                    )
                }
            }
            
            if (!isEditingName) {
                Button(
                    onClick = { isEditingName = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Edit Profile")
                }
            }
        }
    }
}





@Composable private fun MessageDensityCard(settingsManager: SettingsManager) {
    val selected by settingsManager.messageDensity.collectAsState("Comfortable")
    PrefCard("Message Density", "How messages are visually spaced") {
        ChipGroup(
            options = listOf("Compact", "Comfortable", "Cozy"),
            selected = selected,
            onSelect = { settingsManager.setMessageDensity(it) }
        )
    }
}

@Composable private fun FontSizeCard(settingsManager: SettingsManager) {
    val fontSize by settingsManager.fontSize.collectAsState(50f)
    val tokens = rememberCosmicTokens()
    
    PrefCard("Font Size", "Adjust the size of message text and interface elements", vertical = true) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${(fontSize / 50f * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = tokens.Accent,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                )
                Spacer(Modifier.width(16.dp))
                Slider(
                    value = fontSize,
                    onValueChange = { settingsManager.setFontSize(it) },
                    valueRange = 30f..100f,
                    colors = SliderDefaults.colors(
                        thumbColor        = tokens.Accent,
                        activeTrackColor  = tokens.Accent,
                        inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Preview area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(tokens.Bg)
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        "Preview Text", 
                        style = MaterialTheme.typography.labelSmall.copy(color = tokens.Text_Muted, fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is how your messages will look. Drag the slider to find your comfort zone.",
                        style = MaterialTheme.typography.bodyMedium.copy(color = tokens.Text_White)
                    )
                }
            }
        }
    }
}

@Composable private fun ReduceMotionCard(settingsManager: SettingsManager) {
    val on by settingsManager.reduceMotion.collectAsState(false)
    PrefCard("Reduce Motion", "Minimize animations for accessibility") {
        CosmicSwitch(checked = on, onCheckedChange = { settingsManager.setReduceMotion(it) })
    }
}

@Composable private fun MentionsOnlyCard(settingsManager: SettingsManager) {
    val on by settingsManager.mentionsOnly.collectAsState(true)
    PrefCard("Mentions Only", "Only get notified when you're @mentioned") {
        CosmicSwitch(checked = on, onCheckedChange = { settingsManager.setMentionsOnly(it) })
    }
}

@Composable private fun SoundPickerCard(settingsManager: SettingsManager) {
    val selected by settingsManager.notificationSound.collectAsState("Chime")
    PrefCard("Notification Sound", "Sound played when you receive a notification") {
        ChipGroup(
            options = listOf("None", "Default", "Chime", "Bell"),
            selected = selected,
            onSelect = { settingsManager.setNotificationSound(it) }
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

