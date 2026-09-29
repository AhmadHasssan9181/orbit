package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import repository.SettingsManager
import ui.assets.OrbitDoodleWallpaper
import ui.assets.orbitDoodleWallpaperIdForLabel
import ui.assets.orbitDoodleWallpaperIsLight
import ui.assets.orbitDoodleWallpaperLabelForId
import ui.components.OrbitButton
import ui.components.OrbitButtonTone
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
//
// Only settings the app actually honours are listed. The inline-media, link
// preview, embed, GIF autoplay, jumbo/animated emoji, sticker/emoji suggestion,
// spellcheck and "Show inline media" switches all had a SettingsManager accessor
// and a writer here and no reader anywhere — the help text claimed behaviour
// that did not exist. Their accessors are untouched in SettingsManager; only the
// controls were removed.

@Composable
internal fun ChatSettingsPane(settingsManager: SettingsManager) {
    val tokens = rememberCosmicTokens()
    val isDarkTheme = tokens.Bg.luminance() < 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Display & Media")
        val chatWallpaper by settingsManager.chatWallpaper.collectAsState("auto")
        PrefCard(
            "Chat wallpaper",
            // Wallpaper canvases are fixed colours while message text comes from
            // the theme tokens, so a light wallpaper on a dark theme was
            // white-on-white. Only compatible wallpapers are offered.
            "Pick a background for message spaces. Only wallpapers that contrast with the current theme are listed.",
            // Vertical, for the same reason App Theme is. A Row measures its
            // non-weighted children against the full width first, and this chip
            // group's own content (eleven wallpapers, ~900dp of labels) is wider
            // than the card, so it claimed the whole row and the heading's
            // weight(1f) request was left with nothing — "Chat wallpaper" wrapped
            // one glyph per line. Under the label the chips get the full card
            // width and scroll.
            vertical = true
        ) {
            ChipGroup(
                options = compatibleWallpaperLabels(isDarkTheme),
                selected = orbitDoodleWallpaperLabelForId(chatWallpaper),
                onSelect = { label -> settingsManager.setChatWallpaper(orbitDoodleWallpaperIdForLabel(label)) }
            )
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
    }
}

/** Wallpaper labels filtered to those that can carry readable text on this theme. */
internal fun compatibleWallpaperLabels(isDarkTheme: Boolean): List<String> =
    listOf("Auto") + OrbitDoodleWallpaper.entries
        .filter { orbitDoodleWallpaperIsLight(it.id) != isDarkTheme }
        .map { it.label }

// ═══════════════════════════════════════════════════════════════════
//  WINDOWS SETTINGS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun WindowsSettingsPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Window Behavior")
        val minimizeToTray by settingsManager.minimizeToTray.collectAsState(false)
        PrefCard("Minimize to tray", "Clicking the 'X' button minimizes Orbit to the system tray instead of quitting.") {
            CosmicSwitch(checked = minimizeToTray, onCheckedChange = { settingsManager.setMinimizeToTray(it) })
        }

        SectionHeader("Updates")
        // No updater exists. The previous button was `onClick = {}` next to a
        // hard-coded "v1.0.4-beta (Build 8942)" that would silently go stale.
        PrefCard("Updates", "Orbit does not check for or install updates yet.") {
            // OrbitButton, not Material's OutlinedButton: a disabled secondary
            // action is text, and a ring around it inside a ring around the card
            // was the third outline for one disabled label.
            OrbitButton(
                text = "Check for Updates (not available)",
                onClick = {},
                enabled = false,
                tone = OrbitButtonTone.Neutral,
                size = 30.dp
            )
        }

        SectionHeader("Diagnostics")
        // This switch had a reader with no writer for its entire life, so DEBUG
        // was permanently off. `OrbitLog.d` is used across the whole call stack —
        // roster ticks, ICE candidates, membership publishing, media frames — and
        // none of it could ever emit. The console filter reads `debugEnabled` per
        // line, so flipping this works without a restart.
        val debugOn by settingsManager.debugLogging.collectAsState(false)
        PrefCard(
            "Debug logging",
            "Print DEBUG lines to the console. Noisy — turn it on to report a " +
                "bug, then off again. ORBIT_DEBUG=1 does the same for one run."
        ) {
            CosmicSwitch(checked = debugOn, onCheckedChange = {
                settingsManager.setDebugLogging(it)
                util.OrbitLog.debugEnabled = it
            })
        }
    }
}
