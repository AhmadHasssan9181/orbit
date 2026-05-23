package repository

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

private var appContext: Application? = null

fun initSettings(context: Application) {
    appContext = context
}

actual fun createCredentialSettings(): Settings {
    val ctx = appContext ?: error(
        "Settings not initialized. Did you forget android:name=\".OrbitApp\" in AndroidManifest.xml?"
    )
    val prefs: SharedPreferences = ctx.getSharedPreferences("orbit_creds", Context.MODE_PRIVATE)
    return object : Settings {
        override fun getStringOrNull(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun remove(key: String) = prefs.edit().remove(key).apply()
        override fun clear() = prefs.edit().clear().apply()
    }
}