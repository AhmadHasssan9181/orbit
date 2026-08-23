package webrtc

/**
 * App-wide call keybinds. The call layer registers handlers; the desktop Window
 * dispatches key events here so shortcuts work regardless of which pane has focus.
 */
object CallShortcuts {
    var onToggleMute: (() -> Unit)? = null
    var onToggleDeafen: (() -> Unit)? = null
}
