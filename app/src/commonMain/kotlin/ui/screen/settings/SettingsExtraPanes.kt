package ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import repository.SettingsManager
import ui.theme.rememberCosmicTokens

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

// Pane content is limited to settings the engine actually reads. Colour
// saturation, high contrast, colourblind mode, screen-reader tuning, focus
// indicators, text-to-speech, channel icons, animated stickers, smooth
// scrolling, hardware acceleration, the "latest capture tech" claim, mouse
// cursor capture, QoS packet priority and the audio-subsystem picker all had a
// SettingsManager accessor and a switch here, and no reader anywhere. Their
// help text described behaviour the app does not have, so the switches are gone;
// the accessors stay untouched in SettingsManager.

/** Percent slider with a live readout, matching the other Voice & Video cards. */
@Composable
private fun PercentSliderCard(
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    label: (Float) -> String = { "${it.toInt()}%" }
) {
    val tokens = rememberCosmicTokens()
    PrefCard(title, subtitle, vertical = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label(value),
                style = MaterialTheme.typography.labelMedium.copy(
                    color = tokens.Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            )
            Spacer(Modifier.width(16.dp))
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = tokens.Accent,
                    activeTrackColor = tokens.Accent,
                    inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  APPEARANCE EXTRAS — expands the Appearance pane
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AppearanceExtrasPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Chat Display")
        val showTimestamps by settingsManager.showTimestamps.collectAsState(true)
        PrefCard("Show timestamps", "Display the time each message was sent.") {
            CosmicSwitch(checked = showTimestamps, onCheckedChange = { settingsManager.setShowTimestamps(it) })
        }
        val showRoleColors by settingsManager.showRoleColors.collectAsState(true)
        PrefCard("Show role colors", "Color usernames based on their highest server role.") {
            CosmicSwitch(checked = showRoleColors, onCheckedChange = { settingsManager.setShowRoleColors(it) })
        }

        SectionHeader("Sidebar")
        val showMemberList by settingsManager.showMemberList.collectAsState(true)
        PrefCard("Show member list", "Display the member sidebar in channels by default.") {
            CosmicSwitch(checked = showMemberList, onCheckedChange = { settingsManager.setShowMemberList(it) })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACCESSIBILITY EXTRAS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AccessibilityExtrasPane(settingsManager: SettingsManager) {
    // The Text Size slider that used to live here wrote the same `fontSize`
    // setting as Appearance > Font Size Card, under a different name and a
    // different description, so one setting had two controls. Text size has a
    // single home in Appearance now.
    //
    // Reduce Motion is the one real accessibility control, so this category must
    // never render empty. (ReduceMotionCard itself is private to
    // SettingsScreen.kt; this is the category's only content.)
    ReduceMotionToggle(settingsManager)
}

/** The Reduce Motion switch, shared by this category and the Appearance pane. */
@Composable
internal fun ReduceMotionToggle(settingsManager: SettingsManager) {
    val enabled by settingsManager.reduceMotion.collectAsState(false)
    PrefCard("Reduce Motion", "Cut animation and transitions across the app.") {
        CosmicSwitch(
            checked = enabled,
            onCheckedChange = { settingsManager.setReduceMotion(it) }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  VOICE & VIDEO FULL PANE
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun VoiceVideoFullPane(settingsManager: SettingsManager) {
    val tokens = rememberCosmicTokens()

    // The pane used to build a brand-new native WebRTC client inside a
    // LaunchedEffect(Unit) on the Compose main dispatcher and never release it.
    // The app already has one; device enumeration is native I/O, so it also
    // belongs on IO rather than the UI thread.
    val sharedClient = webrtc.LocalCallManager.current?.webRtcClient
    var audioInputs by remember { mutableStateOf(listOf("Default")) }
    var audioOutputs by remember { mutableStateOf(listOf("Default")) }
    var videoInputs by remember { mutableStateOf(listOf("Default")) }
    LaunchedEffect(sharedClient) {
        val client = sharedClient ?: return@LaunchedEffect
        val (inDevices, outDevices, camDevices) = withContext(Dispatchers.IO) {
            Triple(
                runCatching { client.getAudioInputDevices() }.getOrDefault(emptyList()),
                runCatching { client.getAudioOutputDevices() }.getOrDefault(emptyList()),
                runCatching { client.getVideoCaptureDevices() }.getOrDefault(emptyList())
            )
        }
        audioInputs = listOf("Default") + inDevices.filter { it != "Default" }
        audioOutputs = listOf("Default") + outDevices.filter { it != "Default" }
        videoInputs = listOf("Default") + camDevices.filter { it != "Default" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader("Voice Settings")
        val inputDevice by settingsManager.inputDevice.collectAsState("Default")
        // Vertical: device names are long ("Microphone (2- USB Audio Device)") and
        // there can be several of them, so the chip row outgrows the card and
        // crowds the heading out of the horizontal Row. Under the label it gets
        // the full card width and scrolls.
        PrefCard("Input Device", "Select your microphone.", vertical = true) {
            ChipGroup(options = audioInputs, selected = inputDevice, onSelect = { settingsManager.setInputDevice(it) })
        }
        val inputVolume by settingsManager.inputVolume.collectAsState(100f)
        PercentSliderCard(
            title = "Input Volume",
            subtitle = "Adjust your microphone volume.",
            value = inputVolume,
            onValueChange = { settingsManager.setInputVolume(it) },
            valueRange = 0f..200f
        )

        val outputDevice by settingsManager.outputDevice.collectAsState("Default")
        // Vertical, same reason as Input Device: speaker names are long and there
        // is usually more than one.
        PrefCard("Output Device", "Select your speakers or headphones.", vertical = true) {
            ChipGroup(options = audioOutputs, selected = outputDevice, onSelect = { settingsManager.setOutputDevice(it) })
        }
        val outputVolume by settingsManager.outputVolume.collectAsState(100f)
        PercentSliderCard(
            title = "Output Volume",
            subtitle = "Adjust how loud others are.",
            value = outputVolume,
            onValueChange = { settingsManager.setOutputVolume(it) },
            valueRange = 0f..200f
        )

        SectionHeader("Input Mode")
        val inputMode by settingsManager.inputMode.collectAsState("Voice Activity")
        PrefCard("Input Mode", "How your microphone activates.") {
            ChipGroup(
                options = listOf("Voice Activity", "Push to Talk"),
                selected = inputMode,
                onSelect = { settingsManager.setInputMode(it) }
            )
        }

        if (inputMode == "Push to Talk") {
            var showKeybindCapture by remember { mutableStateOf(false) }
            val storedKeybind by settingsManager.pushToTalkKeybind.collectAsState(PTT_DEFAULT_COMBO)
            val keybindDisplay = displayPttCombo(storedKeybind)

            PrefCard("Push to Talk Keybind", "Hold $keybindDisplay to open your mic; release to mute again.") {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tokens.Sunken,
                    modifier = Modifier.height(36.dp).clip(RoundedCornerShape(8.dp)).clickable { showKeybindCapture = true }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(keybindDisplay, color = tokens.Text_White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (showKeybindCapture) {
                PttKeybindCaptureDialog(
                    currentDisplay = keybindDisplay,
                    onCaptured = { settingsManager.setPushToTalkKeybind(it) },
                    onDismiss = { showKeybindCapture = false }
                )
            }
        }

        SectionHeader("Voice Processing")
        val orbitZero by settingsManager.orbitZeroAiNoiseSuppression.collectAsState(true)
        PrefCard("Enhanced noise suppression", "Aggressively mutes steady background noise such as fans and keyboards.") {
            CosmicSwitch(checked = orbitZero, onCheckedChange = { settingsManager.setOrbitZeroAiNoiseSuppression(it) })
        }
        val noiseSuppression by settingsManager.noiseSuppression.collectAsState(true)
        PrefCard("Standard Noise Reduction", "Traditional filter for steady hums. Skipped while enhanced suppression is on.") {
            CosmicSwitch(checked = noiseSuppression && !orbitZero, onCheckedChange = { settingsManager.setNoiseSuppression(it) })
        }
        val echoCancellation by settingsManager.echoCancellation.collectAsState(true)
        PrefCard("Echo Cancellation", "Prevents your speakers from feeding back into your mic.") {
            CosmicSwitch(checked = echoCancellation, onCheckedChange = { settingsManager.setEchoCancellation(it) })
        }
        val autoGainControl by settingsManager.autoGainControl.collectAsState(true)
        PrefCard("Automatic Gain Control", "Automatically balances your voice volume.") {
            CosmicSwitch(checked = autoGainControl, onCheckedChange = { settingsManager.setAutoGainControl(it) })
        }
        val attenuation by settingsManager.attenuation.collectAsState(0f)
        PercentSliderCard(
            title = "Ducking Strength",
            subtitle = "Lower the volume of other applications when someone speaks.",
            value = attenuation,
            onValueChange = { settingsManager.setAttenuation(it) },
            valueRange = 0f..100f
        )

        SectionHeader("Video Settings")
        val cameraDevice by settingsManager.cameraDevice.collectAsState("Default")
        // Vertical, same reason as Input/Output Device: camera names are long and
        // a machine usually has more than one.
        PrefCard("Camera", "Select which camera to use.", vertical = true) {
            ChipGroup(options = videoInputs, selected = cameraDevice, onSelect = { settingsManager.setCameraDevice(it) })
        }
        val videoBackground by settingsManager.videoBackground.collectAsState("None")
        PrefCard("Video Background", "Blur your background behind you.") {
            // "Custom Image" was offered here but only "Blur" maps to the
            // engine (see WebRtcEffects), so it is no longer listed.
            ChipGroup(
                options = listOf("None", "Blur"),
                selected = videoBackground,
                onSelect = { settingsManager.setVideoBackground(it) }
            )
        }
        val mirrorVideo by settingsManager.mirrorVideo.collectAsState(true)
        PrefCard("Mirror my video", "Flip your camera preview horizontally.") {
            CosmicSwitch(checked = mirrorVideo, onCheckedChange = { settingsManager.setMirrorVideo(it) })
        }

        SectionHeader("Screen Share")
        val screenShareQuality by settingsManager.screenShareQuality.collectAsState("1080p")
        PrefCard("Resolution", "Quality of your screen capture.") {
            ChipGroup(
                options = listOf("720p", "1080p", "1440p"),
                selected = screenShareQuality,
                onSelect = { settingsManager.setScreenShareQuality(it) }
            )
        }
        val screenShareFps by settingsManager.screenShareFps.collectAsState(30)
        // 60 is deliberately not offered. Measured on this machine: a 1080p I420
        // share at 60 fps is ~186 MB/s of frame conversion, and the audio mixer --
        // which runs a 10 ms tick and cannot be late -- lost to it, collapsing
        // from `starved=10 qDepth=3360` to `starved=283 qDepth=480` and dropping
        // playback chunks. Other people's voices stuttered the moment you shared.
        // A shared window is text and static UI; it gains nothing from 60 fps, and
        // 30 is what Discord and Element cap at. The publish path coerces to this
        // ceiling too, so a stale stored 60 cannot sneak past either.
        PrefCard("Frame Rate", "Smoothness of your screen capture. 30 is the maximum: higher starves call audio.") {
            ChipGroup(
                options = listOf("15", "30"),
                selected = screenShareFps.coerceIn(5, 30).toString(),
                onSelect = { settingsManager.setScreenShareFps(it.toInt()) }
            )
        }

        SectionHeader("Advanced")
        val audioBitrate by settingsManager.audioBitrate.collectAsState(64f)
        PercentSliderCard(
            title = "Audio Bitrate",
            subtitle = "Target audio bitrate for group calls.",
            value = audioBitrate,
            onValueChange = { settingsManager.setAudioBitrate(it) },
            valueRange = 8f..384f,
            label = { "${it.toInt()} kbps" }
        )
    }
}
