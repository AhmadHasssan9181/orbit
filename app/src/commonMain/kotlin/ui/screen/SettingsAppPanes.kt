package ui.screen

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
import ui.theme.rememberCosmicTokens

// ═══════════════════════════════════════════════════════════════════
//  CHAT SETTINGS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ChatSettingsPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Display & Media")
        var inlineMedia by remember { mutableStateOf(true) }
        PrefCard("Show inline media", "Automatically display images, videos, and GIFs in chat.") {
            CosmicSwitch(checked = inlineMedia, onCheckedChange = { inlineMedia = it })
        }
        var linkPreviews by remember { mutableStateOf(true) }
        PrefCard("Link previews", "Show embedded previews for shared URLs.") {
            CosmicSwitch(checked = linkPreviews, onCheckedChange = { linkPreviews = it })
        }
        var showEmbeds by remember { mutableStateOf(true) }
        PrefCard("Show embeds", "Display rich media for YouTube, Twitter, and other embeds.") {
            CosmicSwitch(checked = showEmbeds, onCheckedChange = { showEmbeds = it })
        }
        var gifAutoplay by remember { mutableStateOf(true) }
        PrefCard("Auto-play GIFs", "GIFs animate automatically instead of on hover.") {
            CosmicSwitch(checked = gifAutoplay, onCheckedChange = { gifAutoplay = it })
        }

        SectionHeader("Emojis & Stickers")
        var jumbo by remember { mutableStateOf(true) }
        PrefCard("Jumbo emojis", "Display larger emojis when a message contains only emojis.") {
            CosmicSwitch(checked = jumbo, onCheckedChange = { jumbo = it })
        }
        var animatedEmoji by remember { mutableStateOf(true) }
        PrefCard("Animated emojis", "Play animated emojis in chat.") {
            CosmicSwitch(checked = animatedEmoji, onCheckedChange = { animatedEmoji = it })
        }
        var stickerSuggestions by remember { mutableStateOf(true) }
        PrefCard("Sticker suggestions", "Show sticker suggestions when typing messages.") {
            CosmicSwitch(checked = stickerSuggestions, onCheckedChange = { stickerSuggestions = it })
        }
        var emojiSuggestions by remember { mutableStateOf(true) }
        PrefCard("Emoji suggestions", "Show emoji autocomplete while typing with a colon.") {
            CosmicSwitch(checked = emojiSuggestions, onCheckedChange = { emojiSuggestions = it })
        }

        SectionHeader("Text & Input")
        var compactMode by remember { mutableStateOf(false) }
        PrefCard("Compact mode", "Reduce spacing between messages for denser chat view.") {
            CosmicSwitch(checked = compactMode, onCheckedChange = { compactMode = it })
        }
        var enterSend by remember { mutableStateOf(true) }
        PrefCard("Enter to send", "Press Enter to send messages. Shift+Enter for new line.") {
            CosmicSwitch(checked = enterSend, onCheckedChange = { enterSend = it })
        }
        var spellcheck by remember { mutableStateOf(true) }
        PrefCard("Spell check", "Highlight spelling errors as you type.") {
            CosmicSwitch(checked = spellcheck, onCheckedChange = { spellcheck = it })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  KEYBINDS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun KeybindsPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Voice & Video")
        PrefCard("Mute / Unmute", "Toggle your microphone") {
            StatusBadge("Ctrl+Shift+M", Color(0xFF6366F1))
        }
        PrefCard("Deafen / Undeafen", "Toggle audio output") {
            StatusBadge("Ctrl+Shift+D", Color(0xFF6366F1))
        }
        PrefCard("Push to Talk", "Hold to transmit voice") {
            StatusBadge("V", Color(0xFF6366F1))
        }
        PrefCard("Toggle Video", "Turn camera on/off") {
            StatusBadge("Ctrl+Shift+V", Color(0xFF6366F1))
        }

        SectionHeader("Screen Sharing")
        PrefCard("Toggle Screen Share", "Start or stop sharing your screen") {
            StatusBadge("Ctrl+Shift+S", Color(0xFF6366F1))
        }
        PrefCard("Share Specific Window", "Pick a window to share") {
            StatusBadge("Ctrl+Shift+W", Color(0xFF6366F1))
        }

        SectionHeader("Navigation")
        PrefCard("Quick Switcher", "Jump to any channel or DM") {
            StatusBadge("Ctrl+K", Color(0xFF6366F1))
        }
        PrefCard("Search", "Search messages in current channel") {
            StatusBadge("Ctrl+F", Color(0xFF6366F1))
        }
        PrefCard("Navigate Up", "Select previous channel") {
            StatusBadge("Alt+↑", Color(0xFF6366F1))
        }
        PrefCard("Navigate Down", "Select next channel") {
            StatusBadge("Alt+↓", Color(0xFF6366F1))
        }
        PrefCard("Mark as Read", "Mark current channel as read") {
            StatusBadge("Escape", Color(0xFF6366F1))
        }

        SectionHeader("Messaging")
        PrefCard("Edit Last Message", "Edit your most recent message") {
            StatusBadge("↑", Color(0xFF6366F1))
        }
        PrefCard("Pin/Unpin Message", "Toggle message pin in channel") {
            StatusBadge("Ctrl+P", Color(0xFF6366F1))
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Custom Keybind")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  LANGUAGE & TIME
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun LanguageTimePane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Language")
        var selectedLang by remember { mutableStateOf("English (US)") }
        PrefCard("App Language", "The language used throughout the Orbit interface.") {
            ChipGroup(
                options = listOf("English (US)", "English (UK)", "Español", "Français", "Deutsch", "日本語"),
                selected = selectedLang,
                onSelect = { selectedLang = it }
            )
        }
        var spellcheckLang by remember { mutableStateOf("English") }
        PrefCard("Spell Check Language", "Language used for spell checking in the message input.") {
            ChipGroup(
                options = listOf("English", "Spanish", "French", "German"),
                selected = spellcheckLang,
                onSelect = { spellcheckLang = it }
            )
        }

        SectionHeader("Date & Time")
        var is24h by remember { mutableStateOf(false) }
        PrefCard("24-Hour Clock", "Display times as 14:00 instead of 2:00 PM.") {
            CosmicSwitch(checked = is24h, onCheckedChange = { is24h = it })
        }
        var relativeTime by remember { mutableStateOf(true) }
        PrefCard("Relative timestamps", "Show '2 hours ago' instead of exact timestamps.") {
            CosmicSwitch(checked = relativeTime, onCheckedChange = { relativeTime = it })
        }
        var dateFormat by remember { mutableStateOf("DD/MM/YYYY") }
        PrefCard("Date Format", "How dates are displayed in message timestamps.") {
            ChipGroup(
                options = listOf("DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD"),
                selected = dateFormat,
                onSelect = { dateFormat = it }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  WINDOWS / DESKTOP SETTINGS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun WindowsSettingsPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Startup")
        var boot by remember { mutableStateOf(true) }
        PrefCard("Open Orbit on startup", "Launch Orbit automatically when you turn on your computer.") {
            CosmicSwitch(checked = boot, onCheckedChange = { boot = it })
        }
        var startMinimized by remember { mutableStateOf(false) }
        PrefCard("Start minimized", "Orbit starts in the background, only visible in the system tray.") {
            CosmicSwitch(checked = startMinimized, onCheckedChange = { startMinimized = it })
        }

        SectionHeader("Window Behavior")
        var tray by remember { mutableStateOf(true) }
        PrefCard("Minimize to Tray", "Clicking the close button minimizes Orbit to your system tray.") {
            CosmicSwitch(checked = tray, onCheckedChange = { tray = it })
        }
        var alwaysOnTop by remember { mutableStateOf(false) }
        PrefCard("Always on Top", "Keep the Orbit window above all other applications.") {
            CosmicSwitch(checked = alwaysOnTop, onCheckedChange = { alwaysOnTop = it })
        }

        SectionHeader("Taskbar")
        var badge by remember { mutableStateOf(true) }
        PrefCard("Taskbar badge", "Show unread message count on the taskbar icon.") {
            CosmicSwitch(checked = badge, onCheckedChange = { badge = it })
        }
        var flashTaskbar by remember { mutableStateOf(true) }
        PrefCard("Flash taskbar", "Flash the Orbit icon in the taskbar when a notification arrives.") {
            CosmicSwitch(checked = flashTaskbar, onCheckedChange = { flashTaskbar = it })
        }

        SectionHeader("Updates")
        var autoUpdate by remember { mutableStateOf(true) }
        PrefCard("Auto-update", "Automatically download and install Orbit updates.") {
            CosmicSwitch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
        }
        PrefCard("Current Version", "Orbit Desktop v1.0.0-beta") {
            StatusBadge("Up to date", Color(0xFF10B981))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ADVANCED
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AdvancedPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Developer")
        var devMode by remember { mutableStateOf(false) }
        PrefCard("Developer Mode", "Show IDs and developer tools in context menus.") {
            CosmicSwitch(checked = devMode, onCheckedChange = { devMode = it })
        }
        var debugLogs by remember { mutableStateOf(false) }
        PrefCard("Debug Logging", "Write verbose debug logs to disk for troubleshooting.") {
            CosmicSwitch(checked = debugLogs, onCheckedChange = { debugLogs = it })
        }

        SectionHeader("Performance")
        var hw by remember { mutableStateOf(true) }
        PrefCard("Hardware Acceleration", "Use your GPU for smoother rendering. Disable if you see visual glitches.") {
            CosmicSwitch(checked = hw, onCheckedChange = { hw = it })
        }
        var reducedBandwidth by remember { mutableStateOf(false) }
        PrefCard("Reduced Bandwidth Mode", "Lower media quality to save bandwidth on slow connections.") {
            CosmicSwitch(checked = reducedBandwidth, onCheckedChange = { reducedBandwidth = it })
        }

        SectionHeader("Experimental")
        var testMode by remember { mutableStateOf(false) }
        PrefCard("Beta Test Mode", "Enable experimental features that may be unstable.") {
            CosmicSwitch(checked = testMode, onCheckedChange = { testMode = it })
        }
        var newUI by remember { mutableStateOf(false) }
        PrefCard("New Chat UI Preview", "Try the next generation chat interface before it's released.") {
            CosmicSwitch(checked = newUI, onCheckedChange = { newUI = it })
        }

        SectionHeader("Data")
        PrefCard("Clear Cache", "Free up disk space by removing cached images and media.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = tokens.CardBg)) { Text("Clear", color = tokens.Text_White) }
        }
        PrefCard("Export Data", "Download a copy of all your Orbit account data.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = tokens.CardBg)) { Text("Export", color = tokens.Text_White) }
        }
        PrefCard("Delete All Messages", "Permanently delete all messages you've sent across Orbit.") {
            Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444).copy(alpha = 0.8f))) { Text("Delete") }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACTIVITY PRIVACY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun ActivityPrivacyPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Activity Status")
        var status by remember { mutableStateOf(true) }
        PrefCard("Display current activity", "Show the game you're playing or music you're listening to.") {
            CosmicSwitch(checked = status, onCheckedChange = { status = it })
        }
        var shareDefault by remember { mutableStateOf(true) }
        PrefCard("Share in large servers", "Show activity status in servers with 200+ members.") {
            CosmicSwitch(checked = shareDefault, onCheckedChange = { shareDefault = it })
        }

        SectionHeader("Online Status")
        var showOnline by remember { mutableStateOf(true) }
        PrefCard("Show when you're online", "Other users can see your green online indicator.") {
            CosmicSwitch(checked = showOnline, onCheckedChange = { showOnline = it })
        }
        var idleTimeout by remember { mutableStateOf("5 minutes") }
        PrefCard("Idle timeout", "How long before your status changes to 'Idle'.") {
            ChipGroup(
                options = listOf("2 minutes", "5 minutes", "10 minutes", "Never"),
                selected = idleTimeout,
                onSelect = { idleTimeout = it }
            )
        }

        SectionHeader("Listening Along")
        var listening by remember { mutableStateOf(true) }
        PrefCard("Allow Listen Along", "Let friends listen to your Spotify along with you.") {
            CosmicSwitch(checked = listening, onCheckedChange = { listening = it })
        }
        var spotifyStatus by remember { mutableStateOf(true) }
        PrefCard("Show Spotify as status", "Display the current song as your status message.") {
            CosmicSwitch(checked = spotifyStatus, onCheckedChange = { spotifyStatus = it })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  REGISTERED GAMES
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun RegisteredGamesPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroCard(
            icon = Icons.Default.SportsEsports,
            iconTint = Color(0xFF60A5FA),
            title = "Game Detection",
            subtitle = "Orbit automatically detects running games to display as your activity status. You can also add games manually."
        )

        SectionHeader("Detected Games")
        PrefCard("No game currently running", "Launch a game and it will appear here automatically.") {
            Icon(Icons.Default.SearchOff, null, tint = tokens.Text_Muted)
        }

        SectionHeader("Manually Added")
        PrefCard("Cyberpunk 2077", "Last played 2 days ago") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var showStatus by remember { mutableStateOf(true) }
                CosmicSwitch(checked = showStatus, onCheckedChange = { showStatus = it })
            }
        }
        PrefCard("Minecraft", "Last played yesterday") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var showStatus by remember { mutableStateOf(true) }
                CosmicSwitch(checked = showStatus, onCheckedChange = { showStatus = it })
            }
        }
        PrefCard("Valorant", "Last played 5 days ago") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var showStatus by remember { mutableStateOf(false) }
                CosmicSwitch(checked = showStatus, onCheckedChange = { showStatus = it })
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = {}, colors = ButtonDefaults.buttonColors(containerColor = tokens.Accent)) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Game Manually")
        }

        SectionHeader("Settings")
        var autoDetect by remember { mutableStateOf(true) }
        PrefCard("Auto-detect games", "Orbit scans running processes to find games automatically.") {
            CosmicSwitch(checked = autoDetect, onCheckedChange = { autoDetect = it })
        }
        var statusDefault by remember { mutableStateOf(true) }
        PrefCard("Show as status by default", "Newly detected games automatically display as your status.") {
            CosmicSwitch(checked = statusDefault, onCheckedChange = { statusDefault = it })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  GAME OVERLAY
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun GameOverlayPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Overlay")
        var overlay by remember { mutableStateOf(false) }
        PrefCard("Enable in-game overlay", "See who's talking and text chat without Alt-Tabbing out of your game.") {
            CosmicSwitch(checked = overlay, onCheckedChange = { overlay = it })
        }
        var overlayNotifications by remember { mutableStateOf(true) }
        PrefCard("Show notifications", "Display message notifications in the overlay while gaming.") {
            CosmicSwitch(checked = overlayNotifications, onCheckedChange = { overlayNotifications = it })
        }
        var overlayUsers by remember { mutableStateOf(true) }
        PrefCard("Show user avatars", "Display small avatars of who's speaking in voice chat.") {
            CosmicSwitch(checked = overlayUsers, onCheckedChange = { overlayUsers = it })
        }

        SectionHeader("Overlay Position")
        var position by remember { mutableStateOf("Top Right") }
        PrefCard("Notification position", "Where overlay notifications appear on screen.") {
            ChipGroup(
                options = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right"),
                selected = position,
                onSelect = { position = it }
            )
        }
        var voicePosition by remember { mutableStateOf("Top Left") }
        PrefCard("Voice overlay position", "Where the voice user list appears on screen.") {
            ChipGroup(
                options = listOf("Top Left", "Top Right", "Bottom Left", "Bottom Right"),
                selected = voicePosition,
                onSelect = { voicePosition = it }
            )
        }

        SectionHeader("Shortcuts")
        PrefCard("Toggle Overlay Lock", "Lock/unlock overlay for mouse interaction") {
            StatusBadge("Shift + ~", Color(0xFF6366F1))
        }
        PrefCard("Toggle Overlay", "Show/hide the entire overlay") {
            StatusBadge("Shift + Tab", Color(0xFF6366F1))
        }

        SectionHeader("Overlay Appearance")
        var opacity by remember { mutableStateOf(80f) }
        PrefCard("Overlay opacity", "Transparency of the overlay elements.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${opacity.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = opacity,
                    onValueChange = { opacity = it },
                    valueRange = 20f..100f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }
    }
}
