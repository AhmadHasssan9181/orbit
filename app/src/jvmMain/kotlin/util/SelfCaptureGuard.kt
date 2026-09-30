package util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.W32APIOptions

/**
 * Stops Orbit capturing itself when it shares its own screen.
 *
 * ## The bug this exists for
 *
 * Sharing Orbit's own screen produced an infinite mirror: the capture picked up
 * the Orbit window, the Orbit window showed the capture as a live preview tile,
 * and that preview was captured again. Each generation is smaller than the last,
 * so the share looked like a tunnel of progressively shrunken copies of the app.
 * It is not cosmetic -- what the other person sees is that tunnel.
 *
 * No amount of UI work fixes it properly. Removing the local preview stops the
 * loop after one generation but still shows the app in the stream, which is the
 * wrong picture anyway: when you share your screen you are already looking at it.
 *
 * ## What Windows actually provides
 *
 * `SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)` removes a window from
 * screen capture entirely. This is what Discord, Teams and OBS do, and it is the
 * only fix that is correct rather than cosmetically reduced.
 *
 * `User32` in jna-platform does not declare it, so it is added here by extending
 * the interface -- JNA resolves the symbol from user32 at load time.
 *
 * ## Why it must be undone
 *
 * The flag persists on the window until changed. Left set, Orbit's window would be
 * invisible to every screen recorder, screenshot tool and share for the rest of
 * the session -- so [release] is called from the share-stop path and is not
 * optional. A guard that cannot be undone is a guard that will eventually be left
 * on by a crash.
 *
 * ## Older Windows
 *
 * `WDA_EXCLUDEFROMCAPTURE` needs Windows 10 1903+. Older builds only know
 * `WDA_MONITOR`, which renders the window as a black rectangle in captures rather
 * than removing it -- visually the same result for our purposes, and far better
 * than the mirror. That is the fallback.
 */
internal object SelfCaptureGuard {

    private const val WDA_NONE = 0x00000000L
    private const val WDA_MONITOR = 0x00000001L
    private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011L

    /** Thread-safety is not a concern: only the share path touches this. */
    @Volatile
    private var excluded: Boolean = false

    private interface User32Ext : User32 {
        fun SetWindowDisplayAffinity(hwnd: WinDef.HWND, dwAffinity: WinDef.DWORD): WinDef.BOOL

        companion object {
            val INSTANCE: User32Ext =
                Native.load("user32", User32Ext::class.java, W32APIOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * Hide Orbit's own windows from screen capture.
     *
     * Returns true when the guard is actually in force, so the caller can log a
     * share it could not protect rather than silently sending the mirror.
     */
    fun engage(): Boolean {
        if (excluded) return true
        return try {
            val hwnds = ownWindowHandles()
            if (hwnds.isEmpty()) {
                println("DEBUG [SelfCaptureGuard] no own window found; capture guard NOT engaged")
                return false
            }
            var anySet = false
            for ((hwnd, handleValue) in hwnds) {
                val ok = User32Ext.INSTANCE.SetWindowDisplayAffinity(
                    hwnd,
                    WinDef.DWORD(WDA_EXCLUDEFROMCAPTURE)
                )
                // Win32 returns BOOL: NON-ZERO is success.
                //
                // This had it inverted, so every window took the fallback branch on
                // a machine where EXCLUDEFROMCAPTURE was available and working.
                //
                // With the check corrected the call now reports success on every
                // window AND the mirror is still there. So "it returned TRUE" is not
                // evidence that it did anything, and the window identity is logged
                // because that is the thing worth knowing next: AWT can hand back
                // helper windows alongside the real one, and setting an affinity on
                // the wrong HWND returns TRUE and changes nothing.
                println(
                    "DEBUG [SelfCaptureGuard] hwnd=0x" + java.lang.Long.toHexString(handleValue) +
                        " excludeFromCapture=" + (ok.toInt() != 0)
                )
                if (ok.toInt() != 0) {
                    anySet = true
                } else {
                    val fallbackOk = User32Ext.INSTANCE.SetWindowDisplayAffinity(
                        hwnd,
                        WinDef.DWORD(WDA_MONITOR)
                    )
                    anySet = fallbackOk.toInt() != 0
                    println("DEBUG [SelfCaptureGuard] EXCLUDEFROMCAPTURE rejected, MONITOR fallback applied=$anySet")
                }
            }
            excluded = anySet
            println("DEBUG [SelfCaptureGuard] engaged on ${hwnds.size} window(s), engaged=$anySet")
            anySet
        } catch (e: Throwable) {
            // Never let the guard break the share itself. A mirrored share is bad;
            // a failed share is worse.
            println("WARN [SelfCaptureGuard] engage failed: ${e.message}")
            false
        }
    }

    /** Undo [engage]. Safe to call when it was never engaged. */
    fun release() {
        if (!excluded) return
        try {
            ownWindowHandles().forEach { (hwnd, _) ->
                User32Ext.INSTANCE.SetWindowDisplayAffinity(hwnd, WinDef.DWORD(WDA_NONE))
            }
        } catch (e: Throwable) {
            println("WARN [SelfCaptureGuard] release failed: ${e.message}")
        } finally {
            excluded = false
            println("DEBUG [SelfCaptureGuard] released")
        }
    }

    /**
     * HWNDs of Orbit's own top-level AWT windows.
     *
     * Every SHOWING window, not just the first: the call picture-in-picture is a
     * second top-level window, and leaving it capturable would put the mirror back
     * the moment somebody opened it. The picture-in-picture is excluded from the
     * classpath query below only because it does not exist as an AWT window.
     */
    private fun ownWindowHandles(): List<Pair<WinDef.HWND, Long>> {
        val out = ArrayList<Pair<WinDef.HWND, Long>>()
        for (w in java.awt.Window.getWindows()) {
            if (!w.isShowing) continue
            // A window that is not realised yet has a null peer, and handing that to
            // user32 is a call on HWND 0 -- the desktop, not a window.
            val ptr: Pointer? = Native.getWindowPointer(w)
            val value = if (ptr == null) 0L else Pointer.nativeValue(ptr)
            if (value == 0L) continue
            // The native pointer is carried through for the log line. AWT routinely
            // hands back helper windows alongside the real one, and an affinity set
            // on the wrong HWND returns TRUE and changes nothing at all -- so which
            // window this actually found is the thing worth being able to see.
            out.add(WinDef.HWND(ptr) to value)
        }
        return out.distinctBy { it.second }
    }
}