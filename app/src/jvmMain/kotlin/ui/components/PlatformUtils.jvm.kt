package ui.components

import java.awt.Desktop
import java.net.URI

actual fun openUrlInBrowser(url: String) {
    // SECURITY: scheme allowlist.
    //
    // `java.awt.Desktop.browse` forwards to the OS handler and on Windows honours
    // `file:`, UNC (`\\host\share`) and SMB. The only caller is the login screen's
    // policy link (`m.policy` from /login), so the URL comes from the homeserver
    // rather than from a room member -- which lowers the stakes but does not change
    // the shape: a homeserver that returns `smb://` or `file://` in a policy URL
    // should not be able to make the client launch a handler for it.
    // util.safeExternalOpenUrl is the single allowlist for this decision; the
    // util.Browser.jvm.kt implementation has the same guard.
    val safe = util.safeExternalOpenUrl(url)
    if (safe == null) return
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(safe))
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
