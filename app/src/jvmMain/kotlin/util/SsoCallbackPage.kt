package util

/**
 * The HTML page Orbit serves to the browser at the end of an SSO login.
 *
 * ## Why this is a real file and not an inline string
 *
 * The original was one line of markup:
 *
 * ```kotlin
 * val htmlResponse = "<html><body><h1>Login Successful</h1><p>Return to Orbit.</p></body></html>"
 * ```
 *
 * which had two problems, the second much worse than the first.
 *
 * **It only existed on the happy path.** Every failure -- the state check, a missing
 * `loginToken`, a malformed request -- threw straight out of the socket block, so no
 * response was ever written. The browser got a connection reset and rendered
 * `ERR_CONNECTION_RESET` or a blank tab. Meanwhile the app side showed a real
 * explanation ("an old browser tab finished a previous login"), so the one screen
 * where the user is guaranteed to be looking, the browser, said nothing at all. This
 * file exists so both outcomes render.
 *
 * **It looked unbranded.** A bare `<h1>` on white is not something you want a user to
 * see while they think they are signing in to their chat client.
 *
 * ## Constraints this page has to respect
 *
 * It is served from an ephemeral loopback port, so:
 *
 *  - **No external requests.** No web fonts, no CDN, no analytics, no favicon from
 *    the internet. A login page that phones a third party would leak the fact that
 *    someone is authenticating, and would render differently depending on network
 *    access. Everything is inline; typography uses the system UI stack.
 *  - **No scripts.** There is nothing here to compute. A script on a page rendered
 *    into a browser that just handled an SSO token is a liability, not a feature.
 *  - **The token is never echoed into the page.** It stays in the query string the
 *    app already read; nothing derived from it is printed.
 */
internal object SsoCallbackPage {

    /**
     * The `Content-Security-Policy` sent with this page.
     *
     * Exposed as a constant rather than inlined in the socket code so it can be
     * asserted against the markup: the page's styles are inline, it has no script and
     * loads nothing, so `default-src 'none'` is exactly what it needs. If someone
     * later adds a `<script>` or an external font, the test comparing this string to
     * the rendered HTML is what catches it.
     */
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'"

    /** Orbit's theme seed, matching `LocalThemeSeedColor`. */
    private const val ACCENT = "#6750A4"

    /** Page background and card, lifted from the dark palette in `ui/theme`. */
    private const val BG = "#111214"
    private const val CARD = "#1A1B1E"
    private const val BORDER = "#2A2B2F"
    private const val TEXT = "#F2F1F7"
    private const val MUTED = "#9EA0A8"
    private const val DANGER = "#E5484D"
    private const val OK = "#46A758"

    /**
     * Render the success page.
     *
     * [homeserverHost] is shown so the user can confirm at a glance which account
     * they just signed into -- with SSO that is genuinely non-obvious, and it is the
     * one moment where pointing at the wrong account matters.
     */
    fun success(homeserverHost: String?): String = page(
        title = "Signed in to Orbit",
        accent = OK,
        glyph = checkGlyph,
        heading = "You're signed in",
        body = buildString {
            append("Return to Orbit and you're done.")
            if (!homeserverHost.isNullOrBlank()) {
                append("<span class=\"meta\">Homeserver <strong>")
                append(escapeHtml(homeserverHost))
                append("</strong></span>")
            }
        },
        footnote = "This tab can be closed.",
    )

    /**
     * Render the failure page.
     *
     * [detail] is the app-side reason, written for a person rather than a log. It is
     * escaped: it can contain an exception message, and an exception message can
     * contain a homeserver's response body.
     */
    fun failure(detail: String): String = page(
        title = "Orbit sign-in failed",
        accent = DANGER,
        glyph = crossGlyph,
        heading = "Sign-in didn't complete",
        body = "<span class=\"detail\">${escapeHtml(detail)}</span>",
        footnote = "Go back to Orbit and try again.",
    )

