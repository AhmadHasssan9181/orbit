package ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 7 Signature Material You Preset Seeds.
 */
data class MaterialYouSeed(
    val name: String,
    val color: Color
)

val MaterialYouSeeds = listOf(
    MaterialYouSeed("Crimson", Color(0xFFB33927)),  // Stoat Red / Burgundy
    MaterialYouSeed("Amber", Color(0xFFB58900)),    // Stoat Yellow / Mustard
    MaterialYouSeed("Forest", Color(0xFF2E6B38)),   // Stoat Green / Moss
    MaterialYouSeed("Ocean", Color(0xFF006399)),    // Slate Blue
    MaterialYouSeed("Iris", Color(0xFF6750A4)),     // Lavender / Purple
    MaterialYouSeed("Coral", Color(0xFF9C4331)),    // Terracotta / Rust
    MaterialYouSeed("Teal", Color(0xFF006A6A))      // Mint / Deep Teal
)

// ── Oklab / Perceptual Tonal Palette Generation ─────────────────────────────────

private fun srgbToLinear(c: Float): Float =
    if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(c: Float): Float {
    val clamped = c.coerceIn(0f, 1f)
    return if (clamped <= 0.0031308f) 12.92f * clamped else 1.055f * clamped.pow(1f / 2.4f) - 0.055f
}

private fun cbrt(x: Float): Float =
    if (x >= 0f) x.pow(1f / 3f) else -(-x).pow(1f / 3f)

private fun colorToOklab(c: Color): FloatArray {
    val rL = srgbToLinear(c.red)
    val gL = srgbToLinear(c.green)
    val bL = srgbToLinear(c.blue)

    val l = 0.4122214708f * rL + 0.5363325363f * gL + 0.0514459929f * bL
    val m = 0.2119034982f * rL + 0.6806995451f * gL + 0.1073969566f * bL
    val s = 0.0883024619f * rL + 0.2817188376f * gL + 0.6299787005f * bL

    val l_ = cbrt(l)
    val m_ = cbrt(m)
    val s_ = cbrt(s)

    val oL = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_
    val oa = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_
    val ob = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
    return floatArrayOf(oL, oa, ob)
}

private fun oklabToColor(oL: Float, oa: Float, ob: Float): Color {
    val l_ = oL + 0.3963377774f * oa + 0.2158037573f * ob
    val m_ = oL - 0.1055613458f * oa - 0.0638541728f * ob
    val s_ = oL - 0.0894841775f * oa - 1.2914855480f * ob

    val l = l_ * l_ * l_
    val m = m_ * m_ * m_
    val s = s_ * s_ * s_

    val rL = +4.0767434771f * l - 3.3077115913f * m + 0.2309699292f * s
    val gL = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
    val bL = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s

    return Color(
        red = linearToSrgb(rL),
        green = linearToSrgb(gL),
        blue = linearToSrgb(bL)
    )
}

private class TonalPalette(val hue: Float, val chroma: Float) {
    fun tone(t: Int): Color {
        val oL = (t.coerceIn(0, 100) / 100f)
        val gamutTaper = sin(PI.toFloat() * oL.coerceIn(0f, 1f)).pow(0.55f)
        val effChroma = (chroma * gamutTaper).coerceAtLeast(chroma * 0.40f * sin(PI.toFloat() * oL.coerceIn(0f, 1f)))
        val oa = effChroma * cos(hue)
        val ob = effChroma * sin(hue)
        return oklabToColor(oL, oa, ob)
    }
}

/**
 * Builds Material Design 3 ColorScheme from seed hue with calibrated Stoat aesthetics:
 * - In Dark Mode: Leftmost rail is LIGHTEST (tone 29, #322825), sidebar is DARKER (tone 23, #231917), chat canvas is DARKEST (tone 16, #140C0A)
 * - In Light Mode: Leftmost rail is warm pastel foundation (tone 91, #F7E4E0), sidebar is soft pastel (tone 96, #FFF0ED), chat is crisp white (tone 100)
 */
