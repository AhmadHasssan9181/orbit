package ui.screen.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ui.components.OrbitListRow
import ui.components.OrbitNotices
import ui.components.OrbitNotices.onFailureOrNotify
import ui.components.OrbitRowLeading
import ui.components.OrbitTextField
import ui.icons.OrbitIcons
import ui.model.OrbitUser
import repository.SettingsManager
import repository.OrbitRepository
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
    // keybinds, language and activity/games were pure mock-ups with no backing
    // engine - hidden until they actually work.)
    MyAccount("My Account", "USER SETTINGS"),
    Profiles("Profiles", "USER SETTINGS"),

    // App Settings
    Appearance("Appearance", "APP SETTINGS"),
    Accessibility("Accessibility", "APP SETTINGS"),
    Notifications("Notifications", "APP SETTINGS"),
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
                // The column has no scroll of its own, so Voice & Video (~600dp
                // of cards) and the extras panes (~450dp) ran off the bottom of
                // the window with no way to reach the last rows.
                Column(
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = OrbitSpace.xxxl, vertical = OrbitSpace.xxl)
                ) {
                    when (selectedCategory) {
                        SettingsCategory.MyAccount -> {
                            settingsItem { MyAccountPane(repository) }
                            settingsItem { VerifyDeviceCard(repository) }
                        }
                        SettingsCategory.Profiles -> settingsItem { ProfilesPane(repository) }

                        SettingsCategory.Appearance -> {
                            settingsItem { ThemeSelectorCard(settingsManager) }
                            settingsItem { FontSizeCard(settingsManager) }
                            settingsItem { AppearanceExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Accessibility -> {
                            settingsItem { AccessibilityExtrasPane(settingsManager) }
                        }
                        SettingsCategory.Notifications -> {
                            settingsItem { NotificationsPane(settingsManager) }
                        }
                        SettingsCategory.Chat -> {
                            settingsItem { ChatSettingsPane(settingsManager) }
                        }
                        SettingsCategory.VoiceVideo -> {
                            settingsItem { VoiceVideoFullPane(settingsManager) }
                        }
                        SettingsCategory.Windows -> settingsItem { WindowsSettingsPane(settingsManager) }

                        SettingsCategory.Logout -> {}
                        // No `else -> Unit`: a new SettingsCategory would render a
                        // blank pane with no compiler complaint. Exhaustive `when`
                        // over the enum makes the compiler flag it instead.
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
    SettingsCategory.Notifications -> Icons.Default.Notifications
    SettingsCategory.VoiceVideo -> Icons.Default.Videocam
    SettingsCategory.Chat -> Icons.Default.Chat
    SettingsCategory.Windows -> Icons.Default.DesktopWindows
    SettingsCategory.Logout -> Icons.Default.Logout
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
                                // Both of these refreshed `user` unconditionally,
                                // so a failed avatar upload or name change
                                // re-read the server, got the *unchanged* user
                                // back, and showed the old value as though the
                                // save had worked. Refresh only on success.
                                repository.uploadAvatar(bytes)
                                    .onFailureOrNotify("Avatar upload", "Settings")
                                    .onSuccess {
                                        user = repository.getCurrentUser()
                                        OrbitNotices.info("Avatar updated", "Settings")
                                    }
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
                    OrbitTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = "Display name",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    )
                    Row {
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    // The dialog used to close and the old name
                                    // stay on screen after a failed save, which
                                    // reads as "it didn't take" rather than "it
                                    // errored". Keep it open and say why.
                                    repository.updateDisplayName(newName)
                                        .onFailureOrNotify("Display name", "Settings")
                                        .onSuccess {
                                            user = repository.getCurrentUser()
                                            isEditingName = false
                                        }
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

/**
 * Start a device verification.
 *
 * `VerificationOverlay` was already complete: it handles an incoming request, the
 * SAS emoji comparison, cancellation and success. What did not exist anywhere in the
 * app was the call that STARTS one -- so Orbit could verify a second device but only
 * after that device asked first. Someone installing Orbit on a new laptop had no way
 * to establish trust with the session they already had, short of opening a different
 * client and asking from there.
 *
 * SAS only, because that is the one method this overlay can complete. QR would need
 * a scanner, and offering a method with no way to finish it is worse than not
 * offering it.
 */
@Composable
private fun VerifyDeviceCard(repository: OrbitRepository) {
    val scope = rememberCoroutineScope()
    val t = rememberCosmicTokens()
    var open by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    if (open) {
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text("Verify another device", color = t.Text_White) },
            text = {
                Column {
                    Text(
                        "Enter the Matrix ID of the other device — the same user, so your own ID. " +
                            "You will be shown emojis to compare.",
                        color = t.Text_Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    OrbitTextField(
                        value = draft,
                        onValueChange = { draft = it; problem = null },
                        placeholder = "@you:matrix.org",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    problem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = t.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            busy = true
                            scope.launch {
                                repository.startDeviceVerification(draft)
                                    // Keep the dialog open on failure and say why. The
                                    // overlay takes over on success and the dialog
                                    // closes behind it, so a silent close on failure
                                    // would leave nothing on screen at all.
                                    .onSuccess { open = false }
                                    .onFailure { problem = it.message ?: "Could not start verification" }
                                busy = false
                            }
                        },
                        enabled = !busy && draft.isNotBlank()
                    ) { Text("Start", color = t.Accent) }
                    TextButton(onClick = { open = false }, enabled = !busy) {
                        Text("Cancel", color = t.Text_Muted)
                    }
                }
            },
            containerColor = t.Bg
        )
    }

    PrefCard(
        "Verify another device",
        "Confirm a second session belongs to you by comparing emojis. Only needed if that session shows a warning about unverified keys.",
        vertical = true
    ) {
        ui.components.OrbitButton(
            text = "Verify a device",
            onClick = { problem = null; open = true },
            tone = ui.components.OrbitButtonTone.Neutral
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

/** Human label for a theme, from the enum name so new values need no wiring. */
private fun themeLabel(theme: ui.theme.Theme): String = when (theme) {
    ui.theme.Theme.M3Dynamic -> "Material You"
    else -> theme.name.replaceFirstChar { it.uppercase() }
}

@Composable private fun ThemeSelectorCard(settingsManager: SettingsManager) {
    val onThemeChange = ui.theme.LocalOnThemeChange.current
    val currentSeedColor = ui.theme.LocalThemeSeedColor.current
    val dynamicIsDark = ui.theme.LocalDynamicThemeIsDark.current
    val tokens = rememberCosmicTokens()

    PrefCard("App Theme", "Choose between Dark and Light mode, and pick an adaptive color palette", vertical = true) {
        // Mode Toggle: Dark vs Light
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Mode",
                    color = tokens.Text_White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (dynamicIsDark) "Dark mode with rich adaptive tonal shades" else "Light mode with soft adaptive tonal shades",
                    color = tokens.Text_Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            ChipGroup(
                options = listOf("Dark", "Light"),
                selected = if (dynamicIsDark) "Dark" else "Light",
                onSelect = { mode ->
                    val isDark = (mode == "Dark")
                    settingsManager.setDynamicThemeIsDark(isDark)
                    settingsManager.setTheme(ui.theme.Theme.M3Dynamic)
                    onThemeChange(ui.theme.Theme.M3Dynamic)
                }
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = tokens.Separator)
        Spacer(Modifier.height(12.dp))

        // Palette Colors: Always visible in both Dark and Light modes!
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Palette Color",
                color = tokens.Text_White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "The entire interface adapts to shades of your chosen color",
                color = tokens.Text_Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ui.theme.MaterialYouSeeds.forEach { seed ->
                    val isSelected = currentSeedColor.value == seed.color.value
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                settingsManager.setThemeSeedColor(seed.color.value.toLong())
                                settingsManager.setTheme(ui.theme.Theme.M3Dynamic)
                                onThemeChange(ui.theme.Theme.M3Dynamic)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(seed.color)
                                .border(
                                    width = if (isSelected) 3.dp else if (isHovered) 2.dp else 1.dp,
                                    color = if (isSelected) tokens.Text_White else if (isHovered) tokens.Text_Secondary else Color.Transparent,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = if (seed.color.luminance() > 0.4f) Color.Black else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = seed.name,
                            color = if (isSelected) tokens.Text_White else tokens.Text_Muted,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}


