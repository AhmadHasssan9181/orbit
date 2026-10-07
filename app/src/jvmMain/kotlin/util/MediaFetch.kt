package util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import repository.CredentialManager

/**
 * Shared, authenticated media fetching.
 *
 * Matrix's authenticated media endpoint requires an `Authorization` header. That
 * rules out two obvious approaches and forces this one:
 *
 *  - The OS browser cannot be used. It is not Orbit and holds no credential, so a
 *    bare media URL answered there returns
 *    `{"errcode":"M_MISSING_TOKEN"}` instead of the image.
 *  - Putting the token in the query string works but leaks the long-lived account
 *    credential into the homeserver's access logs, into any reverse proxy's, into
 *    a Referer on redirect, and into libVLC's internal URL string (which surfaces
 *    verbatim in error text). It is the same token that logs into the account.
 *
 * So: fetch here with the header, and hand the CALLER either the bytes or a local
 * file. Used by the "open image/video externally" path and by voice-message
 * playback.
 */
internal val mediaDownloadClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        // SECURITY: pin the redirect policy explicitly rather than inheriting a
        // default whose guarantees live in someone else's dependency.
        //
        // What Ktor actually does (verified against the resolved ktor-client-core
        // 3.4.0): HttpRedirect is installed by default, follows redirects, and DOES
        // strip the Authorization header on a cross-authority hop -- it logs
        // "Removing Authorization header for cross-authority redirect". So the
        // feared "token replayed to a CDN" does not happen, and that part of the
        // concern was wrong.
        //
        // The real gap is `allowHttpsDowngrade`, which defaults to TRUE. The
        // header is only stripped when the *authority* changes, so a same-host
        // https -> http redirect keeps Authorization and sends the long-lived
        // account token in cleartext. A homeserver or an intercepting proxy only
        // has to answer one media request with a 302 to its own http:// endpoint.
        //
        // Setting it false turns that hop into "Blocked redirect ... due to HTTPS
        // downgrade" instead. Media still loads from CDNs, because those are
        // cross-authority and unaffected.
        //
        // `followRedirects` is intentionally not set: ktor 3.4.0's
        // HttpRedirectConfig only exposes `checkHttpMethod` and
        // `allowHttpsDowngrade`, and the plugin follows redirects when installed.
        install(HttpRedirect) {
            checkHttpMethod = false
            allowHttpsDowngrade = false
        }
    }
}

internal val mediaExtensionByContentType: Map<String, String> = mapOf(
    "image/png" to ".png",
    "image/jpeg" to ".jpg",
    "image/gif" to ".gif",
    "image/webp" to ".webp",
    "image/svg+xml" to ".svg",
    "image/avif" to ".avif",
    "image/bmp" to ".bmp",
    "video/mp4" to ".mp4",
    "video/webm" to ".webm",
    "video/quicktime" to ".mov",
    "audio/ogg" to ".ogg",
    "audio/opus" to ".opus",
    "audio/mpeg" to ".mp3",
    "audio/wav" to ".wav",
    "audio/x-wav" to ".wav",
    "audio/webm" to ".weba",
    "audio/mp4" to ".m4a",
    "audio/aac" to ".aac",
    "text/plain" to ".txt",
    "application/pdf" to ".pdf",
)

/** Content-Type, minus parameters and casing, mapped to a file extension. */
internal fun extensionForMediaContentType(raw: String?): String? {
    val normalized = raw?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return mediaExtensionByContentType[normalized]
}

/**
 * Matrix media ids carry no extension (`fEmFZfxsnUDRBBesGZXjCUIc`), so the
 * Content-Type is the only reliable source. When the server sends something
 * unhelpful, fall back to whatever the URL itself claims.
 */
internal fun guessMediaExtension(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val dot = path.lastIndexOf('.')
    if (dot < 0 || dot < path.lastIndexOf('/')) return null
    val ext = path.substring(dot)
    return ext.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
}

/** Result of an authenticated media fetch. */
internal sealed interface MediaFetch {
    /** Already on disk; delete when done. */
    data class LocalFile(val file: java.io.File) : MediaFetch

    /** Fetch failed. [detail] is safe to log. */
    data class Failure(val status: Int?, val detail: String) : MediaFetch
}

/**
 * Downloads media with the account token attached and writes it to a temp file.
 *
 * Returns [MediaFetch.LocalFile] for Matrix URLs (which need the header) and a
 * [MediaFetch.Failure] when the homeserver refuses or the request fails.
 */
internal suspend fun fetchAuthenticatedMedia(url: String): MediaFetch {
    val token = CredentialManager.accessToken
        ?: return MediaFetch.Failure(null, "no access token; sign in again")
    return try {
        val response = mediaDownloadClient.get(url) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val status = response.status.value
        if (status !in 200..299) {
            // Read the error body so the log says WHY: 403 is a token no longer
            // good, 404 is no such media. Those are very different problems and
            // "status 403" alone does not distinguish them.
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            MediaFetch.Failure(status, body.take(200).ifBlank { "no body" })
        } else {
            val bytes: ByteArray = response.body()
            if (bytes.isEmpty()) {
                MediaFetch.Failure(status, "0 bytes")
            } else {
                val ext = extensionForMediaContentType(response.headers[HttpHeaders.ContentType])
                    ?: guessMediaExtension(url)
                    ?: ".bin"
                val file = java.nio.file.Files.createTempFile("orbit-media-", ext).toFile()
                file.writeBytes(bytes)
                MediaFetch.LocalFile(file)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        MediaFetch.Failure(null, e.message ?: e::class.simpleName.orEmpty())
    }
}
