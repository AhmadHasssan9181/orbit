package ui.theme

import androidx.compose.ui.graphics.Color


data class OverridableColourScheme(
    val primary: Int? = null,
    val onPrimary: Int? = null,
    val primaryContainer: Int? = null,
    val onPrimaryContainer: Int? = null,
    val inversePrimary: Int? = null,
    val secondary: Int? = null,
    val onSecondary: Int? = null,
    val secondaryContainer: Int? = null,
    val onSecondaryContainer: Int? = null,
    val tertiary: Int? = null,
    val onTertiary: Int? = null,
    val tertiaryContainer: Int? = null,
    val onTertiaryContainer: Int? = null,
    val background: Int? = null,
    val onBackground: Int? = null,
    val surface: Int? = null,
    val onSurface: Int? = null,
    val surfaceVariant: Int? = null,
    val onSurfaceVariant: Int? = null,
    val surfaceTint: Int? = null,
    val inverseSurface: Int? = null,
    val inverseOnSurface: Int? = null,
    val error: Int? = null,
    val onError: Int? = null,
    val errorContainer: Int? = null,
    val onErrorContainer: Int? = null,
    val outline: Int? = null,
    val outlineVariant: Int? = null,
    val scrim: Int? = null
)

/**
 * Discord/Revolt-inspired color tokens.
 * Dark scheme uses the same surface depth hierarchy as Discord
 * (background → surfaceContainerLowest → Low → Container → High → Highest).
 */
object Colour {
    // ─── Light (WhatsApp mode) ───────────────────────────────────
    val PrimaryLight = Color(0xFF075E54)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFBBF2DC)
    val OnPrimaryContainerLight = Color(0xFF002018)
    val SecondaryLight = Color(0xFF128C7E)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFC8F9EB)
    val OnSecondaryContainerLight = Color(0xFF002118)
    val TertiaryLight = Color(0xFF3B82F6)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val TertiaryContainerLight = Color(0xFFDAE6FF)
    val OnTertiaryContainerLight = Color(0xFF001847)
    val ErrorLight = Color(0xFFBA1A1A)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDAD6)
    val OnErrorContainerLight = Color(0xFF410002)
    val BackgroundLight = Color(0xFFFFFFFF)
    val OnBackgroundLight = Color(0xFF1A1C1E)
    val SurfaceLight = Color(0xFFF8F9FA)
    val OnSurfaceLight = Color(0xFF1A1C1E)
    val SurfaceVariantLight = Color(0xFFE7E0EC)
    val OnSurfaceVariantLight = Color(0xFF49454F)
    val OutlineLight = Color(0xFF79747E)
    val OutlineVariantLight = Color(0xFFCAC4D0)
    val ScrimLight = Color(0xFF000000)
    val InverseSurfaceLight = Color(0xFF313033)
    val InverseOnSurfaceLight = Color(0xFFF4EFF4)
    val InversePrimaryLight = Color(0xFFD0BCFF)
    val SurfaceDimLight = Color(0xFFDED8E1)
    val SurfaceBrightLight = Color(0xFFFFFBFE)
    val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowLight = Color(0xFFF7F2FA)
    val SurfaceContainerLight = Color(0xFFF3EDF7)
    val SurfaceContainerHighLight = Color(0xFFECE6F0)
    val SurfaceContainerHighestLight = Color(0xFFE6E0E9)

    // ─── Dark (Discord/Revolt style) ─────────────────────────────
    val PrimaryDark = Color(0xFF7C6AF0)           // Accent purple
    val OnPrimaryDark = Color(0xFFFFFFFF)
    val PrimaryContainerDark = Color(0xFF3A2F72)   // Muted purple container
    val OnPrimaryContainerDark = Color(0xFFE8DEFF)
    val SecondaryDark = Color(0xFFA78BFA)          // Light purple accent
    val OnSecondaryDark = Color(0xFF1E1036)
    val SecondaryContainerDark = Color(0xFF342A5C)
    val OnSecondaryContainerDark = Color(0xFFE8DEFF)
    val TertiaryDark = Color(0xFF34D399)           // Status green
    val OnTertiaryDark = Color(0xFF003822)
    val TertiaryContainerDark = Color(0xFF005233)
    val OnTertiaryContainerDark = Color(0xFF85F8C7)
    val ErrorDark = Color(0xFFF87171)
    val OnErrorDark = Color(0xFF601410)
    val ErrorContainerDark = Color(0xFF8C1D18)
    val OnErrorContainerDark = Color(0xFFF9DEDC)
    val BackgroundDark = Color(0xFF111827)         // Discord-like dark navy
    val OnBackgroundDark = Color(0xFFE5E7EB)
    val SurfaceDark = Color(0xFF111827)
    val OnSurfaceDark = Color(0xFFE5E7EB)
    val SurfaceVariantDark = Color(0xFF2D3346)     // Slightly elevated
    val OnSurfaceVariantDark = Color(0xFFCAC4D0)
    val OutlineDark = Color(0xFF6B7280)            // Muted grey for outlines
    val OutlineVariantDark = Color(0xFF374151)     // Subtle dividers
    val ScrimDark = Color(0xFF000000)
    val InverseSurfaceDark = Color(0xFFE5E7EB)
    val InverseOnSurfaceDark = Color(0xFF1F2937)
    val InversePrimaryDark = Color(0xFF5B47CC)
    // Surface depth hierarchy — key to Discord-like visual layering
    val SurfaceDimDark = Color(0xFF0D1117)         // Deepest
    val SurfaceBrightDark = Color(0xFF2D3346)
    val SurfaceContainerLowestDark = Color(0xFF0B0F19)  // Server rail
    val SurfaceContainerLowDark = Color(0xFF111827)     // Main background
    val SurfaceContainerDark = Color(0xFF161D2F)        // Channel drawer, input
    val SurfaceContainerHighDark = Color(0xFF1E2538)    // Cards, embeds
    val SurfaceContainerHighestDark = Color(0xFF252D42) // Hover states, elevated
}

val Color_GradientStart = Color(0xFF6C20DF)
val Color_GradientMid = Color(0xFF863CD5)
val Color_SuccessGreen = Color(0xFF22C55E)
val Color_WarningAmber = Color(0xFFF59E0B)
val Color_DNDRed = Color(0xFFEF4444)
