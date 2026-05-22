package ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Colour.PrimaryLight,
    onPrimary = Colour.OnPrimaryLight,
    primaryContainer = Colour.PrimaryContainerLight,
    onPrimaryContainer = Colour.OnPrimaryContainerLight,
    secondary = Colour.SecondaryLight,
    onSecondary = Colour.OnSecondaryLight,
    secondaryContainer = Colour.SecondaryContainerLight,
    onSecondaryContainer = Colour.OnSecondaryContainerLight,
    tertiary = Colour.TertiaryLight,
    onTertiary = Colour.OnTertiaryLight,
    tertiaryContainer = Colour.TertiaryContainerLight,
    onTertiaryContainer = Colour.OnTertiaryContainerLight,
    error = Colour.ErrorLight,
    onError = Colour.OnErrorLight,
    errorContainer = Colour.ErrorContainerLight,
    onErrorContainer = Colour.OnErrorContainerLight,
    background = Colour.BackgroundLight,
    onBackground = Colour.OnBackgroundLight,
    surface = Colour.SurfaceLight,
    onSurface = Colour.OnSurfaceLight,
    surfaceVariant = Colour.SurfaceVariantLight,
    onSurfaceVariant = Colour.OnSurfaceVariantLight,
    outline = Colour.OutlineLight,
    outlineVariant = Colour.OutlineVariantLight,
    scrim = Colour.ScrimLight,
    inverseSurface = Colour.InverseSurfaceLight,
    inverseOnSurface = Colour.InverseOnSurfaceLight,
    inversePrimary = Colour.InversePrimaryLight,
    surfaceDim = Colour.SurfaceDimLight,
    surfaceBright = Colour.SurfaceBrightLight,
    surfaceContainerLowest = Colour.SurfaceContainerLowestLight,
    surfaceContainerLow = Colour.SurfaceContainerLowLight,
    surfaceContainer = Colour.SurfaceContainerLight,
    surfaceContainerHigh = Colour.SurfaceContainerHighLight,
    surfaceContainerHighest = Colour.SurfaceContainerHighestLight,
)

private val DefaultColorScheme = darkColorScheme(
    primary = Colour.PrimaryDark,
    onPrimary = Colour.OnPrimaryDark,
    primaryContainer = Colour.PrimaryContainerDark,
    onPrimaryContainer = Colour.OnPrimaryContainerDark,
    secondary = Colour.SecondaryDark,
    onSecondary = Colour.OnSecondaryDark,
    secondaryContainer = Colour.SecondaryContainerDark,
    onSecondaryContainer = Colour.OnSecondaryContainerDark,
    tertiary = Colour.TertiaryDark,
    onTertiary = Colour.OnTertiaryDark,
    tertiaryContainer = Colour.TertiaryContainerDark,
    onTertiaryContainer = Colour.OnTertiaryContainerDark,
    error = Colour.ErrorDark,
    onError = Colour.OnErrorDark,
    errorContainer = Colour.ErrorContainerDark,
    onErrorContainer = Colour.OnErrorContainerDark,
    background = Colour.BackgroundDark,
    onBackground = Colour.OnBackgroundDark,
    surface = Colour.SurfaceDark,
    onSurface = Colour.OnSurfaceDark,
    surfaceVariant = Colour.SurfaceVariantDark,
    onSurfaceVariant = Colour.OnSurfaceVariantDark,
    outline = Colour.OutlineDark,
    outlineVariant = Colour.OutlineVariantDark,
    scrim = Colour.ScrimDark,
    inverseSurface = Colour.InverseSurfaceDark,
    inverseOnSurface = Colour.InverseOnSurfaceDark,
    inversePrimary = Colour.InversePrimaryDark,
    surfaceDim = Colour.SurfaceDimDark,
    surfaceBright = Colour.SurfaceBrightDark,
    surfaceContainerLowest = Colour.SurfaceContainerLowestDark,
    surfaceContainerLow = Colour.SurfaceContainerLowDark,
    surfaceContainer = Colour.SurfaceContainerDark,
    surfaceContainerHigh = Colour.SurfaceContainerHighDark,
    surfaceContainerHighest = Colour.SurfaceContainerHighestDark,
)

val LocalIsLiteMode = compositionLocalOf<Boolean> { false }
val LocalOnLiteModeChange = compositionLocalOf<(Boolean) -> Unit> { {} }

val AmoledColorScheme = DefaultColorScheme.copy(
    background = Color(0xff000000),
    onBackground = Color(0xffffffff),
    surfaceVariant = Color(0xff131313),
    onSurfaceVariant = Color(0xffffffff),
    surface = Color(0xff000000),
    onSurface = Color(0xffffffff),
    surfaceContainerLowest = Color(0xff000000),
    surfaceContainerLow = Color(0xff000000),
    surfaceContainer = Color(0xff000000),
    surfaceContainerHigh = Color(0xff000000),
    surfaceContainerHighest = Color(0xff000000),
)

enum class Theme {
    None,
    Default,
    Light,
    M3Dynamic,
    Amoled
}

@Composable
fun getColorScheme(
    requestedTheme: Theme,
    colourOverrides: OverridableColourScheme? = null
): ColorScheme {
    val systemInDarkTheme = isSystemInDarkTheme()
    val m3Supported = systemSupportsDynamicColors()

    val colorScheme = when {
        requestedTheme == Theme.Default -> DefaultColorScheme
        requestedTheme == Theme.Light -> LightColorScheme
        requestedTheme == Theme.Amoled -> AmoledColorScheme
        requestedTheme == Theme.None && systemInDarkTheme -> DefaultColorScheme
        requestedTheme == Theme.None && !systemInDarkTheme -> LightColorScheme
        else -> DefaultColorScheme
    }.copy()

    if (colourOverrides == null) return colorScheme
    return colourOverrides.applyTo(colorScheme)
}

@Composable
fun StoatTheme(
    requestedTheme: Theme,
    requestedUserInterfaceFont: UserInterfaceFont,
    colourOverrides: OverridableColourScheme? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = getColorScheme(requestedTheme, colourOverrides)
    val typography = when (requestedUserInterfaceFont) {
        UserInterfaceFont.Default -> StoatTypography
        UserInterfaceFont.GoogleSansFlex -> GoogleTypography
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

fun systemSupportsDynamicColors(): Boolean {
    return false
}

fun getDefaultTheme(): Theme {
    return when {
        systemSupportsDynamicColors() -> Theme.M3Dynamic
        else -> Theme.Default
    }
}

fun isThemeDark(theme: Theme, systemIsDark: Boolean): Boolean {
    return when (theme) {
        Theme.Default, Theme.Amoled -> true
        Theme.Light -> false
        Theme.M3Dynamic, Theme.None -> systemIsDark
    }
}

@Composable
fun isThemeDark(theme: Theme) = isThemeDark(theme, isSystemInDarkTheme())