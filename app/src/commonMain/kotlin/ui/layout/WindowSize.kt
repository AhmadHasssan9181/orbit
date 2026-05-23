package ui.layout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Three-tier breakpoint system:
 *  Compact  = phone portrait  (< 600dp)
 *  Medium   = tablet portrait (600–839dp)
 *  Expanded = desktop / landscape tablet (≥ 840dp)
 */
enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded;

    val isCompact: Boolean get() = this == Compact
    val isMediumOrLarger: Boolean get() = this == Medium || this == Expanded
    val isExpanded: Boolean get() = this == Expanded
}

fun windowSizeClassFor(widthDp: Dp): WindowSizeClass = when {
    widthDp < 600.dp -> WindowSizeClass.Compact
    widthDp < 840.dp -> WindowSizeClass.Medium
    else             -> WindowSizeClass.Expanded
}

val LocalWindowSizeClass = compositionLocalOf<WindowSizeClass> { WindowSizeClass.Expanded }
