package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import repository.SettingsManager
import ui.assets.OrbitDoodleWallpaperOptions
import ui.assets.orbitDoodleWallpaperIdForLabel
import ui.assets.orbitDoodleWallpaperLabelForId
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// ═══════════════════════════════════════════════════════════════════
//  CHAT SETTINGS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ChatSettingsPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Display & Media")
        val inlineMedia by settingsManager.inlineMedia.collectAsState(true)
        PrefCard("Show inline media", "Automatically display images, videos, and GIFs in chat.") {
            CosmicSwitch(checked = inlineMedia, onCheckedChange = { settingsManager.setInlineMedia(it) })
        }
        val linkPreviews by settingsManager.linkPreviews.collectAsState(true)
        PrefCard("Link previews", "Show embedded previews for shared URLs.") {
            CosmicSwitch(checked = linkPreviews, onCheckedChange = { settingsManager.setLinkPreviews(it) })
        }
        val showEmbeds by settingsManager.showEmbeds.collectAsState(true)
        PrefCard("Show embeds", "Display rich media for YouTube, Twitter, and other embeds.") {
            CosmicSwitch(checked = showEmbeds, onCheckedChange = { settingsManager.setShowEmbeds(it) })
        }
        val gifAutoplay by settingsManager.gifAutoplay.collectAsState(true)
        PrefCard("Auto-play GIFs", "GIFs animate automatically instead of on hover.") {
            CosmicSwitch(checked = gifAutoplay, onCheckedChange = { settingsManager.setGifAutoplay(it) })
        }
        val chatWallpaper by settingsManager.chatWallpaper.collectAsState("auto")
        PrefCard("Chat wallpaper", "Pick one of the Orbit doodle SVG backgrounds for message spaces.") {
            ChipGroup(
                options = OrbitDoodleWallpaperOptions,
                selected = orbitDoodleWallpaperLabelForId(chatWallpaper),
                onSelect = { label -> settingsManager.setChatWallpaper(orbitDoodleWallpaperIdForLabel(label)) }
            )
        }

        SectionHeader("Emojis & Stickers")
        val jumboEmojis by settingsManager.jumboEmojis.collectAsState(true)
        PrefCard("Jumbo emojis", "Display larger emojis when a message contains only emojis.") {
            CosmicSwitch(checked = jumboEmojis, onCheckedChange = { settingsManager.setJumboEmojis(it) })
        }
        val animatedEmoji by settingsManager.animatedEmoji.collectAsState(true)
        PrefCard("Animated emojis", "Play animated emojis in chat.") {
            CosmicSwitch(checked = animatedEmoji, onCheckedChange = { settingsManager.setAnimatedEmoji(it) })
        }
        val stickerSuggestions by settingsManager.stickerSuggestions.collectAsState(true)
        PrefCard("Sticker suggestions", "Show sticker suggestions when typing messages.") {
            CosmicSwitch(checked = stickerSuggestions, onCheckedChange = { settingsManager.setStickerSuggestions(it) })
        }
        val emojiSuggestions by settingsManager.emojiSuggestions.collectAsState(true)
        PrefCard("Emoji suggestions", "Show emoji autocomplete while typing with a colon.") {
            CosmicSwitch(checked = emojiSuggestions, onCheckedChange = { settingsManager.setEmojiSuggestions(it) })
        }

        SectionHeader("Chat Behavior")
        val compactMode by settingsManager.compactMode.collectAsState(false)
        PrefCard("Compact mode", "Fit more messages on screen by removing avatars and padding.") {
            CosmicSwitch(checked = compactMode, onCheckedChange = { settingsManager.setCompactMode(it) })
        }
        val enterSend by settingsManager.enterSend.collectAsState(true)
        PrefCard("Enter to send", "Pressing Enter sends the message. Use Shift+Enter for new lines.") {
            CosmicSwitch(checked = enterSend, onCheckedChange = { settingsManager.setEnterSend(it) })
        }
        val spellcheck by settingsManager.spellcheck.collectAsState(true)
        PrefCard("Spellcheck", "Highlight misspelled words in the message input box.") {
            CosmicSwitch(checked = spellcheck, onCheckedChange = { settingsManager.setSpellcheck(it) })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  KEYBINDS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun KeybindsPane() {
    val t = rememberCosmicTokens()
    val settingsManager = remember { repository.SettingsManager() }
    val storedPttKeybind by settingsManager.pushToTalkKeybind.collectAsState(PTT_DEFAULT_COMBO)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Voice & Video")
        KeybindCard("Toggle Mute", "Ctrl + Shift + M")
        KeybindCard("Toggle Deafen", "Ctrl + Shift + D")
        KeybindCard("Push to Talk", "${displayPttCombo(storedPttKeybind)} (Hold)")
        KeybindCard("Push to Mute", "None")

        SectionHeader("Screen Share")
        KeybindCard("Start/Stop Stream", "Ctrl + Shift + S")
        KeybindCard("Toggle Camera", "Ctrl + Shift + V")

        SectionHeader("Navigation")
        KeybindCard("Return to Active Call", "Ctrl + Shift + Alt + V")
    }
}

