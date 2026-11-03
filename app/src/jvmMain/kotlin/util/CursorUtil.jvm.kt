package util

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/**
 * Returns the standard horizontal resize cursor on desktop (`<->`).
 */
actual fun horizontalResizeCursor(): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
