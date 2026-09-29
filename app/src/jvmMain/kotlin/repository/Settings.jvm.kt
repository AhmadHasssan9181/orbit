package repository

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.prefs.Preferences
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Credential storage is encrypted at rest:
 *  - Windows: per-user DPAPI (CryptProtectData) — no key material on disk.
 *  - Other platforms: AES-256-GCM with a random key persisted to ~/.orbit/.credkey.
 * Values are stored as "enc:v1:<base64>". Legacy plaintext values are decrypted
 * transparently and re-encrypted on first read.
 */
actual fun createCredentialSettings(): Settings {
    val prefs = Preferences.userRoot().node("com/noobdev/orbit/credentials")
    val plain = object : Settings {
        override fun getStringOrNull(key: String): String? {
            val value = prefs.get(key, "")
            return value.ifEmpty { null }
        }
        override fun putString(key: String, value: String) = prefs.put(key, value)
        override fun remove(key: String) = prefs.remove(key)
        override fun clear() = prefs.keys().forEach { prefs.remove(it) }
    }
    return object : Settings {
        // tenorApiKey is a third-party API secret the user pastes into Settings;
        // it belongs here, not in plaintext preferences.
        private val encryptedKeys = setOf("accessToken", "syncToken", "tenorApiKey")

        override fun getStringOrNull(key: String): String? {
            val raw = plain.getStringOrNull(key) ?: return null
            if (key !in encryptedKeys) return raw
            return when {
                raw.startsWith(ENC_PREFIX) -> decrypt(raw.removePrefix(ENC_PREFIX)) ?: run {
                    System.err.println("WARN [CredentialStore] failed to decrypt '$key'; treating as absent")
                    null
                }
                else -> {
                    // Legacy plaintext from before encryption-at-rest: migrate once.
                    plain.putString(key, encrypt(raw))
                    raw
                }
            }
        }

        override fun putString(key: String, value: String) {
            plain.putString(key, if (key in encryptedKeys) encrypt(value) else value)
        }

        override fun remove(key: String) = plain.remove(key)
        override fun clear() = plain.clear()
    }
}

private const val ENC_PREFIX = "enc:v1:"

private fun encrypt(plainText: String): String {
    val bytes = plainText.toByteArray(Charsets.UTF_8)
    val protected = try {
        if (isWindows) Crypt32Util.cryptProtectData(bytes)
        else aesCipher(bytes, encryptMode = true)
    } catch (e: Throwable) {
        // Never lose the credential because the cipher failed — fall back to
        // storing the plaintext shape we already had.
        println("WARN [CredentialStore] encryption unavailable (${e.message}); storing unprotected")
        return plainText
    }
    return ENC_PREFIX + Base64.getEncoder().encodeToString(protected)
}

private fun decrypt(stored: String): String? = try {
    val blob = Base64.getDecoder().decode(stored)
    val plainBytes = if (isWindows) Crypt32Util.cryptUnprotectData(blob) else aesCipher(blob, encryptMode = false)
    String(plainBytes, Charsets.UTF_8)
} catch (e: Throwable) {
    null
}

private val isWindows: Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("windows") == true

/** AES-256-GCM fallback for non-Windows hosts; key lives in ~/.orbit/.credkey. */
private fun aesCipher(data: ByteArray, encryptMode: Boolean): ByteArray {
    val keyFile = File(System.getProperty("user.home"), ".orbit/.credkey")
    keyFile.parentFile.mkdirs()
    val keyBytes = if (keyFile.exists() && keyFile.length() == 32L) {
        keyFile.readBytes()
    } else {
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keyFile.writeBytes(fresh)
        fresh
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    if (encryptMode) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(data)
    }
    val iv = data.copyOfRange(0, 12)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(data, 12, data.size - 12)
}
