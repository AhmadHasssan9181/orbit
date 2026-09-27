package ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

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
val LocalChatWallpaper = compositionLocalOf<String> { "auto" }
val LocalShowTimestamps = compositionLocalOf<Boolean> { true }
val LocalCompactMode = compositionLocalOf<Boolean> { false }
val LocalShowRoleColors = compositionLocalOf<Boolean> { true }
val LocalShowMemberList = compositionLocalOf<Boolean> { true }
val LocalEnterSend = compositionLocalOf<Boolean> { true }
val LocalUse24HourClock = compositionLocalOf<Boolean> { false }
val LocalDateFormat = compositionLocalOf<String> { "DD/MM/YYYY" }

/** Members of the open room, so message UI can resolve avatars without extra lookups. */
val LocalRoomMembers = compositionLocalOf<List<ui.model.OrbitUser>> { emptyList() }

/** Room id → id of the first message that arrived while the room was away this session. */
val LocalRoomUnreadMarkers = compositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * Every derived colour the UI layer is allowed to use.
 *
 * Screens must not hard-code colours — that is exactly how the app ended up
 * with forty files disagreeing about what "muted" means. Anything new goes
 * here first, and the per-package `internal val` aliases (ScreenTokens.kt,
 * ChatTokens.kt, …) keep the old call sites compiling unchanged.
 */
data class CosmicTokens(
    // ── Structural surfaces ──────────────────────────────────────
    /** The canvas: chat background, page background. */
    val Bg: Color,
    /** Cards, embeds, inputs. One step up from Bg. */
    val CardBg: Color,
    /** Card hover. One step up from CardBg. */
    val CardBgHover: Color,
    /** Menus, popovers, tooltips — floats above everything. */
    val Elevated: Color,
    /** Secondary floating surface (nested menus). */
    val ElevatedAlt: Color,
    /** Recessed surface: wells, code blocks, track backgrounds. */
    val Sunken: Color,

    // ── Accent ───────────────────────────────────────────────────
    val Accent: Color,
    val AccentLight: Color,
    val AccentGhost: Color,
    val AccentGhostStrong: Color,
    val OnAccent: Color,
    val AccentBorder: Color,

    // ── Type ─────────────────────────────────────────────────────
    val Text_White: Color,
    val Text_Secondary: Color,
    val Text_Muted: Color,
    val Text_Subtle: Color,

    // ── Lines ────────────────────────────────────────────────────
    val Separator: Color,
    val SeparatorStrong: Color,

    // ── Regions ──────────────────────────────────────────────────
    val Header_Bg: Color,
    val Input_Bg: Color,
    val Members_Bg: Color,
    val Rail_Bg: Color,
    val Rail_Active: Color,
    val Pill_Color: Color,
    val Drawer_Bg: Color,
    val UserBar_Bg: Color,

    // ── Interaction ──────────────────────────────────────────────
    val Hover: Color,
    val Pressed: Color,
    val Selected_Bg: Color,
    val ChannelSelected_Bg: Color,
    val ChannelHover_Bg: Color,
    val MessageHover_Bg: Color,
    val MentionHighlight: Color,

    // ── Semantics ────────────────────────────────────────────────
    val Success: Color,
    val SuccessGhost: Color,
    val Warning: Color,
    val WarningGhost: Color,
    val Danger: Color,
    val DangerGhost: Color,
    val Info: Color,
    val InfoGhost: Color,
    /** Voice/video rooms with an active session. */
    val Live: Color,

    // ── Presence ─────────────────────────────────────────────────
    val PresenceOnline: Color,
    val PresenceIdle: Color,
    val PresenceDnd: Color,
    val PresenceOffline: Color,

    // ── Content ──────────────────────────────────────────────────
    val CodeBg: Color,
    val QuoteBar: Color,
    val BubbleOwn: Color,
    val BubbleOther: Color,
    val Link: Color,
    val AvatarRing: Color,

    // ── Chrome ───────────────────────────────────────────────────
    val Scrim: Color,
    val FocusRing: Color,
    val ScrollbarTrack: Color,
    val ScrollbarThumb: Color,
    val ScrollbarThumbHover: Color,
) {
    /** Elevation levels as (blurRadius, spread-ish) pairs for Modifier.shadow. */
    val shadowSmall get() = 1.dp to 2.dp
    val shadowMedium get() = 6.dp to 4.dp
    val shadowLarge get() = 18.dp to 8.dp
}

