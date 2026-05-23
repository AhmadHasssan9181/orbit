package repository

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import com.russhwolf.settings.get

object CredentialManager {
    private val settings = Settings()

    var homeserverUrl: String?
        get() = settings.getStringOrNull("homeserverUrl")
        set(value) {
            if (value != null) settings.putString("homeserverUrl", value)
            else settings.remove("homeserverUrl")
        }

    var accessToken: String?
        get() = settings.getStringOrNull("accessToken")
        set(value) {
            if (value != null) settings.putString("accessToken", value)
            else settings.remove("accessToken")
        }

    var userId: String?
        get() = settings.getStringOrNull("userId")
        set(value) {
            if (value != null) settings.putString("userId", value)
            else settings.remove("userId")
        }

    var deviceId: String?
        get() = settings.getStringOrNull("deviceId")
        set(value) {
            if (value != null) settings.putString("deviceId", value)
            else settings.remove("deviceId")
        }

    var syncToken: String?
        get() = settings.getStringOrNull("syncToken")
        set(value) {
            if (value != null) settings.putString("syncToken", value)
            else settings.remove("syncToken")
        }

    fun clear() {
        settings.clear()
    }
}
