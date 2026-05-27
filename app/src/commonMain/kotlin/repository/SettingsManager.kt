package repository

import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.Settings
import com.russhwolf.settings.coroutines.toFlowSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ui.theme.Theme

class SettingsManager {
    private val settings = Settings()
    private val flowSettings = (settings as ObservableSettings).toFlowSettings(Dispatchers.Default)

    val activeTheme: Flow<Theme> = flowSettings.getStringFlow("activeTheme", Theme.Default.name)
        .map { 
            try {
                Theme.valueOf(it)
            } catch (e: Exception) {
                Theme.Default
            }
        }

    fun setTheme(theme: Theme) = settings.putString("activeTheme", theme.name)

    // Helper properties mapping key, default value and type
    val isLiteMode = flowSettings.getBooleanFlow("isLiteMode", false)
    fun setLiteMode(value: Boolean) = settings.putBoolean("isLiteMode", value)

    val messageDensity = flowSettings.getStringFlow("messageDensity", "Comfortable")
    fun setMessageDensity(value: String) = settings.putString("messageDensity", value)

    val fontSize = flowSettings.getFloatFlow("fontSize", 50f)
    fun setFontSize(value: Float) = settings.putFloat("fontSize", value)

    val reduceMotion = flowSettings.getBooleanFlow("reduceMotion", false)
    fun setReduceMotion(value: Boolean) = settings.putBoolean("reduceMotion", value)

    val mentionsOnly = flowSettings.getBooleanFlow("mentionsOnly", true)
    fun setMentionsOnly(value: Boolean) = settings.putBoolean("mentionsOnly", value)

    val notificationSound = flowSettings.getStringFlow("notificationSound", "Chime")
    fun setNotificationSound(value: String) = settings.putString("notificationSound", value)

    val chatWallpaper = flowSettings.getStringFlow("chatWallpaper", "auto")
    fun setChatWallpaper(value: String) = settings.putString("chatWallpaper", value)

    // --- Appearance & Chat ---
    val showTimestamps = flowSettings.getBooleanFlow("showTimestamps", true)
    fun setShowTimestamps(value: Boolean) = settings.putBoolean("showTimestamps", value)
    
    val showRoleColors = flowSettings.getBooleanFlow("showRoleColors", true)
    fun setShowRoleColors(value: Boolean) = settings.putBoolean("showRoleColors", value)
    
    val showMemberList = flowSettings.getBooleanFlow("showMemberList", true)
    fun setShowMemberList(value: Boolean) = settings.putBoolean("showMemberList", value)
    
    val channelIcons = flowSettings.getBooleanFlow("channelIcons", true)
    fun setChannelIcons(value: Boolean) = settings.putBoolean("channelIcons", value)
    
    val animatedStickers = flowSettings.getBooleanFlow("animatedStickers", true)
    fun setAnimatedStickers(value: Boolean) = settings.putBoolean("animatedStickers", value)
    
    val smoothScroll = flowSettings.getBooleanFlow("smoothScroll", true)
    fun setSmoothScroll(value: Boolean) = settings.putBoolean("smoothScroll", value)
    
    val inlineMedia = flowSettings.getBooleanFlow("inlineMedia", true)
    fun setInlineMedia(value: Boolean) = settings.putBoolean("inlineMedia", value)
    
    val linkPreviews = flowSettings.getBooleanFlow("linkPreviews", true)
    fun setLinkPreviews(value: Boolean) = settings.putBoolean("linkPreviews", value)
    
    val showEmbeds = flowSettings.getBooleanFlow("showEmbeds", true)
    fun setShowEmbeds(value: Boolean) = settings.putBoolean("showEmbeds", value)
    
    val gifAutoplay = flowSettings.getBooleanFlow("gifAutoplay", true)
    fun setGifAutoplay(value: Boolean) = settings.putBoolean("gifAutoplay", value)
    
    val jumboEmojis = flowSettings.getBooleanFlow("jumboEmojis", true)
    fun setJumboEmojis(value: Boolean) = settings.putBoolean("jumboEmojis", value)
    
    val animatedEmoji = flowSettings.getBooleanFlow("animatedEmoji", true)
    fun setAnimatedEmoji(value: Boolean) = settings.putBoolean("animatedEmoji", value)
    
    val stickerSuggestions = flowSettings.getBooleanFlow("stickerSuggestions", true)
    fun setStickerSuggestions(value: Boolean) = settings.putBoolean("stickerSuggestions", value)
    
    val emojiSuggestions = flowSettings.getBooleanFlow("emojiSuggestions", true)
    fun setEmojiSuggestions(value: Boolean) = settings.putBoolean("emojiSuggestions", value)
    
    val compactMode = flowSettings.getBooleanFlow("compactMode", false)
    fun setCompactMode(value: Boolean) = settings.putBoolean("compactMode", value)
    