    private fun page(
        title: String,
        accent: String,
        glyph: String,
        heading: String,
        body: String,
        footnote: String,
    ): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<meta name="robots" content="noindex, nofollow">
<title>$title &middot; Orbit</title>
<style>
  /* System font stack only. A web font would be an outbound request from the page
     that just handled a login token. */
  :root {
    color-scheme: dark;
    --accent: $accent;
    --bg: $BG;
    --card: $CARD;
    --border: $BORDER;
    --text: $TEXT;
    --muted: $MUTED;
  }
  * { box-sizing: border-box; }
  html, body { height: 100%; margin: 0; }
  body {
    background: var(--bg);
    color: var(--text);
    font: 15px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto,
          "Helvetica Neue", Arial, sans-serif;
    display: grid;
    place-items: center;
    padding: 24px;
    -webkit-font-smoothing: antialiased;
  }
  /* A single soft radial wash in the brand seed. No image request: this is a
     gradient, which the compositor generates locally. */
  body::before {
    content: "";
    position: fixed;
    inset: 0;
    background: radial-gradient(
      60vmax 60vmax at 50% -10%,
      color-mix(in srgb, $ACCENT 22%, transparent),
      transparent 70%
    );
    pointer-events: none;
  }
  main {
    position: relative;
    width: min(30rem, 100%);
    background: var(--card);
    border: 1px solid var(--border);
    border-radius: 16px;
    padding: 2rem 1.75rem;
    text-align: center;
    box-shadow: 0 1px 0 rgba(255,255,255,.04) inset, 0 18px 50px -20px rgba(0,0,0,.8);
  }
  .mark {
    width: 56px;
    height: 56px;
    margin: 0 auto 1.25rem;
    display: grid;
    place-items: center;
    border-radius: 50%;
    border: 1px solid var(--border);
    background: color-mix(in srgb, $ACCENT 16%, transparent);
    color: var(--accent);
  }
  .mark svg { width: 28px; height: 28px; display: block; }
  h1 {
    margin: 0 0 .5rem;
    font-size: 1.25rem;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  p { margin: 0; color: var(--muted); }
  .detail {
    display: block;
    margin-top: 1rem;
    padding: .75rem .9rem;
    border: 1px solid var(--border);
    border-radius: 10px;
    background: rgba(0,0,0,.25);
    color: var(--text);
    text-align: left;
    font-size: .875rem;
    overflow-wrap: anywhere;
  }
  .meta {
    display: block;
    margin-top: 1rem;
    padding-top: 1rem;
    border-top: 1px solid var(--border);
    font-size: .8125rem;
    color: var(--muted);
  }
  .meta strong { color: var(--text); font-weight: 500; overflow-wrap: anywhere; }
  footer {
    margin-top: 1.5rem;
    font-size: .8125rem;
    color: var(--muted);
    opacity: .8;
  }
  @media (prefers-reduced-motion: no-preference) {
    main { animation: rise .32s cubic-bezier(.2,.8,.2,1) both; }
    @keyframes rise {
      from { opacity: 0; transform: translateY(6px); }
      to   { opacity: 1; transform: none; }
    }
  }
</style>
</head>
<body>
<main>
  <div class="mark" aria-hidden="true">$glyph</div>
  <h1>$heading</h1>
  $body
  <footer>$footnote</footer>
</main>
</body>
</html>
""".trimIndent()

    private const val checkGlyph =
        """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
           stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>"""

    private const val crossGlyph =
        """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"
           stroke-linecap="round" stroke-linejoin="round"><path d="M18 6 6 18M6 6l12 12"/></svg>"""

    /**
     * Escape text interpolated into the page.
     *
     * Applied to the homeserver host and, more importantly, to [SsoCallbackPage.failure]'s
     * detail string -- which is built from an exception message, and a Ktor exception
     * message can carry a homeserver's response body. Without this, an error page is a
     * reflected-XSS vector on a page the user is guaranteed to be staring at.
     */
    internal fun escapeHtml(raw: String): String = buildString(raw.length + 16) {
        for (c in raw) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}