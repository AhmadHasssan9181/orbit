package webrtc

/**
 * Remembers who is calling so system notifications can name them. Populated by
 * WebRtcEffects when an m.call.invite arrives; display names resolve async and
 * may still be null when the notification fires (falls back to the mxid
 * localpart).
 */
object IncomingCallRegistry {
    @Volatile var callerName: String? = null

    fun register(senderId: String?, displayName: String?) {
        callerName = displayName?.takeIf { it.isNotBlank() }
            ?: senderId?.substringBefore(':')?.removePrefix("@")?.replaceFirstChar { it.uppercase() }
    }

    fun clear() {
        callerName = null
    }
}
