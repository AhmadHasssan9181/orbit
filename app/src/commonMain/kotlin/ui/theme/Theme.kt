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
val LocalActiveTheme = compositionLocalOf<Theme> { Theme.Default }
val LocalOnThemeChange = compositionLocalOf<(Theme) -> Unit> { {} }
val LocalMessageDensity = compositionLocalOf<String> { "Comfortable" }
val LocalFontSize = compositionLocalOf<Float> { 50f }
val LocalReduceMotion = compositionLocalOf<Boolean> { false }
val LocalMentionsOnly = compositionLocalOf<Boolean> { true }
val LocalNotificationSound = compositionLocalOf<String> { "Chime" }

/**
 * Discord/Revolt-style semantic color tokens derived from MaterialTheme.
 * Every screen should use these tokens instead of hard-coding colors.
 */
@Composable
fun rememberCosmicTokens(): CosmicTokens {
    val isLite = LocalIsLiteMode.current
    val cs = MaterialTheme.colorScheme

    return if (isLite) {
        // Lite mode now uses the active theme colors for a modern messenger look.
        // Light theme → bright clean messenger. Dark themes → dark messenger.
        val isDark = isThemeDark(LocalActiveTheme.current)
        if (isDark) {
            // Dark messenger — uses surface hierarchy with slightly elevated backgrounds
            CosmicTokens(
                Bg = cs.surfaceContainerLow,
                CardBg = cs.surfaceContainerHigh,
                CardBgHover = cs.surfaceContainerHighest,
                Accent = cs.primary,
                AccentLight = cs.secondary,
                AccentGhost = cs.primary.copy(alpha = 0.12f),
                Text_White = cs.onSurface,
                Text_Secondary = cs.onSurfaceVariant,
                Text_Muted = cs.outline,
                Separator = cs.outlineVariant.copy(alpha = 0.4f),
                Header_Bg = cs.surfaceContainer,
                Input_Bg = cs.surfaceContainerHigh,
                Members_Bg = cs.surfaceContainerLow,
                Rail_Bg = cs.surfaceContainerLowest,
                Rail_Active = cs.primary.copy(alpha = 0.18f),
                Pill_Color = cs.onSurface,
                Drawer_Bg = cs.surfaceContainer,
                UserBar_Bg = cs.surfaceContainerLowest,
                ChannelSelected_Bg = cs.primary.copy(alpha = 0.15f),
                ChannelHover_Bg = cs.surfaceVariant.copy(alpha = 0.12f),
                MentionHighlight = cs.primary.copy(alpha = 0.08f),
                MessageHover_Bg = Color(0x06FFFFFF),
            )
        } else {
            // Light messenger — clean, modern, bright
            CosmicTokens(
                Bg = cs.surface,
                CardBg = cs.surfaceContainerLowest,
                CardBgHover = cs.surfaceContainerLow,
                Accent = cs.primary,
                AccentLight = cs.secondary,
                AccentGhost = cs.primary.copy(alpha = 0.08f),
                Text_White = cs.onSurface,
                Text_Secondary = cs.onSurfaceVariant,
                Text_Muted = cs.outline,
                Separator = cs.outlineVariant.copy(alpha = 0.5f),
                Header_Bg = cs.surfaceContainerLow,
                Input_Bg = cs.surfaceContainerLowest,
                Members_Bg = cs.surface,
                Rail_Bg = cs.surfaceContainerLow,
                Rail_Active = cs.primary.copy(alpha = 0.12f),
                Pill_Color = cs.onSurface,
                Drawer_Bg = cs.surface,
                UserBar_Bg = cs.surfaceContainerLow,
                ChannelSelected_Bg = cs.primary.copy(alpha = 0.10f),
                ChannelHover_Bg = cs.surfaceContainerHigh.copy(alpha = 0.5f),
                MentionHighlight = cs.primary.copy(alpha = 0.06f),
                MessageHover_Bg = Color(0x08000000),
            )
        }
    } else {
        CosmicTokens(
            Bg = cs.surfaceContainerLow,             // main chat bg  (#161B27)
            CardBg = cs.surfaceContainerHigh,         // cards, embeds (#252A36)
            CardBgHover = cs.surfaceContainerHighest, // hover state   (#303541)
            Accent = cs.primary,
            AccentLight = cs.secondary,
            AccentGhost = cs.primary.copy(alpha = 0.12f),
            Text_White = cs.onSurface,                // bright text   (#DEE2F2)
            Text_Secondary = cs.onSurfaceVariant,     // dimmer text   (#C0C6D9)
            Text_Muted = cs.outline,                  // muted text    (#8A90A2)
            Separator = cs.outlineVariant,             // dividers      (#404756)
            Header_Bg = cs.surfaceContainerLow.copy(alpha = 0.96f),
            Input_Bg = cs.surfaceContainer,            // input field   (#1A202B)
            Members_Bg = cs.surfaceContainerLow,       // member panel
            Rail_Bg = cs.surfaceContainerLowest,       // server rail   (#090E19)
            Rail_Active = cs.primary.copy(alpha = 0.15f),
            Pill_Color = cs.onSurface,
            Drawer_Bg = cs.surfaceContainer,           // channel drawer (#1A202B)
            UserBar_Bg = cs.surfaceContainerLowest,    // user bar      (#090E19)
            // Discord-style extras
            ChannelSelected_Bg = cs.surfaceVariant.copy(alpha = 0.25f),
            ChannelHover_Bg = cs.surfaceVariant.copy(alpha = 0.12f),
            MentionHighlight = cs.primary.copy(alpha = 0.08f),
            MessageHover_Bg = Color(0x06FFFFFF),
        )
    }
}