    val enterSend = flowSettings.getBooleanFlow("enterSend", true)
    fun setEnterSend(value: Boolean) = settings.putBoolean("enterSend", value)
    
    val spellcheck = flowSettings.getBooleanFlow("spellcheck", true)
    fun setSpellcheck(value: Boolean) = settings.putBoolean("spellcheck", value)

    // --- Accessibility ---
    val colorSaturation = flowSettings.getFloatFlow("colorSaturation", 100f)
    fun setColorSaturation(value: Float) = settings.putFloat("colorSaturation", value)
    
    val highContrast = flowSettings.getBooleanFlow("highContrast", false)
    fun setHighContrast(value: Boolean) = settings.putBoolean("highContrast", value)
    
    val colorblindMode = flowSettings.getStringFlow("colorblindMode", "None")
    fun setColorblindMode(value: String) = settings.putString("colorblindMode", value)
    
    val screenReader = flowSettings.getBooleanFlow("screenReader", false)
    fun setScreenReader(value: Boolean) = settings.putBoolean("screenReader", value)
    
    val focusIndicator = flowSettings.getBooleanFlow("focusIndicator", true)
    fun setFocusIndicator(value: Boolean) = settings.putBoolean("focusIndicator", value)
    
    val textToSpeech = flowSettings.getBooleanFlow("textToSpeech", false)
    fun setTextToSpeech(value: Boolean) = settings.putBoolean("textToSpeech", value)
    
    val ttsRate = flowSettings.getStringFlow("ttsRate", "Normal")
    fun setTtsRate(value: String) = settings.putString("ttsRate", value)

    // --- Voice & Video ---
    val inputDevice = flowSettings.getStringFlow("inputDevice", "Default")
    fun setInputDevice(value: String) = settings.putString("inputDevice", value)
    
    val inputVolume = flowSettings.getFloatFlow("inputVolume", 75f)
    fun setInputVolume(value: Float) = settings.putFloat("inputVolume", value)
    
    val outputDevice = flowSettings.getStringFlow("outputDevice", "Default")
    fun setOutputDevice(value: String) = settings.putString("outputDevice", value)
    
    val outputVolume = flowSettings.getFloatFlow("outputVolume", 100f)
    fun setOutputVolume(value: Float) = settings.putFloat("outputVolume", value)
    
    val inputMode = flowSettings.getStringFlow("inputMode", "Voice Activity")
    fun setInputMode(value: String) = settings.putString("inputMode", value)
    
    val voiceSensitivity = flowSettings.getFloatFlow("voiceSensitivity", -50f)
    fun setVoiceSensitivity(value: Float) = settings.putFloat("voiceSensitivity", value)
    
    val pushToTalkKeybind = flowSettings.getStringFlow("pushToTalkKeybind", "Unassigned")
    fun setPushToTalkKeybind(value: String) = settings.putString("pushToTalkKeybind", value)
    
    val orbitZeroAiNoiseSuppression = flowSettings.getBooleanFlow("orbitZeroAiNoiseSuppression", true)
    fun setOrbitZeroAiNoiseSuppression(value: Boolean) = settings.putBoolean("orbitZeroAiNoiseSuppression", value)
    
    val noiseSuppression = flowSettings.getBooleanFlow("noiseSuppression", true)
    fun setNoiseSuppression(value: Boolean) = settings.putBoolean("noiseSuppression", value)
    
    val echoCancellation = flowSettings.getBooleanFlow("echoCancellation", true)
    fun setEchoCancellation(value: Boolean) = settings.putBoolean("echoCancellation", value)
    
    val autoGainControl = flowSettings.getBooleanFlow("autoGainControl", true)
    fun setAutoGainControl(value: Boolean) = settings.putBoolean("autoGainControl", value)
    
    val attenuation = flowSettings.getFloatFlow("attenuation", 0f)
    fun setAttenuation(value: Float) = settings.putFloat("attenuation", value)
    
    val videoQuality = flowSettings.getStringFlow("videoQuality", "1080p (60fps)")
    fun setVideoQuality(value: String) = settings.putString("videoQuality", value)
    
    val cameraDevice = flowSettings.getStringFlow("cameraDevice", "Default")
    fun setCameraDevice(value: String) = settings.putString("cameraDevice", value)
    
    val mirrorVideo = flowSettings.getBooleanFlow("mirrorVideo", true)
    fun setMirrorVideo(value: Boolean) = settings.putBoolean("mirrorVideo", value)
    

    
    val videoBackground = flowSettings.getStringFlow("videoBackground", "None")
    fun setVideoBackground(value: String) = settings.putString("videoBackground", value)
    
