package util

import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.util.Collections
import java.util.WeakHashMap

/**
 * System tray balloons for incoming/missed calls, suppressed while the main
 * window owns focus. Reuses the TrayIcon Compose Desktop already registered in
 * the system tray instead of adding a second one; silently no-ops when no tray
 * is available (headless, unsupported platform).
 */
object CallNotifier {
    @Volatile private var windowFocused = true

    // Weak keys so a closed window does not keep its listener alive, and so
    // re-attaching the same window replaces rather than stacks listeners.
    private val focusListeners = Collections.synchronizedMap(WeakHashMap<Window, WindowFocusListener>())

    fun attachWindow(window: Window) {
        // Re-attach would otherwise add a second listener for the same window.
        focusListeners[window]?.let { window.removeWindowFocusListener(it) }

        windowFocused = window.isFocused
        val listener = object : WindowFocusListener {
            override fun windowGainedFocus(e: WindowEvent?) {
                windowFocused = true
            }

            override fun windowLostFocus(e: WindowEvent?) {
                windowFocused = false
            }
        }
        focusListeners[window] = listener
        window.addWindowFocusListener(listener)
    }

    /** Call on window disposal, otherwise the listener outlives the window. */
    fun detachWindow(window: Window) {
        focusListeners.remove(window)?.let { window.removeWindowFocusListener(it) }
    }

    fun show(title: String, body: String) {
        if (windowFocused) return
        val icon = try {
            if (SystemTray.isSupported()) SystemTray.getSystemTray().trayIcons.firstOrNull() else null
        } catch (e: Exception) {
            // Headless or no tray: expected, but previously indistinguishable
            // from a broken notifier.
            System.err.println("DEBUG [CallNotifier] system tray unavailable: ${e.message}")
            null
        } ?: run {
            System.err.println("DEBUG [CallNotifier] no TrayIcon registered; skipping notification '$title'")
            return
        }
        try {
            EventQueue.invokeLater {
                try {
                    icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
                } catch (e: Exception) {
                    System.err.println("WARN [CallNotifier] displayMessage failed for '$title': ${e.message}")
                }
            }
        } catch (e: Exception) {
            System.err.println("WARN [CallNotifier] could not post '$title' to the EDT: ${e.message}")
        }
    }
}
