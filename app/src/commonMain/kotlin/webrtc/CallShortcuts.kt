package webrtc

/**
 * App-wide call keybinds. The call layer registers handlers; the desktop Window
 * dispatches key events here so shortcuts work regardless of which pane has focus.
 */
object CallShortcuts {
    var onToggleMute: (() -> Unit)? = null
    var onToggleDeafen: (() -> Unit)? = null
    var onToggleCamera: (() -> Unit)? = null

    // Push-to-talk (Voice & Video settings): invoked with true while the combo
    // is held and false on release; repeats are idempotent in the handler.
    var onPushToTalk: ((active: Boolean) -> Unit)? = null

    // Fired from the window-close / tray-quit path BEFORE the JVM starts
    // shutting down, so an active call can publish its leave membership while
    // the exit watchdog's grace window is still open.
    var onAppQuitRequested: (() -> Unit)? = null
}
