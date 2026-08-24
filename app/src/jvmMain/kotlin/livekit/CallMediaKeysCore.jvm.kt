package livekit

import java.security.SecureRandom

actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    SecureRandom().nextBytes(bytes)
    return bytes
}
