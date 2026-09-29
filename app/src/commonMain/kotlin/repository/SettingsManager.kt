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


    val fontSize = flowSettings.getFloatFlow("fontSize", 50f)
    fun setFontSize(value: Float) = settings.putFloat("fontSize", value)

    val reduceMotion = flowSettings.getBooleanFlow("reduceMotion", false)
    fun setReduceMotion(value: Boolean) = settings.putBoolean("reduceMotion", value)


    val chatWallpaper = flowSettings.getStringFlow("chatWallpaper", "auto")
    fun setChatWallpaper(value: String) = settings.putString("chatWallpaper", value)

    // --- Appearance & Chat ---
    val showTimestamps = flowSettings.getBooleanFlow("showTimestamps", true)
    fun setShowTimestamps(value: Boolean) = settings.putBoolean("showTimestamps", value)
    
    val showRoleColors = flowSettings.getBooleanFlow("showRoleColors", true)
    fun setShowRoleColors(value: Boolean) = settings.putBoolean("showRoleColors", value)
    
    val showMemberList = flowSettings.getBooleanFlow("showMemberList", true)
    fun setShowMemberList(value: Boolean) = settings.putBoolean("showMemberList", value)
    
    
    val compactMode = flowSettings.getBooleanFlow("compactMode", false)
    fun setCompactMode(value: Boolean) = settings.putBoolean("compactMode", value)
    
    val enterSend = flowSettings.getBooleanFlow("enterSend", true)
    fun setEnterSend(value: Boolean) = settings.putBoolean("enterSend", value)
    
    val spellcheck = flowSettings.getBooleanFlow("spellcheck", true)

    // --- Accessibility ---
    
    
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
    
    
    val cameraDevice = flowSettings.getStringFlow("cameraDevice", "Default")
    fun setCameraDevice(value: String) = settings.putString("cameraDevice", value)
    
    val mirrorVideo = flowSettings.getBooleanFlow("mirrorVideo", true)
    fun setMirrorVideo(value: Boolean) = settings.putBoolean("mirrorVideo", value)
    

    val videoBackground = flowSettings.getStringFlow("videoBackground", "None")
    fun setVideoBackground(value: String) = settings.putString("videoBackground", value)
    
    // Screen Share Settings
    val screenShareQuality = flowSettings.getStringFlow("screenShareQuality", "1080p")
    fun setScreenShareQuality(value: String) = settings.putString("screenShareQuality", value)
    fun screenShareQualitySync(): String = settings.getString("screenShareQuality", "1080p")

    val screenShareFps = flowSettings.getIntFlow("screenShareFps", 30)
    fun setScreenShareFps(value: Int) = settings.putInt("screenShareFps", value)
    fun screenShareFpsSync(): Int = settings.getInt("screenShareFps", 30)
    
    
    val audioBitrate = flowSettings.getFloatFlow("audioBitrate", 64f)
    fun setAudioBitrate(value: Float) = settings.putFloat("audioBitrate", value)
    

    // --- Calls / SFU ---
    // Blank means auto: .well-known rtc_foci → public dev-fallback SFU.
    val liveKitServiceUrl = flowSettings.getStringFlow("liveKitServiceUrl", "")

    // --- Integrations ---
    // Secret material must not live in plaintext preferences; blank disables the
    // feature. Backed by the same DPAPI-encrypted credential store as the Matrix
    // access token (see createCredentialSettings()).
    private val credentialSettings = createCredentialSettings()
    private val _tenorApiKey = kotlinx.coroutines.flow.MutableStateFlow(
        credentialSettings.getStringOrNull(TENOR_API_KEY_SETTING) ?: ""
    )
    val tenorApiKey: Flow<String> = _tenorApiKey

    fun setTenorApiKey(value: String) {
        credentialSettings.putString(TENOR_API_KEY_SETTING, value)
        _tenorApiKey.value = value
    }

    fun tenorApiKeySync(): String = credentialSettings.getStringOrNull(TENOR_API_KEY_SETTING) ?: ""

    private companion object {
        const val TENOR_API_KEY_SETTING = "tenorApiKey"
    }

    // --- Notifications ---
    val desktopNotifs = flowSettings.getBooleanFlow("desktopNotifs", true)
    
    
    val joinLeaveSound = flowSettings.getBooleanFlow("joinLeaveSound", true)
    
    
    val callRingtone = flowSettings.getBooleanFlow("callRingtone", true)
    
    val deafenSound = flowSettings.getBooleanFlow("deafenSound", true)
    
    
    // --- Language & Time ---
    
    
    val use24HourClock = flowSettings.getBooleanFlow("use24HourClock", false)
    
    
    val dateFormat = flowSettings.getStringFlow("dateFormat", "DD/MM/YYYY")

    // --- Windows ---
    
    
    // Default OFF: closing the window should quit the app, not park a hidden
    // JVM (with its whole call session) in the tray. Opt in for tray mode.
    val minimizeToTray = flowSettings.getBooleanFlow("minimizeToTray", false)
    fun setMinimizeToTray(value: Boolean) = settings.putBoolean("minimizeToTray", value)
    
    val alwaysOnTop = flowSettings.getBooleanFlow("alwaysOnTop", false)
    
    
    // --- Advanced ---
    
    /**
     * Read once at startup by `Main.kt`, because the console filter is installed
     * before any composable exists and must not start mid-session.
     *
     * `debugEnabled` also honours an `ORBIT_DEBUG` environment variable, checked
     * here rather than at the call site so there is exactly one place that
     * decides whether logging is on. See `Main.kt`.
     */
    fun debugLoggingSync(): Boolean {
        // ORBIT_DEBUG=1 (or true/on/yes) forces debug on for one run without
        // touching the stored preference. Intended for reproducing a bug and
        // reading the log; leaving it set would flood the console forever.
        val override = System.getenv("ORBIT_DEBUG")?.trim()?.lowercase()
        if (override == "1" || override == "true" || override == "on" || override == "yes") return true
        return settings.getBoolean("debugLogging", false)
    }
    
    /**
     * Observable twin of [debugLoggingSync].
     *
     * The sync reader had no writer for its entire life, so DEBUG was
     * permanently off and `OrbitLog` looked broken: dozens of `OrbitLog.d` call
     * sites across the call stack, none of which could ever emit. Turning this
     * on at runtime works because `installOrbitLogConsoleFilter` reads
     * `OrbitLog.debugEnabled` per line rather than capturing it once.
     */
    val debugLogging = flowSettings.getBooleanFlow("debugLogging", false)
    fun setDebugLogging(value: Boolean) = settings.putBoolean("debugLogging", value)
    
    
    // --- Activity & Games ---
    
    
    // Last chosen presence (UserStatus enum name); reapplied to homeserver on startup/change.
    val myStatus = flowSettings.getStringFlow("myStatus", "ONLINE")
    fun setMyStatus(value: String) = settings.putString("myStatus", value)

    // Per-room notification mode ("ALL" / "MENTIONS" / "MUTE"). Local-only:
    // drives badge counting and the chat-header bell, not server push rules.
    fun roomNotificationMode(roomId: String): Flow<String> =
        flowSettings.getStringFlow("roomNotifMode.$roomId", "ALL")
    fun setRoomNotificationMode(roomId: String, mode: String) =
        settings.putString("roomNotifMode.$roomId", mode)
    
    
}
