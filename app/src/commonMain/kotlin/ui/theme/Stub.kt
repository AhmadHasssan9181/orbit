package ui.theme

import androidx.compose.ui.graphics.Color

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*


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
 * Material colour roles for both schemes.
 *
 * Surface depth is a strict five-step ramp so panels can stack without any
 * two neighbours collapsing into the same value — the previous ramp had
 * `surfaceContainer` and `surfaceContainerLow` within 5 RGB of each other,
 * which is why the drawer and the chat canvas used to look like one surface.
 */
object Colour {
    // ─── Light ───────────────────────────────────────────────────
    val PrimaryLight = Iris600
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Iris050
    val OnPrimaryContainerLight = Iris900
    val SecondaryLight = Steel500
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Steel050
    val OnSecondaryContainerLight = Steel900
    val TertiaryLight = Sage600
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val TertiaryContainerLight = Sage050
    val OnTertiaryContainerLight = Sage900
    val ErrorLight = Rust600
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Rust050
    val OnErrorContainerLight = Rust900
    val BackgroundLight = Paper0
    val OnBackgroundLight = LightFgStrong
    val SurfaceLight = Paper0
    val OnSurfaceLight = LightFgStrong
    val SurfaceVariantLight = Paper100
    val OnSurfaceVariantLight = LightFgDefault
    val OutlineLight = Paper400
    val OutlineVariantLight = Paper200
    val ScrimLight = Color(0xFF000000)
    val InverseSurfaceLight = Color(0xFF20242C)
    val InverseOnSurfaceLight = Color(0xFFF0F2F6)
    val InversePrimaryLight = Iris200
    val SurfaceDimLight = Paper200
    val SurfaceBrightLight = Paper0
    val SurfaceContainerLowestLight = Paper0
    val SurfaceContainerLowLight = Paper50
    val SurfaceContainerLight = Paper100
    val SurfaceContainerHighLight = Paper150
    val SurfaceContainerHighestLight = Paper200

    // ─── Dark ────────────────────────────────────────────────────
    val PrimaryDark = Iris500
    val OnPrimaryDark = Color(0xFFFFFFFF)
    val PrimaryContainerDark = Iris800
    val OnPrimaryContainerDark = Iris200
    val SecondaryDark = Iris400
    val OnSecondaryDark = Iris900
    val SecondaryContainerDark = Iris900
    val OnSecondaryContainerDark = Iris200
    val TertiaryDark = Sage500
    val OnTertiaryDark = Color(0xFF072014)
    val TertiaryContainerDark = Sage900
    val OnTertiaryContainerDark = Sage500
    val ErrorDark = Rust500
    val OnErrorDark = Color(0xFF2A0E0C)
    val ErrorContainerDark = Rust900
    val OnErrorContainerDark = Rust500
    val BackgroundDark = Ink800
    val OnBackgroundDark = DarkFgStrong
    val SurfaceDark = Ink800
    val OnSurfaceDark = DarkFgStrong
    val SurfaceVariantDark = Ink700
    val OnSurfaceVariantDark = DarkFgDefault
    val OutlineDark = DarkFgMuted
    val OutlineVariantDark = Ink650
    val ScrimDark = Color(0xFF000000)
    val InverseSurfaceDark = Ink650
    val InverseOnSurfaceDark = DarkFgStrong
    val InversePrimaryDark = Iris200

    // Surface depth hierarchy — the backbone of the layout.
    val SurfaceDimDark = Ink950          // deepest: window chrome, tooltips
    val SurfaceBrightDark = Ink600      // strongest raised surface
    val SurfaceContainerLowestDark = Ink950   // server rail, user bar
    val SurfaceContainerLowDark = Ink800      // app canvas / chat
    val SurfaceContainerDark = Ink850         // channel drawer, side panels
    val SurfaceContainerHighDark = Ink700     // cards, inputs
    val SurfaceContainerHighestDark = Ink650  // popovers, menus, hover
}

val Color_GradientStart = Iris600
val Color_GradientMid = Iris400
val Color_SuccessGreen = Sage500
val Color_WarningAmber = Amber500
val Color_DNDRed = Rust500
