package webrtc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App-wide call keybinds. The call layer registers handlers; the desktop Window
 * dispatches key events here so shortcuts work regardless of which pane has focus.
 */
object CallShortcuts {
    var onToggleMute: (() -> Unit)? = null
    var onToggleDeafen: (() -> Unit)? = null
    var onToggleCamera: (() -> Unit)? = null

    // Start/Stop Stream: registered by the active call surface. Stops an
    // in-flight screen share directly; otherwise opens the source picker
    // (only when the expanded surface is showing).
    var onToggleStream: (() -> Unit)? = null

    // Return to Active Call: expands a minimized call back into its surface.
    var onReturnToCall: (() -> Unit)? = null

    // Leave a maximised call stage. Published ONLY while a stage is actually
    // maximised, so Escape keeps meaning "close whatever is open" everywhere
    // else in the app -- the concept's tooltip says "Exit Full Screen (ESC)",
    // which is a claim about Escape, not a global binding.
    // Tracks whether any call stage/video stream is currently maximized/fullscreen.
    var isStageMaximized by mutableStateOf(false)
    var onExitMaximizedStage: (() -> Unit)? = null

    // App navigation (registered by OrbitApp's shell): Alt+←/→ history and
    // Ctrl+K quick switcher. Live here because the root Window dispatches
    // every app-wide keybind through this object.
    var onNavigateBack: (() -> Unit)? = null
    var onNavigateForward: (() -> Unit)? = null
    var onQuickSwitcher: (() -> Unit)? = null

    // Push-to-talk (Voice & Video settings): invoked with true while the combo
    // is held and false on release; repeats are idempotent in the handler.
    var onPushToTalk: ((active: Boolean) -> Unit)? = null

    // Fired from the window-close / tray-quit path BEFORE the JVM starts
    // shutting down, so an active call can publish its leave membership while
    // the exit watchdog's grace window is still open.
    var onAppQuitRequested: (() -> Unit)? = null
}

/**
 * Bridge between the app-shell call layer and OS-level windows composed
 * outside the app tree (the floating call PiP window in Main.kt).
 * Snapshot-backed so a late registration still triggers recomposition.
 */
object ActiveCallBridge {
    var callManager: CallManager? by mutableStateOf<CallManager?>(null)
        private set

    fun register(manager: CallManager?) {
        callManager = manager
    }
}
