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

    // --- Accessibility ---
    //
    // "spellcheck" was an accessor with no reader and no writer: the switch that
    // once offered it was deleted with the rest of the fabricated settings, and the
    // Compose text field it would have applied to is not a spell-checkable one. It
    // is gone rather than left as a reader, because a reader with no writer is the
    // exact shape that made this file look configurable when it was not.
    
    
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
    //
    // The URL is interpolated straight into "<url>/get_token" and "<url>/sfu/get",
    // so a value with no scheme produces a relative URL and the failure surfaces
    // much later as a call that will not connect, with nothing in the log saying
    // the setting was the reason. `normalizeSfuUrl` rejects it at the point of
    // entry instead; it is public and pure so the settings card can show the same
    // verdict before it lets you save.
    val liveKitServiceUrl = flowSettings.getStringFlow("liveKitServiceUrl", "")
    fun setLiveKitServiceUrl(value: String) =
        settings.putString("liveKitServiceUrl", normalizeSfuUrl(value) ?: "")

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

    // --- Notifications & Sounds ---
    //
    // These four had readers and no writer for the whole life of the app. The
    // readers are real -- WebRtcEffects honours every one of them -- so the app
    // behaved as though each of these were configurable while no control existed to
    // change any of them, and the only reachable value was the default. The setters
    // are below and the switches are in Settings > Notifications.
    val desktopNotifs = flowSettings.getBooleanFlow("desktopNotifs", true)
    fun setDesktopNotifs(value: Boolean) = settings.putBoolean("desktopNotifs", value)

    val joinLeaveSound = flowSettings.getBooleanFlow("joinLeaveSound", true)
    fun setJoinLeaveSound(value: Boolean) = settings.putBoolean("joinLeaveSound", value)

    val callRingtone = flowSettings.getBooleanFlow("callRingtone", true)
    fun setCallRingtone(value: Boolean) = settings.putBoolean("callRingtone", value)

    val deafenSound = flowSettings.getBooleanFlow("deafenSound", true)
    fun setDeafenSound(value: Boolean) = settings.putBoolean("deafenSound", value)

    // --- Language & Time ---
    //
    // Also reader-without-writer: `use24HourClock` was permanently false and
    // `dateFormat` permanently "DD/MM/YYYY", so every timestamp in every message was
    // 12-hour and day-first with no way to ask otherwise.
    val use24HourClock = flowSettings.getBooleanFlow("use24HourClock", false)
    fun setUse24HourClock(value: Boolean) = settings.putBoolean("use24HourClock", value)

    val dateFormat = flowSettings.getStringFlow("dateFormat", "DD/MM/YYYY")
    fun setDateFormat(value: String) = settings.putString("dateFormat", value)

    // --- Windows ---
    
    
    // Default OFF: closing the window should quit the app, not park a hidden
    // JVM (with its whole call session) in the tray. Opt in for tray mode.
    val minimizeToTray = flowSettings.getBooleanFlow("minimizeToTray", false)
    fun setMinimizeToTray(value: Boolean) = settings.putBoolean("minimizeToTray", value)

    // "alwaysOnTop" was also reader-less and writer-less. The one place that could
    // have honoured it, CallPipWindow, passes the literal `alwaysOnTop = true` to the
    // native window -- a picture-in-picture call window that can slide behind the
    // app it belongs to is not a picture-in-picture window. If that ever becomes
    // configurable it needs the reader; until then the accessor is a lie about a
    // capability that does not exist.

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
        // DEFAULT TRUE, and the reason is the whole reason there is a log file at
        // all. Two releases shipped with DEBUG off by default and it cost a real
        // bug report: a user lost every video tile in a group call, sent the log,
        // and it contained nothing about the call because the entire reconnect
        // lifecycle was DEBUG. The sink was working; the level was excluding the
        // diagnosis. Asking a user to find a settings pane before their bug is
        // diagnosable is asking them to do my job.
        //
        // `getBoolean(key, true)` still respects an explicit `false`, so anyone
        // who turns it off keeps it off. Only the never-chosen case flips.
        return settings.getBoolean("debugLogging", true)
    }
    
    /**
     * Observable twin of [debugLoggingSync].
     *
     * The default MUST match the default in [debugLoggingSync] — `true` in both.
     * They disagreeing is how the Diagnostics switch ends up showing "off" while
     * DEBUG lines are actually being written, which is a setting that lies about
     * the one thing it exists to report. This codebase has been bitten by exactly
     * that shape before (see the settings honesty audit), so the pair is pinned by
     * a test rather than left to memory.
     */
    val debugLogging = flowSettings.getBooleanFlow("debugLogging", true)
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

/**
 * Validates a user-supplied SFU URL, returning the value to store or null if it
 * cannot be one.
 *
 * Blank is a legitimate answer and normalises to null, which the setter turns back
 * into "" -- the auto-detect path. Anything else must carry an http(s) scheme,
 * because the value is concatenated onto "/get_token" and "/sfu/get" without one
 * being added, so "livekit.example.com" would ask for a relative path and the
 * result would be a call that silently never connects.
 *
 * Trailing slashes are removed rather than rejected: the user will paste one, the
 * token provider already trims, and failing on it would be pedantry.
 */
internal fun normalizeSfuUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
    // Trim trailing slashes, but never past the "scheme://" itself. A naive
    // trimEnd('/') turns a typed "https://" into "https:", which is a worse value
    // than the one the user entered and produces a token URL with no scheme at all.
    val schemeEnd = lower.indexOf("://") + 3
    val body = trimmed.substring(schemeEnd).trimEnd('/')
    return trimmed.substring(0, schemeEnd) + body
}
