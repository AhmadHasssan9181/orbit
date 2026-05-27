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

    val isLiteMode: Flow<Boolean> = flowSettings.getBooleanFlow("isLiteMode", false)
    val messageDensity: Flow<String> = flowSettings.getStringFlow("messageDensity", "Comfortable")
    val fontSize: Flow<Float> = flowSettings.getFloatFlow("fontSize", 50f)
    val reduceMotion: Flow<Boolean> = flowSettings.getBooleanFlow("reduceMotion", false)
    val mentionsOnly: Flow<Boolean> = flowSettings.getBooleanFlow("mentionsOnly", true)
    val notificationSound: Flow<String> = flowSettings.getStringFlow("notificationSound", "Chime")

    fun setTheme(theme: Theme) = settings.putString("activeTheme", theme.name)
    fun setLiteMode(enabled: Boolean) = settings.putBoolean("isLiteMode", enabled)
    fun setMessageDensity(density: String) = settings.putString("messageDensity", density)
    fun setFontSize(size: Float) = settings.putFloat("fontSize", size)
    fun setReduceMotion(enabled: Boolean) = settings.putBoolean("reduceMotion", enabled)
    fun setMentionsOnly(enabled: Boolean) = settings.putBoolean("mentionsOnly", enabled)
    fun setNotificationSound(sound: String) = settings.putString("notificationSound", sound)
}