fun generateMaterialYouColorScheme(seedColor: Color, isDark: Boolean): ColorScheme {
    val lab = colorToOklab(seedColor)
    val rawChroma = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
    val hue = atan2(lab[2], lab[1])

    return if (isDark) {
        // Stoat Apple-like matte dark mode:
        // Left rail = Tone 29 (#322825 / #2D2A21 / #272B25)
        // Sidebar drawer = Tone 23 (#231917 / #1E1B13 / #191D17)
        // Chat canvas = Tone 16 (#140C0A / #100E07 / #0B0F0A)
        // Selection pill = Tone 40 (#733426 / #534600 / #285021)
        val primary = TonalPalette(hue, rawChroma.coerceIn(0.080f, 0.100f))
        val secondary = TonalPalette(hue, 0.050f)
        val tertiary = TonalPalette(hue + (PI.toFloat() / 3f), 0.060f)

        val surfacePalette = TonalPalette(hue, 0.018f)
        val surfaceContainerPalette = TonalPalette(hue, 0.024f)
        val pillPalette = TonalPalette(hue, 0.110f)
        val outlinePalette = TonalPalette(hue, 0.024f)

        darkColorScheme(
            primary = primary.tone(75),
            onPrimary = primary.tone(10),
            primaryContainer = pillPalette.tone(40),      // Tone 40: warm pastel pill (#733426 / #534600 / #285021)
            onPrimaryContainer = primary.tone(94),
            secondary = secondary.tone(75),
            onSecondary = secondary.tone(10),
            secondaryContainer = pillPalette.tone(28),
            onSecondaryContainer = primary.tone(90),
            tertiary = tertiary.tone(75),
            onTertiary = tertiary.tone(10),
            tertiaryContainer = pillPalette.tone(28),
            onTertiaryContainer = tertiary.tone(90),
            background = surfacePalette.tone(16),         // Tone 16: chat canvas (#140C0A / #100E07 / #0B0F0A)
            onBackground = Color(0xFFE6E3E0),             // Soft off-white
            surface = surfacePalette.tone(16),            // Tone 16: chat canvas
            onSurface = Color(0xFFE6E3E0),                // Soft off-white
            surfaceVariant = surfaceContainerPalette.tone(20),
            onSurfaceVariant = Color(0xFFA5A09C),         // Soft secondary neutral
            outline = outlinePalette.tone(50),
            outlineVariant = outlinePalette.tone(24),
            surfaceContainerLowest = surfacePalette.tone(16),         // Tone 16: deepest canvas
            surfaceContainerLow = surfaceContainerPalette.tone(23),   // Tone 23: darker sidebar drawer (#231917 / #1E1B13 / #191D17)
            surfaceContainer = surfaceContainerPalette.tone(25),      // Tone 25: intermediate
            surfaceContainerHigh = surfaceContainerPalette.tone(29),  // Tone 29: lightest foundation rail & input pill (#322825 / #2D2A21 / #272B25)
            surfaceContainerHighest = pillPalette.tone(40),           // Tone 40: active selection pill
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
            scrim = Color(0xFF000000)
        )
    } else {
        // Stoat Apple-like pastel light mode:
        // Leftmost rail is tinted foundation (tone 91, #F7E4E0), sidebar is soft pastel (tone 96, #FFF0ED), chat is crisp white (tone 100)
        val primary = TonalPalette(hue, rawChroma.coerceIn(0.085f, 0.110f))
        val secondary = TonalPalette(hue, 0.060f)
        val tertiary = TonalPalette(hue + (PI.toFloat() / 3f), 0.070f)

        val surfacePalette = TonalPalette(hue, 0.024f)           // Soft pastel tint
        val surfaceContainerPalette = TonalPalette(hue, 0.038f)  // Warm pastel foundation
        val pillPalette = TonalPalette(hue, 0.075f)              // Signature pastel pill
        val outlinePalette = TonalPalette(hue, 0.030f)

        lightColorScheme(
            primary = primary.tone(40),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = pillPalette.tone(86),      // Tone 86: delicate pastel pill (#F6D2CE / #F3E28D)
            onPrimaryContainer = Color(0xFF1B1A19),
            secondary = secondary.tone(40),
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = pillPalette.tone(88),
            onSecondaryContainer = Color(0xFF1B1A19),
            tertiary = tertiary.tone(40),
            onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = tertiary.tone(88),
            onTertiaryContainer = Color(0xFF1B1A19),
            background = surfacePalette.tone(96),         // Tone 96: high luminance light background
            onBackground = Color(0xFF1B1A19),             // Deep charcoal for crisp contrast
            surface = surfacePalette.tone(96),            // Tone 96: light surface
            onSurface = Color(0xFF1B1A19),                // Deep charcoal
            surfaceVariant = surfaceContainerPalette.tone(91),
            onSurfaceVariant = Color(0xFF524C49),         // Medium charcoal
            outline = outlinePalette.tone(55),
            outlineVariant = outlinePalette.tone(84),
            surfaceContainerLowest = Color(0xFFFFFFFF),               // Pure white
            surfaceContainerLow = surfacePalette.tone(96),            // Tone 96: soft pastel outer card (drawer) (#FFF0ED)
            surfaceContainer = surfaceContainerPalette.tone(91),      // Tone 91: warm pastel foundation rail (#F7E4E0)
            surfaceContainerHigh = surfaceContainerPalette.tone(91),  // Tone 91: composer input pill
            surfaceContainerHighest = pillPalette.tone(86),           // Tone 86: active selection pill
            error = Color(0xFFBA1A1A),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFFDAD6),
            onErrorContainer = Color(0xFF410002),
            scrim = Color(0xFF000000)
        )
    }
}

