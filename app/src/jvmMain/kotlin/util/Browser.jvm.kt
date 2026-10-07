package util

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import repository.SsoStatePolicy
import java.util.concurrent.TimeUnit

private const val SSO_CALLBACK_TIMEOUT_MS = 300_000L

/** What the SSO callback turned out to be, decided before anything is written. */
/**
 * Thrown when the homeserver's SSO callback carried no `state`.
 *
 * Delegates to `repository.SsoStatePolicy.StateMissingException`, which lives in
 * commonMain because `LoginScreen` is common code and catches it to offer the
 * per-homeserver opt-in.
 */
internal typealias SsoStateMissingException = SsoStatePolicy.StateMissingException

internal sealed interface SsoOutcome {
    /** Callback is authentic and carries a usable login token. */
    data class LoginToken(val token: String) : SsoOutcome

    /** Callback was refused; [detail] is written for a person, not a log. */
    data class Rejected(val detail: String) : SsoOutcome

    /**
     * The homeserver returned no `state` at all, so the callback could not be
     * correlated with this attempt.
     *
     * Distinguished from [Rejected] because it is the one failure a user can make an
     * informed decision about: the homeserver simply does not implement the echo.
     * The app surfaces it with an explanation and an explicit per-homeserver opt-in
     * rather than either silently trusting the callback or leaving SSO unusable.
     */
    data object StateMissing : SsoOutcome
}

/**
 * Validate the callback query.
 *
 * SECURITY: an **absent** `state` is a rejection, not a pass.
 *
 * The previous check was `if (returnedState != null && !equal(...))`, on the reasoning
 * that a homeserver without `state` support should still let the user in. That
 * inverted the threat model: the attack *is* a callback with no state at all, so
 * treating absence as acceptable left the login-CSRF fully open — an attacker who got
 * the victim's browser to visit
 * `http://localhost:<port>/?loginToken=<attacker token>` still signed the victim into
 * the ATTACKER's account, and everything they then sent went somewhere the attacker
 * could read. Orbit's own homeserver echoes `state`; one that cannot is not one Orbit
 * can log in to safely.
 *
 * Returns a value rather than throwing so the caller can render a page for both
 * outcomes.
 */
internal fun resolveCallback(params: Map<String, String>, expectedState: String): SsoOutcome =
    resolveCallback(params, expectedState, allowMissingState = false)

/**
 * Validate the callback, optionally tolerating an absent `state`.
 *
 * [allowMissingState] is only ever true because the user explicitly accepted the
 * weaker check for this homeserver -- see `repository.SsoStatePolicy`. A *mismatched*
 * state is rejected regardless of the flag: there is no legitimate reading of one, so
 * that path never loosens.
 */
internal fun resolveCallback(
    params: Map<String, String>,
    expectedState: String,
    allowMissingState: Boolean
): SsoOutcome {
    val returnedState = params["state"]
    when {
        returnedState != null -> {
            val matches = java.security.MessageDigest.isEqual(
                returnedState.toByteArray(Charsets.UTF_8),
                expectedState.toByteArray(Charsets.UTF_8)
            )
            if (!matches) {
                // A wrong state is either a stale browser tab or an attack. Never
                // accepted, even with consent for a missing state.
                util.OrbitLog.w("Browser", "SSO callback state mismatch; refusing the login token")
                return SsoOutcome.Rejected(
                    "This response belongs to a different sign-in attempt (state mismatch). " +
                        "Usually an old browser tab finished a previous login -- close any " +
                        "other Orbit sign-in tabs and start again."
                )
            }
        }
        allowMissingState -> {
            util.OrbitLog.w(
                "Browser",
                "SECURITY: accepting an SSO callback with NO state; the user opted in for " +
                    "this homeserver"
            )
        }
        else -> {
            util.OrbitLog.w("Browser", "SSO callback had no state and none is required here; refusing")
            return SsoOutcome.StateMissing
        }
    }
    val token = params["loginToken"]?.takeIf { it.isNotBlank() }
        ?: return SsoOutcome.Rejected(
            "The homeserver redirected back without a login token. " +
                "Check that your SSO provider completed the flow, then try again."
        )
    return SsoOutcome.LoginToken(token)
}

