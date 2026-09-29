package ui.screen

import ui.screen.main.OrbitApp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.screen.call.ActiveCallPipWindow
import ui.screen.installGlobalPttReleaseWatcher
import ui.screen.publishPttAwtKeyCodes
import ui.screen.settings.normalizedPttKeybind
import ui.screen.settings.parsePttCombo
import java.util.concurrent.atomic.AtomicBoolean

// H5: exitApplication() cannot terminate the JVM while JNI-attached native
// workers or stray non-daemon threads are alive (observed ~6 GB zombie after
// window close). Arm a daemon watchdog before every explicit quit: if the
// graceful shutdown has not finished the process within the budget, halt it.
// On a clean exit the JVM dies before the watchdog ever fires. The budget is
// generous because quitting mid-call first publishes the leave membership.
private val forcedExitArmed = AtomicBoolean(false)

private fun armForcedExitWatchdog(budgetMs: Long = 5_000) {
    if (!forcedExitArmed.compareAndSet(false, true)) return
    Thread {
        try {
            Thread.sleep(budgetMs)
        } catch (_: InterruptedException) {
        }
        println("WARN [Exit] graceful shutdown exceeded ${budgetMs}ms; forcing JVM halt")
        Runtime.getRuntime().halt(0)
    }.apply {
        isDaemon = true
        name = "orbit-exit-watchdog"
    }.start()
}

fun main() = application {
    util.installOrbitLogConsoleFilter()
    // Process-wide, installed once: guarantees the push-to-talk KeyUp is seen
    // even when a dialog that does not handle shortcuts owns focus.
    remember { installGlobalPttReleaseWatcher() }
    val settingsManager = remember { repository.SettingsManager() }
    util.OrbitLog.debugEnabled = settingsManager.debugLoggingSync()
    val minimizeToTray by settingsManager.minimizeToTray.collectAsState(false)
    // Push-to-talk combo is user-rebindable; parse once per stored change.
    val storedPttKeybind by settingsManager.pushToTalkKeybind.collectAsState("Unassigned")
    val pttBinding = remember(storedPttKeybind) { parsePttCombo(normalizedPttKeybind(storedPttKeybind)) }
    // The AWT-level release watcher cannot see the user's binding, so publish
    // its AWT key codes here. Without this the watcher would have to guess, and
    // a guess means either a stuck mic or a mic cut by unrelated keypresses.
    remember(pttBinding) {
        publishPttAwtKeyCodes(
            pttBinding?.let { b ->
                // Key.keyCode is the Unicode code point; map the two families
                // parsePttCombo can actually produce onto AWT's virtual codes.
                val char = b.key.keyCode.toInt().toChar()
                setOfNotNull(
                    if (b.ctrl) java.awt.event.KeyEvent.VK_CONTROL else null,
                    if (b.shift) java.awt.event.KeyEvent.VK_SHIFT else null,
                    if (b.alt) java.awt.event.KeyEvent.VK_ALT else null,
                    when {
                        char in 'a'..'z' -> java.awt.event.KeyEvent.VK_A + (char - 'a')
                        char in 'A'..'Z' -> java.awt.event.KeyEvent.VK_A + (char - 'A')
                        char in '0'..'9' -> java.awt.event.KeyEvent.VK_0 + (char - '0')
                        else -> null
                    }
                )
            } ?: emptySet()
        )
    }

    // A messenger is a window you keep open all day, so it opens large enough
    // to show the rail, the channel list, the chat and the member panel at
    // once (68 + 248 + 264 = 580dp of chrome) and is positioned rather than
    // cascading off the top-left corner of the screen.
    val state = rememberWindowState(
        size = androidx.compose.ui.unit.DpSize(1320.dp, 860.dp),
        position = WindowPosition(Alignment.Center)
    )
    var windowRef by remember { mutableStateOf<java.awt.Window?>(null) }
    // Tracks the tray-hide path so the floating call PiP window knows when
    // the main window is gone (iconify is read straight off [state]).
    var mainWindowPresentable by remember { mutableStateOf(true) }

    // Quit: end any active call first (the leave publish races the exit
    // watchdog inside its grace window, never blocks), then exit.
    fun quitOrbit() {
        armForcedExitWatchdog()
        webrtc.CallShortcuts.onAppQuitRequested?.invoke()
        exitApplication()
    }

    // Close hides to tray only when tray mode is explicitly enabled in
    // settings; otherwise closing the window quits the whole app.
    fun closeRequested() {
        if (minimizeToTray) {
            windowRef?.isVisible = false
            mainWindowPresentable = false
        } else quitOrbit()
    }

    fun restoreMainWindow() {
        state.isMinimized = false
        windowRef?.isVisible = true
        mainWindowPresentable = true
        windowRef?.toFront()
    }

    Window(
        onCloseRequest = ::closeRequested,
        state = state,
        title = "Orbit",
        undecorated = true,
        transparent = false,
        icon = painterResource("logo.jpeg"),
        onPreviewKeyEvent = { event -> dispatchOrbitShortcut(event, pttBinding) }
    ) {
        // Both of these used to run in the composable body, so every
        // recomposition re-assigned windowRef and called attachWindow again —
        // and attachWindow() adds a fresh WindowFocusListener each time with no
        // way to remove it.
        LaunchedEffect(Unit) {
            windowRef = window
            util.CallNotifier.attachWindow(window)
        }
        DisposableEffect(Unit) {
            // Push-to-talk latches on KeyUp of its combo; if the window loses
            // focus while the key is still down no further key event will ever
            // arrive, so release it here or the mic stays hot.
            val focusListener = object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent?) = Unit
                override fun windowLostFocus(e: java.awt.event.WindowEvent?) = forceReleasePushToTalk()
            }
            window.addWindowFocusListener(focusListener)
            onDispose {
                window.removeWindowFocusListener(focusListener)
                util.CallNotifier.detachWindow(window)
                forceReleasePushToTalk()
            }
        }
        CompositionLocalProvider(LocalPttKeyBinding provides pttBinding) {
            Column(modifier = Modifier.fillMaxSize()) {
                CustomTitleBar(state = state, onClose = ::closeRequested)
                OrbitApp(modifier = Modifier.weight(1f))
            }
        }
    }

    Tray(
        icon = painterResource("logo.jpeg"),
        menu = {
            Item("Open Orbit", onClick = { restoreMainWindow() })
            Item("Quit", onClick = { quitOrbit() })
        }
    )

    // Floating call window while Orbit itself is minimized / tray-hidden.
    ActiveCallPipWindow(
        mainWindowPresentable = mainWindowPresentable && !state.isMinimized,
        onRestoreMainWindow = ::restoreMainWindow
    )
}
