package ui.screen.settings

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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import ui.components.OrbitListRow
import ui.components.OrbitRowLeading
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import repository.SettingsManager
import repository.OrbitRepository
import ui.theme.LocalIsLiteMode
import ui.theme.LocalOnLiteModeChange
import ui.theme.OrbitRadius
import ui.theme.OrbitSpace
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// - Cosmic Design Tokens

enum class SettingsCategory(val title: String, val group: String) {
    // User Settings
    // (Fabricated panes removed: perks, family centre, connections, authorised apps,
    // keybinds, language, activity/games, notifications and advanced were pure
    // mock-ups with no backing engine - hidden until they actually work.)
    MyAccount("My Account", "USER SETTINGS"),
    Profiles("Profiles", "USER SETTINGS"),

    // App Settings
    Appearance("Appearance", "APP SETTINGS"),
    Accessibility("Accessibility", "APP SETTINGS"),
    VoiceVideo("Voice & Video", "APP SETTINGS"),
    Chat("Chat", "APP SETTINGS"),
    Windows("Windows Settings", "APP SETTINGS"),

    // Auth
    Logout("Log Out", "")
}

@Composable
/**
 * Settings shell.
 *
 * The old layout was a 280dp sidebar of clickable text next to a content
 * column padded `start=40, top=60, end=100, bottom=40`, with the close button
 * floating in the top-right corner on a circle outline. On a 2560px monitor
 * that put the settings column at roughly 40% of the window with a third of it
 * empty; the content is now width-capped and centred, the sidebar is a real
 * navigation list, and closing is a labelled control in the header where you
 * expect it.
 */
fun SettingsScreen(
    repository: OrbitRepository,
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf(SettingsCategory.MyAccount) }
    val t = rememberCosmicTokens()
    val listState = rememberLazyListState()

    Row(modifier = modifier.fillMaxSize().background(t.Bg)) {
        // - Sidebar
        Column(
            modifier = Modifier
                .width(232.dp)
                .fillMaxHeight()
                .background(t.Drawer_Bg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = OrbitSpace.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = t.Text_White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.sp
                    )
                )
            }
            androidx.compose.material3.HorizontalDivider(color = t.Separator, thickness = 1.dp)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = OrbitSpace.md,
                        bottom = OrbitSpace.xxl,
                        start = OrbitSpace.sm,
                        end = OrbitSpace.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                        val categoriesByGroup = SettingsCategory.entries.groupBy { it.group }
                    categoriesByGroup.forEach { (group, cats) ->
                        if (group.isNotEmpty()) {
                            item(key = "group_$group") {
                                Text(
                                    text = group,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = t.Text_Subtle,
                                        letterSpacing = 0.8.sp,
                                        fontSize = 9.5.sp
                                    ),
                                    modifier = Modifier.padding(
                                        start = 10.dp,
                                        top = if (group == "USER SETTINGS") 0.dp else OrbitSpace.lg,
                                        bottom = OrbitSpace.xs
                                    )
                                )
                            }
                        }
                        items(cats, key = { it.name }) { cat ->
                            if (cat == SettingsCategory.Logout) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = t.Separator,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = OrbitSpace.sm)
                                )
                            }
                            OrbitListRow(
                                title = cat.title,
                                onClick = {
                                    if (cat == SettingsCategory.Logout) onLogout() else selectedCategory = cat
                                },
                                selected = cat == selectedCategory,
                                height = 32.dp,
                                showSelectionBar = false,
                                leading = OrbitRowLeading(icon = settingsIconFor(cat)),
                                titleStyle = if (cat == SettingsCategory.Logout) {
                                    MaterialTheme.typography.bodyMedium.copy(color = t.Danger, fontSize = 13.sp)
                                } else {
                                    null
                                }
                            )
                        }
                    }
                }
                ui.components.OrbitScrollbar(listState = listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }

        // - Content
        Column(modifier = Modifier.weight(1f).fillMaxHeight().background(t.Bg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(t.Header_Bg)
                    .padding(horizontal = OrbitSpace.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedCategory.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = t.Text_White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        letterSpacing = 0.sp
                    ),
                    modifier = Modifier.weight(1f)
                )
                ui.components.OrbitButton(
                    text = "Done",
                    onClick = onBack,
                    tone = ui.components.OrbitButtonTone.Neutral,
                    size = 30.dp
                )
            }
            androidx.compose.material3.HorizontalDivider(color = t.Separator, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                // Cap the measure: settings text at 2560px wide is unreadable.
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                        .padding(horizontal = OrbitSpace.xxxl, vertical = OrbitSpace.xxl)
                ) {
                    when (selectedCategory) {
                        SettingsCategory.MyAccount -> settingsItem { MyAccountPane(repository) }
                        SettingsCategory.Profiles -> settingsItem { ProfilesPane(repository) }

                        SettingsCategory.Appearance -> {
                            settingsItem { ThemeSelectorCard() }
                            settingsItem { LiteModeCard() }
                            settingsItem { FontSizeCard(settingsManager) }
                            settingsItem { AppearanceExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Accessibility -> {
                            settingsItem { ReduceMotionCard(settingsManager) }
                            settingsItem { AccessibilityExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Chat -> {
                            settingsItem { MessageDensityCard(settingsManager) }
                            settingsItem { ChatSettingsPane(settingsManager) }
                        }
                        SettingsCategory.VoiceVideo -> {
                            settingsItem { VoiceVideoFullPane(settingsManager) }
                        }
                        SettingsCategory.Windows -> settingsItem { WindowsSettingsPane(settingsManager) }

                        SettingsCategory.Logout -> {}
                        else -> settingsItem { PlaceholderPane(selectedCategory.title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.settingsItem(content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(bottom = OrbitSpace.md)) { content() }
}

private fun settingsIconFor(cat: SettingsCategory): androidx.compose.ui.graphics.vector.ImageVector = when (cat) {
    SettingsCategory.MyAccount -> Icons.Default.Person
    SettingsCategory.Profiles -> Icons.Default.Face
    SettingsCategory.Appearance -> Icons.Default.Palette
    SettingsCategory.Accessibility -> Icons.Default.Accessibility
    SettingsCategory.VoiceVideo -> Icons.Default.Videocam
    SettingsCategory.Chat -> Icons.Default.Chat
    SettingsCategory.Windows -> Icons.Default.DesktopWindows
    SettingsCategory.Logout -> Icons.Default.Logout
}
// - Placeholder Pane

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

// - My Account Pane

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
                if (user?.avatarUrl != null) {
                    coil3.compose.AsyncImage(
                        model = user?.avatarUrl,
                        contentDescription = "Your avatar",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                    )
                } else {
                    Icon(Icons.Default.AccountCircle, null, modifier = Modifier.fillMaxSize(), tint = Accent)
                }
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


