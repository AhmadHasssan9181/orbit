package ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalIsLiteMode = compositionLocalOf { false }
val LocalOnLiteModeChange = compositionLocalOf<(Boolean) -> Unit> { {} }

private val CosmicDarkScheme = darkColorScheme(
    primary          = Color(0xFF7C6AF0),
    onPrimary        = Color(0xFFEAECF5),
    primaryContainer = Color(0x2A7C6AF0),
    secondary        = Color(0xFFA78BFA),
    onSecondary      = Color(0xFFEAECF5),
    tertiary         = Color(0xFF34D399),
    surface          = Color(0xFF141720),
    onSurface        = Color(0xFFEAECF5),
    surfaceVariant   = Color(0xFF111318),
    surfaceDim       = Color(0xFF0D0E14),
    background       = Color(0xFF080A0F),
    onBackground     = Color(0xFFEAECF5),
    error            = Color(0xFFF87171),
    onError          = Color.White,
    outline          = Color(0xFF1C1F2B),
    outlineVariant   = Color(0xFF252838),
)

private val LiteScheme = lightColorScheme(
    primary          = Color(0xFF075E54),
    onPrimary        = Color.White,
    surface          = Color(0xFFEAE6DF),
    onSurface        = Color(0xFF111B21),
    surfaceVariant   = Color(0xFFF0F2F5),
    background       = Color(0xFFEAE6DF),
    onBackground     = Color(0xFF111B21),
)

@Composable
fun OrbitTheme(
    darkMode: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkMode) CosmicDarkScheme else LiteScheme,
        typography  = OrbitTypography.Typography,
        content     = content
    )
}