// ── Dynamic CosmicTokens Bridges ───────────────────────────────────────────────

fun dynamicDarkTokens(cs: ColorScheme) = CosmicTokens(
    Bg = cs.background,                              // Tone 16: darkest chat canvas (#140C0A / #100E07 / #0B0F0A)
    CardBg = cs.surfaceContainerLow,                 // Tone 23: darker sidebar drawer (#231917 / #1E1B13 / #191D17)
    CardBgHover = cs.surfaceContainer,               // Tone 25
    Elevated = cs.surfaceContainerHigh,              // Tone 29: lightest surface (#322825 / #2D2A21 / #272B25)
    ElevatedAlt = cs.surfaceContainerHighest,        // Tone 40
    Sunken = cs.surfaceContainerLowest,              // Tone 16

    Accent = cs.primary,
    AccentLight = cs.primaryContainer,
    AccentGhost = cs.primary.copy(alpha = 0.14f),
    AccentGhostStrong = cs.primary.copy(alpha = 0.22f),
    OnAccent = cs.onPrimary,
    AccentBorder = cs.primary.copy(alpha = 0.35f),

    Text_White = cs.onSurface,
    Text_Secondary = cs.onSurfaceVariant,
    Text_Muted = Color(0xFF78736F),
    Text_Subtle = cs.outline,

    Separator = cs.outlineVariant.copy(alpha = 0.35f),
    SeparatorStrong = cs.outlineVariant.copy(alpha = 0.60f),

    Header_Bg = Color.Transparent,
    Input_Bg = cs.surfaceContainerHigh,              // Tone 29: lighter pill inside chat (#322825 / #2D2A21 / #272B25)
    Members_Bg = cs.surfaceContainerLow,
    Rail_Bg = cs.surfaceContainerHigh,               // Tone 29: LIGHTEST foundation rail & title bar (#322825 / #2D2A21 / #272B25)!
    Rail_Idle = cs.surfaceContainerLow,
    Rail_Active = cs.primary.copy(alpha = 0.20f),
    Pill_Color = cs.onSurface,
    Drawer_Bg = cs.surfaceContainerLow,              // Tone 23: DARKER sidebar drawer (#231917 / #1E1B13 / #191D17)!
    UserBar_Bg = cs.surfaceContainerLow,

    Hover = cs.onSurface.copy(alpha = 0.05f),
    Pressed = cs.onSurface.copy(alpha = 0.10f),
    Selected_Bg = cs.primaryContainer,               // Tone 40: warm pastel terracotta / mustard / moss pill (#733426 / #534600 / #285021)
    ChannelSelected_Bg = cs.primaryContainer,
    ChannelHover_Bg = cs.surfaceContainer,
    MessageHover_Bg = cs.surfaceContainer.copy(alpha = 0.35f),
    MentionHighlight = cs.primary.copy(alpha = 0.12f),

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

    CodeBg = cs.background,
    QuoteBar = cs.primary,
    BubbleOwn = cs.primaryContainer,
    BubbleOther = cs.surfaceContainerLow,
    Link = cs.primary,
    AvatarRing = cs.onSurface.copy(alpha = 0.08f),

    Scrim = cs.scrim.copy(alpha = 0.70f),
    FocusRing = cs.primary.copy(alpha = 0.45f),
    ScrollbarTrack = Color.Transparent,
    ServerDivider = cs.outlineVariant.copy(alpha = 0.30f),
    ScrollbarThumb = cs.onSurface.copy(alpha = 0.18f),
    ScrollbarThumbHover = cs.onSurface.copy(alpha = 0.32f)
)

