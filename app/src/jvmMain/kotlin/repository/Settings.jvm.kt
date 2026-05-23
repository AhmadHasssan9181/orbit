package repository

import java.util.prefs.Preferences

actual fun createCredentialSettings(): Settings {
    val prefs = Preferences.userRoot().node("com/noobdev/orbit/credentials")
    return object : Settings {
        override fun getStringOrNull(key: String): String? {
            val value = prefs.get(key, "")
            return value.ifEmpty { null }
        }
        override fun putString(key: String, value: String) = prefs.put(key, value)
        override fun remove(key: String) = prefs.remove(key)
        override fun clear() = prefs.keys().forEach { prefs.remove(it) }
    }
}