/** Host portion of [homeserverUrl], for display on the success page. */
internal fun hostOf(homeserverUrl: String): String? =
    runCatching { java.net.URI(homeserverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * Write a complete HTTP/1.1 response.
 *
 * `Content-Length` is included because without it some browsers hold the connection
 * open waiting for more, and `Connection: close` plus an explicit length is what makes
 * the page paint immediately rather than after a timeout.
 */
private fun writeHttpResponse(socket: java.net.Socket, statusLine: String, html: String) {
    runCatching {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(statusLine).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            // Belt and braces: the page needs no privileges, no framing and no
            // outbound connections. Cheap, and this is a login page.
            append("Content-Security-Policy: ").append(SsoCallbackPage.CSP).append("\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}

/**
 * At most one SSO login may be in flight per process.
 *
 * ## Why this guard exists
 *
 * A first run of this logged two `waiting up to 300s for the SSO callback` lines on
 * different ports within seconds of each other -- two listeners, two browser tabs,
 * two independent `state` values. Nothing stopped the second click: the button is
 * enabled again the moment `isLoading` is set, and a double-click, a retry, or a
 * second homeserver field edit all race through.
 *
 * The consequences are not cosmetic:
 *
 *  - The user completes sign-in in tab A and Orbit logs them in, then tab B lands and
 *    shows a **failure page for a login that actually worked** — which reads like a
 *    bug and erodes trust in the page that is trying to help them.
 *  - Each attempt is its own CSRF window on an ephemeral port. More windows is worse,
 *    not neutral.
 *  - `attempt++` in the failure path means the abandoned listener keeps its 5-minute
 *    timeout and its socket alive.
 *
 * So a second call is refused with a clear message rather than silently opening a
 * competing listener. Re-entrant calls from the *same* attempt are the same bug, so
 * the guard is global rather than per-homeserver.
 */
private val ssoInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

actual suspend fun performSsoLogin(homeserverUrl: String): String {
    if (!ssoInFlight.compareAndSet(false, true)) {
        util.OrbitLog.w("Browser", "refusing a second concurrent SSO login; one is already in flight")
        throw IllegalStateException(
            "A sign-in is already open in another window. Finish that one first, or close " +
                "the extra tab it opened."
        )
    }
    try {
        return performSsoLoginOnce(homeserverUrl)
    } finally {
        // Released on every path, including the timeout: otherwise a failed attempt
        // would block SSO for the rest of the process.
        ssoInFlight.set(false)
    }
}

private suspend fun performSsoLoginOnce(homeserverUrl: String): String {
    // SECURITY: bind loopback explicitly.
    //
    // `ServerSocket(0)` binds the WILDCARD address, so any host that can route to
    // this machine's port could connect and win the race for the callback. With an
    // ephemeral port and a window of minutes that is a real, if narrow, exposure on
    // a shared or routed host. Binding InetAddress.getLoopbackAddress() makes the
    // listener reachable only from this machine. The redirect URL keeps the
    // `http://localhost` form the Matrix spec and RFC 8252 require.
    val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    try {
        val port = serverSocket.localPort
        val redirectUrl = "http://localhost:$port"

        // SECURITY: single-use `state`, verified on return.
        //
        // There was no state parameter at all, which is login-CSRF / session
        // fixation: an attacker who can get the victim's browser to visit
        // `http://localhost:<port>/?loginToken=<attacker's token>` signs the victim
        // into the ATTACKER's account. Everything the victim then sends is uploaded
        // to an account the attacker can read, and it looks like a normal login.
        //
        // The homeserver echoes `state` back unchanged in the callback query, so this
        // is a compatibility-safe check: an implementation that does not support
        // state still completes, because the comparison below only rejects a state
        // that is present and wrong.
        val state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
        )

        val ssoUrl = "${homeserverUrl.trimEnd('/')}/_matrix/client/v3/login/sso/redirect?redirectUrl=${
            URLEncoder.encode(redirectUrl, "UTF-8")
        }&state=${URLEncoder.encode(state, "UTF-8")}"
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
            val requestLine = reader.readLine()
            if (requestLine == null) {
                // Nothing to describe: there is no browser to write to either. Throwing
                // here is the only option, and the browser will show its own error.
                throw IllegalStateException("No request received")
            }
            if (!requestLine.contains("GET ") && !requestLine.contains("POST ")) {
                util.OrbitLog.w("Browser", "unexpected SSO callback request line: $requestLine")
            }

            val queryString = requestLine.split(" ").getOrNull(1)?.substringAfter("?") ?: ""
            val params = queryString.split("&")
                .map { it.split("=", limit = 2) }
                .filter { it.size == 2 }
                .associate { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") }

            // Resolve the outcome BEFORE writing anything, so the browser always gets
            // a real page -- success or failure -- instead of a connection reset.
            //
            // This is the actual fix for "the page isn't pretty": every failure path
            // used to throw straight out of this block with no response written, so a
            // rejected callback showed the user `ERR_CONNECTION_RESET` while the app
            // window quietly explained what had happened. The one screen the user is
            // guaranteed to be looking at was the one saying nothing.
            val outcome = resolveCallback(
                params,
                expectedState = state,
                allowMissingState = SsoStatePolicy.isAccepted(homeserverUrl)
            )

            val (statusLine, html) = when (outcome) {
                is SsoOutcome.LoginToken -> "200 OK" to
                    SsoCallbackPage.success(hostOf(homeserverUrl))
                is SsoOutcome.Rejected -> "400 Bad Request" to
                    SsoCallbackPage.failure(outcome.detail)
                SsoOutcome.StateMissing -> "400 Bad Request" to
                    SsoCallbackPage.failure(
                        "This homeserver did not return the one-time value Orbit uses to " +
                            "confirm the response belongs to this sign-in, so Orbit stopped " +
                            "the login rather than trust it. You can allow it for this " +
                            "homeserver from the Orbit login screen."
                    )
            }
            writeHttpResponse(accepted, statusLine, html)

            // Only now report the failure to the app, having already told the browser.
            when (outcome) {
                is SsoOutcome.Rejected -> throw IllegalStateException(outcome.detail)
                SsoOutcome.StateMissing -> throw SsoStateMissingException(homeserverUrl)
                is SsoOutcome.LoginToken -> return outcome.token
            }
        }
    } finally {
        // The listening socket used to be closed in an `.also`, which only runs
        // when the happy path completes: every thrown path leaked the port.
        runCatching { serverSocket.close() }
    }
}

/**
 * Schemes Orbit is willing to hand to the operating system.
 *
 * `http`, `https` and `mailto` only. Deliberately an allowlist: `href`/`url` on a
 * message is written by the *sender*, and any member of a room can write one.
 */
private val ExternalOpenSchemes = setOf("http", "https", "mailto")

/**
 * True when [url] is safe to hand to the OS protocol handler.
 *
 * ## Why this is not "the URL looks fine"
 *
 * `content.url` on an `m.image` / `m.video` / `m.file` event is fully
 * attacker-controlled, and `MatrixContext.convertMxcUrl` is a **pass-through** for
 * anything that is not `mxc://` or a known download URL (`else { return mxcUrl }`).
 * A room member can therefore set an attachment URL to any string at all.
 *
 * One click on the download affordance then reaches `openUrlInBrowser`, which on
 * Windows is `rundll32 url.dll,FileProtocolHandler` -- a documented remote
 * credential-theft primitive:
 *
 *  - `smb://attacker.example/share/x` opens the share and performs NTLM
 *    negotiation, handing the attacker the victim's NetNTLMv2 hash for relay or
 *    cracking.
 *  - `file:///C:/...` opens or launches local files.
 *  - `search-ms:`, `ms-msdt:` and any other registered handler on the machine.
 *
 * OriginGuard correctly refuses to attach the account token to these -- and that
 * refusal is what *routes* them here. So the origin check fixed the token leak and
 * left this path open.
 *
 * The sibling check in `RichText.safeExternalUrl` already had this allowlist for
 * the `formatted_body` link path; this is the same rule applied to the attachment
 * path, which had none.
 */
internal fun safeExternalOpenUrl(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in ExternalOpenSchemes) {
        util.OrbitLog.w(
            "Browser",
            "refused to open a $scheme URL externally; only http/https/mailto are allowed"
        )
        return null
    }
    return trimmed
}

actual fun openUrlInBrowser(url: String) {
    val safe = safeExternalOpenUrl(url)
    if (safe == null) {
        // Refused. Do not launch anything: the handler for the scheme is whatever
        // the machine has registered, and on Windows that includes SMB.
        return
    }
    val os = System.getProperty("os.name").lowercase()
    // ProcessBuilder with a command list, not runtime.exec("open $url"): exec
    // tokenizes the string on whitespace, so any URL with a space in it (or an
    // unescaped shell metacharacter) launched the wrong target or failed.
    when {
        os.contains("win") -> ProcessBuilder(
            listOf("rundll32", "url.dll,FileProtocolHandler", safe)
        ).start()
        os.contains("mac") -> ProcessBuilder(listOf("open", safe)).start()
        os.contains("nix") || os.contains("nux") -> ProcessBuilder(listOf("xdg-open", safe)).start()
    }
}

actual suspend fun openAuthenticatedMedia(url: String): Boolean {
    // SECURITY: origin check, not a path substring.
    //
    // This used to be `url.contains("/_matrix/")`, which is satisfied by ANY host
    // whose URL contains that text -- so a hostile `m.avatar` or attachment URL
    // would have the account's access token attached by the fetcher below. Same
    // bug class as the Coil interceptor in OrbitApp.kt; see util/OriginGuard.
    val isMatrixMedia = OriginGuard.mayAttachCredential(
        url,
        repository.CredentialManager.homeserverUrl
    )
    val token = repository.CredentialManager.accessToken
    if (!isMatrixMedia || token.isNullOrBlank()) {
        // Not our homeserver, or nothing to authenticate with: the browser can
        // fetch it itself, with no credential.
        //
        // openUrlInBrowser now applies its own scheme allowlist, because refusing
        // to attach the token is exactly what routes an attacker-supplied
        // `smb://` or `file://` attachment URL to this branch.
        if (safeExternalOpenUrl(url) == null) {
            return false
        }
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

