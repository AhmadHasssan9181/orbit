package ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The accent-driven neutral tint.
 *
 * The tint is applied to ~40 tokens at once, in a palette whose file comments
 * make unusually specific claims about surface depth and text contrast. Those
 * claims are the whole reason the design reads correctly, and they are invisible
 * in a diff: a tint that shifted lightness would still compile, still look
 * plausible in a swatch, and quietly collapse two surfaces together or drop body
 * text below its contrast floor.
 *
 * So these assert the invariants that make the approach safe, rather than
 * re-asserting the specific colours.
 */
class ThemeTintTest {

    /** Hex for assertion messages; `Color.value` is a colour-space ULong, not ARGB. */
    private fun Color.label(): String =
        "#" + toArgb().toLong().and(0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

    private fun luminanceOf(c: Color): Double = c.luminance().toDouble()

    private fun contrast(a: Color, b: Color): Double {
        val la = luminanceOf(a) + 0.05
        val lb = luminanceOf(b) + 0.05
        return maxOf(la, lb) / minOf(la, lb)
    }

    /** Accents of very different hue, so no single one can pass by luck. */
    private val accents = listOf(
        Color(0xFF5865F2), // blurple
        Color(0xFFE0559B), // pink
        Color(0xFF1FA97C), // green
        Color(0xFFE8A020), // amber
        Color(0xFF9B5DE5)  // violet
    )

    private val bases = listOf(
        Color(0xFFF2F3F5), Color(0xFFDBDEE1), Color(0xFF949BA4), Color(0xFF6D6F78),
        Color(0xFF313338), Color(0xFF2B2D31), Color(0xFF1E1F22), Color(0xFF111214),
        Color(0xFFFFFFFF), Color(0xFF13161C), Color(0xFFF4F6F9), Color(0xFFB4BCC9)
    )

    /**
     * Lightness must not move.
     *
     * The invariant the whole design rests on. Oklab L is untouched by
     * [ThemeTint.apply], which is what guarantees the surface ramp keeps its
     * ordering and that text contrast is preserved. If a future change blends
     * toward a fixed colour instead of shifting hue, this fails immediately.
     */
    @Test
    fun lightnessIsPreserved() {
        for (accent in accents) {
            for (base in bases) {
                val tinted = ThemeTint.apply(base, accent, ThemeTint.TEXT)
                assertTrue(
                    abs(luminanceOf(tinted) - luminanceOf(base)) < 0.02,
                    "${base.label()} -> ${tinted.label()} moved luminance"
                )
            }
        }
    }

    /**
     * The dark surface ramp keeps its strict ordering.
     *
     * `Stub.kt` documents that neighbouring surface steps must not collapse into
     * the same value. An implementation that shifted lightness breaks this
     * before it breaks anything more visible.
     */
    @Test
    fun darkSurfaceRampKeepsItsOrdering() {
        val ramp = listOf(
            Color(0xFF111214), Color(0xFF1E1F22), Color(0xFF2B2D31), Color(0xFF313338),
            Color(0xFF383A40), Color(0xFF404249), Color(0xFF4E5058)
        )
        for (accent in accents) {
            for (strength in listOf(ThemeTint.SURFACE, ThemeTint.TEXT, ThemeTint.LINE)) {
                val tinted = ramp.map { ThemeTint.apply(it, accent, strength) }
                for (i in 0 until ramp.size - 1) {
                    assertTrue(
                        tinted[i].luminance() < tinted[i + 1].luminance(),
                        "ramp steps $i and ${i + 1} collapsed under ${accent.label()}"
                    )
                }
            }
        }
    }

    /** The same, for the light ramp, which runs the other way. */
    @Test
    fun lightSurfaceRampKeepsItsOrdering() {
        val ramp = listOf(
            Color(0xFFFFFFFF), Color(0xFFFAFBFC), Color(0xFFF4F6F9), Color(0xFFEDF0F5),
            Color(0xFFE3E7EE), Color(0xFFD2D8E2), Color(0xFFB4BCC9)
        )
        for (accent in accents) {
            val tinted = ramp.map { ThemeTint.apply(it, accent, ThemeTint.SURFACE) }
            for (i in 0 until ramp.size - 1) {
                assertTrue(
                    tinted[i].luminance() > tinted[i + 1].luminance(),
                    "light ramp steps $i and ${i + 1} collapsed under ${accent.label()}"
                )
            }
        }
    }

    /**
     * Body text keeps its contrast against the surface it sits on.
     *
     * Checked against the pairs the app actually uses -- message text on the
     * chat canvas, headings on a card -- not a synthetic pair.
     */
    @Test
    fun darkBodyTextKeepsReadableContrast() {
        for (accent in accents) {
            val bg = ThemeTint.apply(Ink800, accent, ThemeTint.SURFACE)
            val body = ThemeTint.apply(DarkFgDefault, accent, ThemeTint.TEXT)
            assertTrue(
                contrast(body, bg) >= 4.5,
                "body text dropped to ${"%.2f".format(contrast(body, bg))}:1 under ${accent.label()}"
            )

            val card = ThemeTint.apply(Ink700, accent, ThemeTint.SURFACE)
            val heading = ThemeTint.apply(DarkFgStrong, accent, ThemeTint.TEXT)
            assertTrue(
                contrast(heading, card) >= 4.5,
                "heading dropped to ${"%.2f".format(contrast(heading, card))}:1 under ${accent.label()}"
            )
        }
    }

    /** Light mode's equivalent -- dark text on a light surface. */
    @Test
    fun lightBodyTextKeepsReadableContrast() {
        for (accent in accents) {
            val bg = ThemeTint.apply(Paper0, accent, ThemeTint.SURFACE)
            val body = ThemeTint.apply(LightFgDefault, accent, ThemeTint.TEXT)
            assertTrue(
                contrast(body, bg) >= 4.5,
                "light body text dropped to ${"%.2f".format(contrast(body, bg))}:1 under ${accent.label()}"
            )
        }
    }

    /**
     * The tint is actually visible.
     *
     * The opposite failure: a tint so weak it changes nothing would pass every
     * other test here while leaving the theme picker looking decorative again.
     */
    @Test
    fun nearWhiteTextPicksUpVisibleColour() {
        for (accent in accents) {
            val tinted = ThemeTint.apply(Color(0xFFF2F3F5), accent, ThemeTint.TEXT)
            assertTrue(
                tinted != Color(0xFFF2F3F5),
                "${accent.label()} produced no visible tint on near-white text"
            )
        }
    }

    /**
     * A grey accent must not move anything.
     *
     * Without this a desaturated seed would quietly desaturate the whole app,
     * since the chroma floor would otherwise let a near-grey through.
     */
    @Test
    fun greyAccentIsIgnored() {
        val grey = Color(0xFF808080)
        for (base in bases) {
            assertEquals(base, ThemeTint.apply(base, grey, ThemeTint.TEXT), "${base.label()} moved under a grey accent")
        }
    }

    /**
     * Alpha survives.
     *
     * The token set is full of translucent overlays (hover, pressed, dividers).
     * `oklabToColor` returns opaque, so dropping alpha here would turn every
     * overlay into a solid panel -- a change no contrast assertion would catch.
     */
    @Test
    fun alphaIsPreserved() {
        val accent = Color(0xFF5865F2)
        for (a in listOf(0x0A, 0x14, 0x1F, 0x33, 0x59, 0xB3, 0xE6, 0xFF)) {
            val base = Color(0xFF1A1B1E).copy(alpha = a / 255f)
            val tinted = ThemeTint.apply(base, accent, ThemeTint.SURFACE)
            assertTrue(
                abs(tinted.alpha - base.alpha) < 0.005f,
                "alpha ${base.alpha} became ${tinted.alpha}"
            )
        }
    }

    /** Output is always a real sRGB colour, never a clamped or NaN artefact. */
    @Test
    fun resultIsAlwaysAValidColour() {
        for (accent in accents) {
            for (base in bases) {
                for (strength in listOf(0f, 0.3f, 0.55f, 1f)) {
                    val c = ThemeTint.apply(base, accent, strength)
                    for (v in listOf(c.red, c.green, c.blue, c.alpha)) {
                        assertTrue(v.isFinite() && v in 0f..1f, "component out of range in ${c.label()}")
                    }
                }
            }
        }
    }

    /** Zero strength is the identity, which is what makes the tint composable. */
    @Test
    fun zeroStrengthIsIdentity() {
        val accent = Color(0xFF5865F2)
        for (base in bases) {
            val out = ThemeTint.apply(base, accent, 0f)
            assertTrue(abs(luminanceOf(out) - luminanceOf(base)) < 0.005f, "${base.label()} moved at zero strength")
        }
    }

    /**
     * There is no lightness at which the tint silently switches off.
     *
     * Regression test for a real bug: the in-gamut check used a 0.995 tolerance
     * which `oklabToColor`'s per-channel clamping failed everywhere at certain
     * lightnesses. The bisection then walked its lower bound to zero and reported
     * "no chroma fits", so muted text came back perfectly neutral while every
     * other token tinted. The symptom was one grey in the type ramp stubbornly
     * refusing to pick up the theme colour.
     *
     * Sweeping the whole ramp is the only way to catch it -- asserting on a
     * handful of named tokens happens to miss the band.
     */
    @Test
    fun tintDoesNotSwitchOffAtAnyLightness() {
        for (accent in accents) {
            val untouched = (16..239).filter { v ->
                val base = Color(v, v, v)
                ThemeTint.apply(base, accent, ThemeTint.TEXT) == base
            }
            assertTrue(
                untouched.isEmpty(),
                "${accent.label()} left greys untinted at 8-bit levels $untouched"
            )
        }
    }

    /** The type ramp specifically: every text step must pick up the accent. */
    @Test
    fun everyTextRampStepIsTinted() {
        val ramp = listOf(
            Color(0xFFF2F3F5), Color(0xFFDBDEE1), Color(0xFF949BA4), Color(0xFF6D6F78),
            Color(0xFF13161C), Color(0xFF3B4351), Color(0xFF697180), Color(0xFF98A0AE)
        )
        for (accent in accents) {
            for (base in ramp) {
                assertTrue(
                    ThemeTint.apply(base, accent, ThemeTint.TEXT) != base,
                    "${base.label()} stayed neutral under ${accent.label()}"
                )
            }
        }
    }

    /**
     * The light end of the accent is light, and the dark end is dark.
     *
     * This is `CosmicTokens.OnDark` and `OnNeutral`'s light-mode value in a
     * nutshell. `OnDark` is used on surfaces that are dark in BOTH themes --
     * the call screen roots at a hardcoded `Color.Black` -- so if this ever
     * returned a dark colour, every glyph on the call stage would vanish into
     * its own background in the light theme.
     */
    @Test
    fun shadeEndpointsSitWhereTheyAreNamed() {
        for (accent in accents) {
            val light = ThemeTint.shade(accent, 0.96f)
            val dark = ThemeTint.shade(accent, 0.30f)
            assertTrue(light.luminance() > 0.6f, "light end of ${accent.label()} is ${light.label()}")
            assertTrue(dark.luminance() < 0.2f, "dark end of ${accent.label()} is ${dark.label()}")
        }
    }

    /**
     * Pure white cannot meaningfully carry the tint, which is why glyphs use
     * [ThemeTint.shade].
     *
     * Guards the reasoning behind `OnDark`: `apply` leaves white essentially
     * untouched because white has almost no chroma headroom in sRGB, so anything
     * needing a tinted white has to start from the accent. The bound is
     * deliberately loose -- white does pick up a sliver, roughly a third of a
     * 8-bit step on one channel, which is imperceptible and not worth asserting
     * away. If this ever fails by a wide margin, `apply` has gained real headroom
     * and `OnDark`'s use of `shade` can be simplified.
     */
    @Test
    fun pureWhiteBarelyAbsorbsTheTint() {
        for (accent in accents) {
            val out = ThemeTint.apply(Color(0xFFFFFFFF), accent, ThemeTint.TEXT)
            for (v in listOf(out.red, out.green, out.blue)) {
                assertTrue(v > 0.985f, "white absorbed a visible tint under ${accent.label()}: ${out.label()}")
            }
        }
    }

    /** A glyph must stay separable from the surface it sits on. */
    @Test
    fun shadeGlyphsStayVisibleAgainstTheirSurfaces() {
        for (accent in accents) {
            val glyph = ThemeTint.shade(accent, 0.96f)
            assertTrue(
                contrast(glyph, Color.Black) >= 4.5,
                "dark-mode glyph unreadable on the call stage under ${accent.label()}"
            )
            val lightGlyph = ThemeTint.shade(accent, 0.30f)
            assertTrue(
                contrast(lightGlyph, Paper0) >= 4.5,
                "light-mode glyph unreadable on a light surface under ${accent.label()}"
            )
        }
    }

    private fun chromaOf(c: Color): Float {
        val lab = colorToOklab(c)
        return kotlin.math.hypot(lab[1], lab[2])
    }

    /**
 * The DEFAULT theme's text must be meaningfully more colourful than the hard-coded
 * grey it replaced.
 *
 * Regression test for the bug that made all this work look broken. The default
 * theme is served by `generateMaterialYouColorScheme`, whose `onSurface` /
 * `onBackground` / `onSurfaceVariant` were hard-coded warm greys and whose surface
 * palette ran at chroma 0.018 -- indistinguishable from neutral. So the theme
 * picker changed a hue nothing was drawn with, and the app read as flat grey.
 *
 * Each threshold is expressed as a multiple of the chroma of the exact grey that
 * used to sit there, rather than as an arbitrary number. The tints are now
 * deliberately muted, so an absolute cutoff would either be so low it also
 * passes the old greys or so high it demands the garish version this replaced.
 * A ratio states the actual intent: same muted character, clearly more colour.
 */
    @Test
    fun defaultThemeTextIsMoreColouredThanTheGreysItReplaced() {
        // The hard-coded constants, verbatim from the pre-tint scheme.
        val oldDarkText = Color(0xFFE6E3E0)
        val oldDarkSecondary = Color(0xFFA5A09C)
        val oldLightText = Color(0xFF1B1A19)
        val oldLightSecondary = Color(0xFF524C49)

        for (seed in accents) {
            val dark = generateMaterialYouColorScheme(seed, true)
            assertTrue(
                chromaOf(dark.onSurface) > chromaOf(oldDarkText) * 1.5f,
                "dark onSurface ${dark.onSurface.label()} is not meaningfully tinted for ${seed.label()}"
            )
            assertTrue(
                chromaOf(dark.onSurfaceVariant) > chromaOf(oldDarkSecondary) * 2f,
                "dark onSurfaceVariant ${dark.onSurfaceVariant.label()} is not tinted for ${seed.label()}"
            )

            val light = generateMaterialYouColorScheme(seed, false)
            assertTrue(
                chromaOf(light.onSurface) > chromaOf(oldLightText) * 4f,
                "light onSurface ${light.onSurface.label()} is not tinted for ${seed.label()}"
            )
            assertTrue(
                chromaOf(light.onSurfaceVariant) > chromaOf(oldLightSecondary) * 2.5f,
                "light onSurfaceVariant ${light.onSurfaceVariant.label()} is not tinted for ${seed.label()}"
            )
        }
    }

    /**
 * Surfaces must stay muted.
     *
     * The counterweight to the test above, and the one guarding the mistake that
     * made the theme garish: pushing surface chroma up until the canvas itself
     * was visibly coloured. Surfaces carry the accent only faintly; the theme
     * colour belongs to the type and the glyphs. This is a ceiling, deliberately
     * generous -- it exists to catch a return to the 0.048 surface chroma, not
     * to police small differences.
     */
    @Test
    fun surfacesStayMutedRatherThanTakingTheAccent() {
        for (seed in accents) {
            val dark = generateMaterialYouColorScheme(seed, true)
            assertTrue(
                chromaOf(dark.background) < 0.020f,
                "dark canvas is too colourful for ${seed.label()}: ${dark.background.label()}"
            )
            assertTrue(
                chromaOf(dark.surfaceContainerHigh) < 0.030f,
                "dark rail is too colourful for ${seed.label()}: ${dark.surfaceContainerHigh.label()}"
            )
        }
    }

    /**
     * Dark text must not be BRIGHTER than the muted grey it replaced.
     *
     * The tint is meant to add colour, not luminance. An earlier pass picked tone
     * 95, which rendered body text at luminance ~0.85 against the original
     * `#E6E3E0`'s ~0.77 -- and that extra brightness was far more obvious than the
     * added colour, reading as a whole theme that was too bright rather than one
     * grey that had picked up a hue.
     *
     * Colour is invisible below roughly a chroma difference like this; brightness
     * is not. Hence a ceiling as well as the floor in
     * [defaultThemeTextIsMoreColouredThanTheGreysItReplaced]: the tint has to land
     * between "unchanged grey" and "brighter than it was".
     */
    @Test
    fun darkTextStaysAtOrBelowTheOriginalBrightness() {
        val originalText = Color(0xFFE6E3E0)
        val originalSecondary = Color(0xFFA5A09C)
        for (seed in accents) {
            val dark = generateMaterialYouColorScheme(seed, true)
            assertTrue(
                dark.onSurface.luminance() <= originalText.luminance() + 0.015f,
                "onSurface ${dark.onSurface.label()} is brighter than the baseline for ${seed.label()}"
            )
            assertTrue(
                dark.onSurfaceVariant.luminance() <= originalSecondary.luminance() + 0.015f,
                "onSurfaceVariant ${dark.onSurfaceVariant.label()} is brighter than the baseline for ${seed.label()}"
            )
        }
    }

    /**
     * Different seeds must produce visibly different themes.
     *
     * Guards against a "tint" that is technically present but identical for every
     * colour -- the previous state, where everything was the same grey.
     */
    @Test
    fun differentSeedsProduceDifferentOnSurfaces() {
        val hues = accents.map { generateMaterialYouColorScheme(it, true).onSurface }
        assertTrue(
            hues.toSet().size == accents.size,
            "seeds collapsed to the same onSurface: ${hues.map { it.label() }}"
        )
    }
}