    // Screen Share Settings
    val screenShareQuality = flowSettings.getStringFlow("screenShareQuality", "1080p")
    fun setScreenShareQuality(value: String) = settings.putString("screenShareQuality", value)
    
    val screenShareFps = flowSettings.getIntFlow("screenShareFps", 30)
    fun setScreenShareFps(value: Int) = settings.putInt("screenShareFps", value)
    
    val latestScreenCaptureTech = flowSettings.getBooleanFlow("latestScreenCaptureTech", true)
    fun setLatestScreenCaptureTech(value: Boolean) = settings.putBoolean("latestScreenCaptureTech", value)
    
    val captureMouseCursor = flowSettings.getBooleanFlow("captureMouseCursor", true)
    fun setCaptureMouseCursor(value: Boolean) = settings.putBoolean("captureMouseCursor", value)
    
    // QoS & Advanced Audio
    val qosHighPacketPriority = flowSettings.getBooleanFlow("qosHighPacketPriority", false)
    fun setQosHighPacketPriority(value: Boolean) = settings.putBoolean("qosHighPacketPriority", value)
    
    val audioBitrate = flowSettings.getFloatFlow("audioBitrate", 64f)
    fun setAudioBitrate(value: Float) = settings.putFloat("audioBitrate", value)
    
    val audioSubsystem = flowSettings.getStringFlow("audioSubsystem", "Standard")
    fun setAudioSubsystem(value: String) = settings.putString("audioSubsystem", value)

    // --- Notifications ---
    val desktopNotifs = flowSettings.getBooleanFlow("desktopNotifs", true)
    fun setDesktopNotifs(value: Boolean) = settings.putBoolean("desktopNotifs", value)
    
    val desktopPreview = flowSettings.getBooleanFlow("desktopPreview", true)
    fun setDesktopPreview(value: Boolean) = settings.putBoolean("desktopPreview", value)
    
    val inAppSounds = flowSettings.getBooleanFlow("inAppSounds", true)
    fun setInAppSounds(value: Boolean) = settings.putBoolean("inAppSounds", value)
    
    val unreadBadge = flowSettings.getBooleanFlow("unreadBadge", true)
    fun setUnreadBadge(value: Boolean) = settings.putBoolean("unreadBadge", value)
    
    val joinLeaveSound = flowSettings.getBooleanFlow("joinLeaveSound", true)
    fun setJoinLeaveSound(value: Boolean) = settings.putBoolean("joinLeaveSound", value)
    
    val messageSound = flowSettings.getBooleanFlow("messageSound", true)
    fun setMessageSound(value: Boolean) = settings.putBoolean("messageSound", value)
    
    val callRingtone = flowSettings.getBooleanFlow("callRingtone", true)
    fun setCallRingtone(value: Boolean) = settings.putBoolean("callRingtone", value)
    
    val deafenSound = flowSettings.getBooleanFlow("deafenSound", true)
    fun setDeafenSound(value: Boolean) = settings.putBoolean("deafenSound", value)
    
    val doNotDisturb = flowSettings.getBooleanFlow("doNotDisturb", false)
    fun setDoNotDisturb(value: Boolean) = settings.putBoolean("doNotDisturb", value)
    
    val dndSchedule = flowSettings.getBooleanFlow("dndSchedule", false)
    fun setDndSchedule(value: Boolean) = settings.putBoolean("dndSchedule", value)

    // --- Language & Time ---
    val appLanguage = flowSettings.getStringFlow("appLanguage", "English (US)")
    fun setAppLanguage(value: String) = settings.putString("appLanguage", value)
    
    val spellcheckLang = flowSettings.getStringFlow("spellcheckLang", "English")
    fun setSpellcheckLang(value: String) = settings.putString("spellcheckLang", value)
    
    val use24HourClock = flowSettings.getBooleanFlow("use24HourClock", false)
    fun setUse24HourClock(value: Boolean) = settings.putBoolean("use24HourClock", value)
    
    val relativeTime = flowSettings.getBooleanFlow("relativeTime", true)
    fun setRelativeTime(value: Boolean) = settings.putBoolean("relativeTime", value)
    
    val dateFormat = flowSettings.getStringFlow("dateFormat", "DD/MM/YYYY")
    fun setDateFormat(value: String) = settings.putString("dateFormat", value)

    // --- Windows ---
    val openOnStartup = flowSettings.getBooleanFlow("openOnStartup", true)
    fun setOpenOnStartup(value: Boolean) = settings.putBoolean("openOnStartup", value)
    
    val startMinimized = flowSettings.getBooleanFlow("startMinimized", false)
    fun setStartMinimized(value: Boolean) = settings.putBoolean("startMinimized", value)
    
    val minimizeToTray = flowSettings.getBooleanFlow("minimizeToTray", true)
    fun setMinimizeToTray(value: Boolean) = settings.putBoolean("minimizeToTray", value)
    