fun dynamicLightTokens(cs: ColorScheme) = CosmicTokens(
    Bg = Color(0xFFFFFFFF),                          // Tone 100: crisp pure white floating chat box
    CardBg = Color(0xFFFFFFFF),
    CardBgHover = cs.surfaceContainerLowest,
    Elevated = Color(0xFFFFFFFF),
    ElevatedAlt = cs.surfaceContainerLow,
    Sunken = cs.surfaceContainer,

    Accent = cs.primary,                             // Tone 40 deep accent
    AccentLight = cs.primaryContainer,
    AccentGhost = cs.primary.copy(alpha = 0.10f),
    AccentGhostStrong = cs.primary.copy(alpha = 0.18f),
    OnAccent = cs.onPrimary,
    AccentBorder = cs.primary.copy(alpha = 0.28f),

    Text_White = cs.onSurface,                       // Crisp deep charcoal text (#1B1A19)
    Text_Secondary = cs.onSurfaceVariant,           // Medium charcoal (#524C49)
    Text_Muted = Color(0xFF78736F),
    Text_Subtle = cs.outline,

    Separator = cs.outlineVariant.copy(alpha = 0.40f),
    SeparatorStrong = cs.outlineVariant.copy(alpha = 0.75f),

    Header_Bg = Color.Transparent,
    Input_Bg = cs.surfaceContainer,                  // Tone 91: soft pastel pill inside white chat card (#F7E4E0)
    Members_Bg = cs.surfaceContainerLowest,
    Rail_Bg = cs.surfaceContainer,                   // Tone 91: warm pastel foundation rail (#F7E4E0)
    Rail_Idle = cs.surfaceContainerHigh,
    Rail_Active = cs.primary.copy(alpha = 0.12f),
    Pill_Color = cs.onSurface,
    Drawer_Bg = cs.surfaceContainerLow,              // Tone 96: soft airy pastel outer card (drawer) (#FFF0ED)
    UserBar_Bg = cs.surfaceContainerLowest,

    Hover = cs.onSurface.copy(alpha = 0.04f),
    Pressed = cs.onSurface.copy(alpha = 0.08f),
    Selected_Bg = cs.primaryContainer,               // Tone 86: delicate pastel pill (#F6D2CE / #F3E28D)
    ChannelSelected_Bg = cs.primaryContainer,        // Tone 86: delicate pastel pill
    ChannelHover_Bg = cs.surfaceContainerHigh.copy(alpha = 0.60f),
    MessageHover_Bg = cs.surfaceContainer.copy(alpha = 0.25f),
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

    CodeBg = cs.surfaceContainer,
    QuoteBar = cs.primary,
    BubbleOwn = cs.primaryContainer,
    BubbleOther = cs.surfaceContainerLow,
    Link = cs.primary,
    AvatarRing = cs.onSurface.copy(alpha = 0.08f),

    Scrim = cs.scrim.copy(alpha = 0.40f),
    FocusRing = cs.primary.copy(alpha = 0.45f),
    ScrollbarTrack = Color.Transparent,
    ServerDivider = cs.outlineVariant.copy(alpha = 0.30f),
    ScrollbarThumb = cs.onSurface.copy(alpha = 0.16f),
    ScrollbarThumbHover = cs.onSurface.copy(alpha = 0.28f)
)
