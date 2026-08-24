package ui.screen

import ui.screen.main.OrbitApp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.concurrent.atomic.AtomicBoolean

// H5: exitApplication() cannot terminate the JVM while JNI-attached native
// workers or stray non-daemon threads are alive (observed ~6 GB zombie after
// window close). Arm a daemon watchdog before every explicit quit: if the
// graceful shutdown has not finished the process within the budget, halt it.
// On a clean exit the JVM dies before the watchdog ever fires.
private val forcedExitArmed = AtomicBoolean(false)

private fun armForcedExitWatchdog(budgetMs: Long = 2_500) {
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
    val minimizeToTray by settingsManager.minimizeToTray.collectAsState(true)

    var showSplash by remember { mutableStateOf(true) }

    if (showSplash) {
        SplashScreenWindow(onLoadingComplete = {
            showSplash = false
        })
    } else {
        val state = rememberWindowState()
        var windowRef by remember { mutableStateOf<java.awt.Window?>(null) }

        // Close hides to tray while "minimize to tray" is on; quitting happens
        // explicitly via the tray menu.
        fun closeRequested() {
            if (minimizeToTray) windowRef?.isVisible = false else {
                armForcedExitWatchdog()
                exitApplication()
            }
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
                if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
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
                        else -> false
                    }
                } else {
                    false
                }
            }
        ) {
            windowRef = window
            Column(modifier = Modifier.fillMaxSize()) {
                CustomTitleBar(state = state, onClose = ::closeRequested)
                OrbitApp(modifier = Modifier.weight(1f))
            }
        }

        Tray(
            icon = painterResource("logo.jpeg"),
            menu = {
                Item("Open Orbit", onClick = {
                    windowRef?.isVisible = true
                    windowRef?.toFront()
                })
                Item("Quit", onClick = {
                    armForcedExitWatchdog()
                    exitApplication()
                })
            }
        )
    }
}