private fun darkTokens(cs: androidx.compose.material3.ColorScheme) = CosmicTokens(
    Bg = Ink800,
    CardBg = Ink700,
    CardBgHover = Ink650,
    Elevated = Ink650,
    ElevatedAlt = Ink600,
    Sunken = Ink900,

    Accent = cs.primary,
    AccentLight = Iris400,
    AccentGhost = cs.primary.copy(alpha = 0.14f),
    AccentGhostStrong = cs.primary.copy(alpha = 0.24f),
    OnAccent = Color(0xFFFFFFFF),
    AccentBorder = cs.primary.copy(alpha = 0.38f),

    Text_White = DarkFgStrong,
    Text_Secondary = DarkFgDefault,
    Text_Muted = DarkFgMuted,
    Text_Subtle = DarkFgSubtle,

    Separator = Color(0xFF21262F),
    SeparatorStrong = Color(0xFF2E3542),

    Header_Bg = Ink780,
    Input_Bg = Ink700,
    Members_Bg = Ink800,
    Rail_Bg = Ink950,
    Rail_Active = cs.primary.copy(alpha = 0.16f),
    Pill_Color = DarkFgStrong,
    Drawer_Bg = Ink850,
    UserBar_Bg = Ink950,

    Hover = Color(0x0AFFFFFF),
    Pressed = Color(0x14FFFFFF),
    Selected_Bg = cs.primary.copy(alpha = 0.14f),
    ChannelSelected_Bg = cs.primary.copy(alpha = 0.14f),
    ChannelHover_Bg = Color(0x0AFFFFFF),
    MessageHover_Bg = Color(0x08FFFFFF),
    MentionHighlight = cs.primary.copy(alpha = 0.10f),

    Success = Sage500,
    SuccessGhost = Sage500.copy(alpha = 0.16f),
    Warning = Amber500,
    WarningGhost = Amber500.copy(alpha = 0.16f),
    Danger = Rust500,
    DangerGhost = Rust500.copy(alpha = 0.16f),
    Info = Steel500,
    InfoGhost = Steel500.copy(alpha = 0.16f),
    Live = Sage500,

    PresenceOnline = PresenceOnline,
    PresenceIdle = PresenceIdle,
    PresenceDnd = PresenceDnd,
    PresenceOffline = PresenceOffline,

    CodeBg = Ink900,
    QuoteBar = Iris500,
    BubbleOwn = Iris800,
    BubbleOther = Ink700,
    Link = Iris400,
    AvatarRing = Color(0x14FFFFFF),

    Scrim = Color(0xCC05070A),
    FocusRing = Iris400.copy(alpha = 0.55f),
    ScrollbarTrack = Color.Transparent,
    ScrollbarThumb = Color(0x24FFFFFF),
    ScrollbarThumbHover = Color(0x42FFFFFF),
)

private fun lightTokens(cs: androidx.compose.material3.ColorScheme) = CosmicTokens(
    Bg = Paper0,
    CardBg = Paper100,
    CardBgHover = Paper150,
    Elevated = Paper0,
    ElevatedAlt = Paper50,
    Sunken = Paper100,

    Accent = cs.primary,
    AccentLight = Iris500,
    AccentGhost = cs.primary.copy(alpha = 0.10f),
    AccentGhostStrong = cs.primary.copy(alpha = 0.16f),
    OnAccent = Color(0xFFFFFFFF),
    AccentBorder = cs.primary.copy(alpha = 0.30f),

    Text_White = LightFgStrong,
    Text_Secondary = LightFgDefault,
    Text_Muted = LightFgMuted,
    Text_Subtle = LightFgSubtle,

    Separator = Paper200,
    SeparatorStrong = Paper300,

    Header_Bg = Paper50,
    Input_Bg = Paper100,
    Members_Bg = Paper0,
    Rail_Bg = Paper100,
    Rail_Active = cs.primary.copy(alpha = 0.12f),
    Pill_Color = LightFgStrong,
    Drawer_Bg = Paper50,
    UserBar_Bg = Paper0,

    Hover = Color(0x06000000),
    Pressed = Color(0x0D000000),
    Selected_Bg = cs.primary.copy(alpha = 0.10f),
    ChannelSelected_Bg = cs.primary.copy(alpha = 0.10f),
    ChannelHover_Bg = Color(0x07000000),
    MessageHover_Bg = Color(0x08000000),
    MentionHighlight = cs.primary.copy(alpha = 0.08f),

    Success = Sage600,
    SuccessGhost = Sage600.copy(alpha = 0.12f),
    Warning = Amber600,
    WarningGhost = Amber600.copy(alpha = 0.14f),
    Danger = Rust600,
    DangerGhost = Rust600.copy(alpha = 0.12f),
    Info = Steel500,
    InfoGhost = Steel500.copy(alpha = 0.12f),
    Live = Sage600,

    PresenceOnline = Sage600,
    PresenceIdle = Amber600,
    PresenceDnd = Rust600,
    PresenceOffline = LightFgSubtle,

    CodeBg = Paper100,
    QuoteBar = Iris600,
    BubbleOwn = Iris050,
    BubbleOther = Paper100,
    Link = Iris600,
    AvatarRing = Color(0x14000000),

    Scrim = Color(0x59000000),
    FocusRing = cs.primary.copy(alpha = 0.55f),
    ScrollbarTrack = Color.Transparent,
    ScrollbarThumb = Color(0x1F000000),
    ScrollbarThumbHover = Color(0x33000000),
)

