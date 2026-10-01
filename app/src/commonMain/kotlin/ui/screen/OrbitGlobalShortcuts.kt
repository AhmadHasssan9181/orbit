package ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import ui.screen.settings.PttBinding
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The push-to-talk combo currently in effect, published by the desktop shell.
 *
 * Compose Desktop's `Dialog` is a separate native window, so the root `Window`'s
 * `onPreviewKeyEvent` never sees a keystroke that lands in a dialog — which is
 * why Ctrl+K, Alt+arrows, Ctrl+Shift+M/D/S/V and push-to-talk used to all go
 * dead the moment any dialog opened. Publishing the binding as a CompositionLocal
 * lets every nested window dispatch the identical handler.
 */
val LocalPttKeyBinding = compositionLocalOf<PttBinding?> { null }

/**
 * True while push-to-talk is open. The release has to be recognised even when
 * the modifier key was let go first: waiting for a "matching" KeyUp meant that
 * releasing Ctrl before Space left the microphone open with no further key
 * event able to close it.
 */
private val pttLatched = AtomicBoolean(false)

/**
 * Force the push-to-talk release path. Safe to call when nothing is latched, and
 * wired to window focus loss / app deactivation so the mic can never stay open
 * after the window loses focus.
 */
fun forceReleasePushToTalk() {
    if (pttLatched.compareAndSet(true, false)) {
        webrtc.CallShortcuts.onPushToTalk?.invoke(false)
    }
}

/**
 * A JVM-global AWT key dispatcher, so the push-to-talk release is seen no matter
 * which Orbit window currently owns focus.
 *
 * The latch is process-wide but a `Dialog` is a separate native window: a dialog
 * that does not install [Modifier.orbitGlobalShortcuts] (the quick switcher, the
 * server-roles dialog) can swallow the KeyUp, and auto-repeat KeyDown after the
 * dialog closes re-latches with nothing left to release it — a hot microphone
 * with no key held. AWT's KeyboardFocusManager sees every key event in the
 * process, so the release cannot be missed.
 *
 * The filter matters as much as the coverage. Releasing on EVERY key (an earlier
 * version) meant that holding Ctrl+Shift+Space and tapping any other key — typing
 * in the composer, pressing Escape — fired KEY_RELEASED for THAT key first,
 * because AWT dispatchers run before the focused component. The mic cut, and only
 * re-opened once Windows auto-repeat re-delivered the held Space ~30 ms later,
 * so the stream stuttered open/closed. So: release only on a KeyUp for a key
 * that is actually held down, and only when nothing in that set is the PTT key.
 */
internal fun installGlobalPttReleaseWatcher() {
    if (!pttWatcherInstalled.compareAndSet(false, true)) return
    val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    manager.addKeyEventDispatcher { event ->
        when (event.id) {
            java.awt.event.KeyEvent.KEY_PRESSED -> {
                awtKeysDown.add(event.keyCode)
                // Latched and the user just pressed a NON-PTT key: they have
                // engaged with the app (typed in the composer, hit Escape), so
                // let go of the talk key rather than talking over them.
                if (pttLatched.get() && !isPttAwtKey(event.keyCode)) forceReleasePushToTalk()
            }

            java.awt.event.KeyEvent.KEY_RELEASED -> {
                awtKeysDown.remove(event.keyCode)
                // Only the PTT key's own release closes the mic. Releasing on
                // ANY key cut the stream while the combo was still held.
                if (pttLatched.get() && isPttAwtKey(event.keyCode)) {
                    forceReleasePushToTalk()
                }
            }
        }
        // Never consume: AWT dispatchers must not swallow events from the window.
        false
    }
}

private val pttWatcherInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

private val awtKeysDown: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

/**
 * The AWT key codes that make up the current push-to-talk combo.
 *
 * The AWT watcher cannot see the user's binding directly, so the shell — the
 * one place that knows it — publishes it. Empty means "unknown", and the
 * watcher then only reacts to an explicit release of a known combo key.
 */
private val pttAwtKeyCodes = java.util.concurrent.atomic.AtomicReference<Set<Int>>(emptySet())

/** Publishes the AWT key codes that make up the current push-to-talk combo. */
fun publishPttAwtKeyCodes(codes: Set<Int>) {
    pttAwtKeyCodes.set(codes.toSet())
}

private fun isPttAwtKey(keyCode: Int): Boolean = keyCode in pttAwtKeyCodes.get()

/** True when this key event is the push-to-talk combo *and* its modifiers. */
private fun isPttDown(event: KeyEvent, binding: PttBinding): Boolean =
    event.type == KeyEventType.KeyDown &&
        event.isCtrlPressed == binding.ctrl &&
        event.isShiftPressed == binding.shift &&
        event.isAltPressed == binding.alt

/**
 * The single source of truth for app-wide call and navigation shortcuts.
 * Returns true when the event was consumed.
 */
fun dispatchOrbitShortcut(event: KeyEvent, pttBinding: PttBinding?): Boolean {
    if (pttBinding != null && event.key == pttBinding.key) {
        // Once latched, ANY release of the PTT key closes the mic, modifiers or
        // not — this is the path that stops the "mic stuck open" bug.
        if (pttLatched.get()) {
            if (event.type == KeyEventType.KeyUp) forceReleasePushToTalk()
            return true
        }
        if (isPttDown(event, pttBinding)) {
            pttLatched.set(true)
            webrtc.CallShortcuts.onPushToTalk?.invoke(true)
            return true
        }
    }

    if (event.type != KeyEventType.KeyDown) return false

    if (event.isCtrlPressed && event.isShiftPressed) {
        when (event.key) {
            Key.M -> webrtc.CallShortcuts.onToggleMute?.invoke()
            Key.D -> webrtc.CallShortcuts.onToggleDeafen?.invoke()
            Key.S -> webrtc.CallShortcuts.onToggleStream?.invoke()
            Key.V -> {
                // Alt distinguishes Return to Active Call from Toggle Camera.
                if (event.isAltPressed) webrtc.CallShortcuts.onReturnToCall?.invoke()
                else webrtc.CallShortcuts.onToggleCamera?.invoke()
            }
            else -> return false
        }
        return true
    }

    if (event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed && event.key == Key.K) {
        webrtc.CallShortcuts.onQuickSwitcher?.invoke()
        return true
    }

    if (event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed) {
        when (event.key) {
            Key.DirectionLeft -> webrtc.CallShortcuts.onNavigateBack?.invoke()
            Key.DirectionRight -> webrtc.CallShortcuts.onNavigateForward?.invoke()
            else -> return false
        }
        return true
    }

    // Escape leaves a maximised call stage, and only then.
    //
    // Checked LAST, and gated on a handler actually being published, because
    // Escape is the app's universal "get me out of this" key. With no stage
    // maximised the handler is null, the event falls through unconsumed, and
    // Escape still closes the dialog or menu that has focus.
    if (!event.isCtrlPressed && !event.isShiftPressed && !event.isAltPressed &&
        event.key == Key.Escape
    ) {
        val exitStage = webrtc.CallShortcuts.onExitMaximizedStage
        if (exitStage != null) {
            exitStage()
            return true
        }
    }

    return false
}

/**
 * Installs [dispatchOrbitShortcut] inside a dialog (or any other composition
 * that is not the root window). The binding is read during composition, not
 * inside the event lambda, because a plain Modifier lambda has no Composer.
 */
@Composable
fun Modifier.orbitGlobalShortcuts(): Modifier {
    val binding = LocalPttKeyBinding.current
    return this.onPreviewKeyEvent { event -> dispatchOrbitShortcut(event, binding) }
}
