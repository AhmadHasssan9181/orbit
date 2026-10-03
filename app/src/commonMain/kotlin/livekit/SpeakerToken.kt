package livekit

/**
 * Collapsing a speaking token to the one person it refers to.
 *
 * ## Why this is `internal` in commonMain and not private to the session
 *
 * The bug it prevents is only visible when two tokens for one human arrive
 * together, which needs the reducer as well as the normalizer to be exercised.
 * Keeping both reachable from a test is what stopped this regressing into "the
 * ring looks slightly wrong and nobody can say why".
 *
 * Two real group-call logs showed the same human reported under two names in the
 * same call:
 *
 * ```
 * 01:50:08.585  active speakers [@marioreo:matrix.org:djpYnE6QIj, TR_AMpAsYgfYnJ4z7]
 * 01:50:09.095  active speakers [@marioreo:matrix.org:djpYnE6QIj]
 * 01:50:09.515  active speakers [TR_AMpAsYgfYnJ4z7]
 * ```
 *
 * and again with `@ahmad9812:matrix.org:rkpOhK4TpM` and
 * `@hamzamasudbutt:matrix.org:BzPLGdcHdw`. Nobody was in two calls at once.
 *
 * Two distinct causes, and this function is the Kotlin-side guard for both:
 *
 *  1. **A track SID where an identity should be.** `TR_...` is a LiveKit track
 *     identifier. Native fell back to it when it had no participant identity for
 *     an audio track, which happens for the second audio track of a participant
 *     publishing screen-with-audio. Fixed at the C++ end, but not something the
 *     UI should be able to crash or lie on if an older native DLL is in flight.
 *  2. **Device suffix.** A MatrixRTC identity is `@user:server`; a LiveKit one
 *     is `@user:server:DEVICE`. The same person appears under both depending on
 *     which code path produced the token, so the raw set double-counts them.
 *
 * Design rule: this returns "" for anything that is not a person, and callers
 * drop it. A missing speaking ring is a cosmetic miss; a phantom speaker is a
 * lie about who is in the room, and the ring is what people use to decide who to
 * talk to.
 */
internal fun normalizeSpeakerToken(raw: String?): String {
    val token = raw?.trim().orEmpty()
    if (token.isEmpty()) return ""

    // A LiveKit track SID. Never a person.
    if (token.startsWith("TR_") || token.startsWith("PA_")) return ""

    if (!token.startsWith("@")) return ""

    // `@user:server` is the identity; `@user:server:DEVICE` is one device of it.
    // Strip a trailing device segment, but only when there are enough segments to
    // be sure the extra one is a device and not part of a domain.
    //
    // Matrix domains can contain dots but not colons beyond the localpart/domain
    // split, so `substringBeforeLast(':')` on `@a:b.c:DEV` yields `@a:b.c` -- the
    // right answer. On `@a:b.c` it would yield `@a:b`, which is WRONG, so the
    // arity check below is what makes this safe rather than clever.
    val parts = token.split(':')
    if (parts.size >= 3) {
        val withoutDevice = parts.dropLast(1).joinToString(":")
        // Only strip when what remains still looks like `@user:server`.
        if (withoutDevice.startsWith("@") && withoutDevice.substringAfter(':').isNotBlank()) {
            return withoutDevice
        }
    }

    // `@user:server` with no device segment: already normalized.
    if (parts.size == 2) return token

    // A malformed Matrix ID: `@user` alone, or `@a:b:c:d` with more segments than
    // any real id has. Returned rather than dropped, because the caller's blank
    // check is the only gate and swallowing a token we merely failed to parse
    // would hide a future format change. Note this is the OPPOSITE policy to the
    // non-`@` case above, and deliberately so: `nonsense` cannot be a person under
    // any reading, whereas `@weird` is clearly trying to be one.
    return token
}