@Composable
fun rememberCosmicTokens(): CosmicTokens {
    val cs = MaterialTheme.colorScheme
    // Derive light/dark from the scheme's own luminance rather than the theme
    // enum, so user colour overrides and dynamic schemes stay coherent.
    return if (cs.background.luminance() < 0.5f) darkTokens(cs) else lightTokens(cs)
}

/**
 * Theme variants.
 *
 * These used to be pure-hue swaps of one saturated blue, which made every
 * single one of them neon. Each now keeps the neutral ramp intact and only
 * shifts the accent plus a whisper of tint into the surfaces, so the variants
 * read as distinct moods rather than as different apps.
 */

/** True black, for OLED panels. Only the canvas steps all the way down. */
val AmoledColorScheme = DefaultColorScheme.copy(
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF15171C),
    onSurfaceVariant = DarkFgDefault,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0B0E),
    surfaceContainer = Color(0xFF101216),
    surfaceContainerHigh = Color(0xFF181A20),
    surfaceContainerHighest = Color(0xFF20232A),
    outline = DarkFgMuted,
    outlineVariant = Color(0xFF23262D),
)

/** Cold slate blues. */
val OceanColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFF5A8BC7),
    secondary = Color(0xFF7FA8D4),
    primaryContainer = Color(0xFF1B3149),
    onPrimaryContainer = Color(0xFFCFE2F6),
    secondaryContainer = Color(0xFF17293B),
    background = Color(0xFF101519),
    surface = Color(0xFF101519),
    surfaceVariant = Color(0xFF1A2228),
    onSurfaceVariant = Color(0xFFBFCBD4),
    surfaceContainerLowest = Color(0xFF0A0E12),
    surfaceContainerLow = Color(0xFF101519),
    surfaceContainer = Color(0xFF141A20),
    surfaceContainerHigh = Color(0xFF1A2128),
    surfaceContainerHighest = Color(0xFF212931),
    outline = Color(0xFF8595A2),
    outlineVariant = Color(0xFF232B32),
)

/** Deep evergreen. */
val ForestColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFF4E9E7C),
    secondary = Color(0xFF74B497),
    primaryContainer = Color(0xFF16332A),
    onPrimaryContainer = Color(0xFFCBE9DB),
    secondaryContainer = Color(0xFF132A23),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF19211C),
    onSurfaceVariant = Color(0xFFBFCBC3),
    surfaceContainerLowest = Color(0xFF090D0B),
    surfaceContainerLow = Color(0xFF0F1512),
    surfaceContainer = Color(0xFF141A17),
    surfaceContainerHigh = Color(0xFF1A211D),
    surfaceContainerHighest = Color(0xFF212925),
    outline = Color(0xFF85968D),
    outlineVariant = Color(0xFF222A25),
)

/** Warm clay and ember — the evening theme. */
val SunsetColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFFCE7A4A),
    secondary = Color(0xFFDD9A6B),
    primaryContainer = Color(0xFF3A2317),
    onPrimaryContainer = Color(0xFFF6DCC9),
    secondaryContainer = Color(0xFF33211A),
    background = Color(0xFF17120F),
    surface = Color(0xFF17120F),
    surfaceVariant = Color(0xFF241C18),
    onSurfaceVariant = Color(0xFFD9C7BC),
    surfaceContainerLowest = Color(0xFF0E0B09),
    surfaceContainerLow = Color(0xFF17120F),
    surfaceContainer = Color(0xFF1C1613),
    surfaceContainerHigh = Color(0xFF241D19),
    surfaceContainerHighest = Color(0xFF2C231E),
    outline = Color(0xFFA08D81),
    outlineVariant = Color(0xFF2E2520),
)

