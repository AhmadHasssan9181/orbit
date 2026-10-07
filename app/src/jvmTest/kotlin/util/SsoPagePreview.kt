package util

import java.io.File

/**
 * Writes the two SSO callback pages to disk so they can be opened in a browser.
 *
 * Not part of the app. This exists so the markup can be reviewed and screenshotted
 * without having to drive a full SSO round-trip through a homeserver, which is the
 * only other way to see these pages.
 *
 * Run with:
 *   ./gradlew --offline :app:jvmTest --tests '*SsoPagePreviewKt'
 *
 * then open the two files it prints.
 */
private fun main() {
    val outDir = File(System.getProperty("java.io.tmpdir"), "orbit-sso-preview")
    outDir.mkdirs()

    val success = File(outDir, "sso-success.html")
    success.writeText(SsoCallbackPage.success("matrix.example.org"))
    println("wrote ${success.absolutePath}")

    val failure = File(outDir, "sso-failure.html")
    failure.writeText(
        SsoCallbackPage.failure(
            "The sign-in response did not match this login attempt (state absent). " +
                "This usually means an old browser tab finished a previous login. " +
                "Close any other Orbit sign-in tabs and start again."
        )
    )
    println("wrote ${failure.absolutePath}")
}