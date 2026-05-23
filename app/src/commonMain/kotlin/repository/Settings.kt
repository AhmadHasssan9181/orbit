package repository

expect fun createCredentialSettings(): Settings

interface Settings {
    fun getStringOrNull(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}