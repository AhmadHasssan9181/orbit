package webrtc

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.GDI32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance, non-blocking desktop and window thumbnail provider.
 *
 * Captures lightweight snapshots of screens and background windows for the
 * stream preview picker without creating full WebRTC capturer sessions.
 */
object DesktopThumbnailProvider {
    private const val THUMB_WIDTH = 320
    private const val THUMB_HEIGHT = 180
    private const val PW_RENDERFULLCONTENT = 2

    private val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun getCachedThumbnail(sourceId: String): ImageBitmap? = cache[sourceId]

    suspend fun captureThumbnail(source: DesktopSourceModel): ImageBitmap? = withContext(Dispatchers.IO) {
        val bitmap = if (source.isWindow) {
            captureWindowThumbnail(source.id)
        } else {
            captureScreenThumbnail(source.id)
        }
        if (bitmap != null) {
            cache[source.id] = bitmap
        }
        bitmap
    }

    private fun captureScreenThumbnail(sourceId: String): ImageBitmap? {
        return runCatching {
            val ge = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val screens = ge.screenDevices
            val screenIdx = sourceId.toIntOrNull() ?: 0
            val bounds: Rectangle = if (screenIdx in screens.indices) {
                screens[screenIdx].defaultConfiguration.bounds
            } else {
                screens.firstOrNull()?.defaultConfiguration?.bounds ?: Rectangle(0, 0, 1920, 1080)
            }
            val robot = Robot()
            val capture = robot.createScreenCapture(bounds)
            scaleToThumbnail(capture)
        }.getOrNull()
    }

    private fun captureWindowThumbnail(sourceId: String): ImageBitmap? {
        if (!isWindows) return null
        val hwndVal = sourceId.toLongOrNull() ?: return null
        if (hwndVal <= 0L) return null

        return runCatching {
            val hwnd = WinDef.HWND(Pointer(hwndVal))
            if (!User32.INSTANCE.IsWindow(hwnd)) return null

            // Try PrintWindow with PW_RENDERFULLCONTENT (renders DWM backing store even if behind other windows)
            val printImage = captureViaPrintWindow(hwnd)
            if (printImage != null) {
                return@runCatching scaleToThumbnail(printImage)
            }

            // Fallback to GDI32Util.getScreenshot
            val gdiImage = runCatching { GDI32Util.getScreenshot(hwnd) }.getOrNull()
            if (gdiImage != null) {
                scaleToThumbnail(gdiImage)
            } else null
        }.getOrNull()
    }

    private fun captureViaPrintWindow(hwnd: WinDef.HWND): BufferedImage? {
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetWindowRect(hwnd, rect)) return null
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        if (w <= 0 || h <= 0) return null

        val hdcWindow = User32.INSTANCE.GetDC(hwnd) ?: return null
        val hdcMem = GDI32.INSTANCE.CreateCompatibleDC(hdcWindow) ?: run {
            User32.INSTANCE.ReleaseDC(hwnd, hdcWindow)
            return null
        }
        val hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(hdcWindow, w, h)
        if (hBitmap == null) {
            GDI32.INSTANCE.DeleteDC(hdcMem)
            User32.INSTANCE.ReleaseDC(hwnd, hdcWindow)
            return null
        }
        val hOld = GDI32.INSTANCE.SelectObject(hdcMem, hBitmap)

        try {
            val printOk = User32.INSTANCE.PrintWindow(hwnd, hdcMem, PW_RENDERFULLCONTENT) ||
                User32.INSTANCE.PrintWindow(hwnd, hdcMem, 0)
            if (!printOk) return null

            val bmi = WinGDI.BITMAPINFO()
            bmi.bmiHeader.biWidth = w
            bmi.bmiHeader.biHeight = -h // top-down DIB
            bmi.bmiHeader.biPlanes = 1
            bmi.bmiHeader.biBitCount = 32
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB

            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val data = (image.raster.dataBuffer as DataBufferInt).data
            val mem = Memory((w.toLong() * h.toLong() * 4L))
            val lines = GDI32.INSTANCE.GetDIBits(hdcMem, hBitmap, 0, h, mem, bmi, WinGDI.DIB_RGB_COLORS)
            if (lines <= 0) return null
            mem.read(0, data, 0, data.size)
            return image
        } finally {
            GDI32.INSTANCE.SelectObject(hdcMem, hOld)
            GDI32.INSTANCE.DeleteObject(hBitmap)
            GDI32.INSTANCE.DeleteDC(hdcMem)
            User32.INSTANCE.ReleaseDC(hwnd, hdcWindow)
        }
    }

    private fun scaleToThumbnail(src: BufferedImage): ImageBitmap {
        val srcW = src.width
        val srcH = src.height
        if (srcW <= 0 || srcH <= 0) return src.toComposeImageBitmap()

        val target = BufferedImage(THUMB_WIDTH, THUMB_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = target.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

        // Dark background matching Orbit theme
        g.color = Color(18, 19, 22)
        g.fillRect(0, 0, THUMB_WIDTH, THUMB_HEIGHT)

        val scale = minOf(THUMB_WIDTH.toFloat() / srcW, THUMB_HEIGHT.toFloat() / srcH)
        val dw = (srcW * scale).toInt().coerceAtLeast(1)
        val dh = (srcH * scale).toInt().coerceAtLeast(1)
        val dx = (THUMB_WIDTH - dw) / 2
        val dy = (THUMB_HEIGHT - dh) / 2

        g.drawImage(src, dx, dy, dw, dh, null)
        g.dispose()
        return target.toComposeImageBitmap()
    }

    fun clearCache() {
        cache.clear()
    }
}