@Composable
private fun KeybindCard(action: String, keys: String) {
    val t = rememberCosmicTokens()
    PrefCard(action, "") {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(t.Bg)
                .border(1.dp, t.Separator, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(keys, color = t.AccentLight, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  LANGUAGE & TIME
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun LanguageTimePane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Language")
        val appLanguage by settingsManager.appLanguage.collectAsState("English (US)")
        PrefCard("App Language", "Change the language used throughout Orbit.") {
            ChipGroup(
                options = listOf("English (US)", "English (UK)", "Español", "Français", "Deutsch", "日本語"),
                selected = appLanguage,
                onSelect = { settingsManager.setAppLanguage(it) }
            )
        }
        val spellcheckLang by settingsManager.spellcheckLang.collectAsState("English")
        PrefCard("Spellcheck Language", "Language used for the built-in spellchecker.") {
            ChipGroup(
                options = listOf("System Default", "English", "Español"),
                selected = spellcheckLang,
                onSelect = { settingsManager.setSpellcheckLang(it) }
            )
        }

        SectionHeader("Time & Date")
        val use24HourClock by settingsManager.use24HourClock.collectAsState(false)
        PrefCard("24-Hour Clock", "Display times like 14:30 instead of 2:30 PM.") {
            CosmicSwitch(checked = use24HourClock, onCheckedChange = { settingsManager.setUse24HourClock(it) })
        }
        val relativeTime by settingsManager.relativeTime.collectAsState(true)
        PrefCard("Relative Timestamps", "Show 'Today at 2:30 PM' instead of full dates for recent messages.") {
            CosmicSwitch(checked = relativeTime, onCheckedChange = { settingsManager.setRelativeTime(it) })
        }
        val dateFormat by settingsManager.dateFormat.collectAsState("DD/MM/YYYY")
        PrefCard("Date Format", "How full dates are displayed.") {
            ChipGroup(
                options = listOf("MM/DD/YYYY", "DD/MM/YYYY", "YYYY-MM-DD"),
                selected = dateFormat,
                onSelect = { settingsManager.setDateFormat(it) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  WINDOWS SETTINGS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun WindowsSettingsPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("System Startup")
        val openOnStartup by settingsManager.openOnStartup.collectAsState(true)
        PrefCard("Open Orbit on startup", "Automatically launch Orbit when your computer starts.") {
            CosmicSwitch(checked = openOnStartup, onCheckedChange = { settingsManager.setOpenOnStartup(it) })
        }
        val startMinimized by settingsManager.startMinimized.collectAsState(false)
        PrefCard("Start minimized", "Launch Orbit silently into the system tray.") {
            CosmicSwitch(checked = startMinimized, onCheckedChange = { settingsManager.setStartMinimized(it) })
        }

        SectionHeader("Window Behavior")
        val minimizeToTray by settingsManager.minimizeToTray.collectAsState(false)
        PrefCard("Minimize to tray", "Clicking the 'X' button minimizes Orbit to the system tray instead of quitting.") {
            CosmicSwitch(checked = minimizeToTray, onCheckedChange = { settingsManager.setMinimizeToTray(it) })
        }
        val alwaysOnTop by settingsManager.alwaysOnTop.collectAsState(false)
        PrefCard("Always on top", "Keep the Orbit window above all other applications.") {
            CosmicSwitch(checked = alwaysOnTop, onCheckedChange = { settingsManager.setAlwaysOnTop(it) })
        }

        SectionHeader("Taskbar Integration")
        val taskbarBadge by settingsManager.taskbarBadge.collectAsState(true)
        PrefCard("Unread badge", "Show a red dot on the taskbar icon when you have unread messages.") {
            CosmicSwitch(checked = taskbarBadge, onCheckedChange = { settingsManager.setTaskbarBadge(it) })
        }
        val flashTaskbar by settingsManager.flashTaskbar.collectAsState(true)
        PrefCard("Flash taskbar", "Flash the taskbar icon when you receive a new direct message or mention.") {
            CosmicSwitch(checked = flashTaskbar, onCheckedChange = { settingsManager.setFlashTaskbar(it) })
        }

        SectionHeader("Updates")
        val autoUpdate by settingsManager.autoUpdate.collectAsState(true)
        PrefCard("Download updates automatically", "Orbit will download updates in the background and apply them on restart.") {
            CosmicSwitch(checked = autoUpdate, onCheckedChange = { settingsManager.setAutoUpdate(it) })
        }
        PrefCard("Current Version", "Orbit v1.0.4-beta (Build 8942)") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = rememberCosmicTokens().Accent), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Check for Updates", fontSize = 12.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ADVANCED
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AdvancedPane(settingsManager: SettingsManager) {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.Terminal,
            iconTint = Color(0xFFEF4444),
            title = "Advanced Options",
            subtitle = "Here be dragons. These settings are for power users and developers. Changing them may impact app stability or performance.",
            gradient = listOf(Color(0xFF3F3F46), Color(0xFF18181B)),
            buttonText = ""
        )

        SectionHeader("Developer Tools")
        val developerMode by settingsManager.developerMode.collectAsState(false)
        PrefCard("Developer Mode", "Exposes context menu items to copy IDs for users, servers, and channels.") {
            CosmicSwitch(checked = developerMode, onCheckedChange = { settingsManager.setDeveloperMode(it) })
        }
        val debugLogging by settingsManager.debugLogging.collectAsState(false)
        PrefCard("Verbose Debug Logging", "Writes extensive diagnostic information to disk for troubleshooting.") {
            CosmicSwitch(checked = debugLogging, onCheckedChange = { settingsManager.setDebugLogging(it) })
        }
        PrefCard("Clear Caches", "Deletes all cached images, avatars, and temporary files.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f)), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Clear Data", fontSize = 12.sp) }
        }

        SectionHeader("System Performance")
        val hardwareAcceleration by settingsManager.hardwareAcceleration.collectAsState(true)
        PrefCard("Hardware Acceleration", "Uses your GPU to render the UI for smoother performance. Turn off if experiencing graphical glitches.") {
            CosmicSwitch(checked = hardwareAcceleration, onCheckedChange = { settingsManager.setHardwareAcceleration(it) })
        }
        val reducedBandwidth by settingsManager.reducedBandwidth.collectAsState(false)
        PrefCard("Reduced Bandwidth Mode", "Limits background syncing and image prefetching to save data.") {
            CosmicSwitch(checked = reducedBandwidth, onCheckedChange = { settingsManager.setReducedBandwidth(it) })
        }

        SectionHeader("Experimental Features")
        val betaTestMode by settingsManager.betaTestMode.collectAsState(false)
        PrefCard("Beta Channel", "Opt-in to receive unstable beta updates before public release.") {
            CosmicSwitch(checked = betaTestMode, onCheckedChange = { settingsManager.setBetaTestMode(it) })
        }
        val newUIPreview by settingsManager.newUIPreview.collectAsState(false)
        PrefCard("New UI Preview", "Enable work-in-progress redesign elements.") {
            CosmicSwitch(checked = newUIPreview, onCheckedChange = { settingsManager.setNewUIPreview(it) })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACTIVITY PRIVACY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ActivityPrivacyPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Activity Status")
        val displayActivity by settingsManager.displayActivity.collectAsState(true)
        PrefCard("Display current activity", "Automatically update your status when you're playing a game, listening to Spotify, or streaming.") {
            CosmicSwitch(checked = displayActivity, onCheckedChange = { settingsManager.setDisplayActivity(it) })
        }
        val shareActivityLargeServers by settingsManager.shareActivityLargeServers.collectAsState(true)
        PrefCard("Share activity in large servers", "Allow your activity to be visible in servers with over 200 members.") {
            CosmicSwitch(checked = shareActivityLargeServers, onCheckedChange = { settingsManager.setShareActivityLargeServers(it) })
        }
        val showOnlineStatus by settingsManager.showOnlineStatus.collectAsState(true)
        PrefCard("Show online status", "Let people know when you're actively using Orbit.") {
            CosmicSwitch(checked = showOnlineStatus, onCheckedChange = { settingsManager.setShowOnlineStatus(it) })
        }

        SectionHeader("Presence Settings")
        val idleTimeout by settingsManager.idleTimeout.collectAsState("5 minutes")
        PrefCard("Idle Timeout", "How long before your status automatically changes to Idle.") {
            ChipGroup(
                options = listOf("5 minutes", "10 minutes", "15 minutes", "Never"),
                selected = idleTimeout,
                onSelect = { settingsManager.setIdleTimeout(it) }
            )
        }

        SectionHeader("Rich Presence Privacy")
        val allowListenAlong by settingsManager.allowListenAlong.collectAsState(true)
        PrefCard("Allow friends to Listen Along", "Let friends join your Spotify listening session.") {
            CosmicSwitch(checked = allowListenAlong, onCheckedChange = { settingsManager.setAllowListenAlong(it) })
        }
        val showSpotifyStatus by settingsManager.showSpotifyStatus.collectAsState(true)
        PrefCard("Show what you're listening to", "Display current Spotify track in your status.") {
            CosmicSwitch(checked = showSpotifyStatus, onCheckedChange = { settingsManager.setShowSpotifyStatus(it) })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  REGISTERED GAMES
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun RegisteredGamesPane(settingsManager: SettingsManager) {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Orbit automatically detects games running on your PC and adds them to this list.", color = t.Text_Muted, style = MaterialTheme.typography.bodySmall)

        SectionHeader("Game Detection")
        val autoDetectGames by settingsManager.autoDetectGames.collectAsState(true)
        PrefCard("Auto-detect running games", "Scan background processes to identify when you're playing a game.") {
            CosmicSwitch(checked = autoDetectGames, onCheckedChange = { settingsManager.setAutoDetectGames(it) })
        }
        val statusDefault by settingsManager.statusDefault.collectAsState(true)
        PrefCard("Show running games as status", "Set your status to 'Playing X' by default when a game is detected.") {
            CosmicSwitch(checked = statusDefault, onCheckedChange = { settingsManager.setStatusDefault(it) })
        }

        SectionHeader("Added Games")
        PrefCard("Valorant", "Added today") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.CardBg), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Remove", fontSize = 12.sp) }
        }
        PrefCard("Minecraft", "Added last week") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.CardBg), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Remove", fontSize = 12.sp) }
        }
        PrefCard("Cyberpunk 2077", "Added last month") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.CardBg), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Remove", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(8.dp))
        PrefCard("Not seeing your game?", "You can manually add games if Orbit doesn't detect them.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = t.Accent), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Add it!", fontSize = 12.sp) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  GAME OVERLAY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun GameOverlayPane(settingsManager: SettingsManager) {
    val t = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.Layers,
            iconTint = Color(0xFF6366F1),
            title = "In-Game Overlay",
            subtitle = "Access voice chat, text channels, and notifications without Alt-Tabbing out of your game.",
            buttonText = "Configure Hotkey"
        )

        SectionHeader("Overlay Settings")
        val enableOverlay by settingsManager.enableOverlay.collectAsState(false)
        PrefCard("Enable In-Game Overlay", "Show the Orbit overlay over fullscreen applications.") {
            CosmicSwitch(checked = enableOverlay, onCheckedChange = { settingsManager.setEnableOverlay(it) })
        }
        KeybindCard("Toggle Overlay Lock", "Shift + `")
        
        SectionHeader("Notifications")
        val overlayNotifications by settingsManager.overlayNotifications.collectAsState(true)
        PrefCard("Show Text Notifications", "Pop up incoming text messages in the corner of your screen.") {
            CosmicSwitch(checked = overlayNotifications, onCheckedChange = { settingsManager.setOverlayNotifications(it) })
        }
        val notificationPosition by settingsManager.notificationPosition.collectAsState("Top Right")
        PrefCard("Notification Position", "Where text notifications appear.") {
            ChipGroup(
                options = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right"),
                selected = notificationPosition,
                onSelect = { settingsManager.setNotificationPosition(it) }
            )
        }

        SectionHeader("Voice Overlay")
        val overlayUsers by settingsManager.overlayUsers.collectAsState(true)
        PrefCard("Show Voice Users", "Display a list of users currently in your voice channel.") {
            CosmicSwitch(checked = overlayUsers, onCheckedChange = { settingsManager.setOverlayUsers(it) })
        }
        val voiceOverlayPosition by settingsManager.voiceOverlayPosition.collectAsState("Top Left")
        PrefCard("Voice User Position", "Where the active speaker list appears.") {
            ChipGroup(
                options = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right"),
                selected = voiceOverlayPosition,
                onSelect = { settingsManager.setVoiceOverlayPosition(it) }
            )
        }

        SectionHeader("Appearance")
        val overlayOpacity by settingsManager.overlayOpacity.collectAsState(80f)
        PrefCard("Avatar Opacity", "How transparent the voice overlay avatars are.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${overlayOpacity.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = t.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = overlayOpacity,
                    onValueChange = { settingsManager.setOverlayOpacity(it) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = t.Accent, activeTrackColor = t.Accent, inactiveTrackColor = t.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}
