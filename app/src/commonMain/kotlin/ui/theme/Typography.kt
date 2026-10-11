package ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.Font
import onichat.app.generated.resources.Res
import onichat.app.generated.resources.PlusJakartaSans_Bold
import onichat.app.generated.resources.PlusJakartaSans_ExtraBold
import onichat.app.generated.resources.PlusJakartaSans_Italic
import onichat.app.generated.resources.PlusJakartaSans_Medium
import onichat.app.generated.resources.PlusJakartaSans_MediumItalic
import onichat.app.generated.resources.PlusJakartaSans_Regular
import onichat.app.generated.resources.PlusJakartaSans_SemiBold
import onichat.app.generated.resources.PlusJakartaSans_SemiBoldItalic

/**
 * The app-wide typeface: Plus Jakarta Sans, bundled rather than fetched.
 *
 * The faces live in `src/commonMain/composeResources/font/` and reach Kotlin
 * through the generated `Res.font.*` accessors, so the family works offline and
 * ships inside the packaged app instead of depending on a font being installed
 * on the machine. Sources are the static (non-variable) TTFs published by the
 * upstream project; the licence is kept alongside the project in
 * `THIRD_PARTY_LICENSES/PlusJakartaSans-OFL.txt`.
 *
 * Every weight the app actually asks for is registered, plus the italic pairs
 * for body copy. An unregistered weight is not a crash -- Skia synthesises it --
 * but a real cut looks noticeably better, so this list is the whole supported
 * surface.
 *
 * Built by [orbitFontFamily] rather than as a top-level `val` because reading a
 * `FontResource` is a `@Composable` operation (`Font(resId, ...)` from
 * `org.jetbrains.compose.resources`). A plain property initializer cannot call
 * it, which is why this file used to fall back to `FontFamily.Default` -- the
 * old stub was there to sidestep a K2 compiler fault that no longer applies.
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
fun orbitFontFamily(): FontFamily {
    // Materialised outside `remember` because each `Font(...)` call does the
    // resource load; the resulting family is then cached so recomposition does
    // not rebuild it. The loaded fonts are keys so a resource change (locale
    // reload in devtools, say) still invalidates the family.
    val regular = Font(Res.font.PlusJakartaSans_Regular, FontWeight.W400, FontStyle.Normal)
    val italic = Font(Res.font.PlusJakartaSans_Italic, FontWeight.W400, FontStyle.Italic)
    val medium = Font(Res.font.PlusJakartaSans_Medium, FontWeight.W500, FontStyle.Normal)
    val mediumItalic = Font(Res.font.PlusJakartaSans_MediumItalic, FontWeight.W500, FontStyle.Italic)
    val semiBold = Font(Res.font.PlusJakartaSans_SemiBold, FontWeight.W600, FontStyle.Normal)
    val semiBoldItalic = Font(Res.font.PlusJakartaSans_SemiBoldItalic, FontWeight.W600, FontStyle.Italic)
    val bold = Font(Res.font.PlusJakartaSans_Bold, FontWeight.W700, FontStyle.Normal)
    val extraBold = Font(Res.font.PlusJakartaSans_ExtraBold, FontWeight.W800, FontStyle.Normal)

    return remember(regular, italic, medium, mediumItalic, semiBold, semiBoldItalic, bold, extraBold) {
        FontFamily(regular, italic, medium, mediumItalic, semiBold, semiBoldItalic, bold, extraBold)
    }
}

/**
 * The app-wide type scale.
 *
 * The baseline is deliberately heavier than a stock Material scale: body and
 * label text sits at [FontWeight.W500] rather than W400, so ordinary copy reads
 * solid instead of thin at a glance. Weight, not colour, is what carries the
 * hierarchy here.
 *
 * - body / label — W500. The default for anything reading as content.
 * - title / labelLarge — W600-W700. Subheadings and button faces, where a bit
 *   more push separates an action from the text around it.
 * - titleLarge — W800. Reserved for the most prominent header on a screen, so
 *   the heaviest cut stays rare enough to mean something.
 *
 * Muted and secondary text should reach for a lower `color`, not a lighter
 * `fontWeight`: dropping below W500 is what makes the app feel thin again.
 * `MaterialTheme.typography.*.copy(color = ...)` covers that without any
 * per-call-site override of the family.
 *
 * Sizes are unchanged from the previous scale — this is a weight pass, not a
 * size pass, so existing per-call-site `fontSize` overrides keep working.
 */
@Composable
fun orbitTypography(): Typography {
    val jakarta = orbitFontFamily()
    return remember(jakarta) { typographyFor(jakarta) }
}

/**
 * The weight contract, as a plain function of the family it applies to.
 *
 * Split out from [orbitTypography] because the weights are the part of this file
 * worth asserting on, and [orbitTypography] cannot be called from a test: it is
 * `@Composable`, and a font family can only be built inside a composition.
 * Taking the family as a parameter keeps the hierarchy checkable while still
 * letting the real one be wired up at the theme root.
 */
fun typographyFor(family: FontFamily): Typography {
    return Typography(
        titleLarge = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W800,
            fontSize = 22.sp, lineHeight = 28.sp
        ),
        titleMedium = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W700,
            fontSize = 18.sp, lineHeight = 24.sp
        ),
        titleSmall = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W600,
            fontSize = 15.sp, lineHeight = 20.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W500,
            fontSize = 16.sp, lineHeight = 24.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W500,
            fontSize = 14.sp, lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W500,
            fontSize = 13.sp, lineHeight = 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W600,
            fontSize = 13.sp, lineHeight = 16.sp
        ),
        labelMedium = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W500,
            fontSize = 12.sp, lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = family, fontWeight = FontWeight.W500,
            fontSize = 11.sp, lineHeight = 14.sp
        ),
    )
}

// The monospace face used for code blocks and log output. The platform mono is
// kept rather than a bundled one: terminal-style output is better served by
// whatever the OS ships (it matches other tools the user has open), and it keeps
// another few hundred KB out of the package.
val FragmentMono: FontFamily = FontFamily.Monospace

enum class UserInterfaceFont { Default, GoogleSansFlex }

fun getDefaultFont(): UserInterfaceFont = UserInterfaceFont.Default