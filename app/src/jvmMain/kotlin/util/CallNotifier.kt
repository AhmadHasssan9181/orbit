package util

import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * System tray balloons for incoming/missed calls, suppressed while the main
 * window owns focus. Reuses the TrayIcon Compose Desktop already registered in
 * the system tray instead of adding a second one; silently no-ops when no tray
 * is available (headless, unsupported platform).
 */
object CallNotifier {
    @Volatile private var windowFocused = true

    fun attachWindow(window: Window) {
        windowFocused = window.isFocused
        window.addWindowFocusListener(object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                windowFocused = true
            }

            override fun windowLostFocus(e: WindowEvent?) {
                windowFocused = false
            }
        })
    }

    fun show(title: String, body: String) {
        if (windowFocused) return
        val icon = try {
            if (SystemTray.isSupported()) SystemTray.getSystemTray().trayIcons.firstOrNull() else null
        } catch (_: Exception) {
            null
        } ?: return
        try {
            EventQueue.invokeLater {
                try {
                    icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
