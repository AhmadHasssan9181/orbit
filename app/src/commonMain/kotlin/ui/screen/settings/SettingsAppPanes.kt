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
        // `collectAsState(true)` MUST match the stored default. With it on `false`
        // the switch rendered "off" on a fresh install while DEBUG lines were in
        // fact being written to disk -- a control that misreports the state of the
        // only thing it controls.
        //
        // It also had a reader with no writer for its entire life, so DEBUG was
        // permanently off and `OrbitLog.d` was dead code across the whole call
        // stack. The console filter reads `debugEnabled` per line, so flipping
        // this works without a restart.
        val debugOn by settingsManager.debugLogging.collectAsState(true)
        PrefCard(
            "Debug logging",
            "On by default so a bug report is diagnosable without you having to " +
                "find this pane first. Writes to " +
                "%LOCALAPPDATA%\\Orbit\\logs\\orbit.log, kept for a few days. " +
                "Turn it off if you prefer a smaller log. ORBIT_DEBUG=1 forces it " +
                "on for one run."
        ) {
            CosmicSwitch(checked = debugOn, onCheckedChange = {
                settingsManager.setDebugLogging(it)
                util.OrbitLog.debugEnabled = it
            })
        }

        // SECURITY: tell the user when their credentials are not encrypted at rest.
        //
        // `encrypt()` in Settings.jvm.kt falls back to storing a value unprotected
        // when the platform cipher is unavailable -- a locked or broken-roaming
        // Windows profile, or `ERROR_INVALID_PARAMETER` from DPAPI. That leaves the
        // long-lived access token in `Preferences` in cleartext.
        //
        // It used to be a bare log line, which is not a channel the user has: a
        // jpackage GUI-subsystem launcher has no console, and nobody opens
        // %LOCALAPPDATA%\Orbit\logs\orbit.log unless they are already debugging
        // something else. So the flag now lives in settings and renders here.
        //
        // Rendered from a persisted value rather than a live one, because the write
        // that fails is the only moment the state changes -- a user who has never
        // touched their token since a bad boot would otherwise see nothing at all.
        val storeDegraded by settingsManager.credentialStoreDegradedFlow.collectAsState()
        if (storeDegraded) {
            PrefCard(
                "Credentials are not encrypted",
                "Windows could not protect your login with its usual per-user encryption " +
                    "(DPAPI), so your access token is currently stored in cleartext. This " +
                    "usually means a locked or roaming Windows profile. Sign out and back in " +
                    "once your profile is healthy, and your token will be re-encrypted " +
                    "automatically. Until then, do not share this Windows account."
            ) {}
        }

        // SECURITY: show when the current homeserver is being reached over plain HTTP.
        //
        // `HomeserverUrlNormalizer` refuses a plaintext homeserver unless the user
        // has explicitly confirmed that host, and the confirmation lives for the
        // process. Reporting it here means "I accepted this once" is a decision the
        // user can still see, rather than one they made by accident and forgot -- and
        // it is the one place they can see which host is on the list.
        val insecureHost = util.currentInsecureHomeserverHost()
        if (insecureHost != null) {
            PrefCard(
                "Plaintext homeserver",
                "Orbit is connected to $insecureHost over plain HTTP, so your password " +
                    "and access token are not encrypted in transit. That is acceptable on " +
                    "loopback for a homeserver you run yourself. For anything reachable " +
                    "over a network, use https and change the homeserver URL."
            ) {}
        }
    }
}
