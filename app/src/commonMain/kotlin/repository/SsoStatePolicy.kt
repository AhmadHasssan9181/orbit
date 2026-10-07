package repository

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether Orbit will accept an SSO callback that came back without a `state`.
 *
 * ## The problem this exists to resolve
 *
 * Matrix's SSO flow redirects the browser back to a loopback URL carrying a
 * `loginToken`. Orbit also sends a `state` and compares it on return, which is the
 * standard defence against login-CSRF / session fixation: without it, an attacker
 * who can get the victim's browser to open
 * `http://localhost:<ephemeral-port>/?loginToken=<attacker token>` signs the victim
 * into the **attacker's** account, and everything the victim then sends is uploaded
 * somewhere the attacker can read.
 *
 * Requiring `state` outright is correct — but it was measured against a homeserver
 * that does not echo it, and SSO became unusable: every callback arrived with no
 * `state` at all and was refused. A security control that blocks the feature outright
 * gets removed by whoever is next, so it needs to be a decision the user can make
 * with the facts in front of them.
 *
 * ## The rule
 *
 * **Absent is not automatically accepted, and a mismatch is never accepted.**
 *
 *  - `state` present but wrong → always rejected. That is either a stale tab or an
 *    attack, and there is no legitimate reading of it.
 *  - `state` absent → rejected by default, with a per-homeserver opt-in.
 *
 * The opt-in is keyed on the **homeserver**, not a global switch, for the same reason
 * `HomeserverUrlNormalizer`'s plaintext consent is: one homeserver that cannot echo
 * `state` says nothing about the next one you sign in to.
 *
 * ## What the user is agreeing to
 *
 * Weakly: that a login callback on this machine will be trusted without a
 * correlation value. The exposure is narrow — the attacker needs the ephemeral port
 * of an in-flight login — but it is real, and that is why this is an explicit,
 * remembered, per-server choice rather than a silent fallback.
 */
object SsoStatePolicy {

    /**
     * Thrown when the homeserver's SSO callback carried no `state`.
     *
     * Lives in `commonMain` rather than beside the socket code in `Browser.jvm.kt`
     * because `LoginScreen` is common code and has to catch it in order to offer the
     * opt-in. A `jvmMain` type here would be invisible to it.
     *
     * A distinct type rather than a message the UI pattern-matches, so the consent
     * affordance cannot be shown for an unrelated failure.
     */
    class StateMissingException(val homeserverUrl: String) : IllegalStateException(
        "The homeserver completed sign-in without returning the value Orbit uses to " +
            "confirm the response belongs to this attempt."
    )

    /**
     * Homeservers the user has chosen to complete SSO against without `state`
     * verification. Lower-cased; the port is deliberately excluded so consent tracks
     * the server rather than one particular endpoint.
     */
    private val optedOut = ConcurrentHashMap.newKeySet<String>()

    /** Canonical key for [homeserverUrl]: scheme + host, no path or port. */
    private fun keyFor(homeserverUrl: String): String? =
        util.OriginGuard.originOf(homeserverUrl)?.let { "${it.scheme}://${it.host}" }

    /** True when the user has already accepted the weaker check for [homeserverUrl]. */
    fun isAccepted(homeserverUrl: String): Boolean =
        keyFor(homeserverUrl)?.let { optedOut.contains(it) } ?: false

    /** Record consent. Returns false when the URL cannot be parsed. */
    fun accept(homeserverUrl: String): Boolean {
        val key = keyFor(homeserverUrl) ?: return false
        optedOut += key
        util.OrbitLog.w(
            "SsoStatePolicy",
            "SECURITY: accepting SSO callbacks without a state check for $key. A login " +
                "callback on this machine will no longer be correlated to this attempt."
        )
        return true
    }

    /** Withdraw consent, e.g. from a Settings control. */
    fun revoke(homeserverUrl: String) {
        keyFor(homeserverUrl)?.let { optedOut -= it }
    }

    /** Test seam. */
    fun reset() = optedOut.clear()

    /**
     * The wording shown to the user before they opt in.
     *
     * Says what actually happened and what the choice costs, rather than "an error
     * occurred". The user is being asked to accept a small, named risk.
     */
    fun warningText(homeserverUrl: String): String {
        val host = util.OriginGuard.originOf(homeserverUrl)?.host ?: homeserverUrl
        return "$host completed the sign-in without returning the one-time value Orbit " +
            "uses to confirm this response belongs to the login you just started. Orbit " +
            "refuses that by default, because accepting it means a malicious page you " +
            "visit at the wrong moment could sign you into somebody else's account.\n\n" +
            "If this is a homeserver you trust and control, you can continue without that " +
            "check for $host only."
    }
}