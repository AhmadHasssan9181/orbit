package ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import repository.SettingsManager
import ui.theme.rememberCosmicTokens

// ═══════════════════════════════════════════════════════════════════
//  APPEARANCE EXTRAS — expands the Appearance pane
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AppearanceExtrasPane(settingsManager: SettingsManager) {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Chat Display")
        val messageDensity by settingsManager.messageDensity.collectAsState("Comfortable")
        PrefCard("Message Display", "How messages are visually laid out.") {
            ChipGroup(
                options = listOf("Compact", "Comfortable", "Cozy"),
                selected = messageDensity,
                onSelect = { settingsManager.setMessageDensity(it) }
            )
        }
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
        val channelIcons by settingsManager.channelIcons.collectAsState(true)
        PrefCard("Channel icons", "Show icons next to channel names (text, voice, etc).") {
            CosmicSwitch(checked = channelIcons, onCheckedChange = { settingsManager.setChannelIcons(it) })
        }

        SectionHeader("Animations")
        val animatedStickers by settingsManager.animatedStickers.collectAsState(true)
        PrefCard("Animated stickers", "Play sticker animations in chat.") {
            CosmicSwitch(checked = animatedStickers, onCheckedChange = { settingsManager.setAnimatedStickers(it) })
        }
        val smoothScroll by settingsManager.smoothScroll.collectAsState(true)
        PrefCard("Smooth scrolling", "Animate scrolling when navigating channels and messages.") {
            CosmicSwitch(checked = smoothScroll, onCheckedChange = { settingsManager.setSmoothScroll(it) })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  ACCESSIBILITY EXTRAS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun AccessibilityExtrasPane(settingsManager: SettingsManager) {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Visual")
        val colorSaturation by settingsManager.colorSaturation.collectAsState(100f)
        PrefCard("Color saturation", "Adjust the intensity of colors throughout the app.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${colorSaturation.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = colorSaturation,
                    onValueChange = { settingsManager.setColorSaturation(it) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }
        val highContrast by settingsManager.highContrast.collectAsState(false)
        PrefCard("High contrast mode", "Increase contrast between text and backgrounds.") {
            CosmicSwitch(checked = highContrast, onCheckedChange = { settingsManager.setHighContrast(it) })
        }
        val colorblindMode by settingsManager.colorblindMode.collectAsState("None")
        PrefCard("Colorblind mode", "Adjust colors for different types of color vision deficiency.") {
            ChipGroup(
                options = listOf("None", "Protanopia", "Deuteranopia", "Tritanopia"),
                selected = colorblindMode,
                onSelect = { settingsManager.setColorblindMode(it) }
            )
        }

        SectionHeader("Screen Reader")
        val screenReader by settingsManager.screenReader.collectAsState(false)
        PrefCard("Screen reader optimizations", "Enhance the UI for screen reader compatibility.") {
            CosmicSwitch(checked = screenReader, onCheckedChange = { settingsManager.setScreenReader(it) })
        }
        val focusIndicator by settingsManager.focusIndicator.collectAsState(true)
        PrefCard("Focus indicators", "Show visible outlines around focused elements for keyboard navigation.") {
            CosmicSwitch(checked = focusIndicator, onCheckedChange = { settingsManager.setFocusIndicator(it) })
        }

        SectionHeader("Audio")
        val textToSpeech by settingsManager.textToSpeech.collectAsState(false)
        PrefCard("Text-to-speech", "Read incoming messages aloud using your system TTS engine.") {
            CosmicSwitch(checked = textToSpeech, onCheckedChange = { settingsManager.setTextToSpeech(it) })
        }
        val ttsRate by settingsManager.ttsRate.collectAsState("Normal")
        PrefCard("TTS speed", "How fast text-to-speech reads messages.") {
            ChipGroup(
                options = listOf("Slow", "Normal", "Fast"),
                selected = ttsRate,
                onSelect = { settingsManager.setTtsRate(it) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  VOICE & VIDEO FULL PANE
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun VoiceVideoFullPane(settingsManager: SettingsManager) {
    val tokens = rememberCosmicTokens()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Input Device")
        val inputDevice by settingsManager.inputDevice.collectAsState("Default")
        PrefCard("Microphone", "Select which microphone to use for voice chat.") {
            ChipGroup(
                options = listOf("Default", "Headset", "Webcam"),
                selected = inputDevice,
                onSelect = { settingsManager.setInputDevice(it) }
            )
        }
        val inputVolume by settingsManager.inputVolume.collectAsState(75f)
        PrefCard("Input Volume", "Adjust how loud your microphone is to others.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${inputVolume.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = inputVolume,
                    onValueChange = { settingsManager.setInputVolume(it) },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }

        SectionHeader("Output Device")
        val outputDevice by settingsManager.outputDevice.collectAsState("Default")
        PrefCard("Speakers", "Select which output device to use for audio.") {
            ChipGroup(
                options = listOf("Default", "Headphones", "Speakers"),
                selected = outputDevice,
                onSelect = { settingsManager.setOutputDevice(it) }
            )
        }
        val outputVolume by settingsManager.outputVolume.collectAsState(100f)
        PrefCard("Output Volume", "Adjust the volume of other people's voices.") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${outputVolume.toInt()}%", style = MaterialTheme.typography.labelMedium.copy(color = tokens.Accent, fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = outputVolume,
                    onValueChange = { settingsManager.setOutputVolume(it) },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(thumbColor = tokens.Accent, activeTrackColor = tokens.Accent, inactiveTrackColor = tokens.Text_Muted.copy(alpha = 0.3f)),
                    modifier = Modifier.width(100.dp)
                )
            }
        }

        SectionHeader("Voice Processing")
        val inputMode by settingsManager.inputMode.collectAsState("Voice Activity")
        PrefCard("Input Mode", "How your microphone activates.") {
            ChipGroup(
                options = listOf("Voice Activity", "Push to Talk"),
                selected = inputMode,
                onSelect = { settingsManager.setInputMode(it) }
            )
        }
        val noiseSuppression by settingsManager.noiseSuppression.collectAsState(true)
        PrefCard("Noise Suppression", "Filter out background noise like fans, keyboards, and traffic.") {
            CosmicSwitch(checked = noiseSuppression, onCheckedChange = { settingsManager.setNoiseSuppression(it) })
        }
        val echoCancellation by settingsManager.echoCancellation.collectAsState(true)
        PrefCard("Echo Cancellation", "Prevent your speakers from feeding back into your microphone.") {
            CosmicSwitch(checked = echoCancellation, onCheckedChange = { settingsManager.setEchoCancellation(it) })
        }
        val autoGainControl by settingsManager.autoGainControl.collectAsState(true)
        PrefCard("Automatic Gain Control", "Automatically normalize your microphone volume.") {
            CosmicSwitch(checked = autoGainControl, onCheckedChange = { settingsManager.setAutoGainControl(it) })
        }

        SectionHeader("Video")
        val videoQuality by settingsManager.videoQuality.collectAsState("1080p (60fps)")
        PrefCard("Video Quality", "Higher quality uses more bandwidth.") {
            ChipGroup(
                options = listOf("720p", "1080p (60fps)", "1440p"),
                selected = videoQuality,
                onSelect = { settingsManager.setVideoQuality(it) }
            )
        }
        val cameraDevice by settingsManager.cameraDevice.collectAsState("Default")
        PrefCard("Camera", "Select which camera to use for video calls.") {
            ChipGroup(
                options = listOf("Default", "Webcam", "Virtual"),
                selected = cameraDevice,
                onSelect = { settingsManager.setCameraDevice(it) }
            )
        }
        val mirrorVideo by settingsManager.mirrorVideo.collectAsState(true)
        PrefCard("Mirror my video", "Flip your camera preview horizontally.") {
            CosmicSwitch(checked = mirrorVideo, onCheckedChange = { settingsManager.setMirrorVideo(it) })
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
        val audioSubsystem by settingsManager.audioSubsystem.collectAsState("Standard")
        PrefCard("Audio Subsystem", "Advanced: Change the audio processing backend.") {
            ChipGroup(
                options = listOf("Standard", "Experimental", "Legacy"),
                selected = audioSubsystem,
                onSelect = { settingsManager.setAudioSubsystem(it) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  NOTIFICATIONS EXTRAS
// ═══════════════════════════════════════════════════════════════════

@Composable
internal fun NotificationsExtrasPane(settingsManager: SettingsManager) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Desktop Notifications")
        val desktopNotifs by settingsManager.desktopNotifs.collectAsState(true)
        PrefCard("Enable desktop notifications", "Show system notification popups for new messages.") {
            CosmicSwitch(checked = desktopNotifs, onCheckedChange = { settingsManager.setDesktopNotifs(it) })
        }
        val desktopPreview by settingsManager.desktopPreview.collectAsState(true)
        PrefCard("Show message preview", "Display the message content in desktop notification banners.") {
            CosmicSwitch(checked = desktopPreview, onCheckedChange = { settingsManager.setDesktopPreview(it) })
        }

        SectionHeader("In-App Notifications")
        val inAppSounds by settingsManager.inAppSounds.collectAsState(true)
        PrefCard("In-app notification sounds", "Play sounds when receiving messages while Orbit is focused.") {
            CosmicSwitch(checked = inAppSounds, onCheckedChange = { settingsManager.setInAppSounds(it) })
        }
        val unreadBadge by settingsManager.unreadBadge.collectAsState(true)
        PrefCard("Unread badge", "Show unread count badges on channels and servers.") {
            CosmicSwitch(checked = unreadBadge, onCheckedChange = { settingsManager.setUnreadBadge(it) })
        }

        SectionHeader("Sounds")
        val joinLeaveSound by settingsManager.joinLeaveSound.collectAsState(true)
        PrefCard("User join/leave sounds", "Play a sound when users join or leave a voice channel.") {
            CosmicSwitch(checked = joinLeaveSound, onCheckedChange = { settingsManager.setJoinLeaveSound(it) })
        }
        val messageSound by settingsManager.messageSound.collectAsState(true)
        PrefCard("New message sound", "Play a sound for every incoming message.") {
            CosmicSwitch(checked = messageSound, onCheckedChange = { settingsManager.setMessageSound(it) })
        }
        val callRingtone by settingsManager.callRingtone.collectAsState(true)
        PrefCard("Incoming call ringtone", "Play a ringtone when someone calls you.") {
            CosmicSwitch(checked = callRingtone, onCheckedChange = { settingsManager.setCallRingtone(it) })
        }
        val deafenSound by settingsManager.deafenSound.collectAsState(true)
        PrefCard("Mute/Deafen confirmation", "Play a sound when you mute or deafen yourself.") {
            CosmicSwitch(checked = deafenSound, onCheckedChange = { settingsManager.setDeafenSound(it) })
        }

        SectionHeader("Do Not Disturb")
        val doNotDisturb by settingsManager.doNotDisturb.collectAsState(false)
        PrefCard("Do Not Disturb", "Suppress all notifications. You'll still see unread indicators.") {
            CosmicSwitch(checked = doNotDisturb, onCheckedChange = { settingsManager.setDoNotDisturb(it) })
        }
        val dndSchedule by settingsManager.dndSchedule.collectAsState(false)
        PrefCard("Scheduled DND", "Automatically enable DND during certain hours (e.g., 11 PM – 7 AM).") {
            CosmicSwitch(checked = dndSchedule, onCheckedChange = { settingsManager.setDndSchedule(it) })
        }
    }
}
