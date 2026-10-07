package util

import io.ktor.http.Url

/**
 * Decides whether an outbound request may carry a credential.
 *
 * ## Why this exists
 *
 * Orbit attaches the long-lived Matrix access token to some outbound requests
 * (image loads, media downloads). Before this object existed that decision was
 * made two different ways, and **both were substring tests**:
 *
 * ```kotlin
 * url.contains("/_matrix/")                                  // OrbitApp.kt (Coil)
 * host.contains(hsHost, ignoreCase = true)                   // OrbitApp.kt (Coil)
 * return url.includes("/_matrix/media/")                     // web mediaUrl.ts
 * ```
 *
 * Substring matching is not an origin check, and the difference is an account
 * takeover. A substring check is satisfied by *any* host that happens to contain
 * the matched text in the right place, so:
 *
 *  - `https://attacker.example/_matrix/logo.png` passes a `/_matrix/` test.
 *    Room state such as `m.room.banner` is writable by **any room member**, so a
 *    hostile member could publish a banner URL that satisfied the test and have
 *    every client render an `<img>` whose request carried the viewer's access
 *    token. The browser would send it to the attacker's host, and it would land
 *    in their access log.
 *  - `matrix.org` as a homeserver makes `evil-matrix.org.attacker.com` and
 *    `matrix.org.evil.com` pass a `contains` test.
 *
 * The Matrix access token is a bearer credential for the whole account, E2EE
 * material included, so this is the highest-consequence bug class in the client.
 *
 * ## The rule
 *
 * **Parse, then compare.** Never authorize a credential with `contains`, `startsWith`
 * on a host, or any other textual test over an unparsed URL. Compare a normalized
 * origin: scheme (case-insensitive), host (case-insensitive, IDN- and trailing-dot
 * normalized), and effective port.
 *
 * Same-origin alone is deliberately *not* the whole policy either. A homeserver
 * legitimately hosts its media at the same origin, so [isMatrixOrigin] is the
 * primary answer, and [isMatrixPath] is available for callers that must confirm the
 * request is actually a Matrix API path *on top of* an origin match. Both are
 * required before a credential is attached; the path check never substitutes for
 * the origin check.
 *
 * ## Ports
 *
 * Effective ports are compared, not literal ones. `https://example.org` and
 * `https://example.org:443` are the same origin, and a caller that passed the
 * explicit default must not be rejected. The converse also holds: `http://example.org`
 * and `http://example.org:443` are *different* origins and must not match.
 */
object OriginGuard {

    /** Default port per scheme, so an explicit default port equals the implicit one. */
    private fun defaultPort(scheme: String): Int =
        when (scheme.lowercase()) {
            "https", "wss" -> 443
            "http", "ws" -> 80
            else -> -1
        }

    /** A scheme+host+effective-port triple with no path, query or fragment. */
    data class Origin(
        val scheme: String,
        val host: String,
        val port: Int,
    ) {
        override fun toString(): String = "$scheme://$host:$port"
    }

    /**
     * Reduce a URL to its origin, or return null if it cannot be parsed or has
     * no host.
     *
     * Host normalization matters here: `EXAMPLE.org`, `example.org.` and
     * `example.org` with a trailing dot are the same host to DNS but not to
     * [String.equals], so an attacker-controlled trailing dot would otherwise
     * bypass an exact-match check. Punycode/IDN input is left as the caller
     * supplied it: `Url` does not expose a platform-independent IDN transform on
     * every supported target, and silently disagreeing with the resolver about
     * what a host is worse than comparing the ASCII form literally.
     */
    fun originOf(rawUrl: String): Origin? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        // Relative URLs carry no host, so there is nothing to authorize.
        if (!trimmed.contains("://")) return null
        val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
        val scheme = url.protocol.name.lowercase()
        if (scheme.isEmpty()) return null
        val host = url.host.trim().trimEnd('.').lowercase()
        if (host.isEmpty()) return null
        val declared = url.port
        val port = if (declared > 0) declared else defaultPort(scheme)
        return Origin(scheme, host, port)
    }

    /**
     * True when [candidateUrl] is on exactly the same origin as [homeserverUrl].
     *
     * A malformed or relative [candidateUrl] returns false. Refusing to guess is
     * the whole point: the caller is deciding whether to hand over a credential,
     * and an unparseable URL is not evidence of anything.
     */
    fun isSameOrigin(candidateUrl: String, homeserverUrl: String?): Boolean {
        if (homeserverUrl.isNullOrBlank()) return false
        val candidate = originOf(candidateUrl) ?: return false
        val homeserver = originOf(homeserverUrl) ?: return false
        return candidate == homeserver
    }

    /**
     * True when [candidateUrl] is a Matrix API path (`/_matrix/...`) served by
     * [homeserverUrl]'s origin.
     *
     * Both halves are required. The path test narrows *what* is being requested;
     * the origin test decides *who* is being asked. Requiring both means neither
     * one alone can be satisfied by an attacker: a hostile host cannot pass the
     * origin test, and a same-origin non-Matrix path cannot pass the path test.
     */
    fun isMatrixOrigin(candidateUrl: String, homeserverUrl: String?): Boolean {
        if (!isSameOrigin(candidateUrl, homeserverUrl)) return false
        return isMatrixPath(candidateUrl)
    }

    /**
     * True when [candidateUrl]'s path starts at a `/_matrix/` segment.
     *
     * Anchored on a path *segment* rather than a substring so that a hostile host
     * name such as `https://attacker.example/evil/_matrix/` is not treated as
     * Matrix traffic on its own. This is a secondary filter: on its own it says
     * nothing about which server is being contacted.
     */
    fun isMatrixPath(candidateUrl: String): Boolean {
        val path = runCatching {
            val url = Url(candidateUrl.trim())
            url.encodedPath
        }.getOrNull() ?: return false
        return path == "/_matrix" || path.startsWith("/_matrix/") ||
            path.contains("/_matrix/") || path.endsWith("/_matrix")
    }

    /**
     * The policy actually used before attaching a credential to an outbound
     * request: the homeserver origin, plus the Matrix API path on that origin.
     *
     * Returns false rather than throwing, so a bad URL simply gets no header.
     */
    fun mayAttachCredential(candidateUrl: String, homeserverUrl: String?): Boolean =
        isMatrixOrigin(candidateUrl, homeserverUrl)
}