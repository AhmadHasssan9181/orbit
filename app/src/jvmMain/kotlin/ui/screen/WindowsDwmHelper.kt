package ui.screen

import androidx.compose.ui.graphics.Color
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Native Windows DWM helper for Windows 11 title bar theming.
 *
 * Uses DwmSetWindowAttribute to seamlessly color the native OS title bar and caption buttons
 * to match Orbit's active theme without messing with the message pump or EDT.
 */
internal object WindowsDwmHelper {

    val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    private interface DwmapiExt : com.sun.jna.Library {
        fun DwmSetWindowAttribute(
            hwnd: Pointer,
            dwAttribute: Int,
            pvAttribute: Pointer,
            cbAttribute: Int
        ): Int

        companion object {
            val INSTANCE: DwmapiExt? = try {
                Native.load("dwmapi", DwmapiExt::class.java)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    fun updateTitleBarColor(window: java.awt.Window, color: Color) {
        if (!isWindows) return
        val ptr: Pointer = Native.getWindowPointer(window) ?: return
        if (Pointer.nativeValue(ptr) == 0L) return

        val r = (color.red * 255f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f).toInt().coerceIn(0, 255)
        val isDark = (r * 0.299 + g * 0.587 + b * 0.114) < 128

        // Win32 COLORREF format: 0x00BBGGRR
        val colorRef = (r and 0xFF) or ((g and 0xFF) shl 8) or ((b and 0xFF) shl 16)
        val textColorRef = if (isDark) 0x00FFFFFF else 0x00000000

        try {
            val darkMem = Memory(4).apply { setInt(0, if (isDark) 1 else 0) }
            DwmapiExt.INSTANCE?.DwmSetWindowAttribute(ptr, DWMWA_USE_IMMERSIVE_DARK_MODE, darkMem, 4)

            val colorMem = Memory(4).apply { setInt(0, colorRef) }
            DwmapiExt.INSTANCE?.DwmSetWindowAttribute(ptr, DWMWA_CAPTION_COLOR, colorMem, 4)

            val textMem = Memory(4).apply { setInt(0, textColorRef) }
            DwmapiExt.INSTANCE?.DwmSetWindowAttribute(ptr, DWMWA_TEXT_COLOR, textMem, 4)
        } catch (_: Throwable) {
        }
    }
}
