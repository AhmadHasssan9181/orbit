package ui.screen.media

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import util.OrbitLog

/**
 * Bounded download of third-party GIFs.
 *
 * ## Why this file exists
 *
 * `GifPicker` did `response.readBytes()` with no size limit, on a client created as
 * a bare `HttpClient()` in `MessageInputBar.kt` -- which installs **no timeout
 * plugin at all**. Two problems, both from trusting a remote response:
 *
 *  - A large or slow-streaming body was read entirely into the heap on every GIF
 *    click. `MediaPicker` caps local attachments at 100 MB; the asymmetry was
 *    unexplained and there was no cap here at all.
 *  - No timeout meant a hung connection hung the coroutine indefinitely, leaving
 *    the composable in `isLoading = true` with no way out.
 *
 * So the fix is a single place that does both jobs: a bounded read that fails
 * instead of allocating without limit, and a hard cap on the search JSON too.
 */

/** Maximum GIF payload accepted, in bytes. A "tinygif" is normally well under 1 MB. */
private const val MAX_GIF_BYTES = 8L * 1024 * 1024

/** Maximum search-response payload. This is JSON listing ~20 thumbnails. */
private const val MAX_JSON_BYTES = 2L * 1024 * 1024

/**
 * Client used for Tenor, with real timeouts.
 *
 * `HttpClient()` with no engine block picks the default engine and, critically, no
 * timeout plugin -- so a stalled request had no upper bound. Every field here is set
 * deliberately: connect timeout covers the TCP/TLS handshake, socket timeout bounds
 * the gap between reads, and the request timeout bounds the whole exchange.
 */
internal fun tenorHttpClient(engine: HttpClientEngine? = null): HttpClient =
    if (engine == null) {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    } else {
        HttpClient(engine) {
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 15_000
            }
        }
    }

/** Thrown when a response exceeds the byte budget for its purpose. */
class ResponseTooLargeException(val limit: Long) :
    Exception("response exceeded the ${limit / 1024 / 1024} MB limit", null)

/**
 * Read [response] fully, refusing to allocate more than [maxBytes].
 *
 * `bodyAsChannel` + `readAvailable` into a fixed buffer means the allocation is
 * capped by [maxBytes] rather than by whatever the server decides to send, and the
 * loop aborts mid-stream instead of after the fact. That matters: `readBytes()`
 * sizes its buffer from the response, so a hostile or broken Content-Length is
 * already resident before any check could run.
 *
 * A declared `Content-Length` above the budget is rejected up front, which saves
 * the round trip entirely.
 */
suspend fun readBounded(
    response: HttpResponse,
    maxBytes: Long,
    what: String
): ByteArray = withContext(Dispatchers.IO) {
    val declared = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        ?: response.contentLength()
    if (declared != null && declared > maxBytes) {
        // Abort the body without draining it. The declared length already tells us
        // this is over budget, so there is no reason to read a single byte.
        response.bodyAsChannel().cancel(ResponseTooLargeException(maxBytes))
        throw ResponseTooLargeException(maxBytes)
    }

    val channel: ByteReadChannel = response.bodyAsChannel()
    val out = java.io.ByteArrayOutputStream(
        minOf(declared ?: maxBytes, maxBytes).coerceAtLeast(1024L).toInt()
    )
    val buffer = ByteArray(DEFAULT_CHUNK)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read == -1) break
        if (read == 0) continue
        total += read
        if (total > maxBytes) {
            // Stop reading now. The remaining bytes are never buffered, which is the
            // whole point: `readBytes()` would have sized its buffer from the
            // response and already held the oversized body.
            channel.cancel(ResponseTooLargeException(maxBytes))
            throw ResponseTooLargeException(maxBytes)
        }
        out.write(buffer, 0, read)
    }
    out.toByteArray()
}

/**
 * Fetch a GIF with a hard size cap and no unbounded read.
 *
 * Also rejects a non-image content type. Tenor is asked for `tinygif`, so an HTML
 * error page arriving with a 200 would otherwise be handed to the attachment
 * uploader as if it were an image.
 */
suspend fun downloadGifBounded(client: HttpClient, url: String): ByteArray {
    val response = client.get(url)
    if (!response.status.isSuccess()) {
        throw IllegalStateException("download failed with HTTP ${response.status.value}", null)
    }
    // Only the media type, no charset/parameters: a server may send
    // `image/gif;charset=binary` and that is still an image.
    val contentType = response.headers[HttpHeaders.ContentType]
        ?.toString()
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
    if (contentType != null && !contentType.startsWith("image/")) {
        OrbitLog.w("GifPicker", "refusing a non-image GIF response: $contentType")
        throw IllegalStateException("expected an image, got $contentType", null)
    }
    return readBounded(response, MAX_GIF_BYTES, "gif")
}

/** Fetch a Tenor search/featured JSON listing, bounded. */
suspend fun fetchTenorJson(client: HttpClient, url: String): ByteArray {
    val response = client.get(url)
    if (!response.status.isSuccess()) {
        throw IllegalStateException("Tenor responded ${response.status.value}", null)
    }
    return readBounded(response, MAX_JSON_BYTES, "tenor-json")
}

private const val DEFAULT_CHUNK = 16 * 1024