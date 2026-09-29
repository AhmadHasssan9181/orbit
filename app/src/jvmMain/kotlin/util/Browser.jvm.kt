package util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val SSO_CALLBACK_TIMEOUT_MS = 300_000L

actual suspend fun performSsoLogin(homeserverUrl: String): String {
    val serverSocket = ServerSocket(0)
    try {
        val port = serverSocket.localPort
        val redirectUrl = "http://localhost:$port"

        val ssoUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/login/sso/redirect?redirectUrl=${
            URLEncoder.encode(redirectUrl, "UTF-8")
        }"
        openUrlInBrowser(ssoUrl)

        serverSocket.soTimeout = SSO_CALLBACK_TIMEOUT_MS.toInt()
        val socket = try {
            println("DEBUG [Browser] waiting up to ${SSO_CALLBACK_TIMEOUT_MS / 1000}s for the SSO callback on port $port")
            serverSocket.accept()
        } catch (e: java.net.SocketTimeoutException) {
            // The old code let accept() time out silently and reported it as a
            // generic "No request received", hiding "you never finished the flow".
            throw IllegalStateException("Timed out after ${SSO_CALLBACK_TIMEOUT_MS / 1000}s waiting for the browser to complete SSO login")
        } catch (e: Exception) {
            throw IllegalStateException("SSO login failed: ${e.message}")
        }

        // `use` closes the accepted socket on every path, including throws.
        socket.use { accepted ->
            val reader = accepted.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: throw IllegalStateException("No request received")
            if (!requestLine.contains("GET ") && !requestLine.contains("POST ")) {
                println("WARN [Browser] unexpected SSO callback request line: $requestLine")
            }

            val queryString = requestLine.split(" ").getOrNull(1)?.substringAfter("?") ?: ""
            val loginToken = queryString.split("&")
                .map { it.split("=", limit = 2) }
                .firstOrNull { it[0] == "loginToken" }
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("No loginToken in the SSO callback")

            val htmlResponse = "<html><body><h1>Login Successful</h1><p>Return to Orbit.</p></body></html>"
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$htmlResponse"
            accepted.getOutputStream().write(response.toByteArray())
            accepted.getOutputStream().flush()
            return loginToken
        }
    } finally {
        // The listening socket used to be closed in an `.also`, which only runs
        // when the happy path completes: every thrown path leaked the port.
        runCatching { serverSocket.close() }
    }
}

actual fun openUrlInBrowser(url: String) {
    val os = System.getProperty("os.name").lowercase()
    val runtime = Runtime.getRuntime()
    // ProcessBuilder with a command list, not runtime.exec("open $url"): exec
    // tokenizes the string on whitespace, so any URL with a space in it (or an
    // unescaped shell metacharacter) launched the wrong target or failed.
    when {
        os.contains("win") -> ProcessBuilder(
            listOf("rundll32", "url.dll,FileProtocolHandler", url)
        ).start()
        os.contains("mac") -> ProcessBuilder(listOf("open", url)).start()
        os.contains("nix") || os.contains("nux") -> ProcessBuilder(listOf("xdg-open", url)).start()
    }
}

actual suspend fun openAuthenticatedMedia(url: String): Boolean {
    val isMatrixMedia = url.contains("/_matrix/")
    val token = repository.CredentialManager.accessToken
    if (!isMatrixMedia || token.isNullOrBlank()) {
        // Nothing secret here, or nothing to authenticate with: the browser can
        // fetch it itself.
        openUrlInBrowser(url)
        return true
    }

    // Shared with voice-message playback -- one fetcher, one set of rules about
    // where the account token may and may not appear.
    return when (val fetched = fetchAuthenticatedMedia(url)) {
        is MediaFetch.Failure -> {
            println("WARN [Browser] authenticated media fetch failed status=${fetched.status} url=$url detail=${fetched.detail}")
            false
        }
        is MediaFetch.LocalFile -> {
            println("DEBUG [Browser] saved authenticated media to ${fetched.file}")
            handLocalFileToOs(fetched.file.toPath())
        }
    }
}

/**
 * Matrix media ids have no extension (`fEmFZfxsnUDRBBesGZXjCUIc`), so the
 * Content-Type is the only reliable source. When the server sends something
 * unhelpful, fall back to whatever the URL itself claims.
 */
private fun guessExtension(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#')
    val dot = path.lastIndexOf('.')
    if (dot < 0 || dot < path.lastIndexOf('/')) return null
    val ext = path.substring(dot)
    return ext.takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
}

/**
 * Opens a real file with the OS default handler. `Desktop.open` is the correct
 * API but is absent from some headless/JRE configurations, so the shell
 * hand-off stays as the fallback rather than the primary.
 */
private fun handLocalFileToOs(file: java.nio.file.Path): Boolean {
    val opened = runCatching {
        if (java.awt.Desktop.isDesktopSupported() &&
            java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)
        ) {
            java.awt.Desktop.getDesktop().open(file.toFile())
            true
        } else {
            false
        }
    }.getOrElse { e ->
        println("WARN [Browser] Desktop.open failed: ${e.message}")
        false
    }
    if (!opened) {
        // Windows needs the file:// form; a bare path is treated as a URL scheme.
        openUrlInBrowser(file.toUri().toString())
    }
    return true
}

