package ui.screen

import ui.screen.main.OrbitApp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.screen.call.ActiveCallPipWindow
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
    val settingsManager = remember { repository.SettingsManager() }
    util.OrbitLog.debugEnabled = settingsManager.debugLoggingSync()
    val minimizeToTray by settingsManager.minimizeToTray.collectAsState(false)
    // Push-to-talk combo is user-rebindable; parse once per stored change.
    val storedPttKeybind by settingsManager.pushToTalkKeybind.collectAsState("Unassigned")
    val pttBinding = remember(storedPttKeybind) { parsePttCombo(normalizedPttKeybind(storedPttKeybind)) }

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
        onPreviewKeyEvent = { event ->
            // App-wide call keybinds (see webrtc.CallShortcuts).
            val binding = pttBinding
            if (binding != null &&
                event.key == binding.key &&
                event.isCtrlPressed == binding.ctrl &&
                event.isShiftPressed == binding.shift &&
                event.isAltPressed == binding.alt
            ) {
                // Push-to-talk: momentary mic open for as long as the combo
                // is held; KeyUp releases it. Auto-repeat KeyDown is fine —
                // the handler ignores repeats while already open.
                webrtc.CallShortcuts.onPushToTalk?.invoke(
                    event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                )
                true
            } else if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                event.isCtrlPressed && event.isShiftPressed
            ) {
                when (event.key) {
                    androidx.compose.ui.input.key.Key.M -> {
                        webrtc.CallShortcuts.onToggleMute?.invoke()
                        true
                    }
                    androidx.compose.ui.input.key.Key.D -> {
                        webrtc.CallShortcuts.onToggleDeafen?.invoke()
                        true
                    }
                    androidx.compose.ui.input.key.Key.S -> {
                        webrtc.CallShortcuts.onToggleStream?.invoke()
                        true
                    }
                    androidx.compose.ui.input.key.Key.V -> {
                        // Alt distinguishes Return to Active Call from Toggle Camera.
                        if (event.isAltPressed) {
                            webrtc.CallShortcuts.onReturnToCall?.invoke()
                        } else {
                            webrtc.CallShortcuts.onToggleCamera?.invoke()
                        }
                        true
                    }
                    else -> false
                }
            } else if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed &&
                event.key == androidx.compose.ui.input.key.Key.K
            ) {
                webrtc.CallShortcuts.onQuickSwitcher?.invoke()
                true
            } else if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
            ) {
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        webrtc.CallShortcuts.onNavigateBack?.invoke()
                        true
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        webrtc.CallShortcuts.onNavigateForward?.invoke()
                        true
                    }
                    else -> false
                }
            } else {
                false
            }
        }
    ) {
        windowRef = window
        util.CallNotifier.attachWindow(window)
        Column(modifier = Modifier.fillMaxSize()) {
            CustomTitleBar(state = state, onClose = ::closeRequested)
            OrbitApp(modifier = Modifier.weight(1f))
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