    val alwaysOnTop = flowSettings.getBooleanFlow("alwaysOnTop", false)
    fun setAlwaysOnTop(value: Boolean) = settings.putBoolean("alwaysOnTop", value)
    
    val taskbarBadge = flowSettings.getBooleanFlow("taskbarBadge", true)
    fun setTaskbarBadge(value: Boolean) = settings.putBoolean("taskbarBadge", value)
    
    val flashTaskbar = flowSettings.getBooleanFlow("flashTaskbar", true)
    fun setFlashTaskbar(value: Boolean) = settings.putBoolean("flashTaskbar", value)
    
    val autoUpdate = flowSettings.getBooleanFlow("autoUpdate", true)
    fun setAutoUpdate(value: Boolean) = settings.putBoolean("autoUpdate", value)

    // --- Advanced ---
    val developerMode = flowSettings.getBooleanFlow("developerMode", false)
    fun setDeveloperMode(value: Boolean) = settings.putBoolean("developerMode", value)
    
    val debugLogging = flowSettings.getBooleanFlow("debugLogging", false)
    fun setDebugLogging(value: Boolean) = settings.putBoolean("debugLogging", value)
    
    val hardwareAcceleration = flowSettings.getBooleanFlow("hardwareAcceleration", true)
    fun setHardwareAcceleration(value: Boolean) = settings.putBoolean("hardwareAcceleration", value)
    
    val reducedBandwidth = flowSettings.getBooleanFlow("reducedBandwidth", false)
    fun setReducedBandwidth(value: Boolean) = settings.putBoolean("reducedBandwidth", value)
    
    val betaTestMode = flowSettings.getBooleanFlow("betaTestMode", false)
    fun setBetaTestMode(value: Boolean) = settings.putBoolean("betaTestMode", value)
    
    val newUIPreview = flowSettings.getBooleanFlow("newUIPreview", false)
    fun setNewUIPreview(value: Boolean) = settings.putBoolean("newUIPreview", value)

    // --- Activity & Games ---
    val displayActivity = flowSettings.getBooleanFlow("displayActivity", true)
    fun setDisplayActivity(value: Boolean) = settings.putBoolean("displayActivity", value)
    
    val shareActivityLargeServers = flowSettings.getBooleanFlow("shareActivityLargeServers", true)
    fun setShareActivityLargeServers(value: Boolean) = settings.putBoolean("shareActivityLargeServers", value)
    
    val showOnlineStatus = flowSettings.getBooleanFlow("showOnlineStatus", true)
    fun setShowOnlineStatus(value: Boolean) = settings.putBoolean("showOnlineStatus", value)
    
    val idleTimeout = flowSettings.getStringFlow("idleTimeout", "5 minutes")
    fun setIdleTimeout(value: String) = settings.putString("idleTimeout", value)
    
    val allowListenAlong = flowSettings.getBooleanFlow("allowListenAlong", true)
    fun setAllowListenAlong(value: Boolean) = settings.putBoolean("allowListenAlong", value)
    
    val showSpotifyStatus = flowSettings.getBooleanFlow("showSpotifyStatus", true)
    fun setShowSpotifyStatus(value: Boolean) = settings.putBoolean("showSpotifyStatus", value)
    
    val autoDetectGames = flowSettings.getBooleanFlow("autoDetectGames", true)
    fun setAutoDetectGames(value: Boolean) = settings.putBoolean("autoDetectGames", value)
    
    val statusDefault = flowSettings.getBooleanFlow("statusDefault", true)
    fun setStatusDefault(value: Boolean) = settings.putBoolean("statusDefault", value)
    
    val enableOverlay = flowSettings.getBooleanFlow("enableOverlay", false)
    fun setEnableOverlay(value: Boolean) = settings.putBoolean("enableOverlay", value)
    
    val overlayNotifications = flowSettings.getBooleanFlow("overlayNotifications", true)
    fun setOverlayNotifications(value: Boolean) = settings.putBoolean("overlayNotifications", value)
    
    val overlayUsers = flowSettings.getBooleanFlow("overlayUsers", true)
    fun setOverlayUsers(value: Boolean) = settings.putBoolean("overlayUsers", value)
    
    val notificationPosition = flowSettings.getStringFlow("notificationPosition", "Top Right")
    fun setNotificationPosition(value: String) = settings.putString("notificationPosition", value)
    
    val voiceOverlayPosition = flowSettings.getStringFlow("voiceOverlayPosition", "Top Left")
    fun setVoiceOverlayPosition(value: String) = settings.putString("voiceOverlayPosition", value)
    
    val overlayOpacity = flowSettings.getFloatFlow("overlayOpacity", 80f)
    fun setOverlayOpacity(value: Float) = settings.putFloat("overlayOpacity", value)
}
