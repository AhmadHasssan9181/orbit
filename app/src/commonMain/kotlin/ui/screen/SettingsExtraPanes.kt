package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
//  APPEARANCE EXTRAS — expands the Appearance pane
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AppearanceExtrasPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Chat Display")
        var density by remember { mutableStateOf("Comfortable") }
        PrefCard("Message Display", "How messages are visually laid out.") {
            ChipGroup(
                options = listOf("Compact", "Comfortable", "Cozy"),
                selected = density,
                onSelect = { density = it }
            )
        }
        var showTimestamps by remember { mutableStateOf(true) }
        PrefCard("Show timestamps", "Display the time each message was sent.") {
            CosmicSwitch(checked = showTimestamps, onCheckedChange = { showTimestamps = it })
        }
        var showRoleColors by remember { mutableStateOf(true) }
        PrefCard("Show role colors", "Color usernames based on their highest server role.") {
            CosmicSwitch(checked = showRoleColors, onCheckedChange = { showRoleColors = it })
        }

        SectionHeader("Sidebar")
        var showMemberList by remember { mutableStateOf(true) }
        PrefCard("Show member list", "Display the member sidebar in channels by default.") {
            CosmicSwitch(checked = showMemberList, onCheckedChange = { showMemberList = it })
        }
        var channelIcons by remember { mutableStateOf(true) }
        PrefCard("Channel icons", "Show icons next to channel names (text, voice, etc).") {
            CosmicSwitch(checked = channelIcons, onCheckedChange = { channelIcons = it })
        }

        SectionHeader("Animations")
        var animatedStickers by remember { mutableStateOf(true) }
        PrefCard("Animated stickers", "Play sticker animations in chat.") {
            CosmicSwitch(checked = animatedStickers, onCheckedChange = { animatedStickers = it })
        }
        var smoothScroll by remember { mutableStateOf(true) }
        PrefCard("Smooth scrolling", "Animate scrolling when navigating channels and messages.") {
            CosmicSwitch(checked = smoothScroll, onCheckedChange = { smoothScroll = it })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACCESSIBILITY EXTRAS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AccessibilityExtrasPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Visual")
        var saturation by remember { mutableStateOf(100f) }
        PrefCard("Color saturation", "Adjust the intensity of colors throughout the app.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${saturation.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }
        var highContrast by remember { mutableStateOf(false) }
        PrefCard("High contrast mode", "Increase contrast between text and backgrounds.") {
            CosmicSwitch(checked = highContrast, onCheckedChange = { highContrast = it })
        }
        var colorblind by remember { mutableStateOf("None") }
        PrefCard("Colorblind mode", "Adjust colors for different types of color vision deficiency.") {
            ChipGroup(
                options = listOf("None", "Protanopia", "Deuteranopia", "Tritanopia"),
                selected = colorblind,
                onSelect = { colorblind = it }
            )
        }

        SectionHeader("Screen Reader")
        var screenReader by remember { mutableStateOf(false) }
        PrefCard("Screen reader optimizations", "Enhance the UI for screen reader compatibility.") {
            CosmicSwitch(checked = screenReader, onCheckedChange = { screenReader = it })
        }
        var focusIndicator by remember { mutableStateOf(true) }
        PrefCard("Focus indicators", "Show visible outlines around focused elements for keyboard navigation.") {
            CosmicSwitch(checked = focusIndicator, onCheckedChange = { focusIndicator = it })
        }

        SectionHeader("Audio")
        var textToSpeech by remember { mutableStateOf(false) }
        PrefCard("Text-to-speech", "Read incoming messages aloud using your system TTS engine.") {
            CosmicSwitch(checked = textToSpeech, onCheckedChange = { textToSpeech = it })
        }
        var ttsRate by remember { mutableStateOf("Normal") }
        PrefCard("TTS speed", "How fast text-to-speech reads messages.") {
            ChipGroup(
                options = listOf("Slow", "Normal", "Fast"),
                selected = ttsRate,
                onSelect = { ttsRate = it }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  VOICE & VIDEO FULL PANE
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun VoiceVideoFullPane() {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Input Device")
        var inputDevice by remember { mutableStateOf("Default") }
        PrefCard("Microphone", "Select which microphone to use for voice chat.") {
            ChipGroup(
                options = listOf("Default", "Headset", "Webcam"),
                selected = inputDevice,
                onSelect = { inputDevice = it }
            )
        }
        var inputVolume by remember { mutableStateOf(75f) }
        PrefCard("Input Volume", "Adjust how loud your microphone is to others.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${inputVolume.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = inputVolume,
                    onValueChange = { inputVolume = it },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }

        SectionHeader("Output Device")
        var outputDevice by remember { mutableStateOf("Default") }
        PrefCard("Speakers", "Select which output device to use for audio.") {
            ChipGroup(
                options = listOf("Default", "Headphones", "Speakers"),
                selected = outputDevice,
                onSelect = { outputDevice = it }
            )
        }
        var outputVolume by remember { mutableStateOf(100f) }
        PrefCard("Output Volume", "Adjust the volume of other people's voices.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${outputVolume.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = outputVolume,
                    onValueChange = { outputVolume = it },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }

        SectionHeader("Voice Processing")
        var inputMode by remember { mutableStateOf("Voice Activity") }
        PrefCard("Input Mode", "How your microphone activates.") {
            ChipGroup(
                options = listOf("Voice Activity", "Push to Talk"),
                selected = inputMode,
                onSelect = { inputMode = it }
            )
        }
        var noiseSuppression by remember { mutableStateOf(true) }
        PrefCard("Noise Suppression", "Filter out background noise like fans, keyboards, and traffic.") {
            CosmicSwitch(checked = noiseSuppression, onCheckedChange = { noiseSuppression = it })
        }
        var echoCancellation by remember { mutableStateOf(true) }
        PrefCard("Echo Cancellation", "Prevent your speakers from feeding back into your microphone.") {
            CosmicSwitch(checked = echoCancellation, onCheckedChange = { echoCancellation = it })
        }
        var autoGainControl by remember { mutableStateOf(true) }
        PrefCard("Automatic Gain Control", "Automatically normalize your microphone volume.") {
            CosmicSwitch(checked = autoGainControl, onCheckedChange = { autoGainControl = it })
        }

        SectionHeader("Video")
        var videoQuality by remember { mutableStateOf("1080p (60fps)") }
        PrefCard("Video Quality", "Higher quality uses more bandwidth.") {
            ChipGroup(
                options = listOf("720p", "1080p (60fps)", "1440p"),
                selected = videoQuality,
                onSelect = { videoQuality = it }
            )
        }
        var camera by remember { mutableStateOf("Default") }
        PrefCard("Camera", "Select which camera to use for video calls.") {
            ChipGroup(
                options = listOf("Default", "Webcam", "Virtual"),
                selected = camera,
                onSelect = { camera = it }
            )
        }
        var mirrorVideo by remember { mutableStateOf(true) }
        PrefCard("Mirror my video", "Flip your camera preview horizontally.") {
            CosmicSwitch(checked = mirrorVideo, onCheckedChange = { mirrorVideo = it })
        }

        SectionHeader("Screen Share")
        PrefCard("Stream Codec", "Encoding format for screen share") {
            Text("H.264", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
        }
        PrefCard("Network Latency", "Real-time connection quality") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF34D399)))
                Text("~120ms", style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFF34D399), fontWeight = FontWeight.Bold))
            }
        }

        SectionHeader("Audio Subsystem")
        var audioSubsystem by remember { mutableStateOf("Standard") }
        PrefCard("Audio Subsystem", "Advanced: Change the audio processing backend.") {
            ChipGroup(
                options = listOf("Standard", "Experimental", "Legacy"),
                selected = audioSubsystem,
                onSelect = { audioSubsystem = it }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  NOTIFICATIONS EXTRAS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun NotificationsExtrasPane() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Desktop Notifications")
        var desktopNotifs by remember { mutableStateOf(true) }
        PrefCard("Enable desktop notifications", "Show system notification popups for new messages.") {
            CosmicSwitch(checked = desktopNotifs, onCheckedChange = { desktopNotifs = it })
        }
        var desktopPreview by remember { mutableStateOf(true) }
        PrefCard("Show message preview", "Display the message content in desktop notification banners.") {
            CosmicSwitch(checked = desktopPreview, onCheckedChange = { desktopPreview = it })
        }

        SectionHeader("In-App Notifications")
        var inAppSounds by remember { mutableStateOf(true) }
        PrefCard("In-app notification sounds", "Play sounds when receiving messages while Orbit is focused.") {
            CosmicSwitch(checked = inAppSounds, onCheckedChange = { inAppSounds = it })
        }
        var unreadBadge by remember { mutableStateOf(true) }
        PrefCard("Unread badge", "Show unread count badges on channels and servers.") {
            CosmicSwitch(checked = unreadBadge, onCheckedChange = { unreadBadge = it })
        }

        SectionHeader("Sounds")
        var joinLeaveSound by remember { mutableStateOf(true) }
        PrefCard("User join/leave sounds", "Play a sound when users join or leave a voice channel.") {
            CosmicSwitch(checked = joinLeaveSound, onCheckedChange = { joinLeaveSound = it })
        }
        var messageSound by remember { mutableStateOf(true) }
        PrefCard("New message sound", "Play a sound for every incoming message.") {
            CosmicSwitch(checked = messageSound, onCheckedChange = { messageSound = it })
        }
        var callRingtone by remember { mutableStateOf(true) }
        PrefCard("Incoming call ringtone", "Play a ringtone when someone calls you.") {
            CosmicSwitch(checked = callRingtone, onCheckedChange = { callRingtone = it })
        }
        var deafenSound by remember { mutableStateOf(true) }
        PrefCard("Mute/Deafen confirmation", "Play a sound when you mute or deafen yourself.") {
            CosmicSwitch(checked = deafenSound, onCheckedChange = { deafenSound = it })
        }

        SectionHeader("Do Not Disturb")
        var dnd by remember { mutableStateOf(false) }
        PrefCard("Do Not Disturb", "Suppress all notifications. You'll still see unread indicators.") {
            CosmicSwitch(checked = dnd, onCheckedChange = { dnd = it })
        }
        var dndSchedule by remember { mutableStateOf(false) }
        PrefCard("Scheduled DND", "Automatically enable DND during certain hours (e.g., 11 PM – 7 AM).") {
            CosmicSwitch(checked = dndSchedule, onCheckedChange = { dndSchedule = it })
        }
    }
}
