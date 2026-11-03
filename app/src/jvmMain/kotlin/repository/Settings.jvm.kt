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
    val profile = util.OrbitPaths.profile
    val nodePath = if (profile.isNotEmpty()) "com/noobdev/orbit/credentials/$profile" else "com/noobdev/orbit/credentials"
    val prefs = Preferences.userRoot().node(nodePath)
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
        init {
            // Surface a degraded store on every launch, not just during the write
            // that failed. See [credentialStoreDegraded].
            credentialStoreDegraded = plain.getStringOrNull(DEGRADED_KEY) == "1"
        }
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

/**
 * True when the credential store fell back to storing values unprotected.
 *
 * ## Why this is persisted rather than just a flag
 *
 * The first version of this was a plain `@Volatile var` set inside `encrypt`. It
 * was dead code: nothing read it, and it was reset to `false` by the very next
 * successful write, so even a reader would only ever have seen a momentary flag.
 *
 * The state has to outlive the write, because the write is the only moment the user
 * is not around to notice. DPAPI failures are not exotic -- a locked or
 * broken-roaming Windows profile, `ERROR_INVALID_PARAMETER` -- and the credential
 * is sitting in `Preferences` in cleartext until something fixes it. So:
 *
 *  - the failure is recorded under [DEGRADED_KEY] in the same preference node;
 *  - it is re-read on every launch (see the `init` in `createCredentialSettings`);
 *  - `SettingsManager.credentialStoreDegradedFlow` exposes it to the UI, and the
 *    Diagnostics pane in Settings shows a persistent warning while it is set;
 *  - the next successful encrypt clears it, because the value really is protected
 *    again by then.
 *
 * A log line alone was not enough: a jpackage GUI-subsystem launcher discards
 * stdout, so "the console said so" is not a channel the user has.
 */
@Volatile
var credentialStoreDegraded: Boolean = false
    internal set

private const val DEGRADED_KEY = "credentialStoreDegraded"

private fun encrypt(plainText: String): String {
    val bytes = plainText.toByteArray(Charsets.UTF_8)
    val protected = try {
        if (isWindows) Crypt32Util.cryptProtectData(bytes)
        else aesCipher(bytes, encryptMode = true)
    } catch (e: Throwable) {
        // SECURITY: this used to `return plainText` with only a println, which wrote
        // the raw access token to disk in cleartext while the app looked perfectly
        // normal, and the old shape failed in the insecure direction, silently.
        //
        // The credential is still persisted -- dropping it would log the user out
        // with no explanation -- but the failure is now (a) reported through
        // OrbitLog, which reaches the packaged app's log file, and (b) persisted so
        // the UI can keep warning until a write succeeds.
        util.OrbitLog.e(
            "CredentialStore",
            "encryption unavailable (${e.message}); storing credential UNENCRYPTED at rest"
        )
        credentialStoreDegraded = true
        recordDegraded(true)
        return plainText
    }
    credentialStoreDegraded = false
    recordDegraded(false)
    return ENC_PREFIX + Base64.getEncoder().encodeToString(protected)
}

/**
 * Mirror the degraded state into the preference node so it survives a restart.
 *
 * Written through the *raw* store, never through this wrapper: the wrapper exists
 * to encrypt, and this value must stay readable when encryption is exactly what is
 * broken.
 */
private fun recordDegraded(degraded: Boolean) {
    runCatching {
        val profile = util.OrbitPaths.profile
        val nodePath = if (profile.isNotEmpty()) "com/noobdev/orbit/credentials/$profile" else "com/noobdev/orbit/credentials"
        val prefs = Preferences.userRoot().node(nodePath)
        if (degraded) prefs.put(DEGRADED_KEY, "1") else prefs.remove(DEGRADED_KEY)
    }
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

/**
 * Write [bytes] to [target] with permissions 0600, owner-only.
 *
 * Falls back to a plain write plus a best-effort `chmod` on filesystems without
 * POSIX permission support. Failures are swallowed deliberately: an unwritable
 * permission bit must not stop Orbit from starting, and the caller already logs
 * the encryption state separately.
 */
private fun writeOwnerOnly(target: File, bytes: ByteArray) {
    val path = target.toPath()
    val ownerOnly = runCatching {
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
    }.getOrNull()

    if (ownerOnly != null) {
        val created = try {
            java.nio.file.Files.createFile(
                path,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly)
            )
            true
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            true
        } catch (e: Exception) {
            // UnsupportedOperationException on non-POSIX mounts, and anything else
            // this runtime rejects. Fall through to the plain write.
            false
        }
        if (created) {
            java.nio.file.Files.write(path, bytes)
            return
        }
    }
    target.writeBytes(bytes)
}

/** Best-effort narrowing of [target] to 0600. Never throws. */
private fun restrictToOwner(target: File) {
    // PosixFilePermissions throws UnsupportedOperationException on Windows and on
    // non-POSIX mounts, which is the whole point of "best effort": this runs on
    // every key-file write and must never be able to break startup.
    runCatching {
        java.nio.file.Files.setPosixFilePermissions(
            target.toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
        )
    }
}

/** AES-256-GCM fallback for non-Windows hosts; key lives in ~/.orbit/.credkey. */
private fun aesCipher(data: ByteArray, encryptMode: Boolean): ByteArray {
    val keyFile = util.OrbitPaths.credentialsKeyFile
    keyFile.parentFile.mkdirs()
    val keyBytes = if (keyFile.exists() && keyFile.length() == 32L) {
        keyFile.readBytes()
    } else {
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        // Create the file owner-only BEFORE the key bytes touch disk. A bare
        // `writeBytes` applies the process umask, which on a typical multi-user
        // host is 0644 -- every local account could then read the key that
        // decrypts the access token, out of the same $HOME as the ciphertext it
        // protects. POSIX-only; Windows never reaches this branch (it uses DPAPI).
        writeOwnerOnly(keyFile, fresh)
        fresh
    }
    // Also narrow a pre-existing key file, so one written by an older build with
    // loose permissions gets fixed the next time we rotate or create it.
    restrictToOwner(keyFile)
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
