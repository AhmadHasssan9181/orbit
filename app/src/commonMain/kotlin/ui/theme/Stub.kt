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

object Colour {
    val PrimaryLight = Color(0xFF000000)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFDDDDDD)
    val OnPrimaryContainerLight = Color(0xFF000000)
    val SecondaryLight = Color(0xFF000000)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFDDDDDD)
    val OnSecondaryContainerLight = Color(0xFF000000)
    val TertiaryLight = Color(0xFF000000)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val TertiaryContainerLight = Color(0xFFDDDDDD)
    val OnTertiaryContainerLight = Color(0xFF000000)
    val ErrorLight = Color(0xFFFF0000)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFFFDDDD)
    val OnErrorContainerLight = Color(0xFF000000)
    val BackgroundLight = Color(0xFFFFFFFF)
    val OnBackgroundLight = Color(0xFF000000)
    val SurfaceLight = Color(0xFFFFFFFF)
    val OnSurfaceLight = Color(0xFF000000)
    val SurfaceVariantLight = Color(0xFFEEEEEE)
    val OnSurfaceVariantLight = Color(0xFF000000)
    val OutlineLight = Color(0xFF888888)
    val OutlineVariantLight = Color(0xFFCCCCCC)
    val ScrimLight = Color(0xFF000000)
    val InverseSurfaceLight = Color(0xFF000000)
    val InverseOnSurfaceLight = Color(0xFFFFFFFF)
    val InversePrimaryLight = Color(0xFFFFFFFF)
    val SurfaceDimLight = Color(0xFFDDDDDD)
    val SurfaceBrightLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowLight = Color(0xFFEEEEEE)
    val SurfaceContainerLight = Color(0xFFDDDDDD)
    val SurfaceContainerHighLight = Color(0xFFCCCCCC)
    val SurfaceContainerHighestLight = Color(0xFFBBBBBB)

    val PrimaryDark = Color(0xFFFFFFFF)
    val OnPrimaryDark = Color(0xFF000000)
    val PrimaryContainerDark = Color(0xFF444444)
    val OnPrimaryContainerDark = Color(0xFFFFFFFF)
    val SecondaryDark = Color(0xFFFFFFFF)
    val OnSecondaryDark = Color(0xFF000000)
    val SecondaryContainerDark = Color(0xFF444444)
    val OnSecondaryContainerDark = Color(0xFFFFFFFF)
    val TertiaryDark = Color(0xFFFFFFFF)
    val OnTertiaryDark = Color(0xFF000000)
    val TertiaryContainerDark = Color(0xFF444444)
    val OnTertiaryContainerDark = Color(0xFFFFFFFF)
    val ErrorDark = Color(0xFFFF5555)
    val OnErrorDark = Color(0xFF000000)
    val ErrorContainerDark = Color(0xFF770000)
    val OnErrorContainerDark = Color(0xFFFFFFFF)
    val BackgroundDark = Color(0xFF111111)
    val OnBackgroundDark = Color(0xFFFFFFFF)
    val SurfaceDark = Color(0xFF111111)
    val OnSurfaceDark = Color(0xFFFFFFFF)
    val SurfaceVariantDark = Color(0xFF333333)
    val OnSurfaceVariantDark = Color(0xFFFFFFFF)
    val OutlineDark = Color(0xFF777777)
    val OutlineVariantDark = Color(0xFF444444)
    val ScrimDark = Color(0xFF000000)
    val InverseSurfaceDark = Color(0xFFFFFFFF)
    val InverseOnSurfaceDark = Color(0xFF000000)
    val InversePrimaryDark = Color(0xFF000000)
    val SurfaceDimDark = Color(0xFF222222)
    val SurfaceBrightDark = Color(0xFF444444)
    val SurfaceContainerLowestDark = Color(0xFF000000)
    val SurfaceContainerLowDark = Color(0xFF111111)
    val SurfaceContainerDark = Color(0xFF222222)
    val SurfaceContainerHighDark = Color(0xFF333333)
    val SurfaceContainerHighestDark = Color(0xFF444444)
}

val Color_GradientStart = Color(0xFF6C20DF)
val Color_GradientMid = Color(0xFF863CD5)
val Color_SuccessGreen = Color(0xFF22C55E)
val Color_WarningAmber = Color(0xFFF59E0B)
val Color_DNDRed = Color(0xFFEF4444)