/** Muted magenta on graphite — after hours, not neon. */
val CyberpunkColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFFB565A0),
    secondary = Color(0xFF9B6FB8),
    primaryContainer = Color(0xFF351F30),
    onPrimaryContainer = Color(0xFFF0D2E6),
    secondaryContainer = Color(0xFF2A1F3A),
    background = Color(0xFF121015),
    surface = Color(0xFF121015),
    surfaceVariant = Color(0xFF1E1A20),
    onSurfaceVariant = Color(0xFFC9BFC9),
    surfaceContainerLowest = Color(0xFF0B090C),
    surfaceContainerLow = Color(0xFF121015),
    surfaceContainer = Color(0xFF17141A),
    surfaceContainerHigh = Color(0xFF1E1A21),
    surfaceContainerHighest = Color(0xFF26222A),
    outline = Color(0xFF948C96),
    outlineVariant = Color(0xFF272229),
)

/** Warm charcoal — the most "designed object" of the set. */
val GraphiteColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFFB08A62),
    secondary = Color(0xFFC4A585),
    primaryContainer = Color(0xFF3A2E20),
    onPrimaryContainer = Color(0xFFF2E2CD),
    secondaryContainer = Color(0xFF332A20),
    background = Color(0xFF15140F),
    surface = Color(0xFF15140F),
    surfaceVariant = Color(0xFF211F19),
    onSurfaceVariant = Color(0xFFCDC7B8),
    surfaceContainerLowest = Color(0xFF0C0B08),
    surfaceContainerLow = Color(0xFF15140F),
    surfaceContainer = Color(0xFF1A1913),
    surfaceContainerHigh = Color(0xFF222019),
    surfaceContainerHighest = Color(0xFF2A271F),
    outline = Color(0xFF9C968A),
    outlineVariant = Color(0xFF2B2822),
)

/** Cool near-monochrome; the accent is reserved almost entirely for selection. */
val SlateColorScheme = DefaultColorScheme.copy(
    primary = Color(0xFF8FA3BF),
    secondary = Color(0xFFA7B8D0),
    primaryContainer = Color(0xFF25303D),
    onPrimaryContainer = Color(0xFFD5E2F1),
    secondaryContainer = Color(0xFF212B36),
    background = Color(0xFF131417),
    surface = Color(0xFF131417),
    surfaceVariant = Color(0xFF1D1F23),
    onSurfaceVariant = Color(0xFFC0C4CB),
    surfaceContainerLowest = Color(0xFF0C0D0F),
    surfaceContainerLow = Color(0xFF131417),
    surfaceContainer = Color(0xFF181A1E),
    surfaceContainerHigh = Color(0xFF1F2126),
    surfaceContainerHighest = Color(0xFF272A30),
    outline = Color(0xFF8B9099),
    outlineVariant = Color(0xFF26282E),
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
    Cyberpunk,
    Graphite,
    Slate
}

@Composable
fun getColorScheme(
    requestedTheme: Theme,
    colourOverrides: OverridableColourScheme? = null
): ColorScheme {
    val systemInDarkTheme = isSystemInDarkTheme()

    val colorScheme = when {
        requestedTheme == Theme.Default -> DefaultColorScheme
        requestedTheme == Theme.Light -> LightColorScheme
        requestedTheme == Theme.Amoled -> AmoledColorScheme
        requestedTheme == Theme.Ocean -> OceanColorScheme
        requestedTheme == Theme.Forest -> ForestColorScheme
        requestedTheme == Theme.Sunset -> SunsetColorScheme
        requestedTheme == Theme.Cyberpunk -> CyberpunkColorScheme
        requestedTheme == Theme.Graphite -> GraphiteColorScheme
        requestedTheme == Theme.Slate -> SlateColorScheme
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
        Theme.Default, Theme.Amoled, Theme.Ocean, Theme.Forest, Theme.Sunset,
        Theme.Cyberpunk, Theme.Graphite, Theme.Slate -> true
        Theme.Light -> false
        Theme.M3Dynamic, Theme.None -> systemIsDark
    }
}

@Composable
fun isThemeDark(theme: Theme) = isThemeDark(theme, isSystemInDarkTheme())
