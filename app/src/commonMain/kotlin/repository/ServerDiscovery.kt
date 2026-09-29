package repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What `GET /.well-known/matrix/client` told us about a server.
 *
 * The spec's discovery flow is: take the domain from a user's Matrix ID, fetch
 * `https://<domain>/.well-known/matrix/client`, read `m.homeserver.base_url`,
 * then validate it with `GET <base_url>/_matrix/client/versions`.
 *
 * The three failure modes the spec distinguishes, and which one this is:
 *  - 404 / not found  -> the server does not delegate; use the domain directly
 *  - non-200 or empty -> that is an ERROR
 *  - present but unparseable -> that is an ERROR
 */
sealed interface ServerDiscoveryResult {
    /** The domain itself hosts the client; no delegation. */
    data class Direct(val baseUrl: String) : ServerDiscoveryResult

    /** Delegated to a different base URL, and `/_matrix/client/versions` answered. */
    data class Delegated(val baseUrl: String) : ServerDiscoveryResult

    /** `/.well-known/matrix/client` returned 404, so the domain is the homeserver. */
    data object NotDelegated : ServerDiscoveryResult

    /** The well-known file existed but was unusable. */
    data class Malformed(val detail: String) : ServerDiscoveryResult

    /** The base URL did not answer `/_matrix/client/versions`. */
    data class Unreachable(val detail: String) : ServerDiscoveryResult
}

/** The domain part of a Matrix ID, or of a bare homeserver URL. */
fun matrixServerNameFrom(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    // A full MXID: @alice:example.com:8448
    if (trimmed.startsWith("@")) {
        val afterColon = trimmed.substringAfter(':', "")
        return afterColon.ifBlank { null }?.removePrefix("matrix.to/")
    }

    // Otherwise treat it as a URL or a bare domain.
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val host = runCatching { io.ktor.http.Url(withScheme).host }.getOrNull()
    if (!host.isNullOrBlank()) return host

    // Bare "example.com" with no scheme: Url may not parse it, so take the text.
    val bare = trimmed.substringBefore('/').substringBefore(':')
    return bare.takeIf { it.contains('.') }
}

/**
 * Reads `m.homeserver.base_url` out of a `.well-known/matrix/client` body.
 *
 * @return the base URL, or null when the file is present but has no usable one.
 */
fun parseWellKnownClient(raw: String): String? {
    val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    val homeserver = obj["m.homeserver"] as? JsonObject ?: return null
    val baseUrl = (homeserver["base_url"] as? JsonPrimitive)?.content ?: return null
    // The spec permits a trailing slash; the normalizer strips it anyway, but
    // reject an obviously relative value rather than building a broken URL.
    if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return null
    return baseUrl.trimEnd('/')
}

/** True when a `/_matrix/client/versions` body is a plausible Matrix client API. */
fun looksLikeMatrixVersions(raw: String): Boolean {
    val versions = runCatching {
        (Json.parseToJsonElement(raw) as? JsonObject)?.get("versions") as? JsonArray
    }.getOrNull() ?: return false
    return versions.any { (it as? JsonPrimitive)?.content?.startsWith("v1.") == true }
}
