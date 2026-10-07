package repository

/**
 * Decides whether a LiveKit SFU URL that came from **room state** may be used.
 *
 * ## The problem
 *
 * `m.call` definitions in room state carry `livekit_service_url`. Any room member
 * can write room state, so that URL is attacker-controlled. It flows:
 *
 * ```
 * m.call state event
 *   -> RtcFocusParser.firstLiveKit(...)            // MatrixCallsRepository
 *   -> parseGroupCallJoinTarget(...)               // raw.substringBefore("?")
 *   -> fetchLiveKitToken(roomId, callId, url)
 *   -> LiveKitTokenProvider -> POST <url>/get_token { openid_token: ... }
 * ```
 *
 * The user's **OpenID token** is POSTed to that host. The OpenID token is a
 * single-use bearer credential the SFU exchanges for a LiveKit JWT, so replaying
 * it at the real `lk-jwt-service` mints a LiveKit JWT *as the victim* — call
 * impersonation — and it discloses their MXID, device id and homeserver to whoever
 * published the definition. An `http://` focus additionally ships it in cleartext.
 *
 * This was previously unchecked: `normalizeSfuUrl` validated only the value the
 * *user typed* into Settings, and the room-supplied value never went near it.
 *
 * ## The rule
 *
 * A room may only point Orbit at an SFU the **homeserver** has already advertised.
 * That is the same trust model Element uses for `rtc_foci`: the homeserver's
 * `.well-known/matrix/client` names its own SFUs, and room state selects among
 * them. It cannot invent one.
 *
 * So a room-supplied URL is accepted only if its origin matches:
 *  - the user's explicit `liveKitServiceUrlOverride` (Settings), or
 *  - the homeserver's `.well-known` `org.matrix.msc4143.rtc_foci` LiveKit focus.
 *
 * When it does not match, the caller falls back to [MatrixContext.effectiveLiveKitServiceUrl]
 * rather than failing the join, because a call joining against the configured SFU
 * is strictly better than not joining — and the failure is reported, so it is
 * visible rather than silent.
 *
 * Origin comparison is delegated to [util.OriginGuard]: parsed scheme + host +
 * effective port, never a substring. A definition pointing at
 * `https://livekit.call.matrix.org.attacker.com` must not be accepted because it
 * contains the real host as a substring.
 */
object SfuUrlPolicy {

    /**
     * Origins this account is willing to send an OpenID token to, as normalized
     * origin strings.
     *
     * Empty means "nothing advertised yet" — which happens before
     * `refreshWellKnownRtcFoci` completes at startup. That is NOT an
     * accept-everything state: an empty allow-list accepts nothing, so the caller
     * falls back to the configured SFU. Failing closed is the only safe reading of
     * "we do not know yet".
     */
    fun allowedOrigins(context: MatrixContext): Set<String> {
        val raw = buildList {
            context.liveKitServiceUrlOverride?.takeIf { it.isNotBlank() }?.let { add(it) }
            context.wellKnownRtcFocusUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        return raw.mapNotNull { util.OriginGuard.originOf(it)?.toString() }.toSet()
    }

    /**
     * True when [candidate] may be used as the SFU for this account.
     *
     * A blank candidate is "no hint" and returns true so callers can pass a
     * null/empty hint straight through to their fallback.
     */
    fun isAdmissible(candidate: String?, context: MatrixContext): Boolean {
        if (candidate.isNullOrBlank()) return true
        val origin = util.OriginGuard.originOf(candidate) ?: run {
            util.OrbitLog.w(
                "SfuUrlPolicy",
                "room-supplied SFU URL is unparseable and will not be used: $candidate"
            )
            return false
        }
        val allowed = allowedOrigins(context)
        if (origin.toString() in allowed) return true
        util.OrbitLog.w(
            "SfuUrlPolicy",
            "SECURITY: refusing room-supplied SFU $origin -- not advertised by this " +
                "homeserver. Any member can write m.call state, so an unverified URL here " +
                "receives the user's OpenID token. Falling back to the configured SFU."
        )
        return false
    }

    /**
     * [candidate] if it is admissible, otherwise null so the caller applies its own
     * fallback. The result is always a value the account is willing to authenticate
     * against.
     */
    fun resolve(candidate: String?, context: MatrixContext): String? =
        if (isAdmissible(candidate, context)) candidate?.takeIf { it.isNotBlank() } else null
}