/**
 * Thin data class holding every derived color token the UI layer consumes.
 */
data class CosmicTokens(
    val Bg: Color,
    val CardBg: Color,
    val CardBgHover: Color,
    val Accent: Color,
    val AccentLight: Color,
    val AccentGhost: Color,
    val Text_White: Color,
    val Text_Secondary: Color,
    val Text_Muted: Color,
    val Separator: Color,
    val Header_Bg: Color,
    val Input_Bg: Color,
    val Members_Bg: Color,
    val Rail_Bg: Color,
    val Rail_Active: Color,
    val Pill_Color: Color,
    val Drawer_Bg: Color,
    val UserBar_Bg: Color,
    // Discord/Revolt-style extra tokens
    val ChannelSelected_Bg: Color,
    val ChannelHover_Bg: Color,
    val MentionHighlight: Color,
    val MessageHover_Bg: Color,
)

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

val OceanColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFF3B82F6),
    secondary = Color(0xFF60A5FA),
    background = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B),
    surface = Color(0xFF0F172A),
    surfaceContainerLowest = Color(0xFF0A0F1E),
    surfaceContainerLow = Color(0xFF0F172A),
    surfaceContainer = Color(0xFF131C30),
    surfaceContainerHigh = Color(0xFF1A2540),
    surfaceContainerHighest = Color(0xFF1E293B),
)

val ForestColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFF10B981),
    secondary = Color(0xFF34D399),
    background = Color(0xFF022C22),
    surfaceVariant = Color(0xFF064E3B),
    surface = Color(0xFF022C22),
    surfaceContainerLowest = Color(0xFF011F18),
    surfaceContainerLow = Color(0xFF022C22),
    surfaceContainer = Color(0xFF033428),
    surfaceContainerHigh = Color(0xFF044030),
    surfaceContainerHighest = Color(0xFF064E3B),
)

val SunsetColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFFF97316),
    secondary = Color(0xFFFBBF24),
    background = Color(0xFF450A0A),
    surfaceVariant = Color(0xFF7F1D1D),
    surface = Color(0xFF450A0A),
    surfaceContainerLowest = Color(0xFF360808),
    surfaceContainerLow = Color(0xFF450A0A),
    surfaceContainer = Color(0xFF4F0D0D),
    surfaceContainerHigh = Color(0xFF5E1111),
    surfaceContainerHighest = Color(0xFF7F1D1D),
)

val CyberpunkColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFFEC4899),
    secondary = Color(0xFF06B6D4),
    background = Color(0xFF09090B),
    surfaceVariant = Color(0xFF18181B),
    surface = Color(0xFF09090B),
    surfaceContainerLowest = Color(0xFF050506),
    surfaceContainerLow = Color(0xFF09090B),
    surfaceContainer = Color(0xFF0E0E11),
    surfaceContainerHigh = Color(0xFF141418),
    surfaceContainerHighest = Color(0xFF18181B),
)

enum class Theme {
    None,
    Default,
    Light,
    M3Dynamic,
    Amoled,
    Ocean,
    Forest,
    Sunset,
    Cyberpunk
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
        requestedTheme == Theme.Ocean -> OceanColorScheme
        requestedTheme == Theme.Forest -> ForestColorScheme
        requestedTheme == Theme.Sunset -> SunsetColorScheme
        requestedTheme == Theme.Cyberpunk -> CyberpunkColorScheme
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
        Theme.Default, Theme.Amoled, Theme.Ocean, Theme.Forest, Theme.Sunset, Theme.Cyberpunk -> true
        Theme.Light -> false
        Theme.M3Dynamic, Theme.None -> systemIsDark
    }
}

@Composable
fun isThemeDark(theme: Theme) = isThemeDark(theme, isSystemInDarkTheme())