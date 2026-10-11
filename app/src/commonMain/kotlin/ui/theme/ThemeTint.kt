package ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * Tints the neutral palette toward whichever accent the user picked.
 *
 * Every neutral in Orbit -- the Ink and Paper ramps, the greys behind text, the
 * dividers, the translucent white overlays used for hover states -- used to be a
 * fixed constant. That made the theme picker feel decorative: change the accent
 * and only buttons and links moved, while the rest of the app stayed the same
 * grey regardless. This moves the rest of the app with it, so a picked colour
 * is felt in the whole surface rather than only where something is clickable.
 *
 * ## Why this shifts HUE rather than blending toward a fixed colour
 *
 * The obvious implementation is `lerp(neutral, shade, 0.1f)`, and it is wrong
 * for this palette. Blending toward one fixed colour rescales lightness: a very
 * dark surface lifts much more than a light one, so Ink950 and Ink650 stop being
 * neighbouring steps and two surfaces that were deliberately distinct collapse
 * together. `Stub.kt` documents that surface depth is a strict ramp for exactly
 * that reason.
 *
 * So instead this replaces the neutral's Oklab CHROMA with the accent's hue at a
 * fraction of its strength, and leaves Oklab L alone. Consequences worth keeping:
 *
 *  - Lightness is untouched, so the surface ramp keeps its exact ordering and
 *    text-vs-background contrast barely moves. That is what makes this safe to
 *    apply to ~40 tokens at once.
 *  - Alpha is carried through untouched, so the translucent hover overlays stay
 *    translucent.
 *  - The result is a tint, not a colourisation. At [SURFACE] strength greys read
 *    as greys with a hint of the accent, which is the intent; see the strength
 *    constants for where it stops being subtle.
 */
internal object ThemeTint {

    /**
     * Strength for large surfaces: canvas, cards, rail, drawer.
     *
     * Very low on purpose. These cover the most screen area, so the eye reads
     * their colour as "the theme's colour", and at the higher values an earlier
     * pass used, every surface in the app turned visibly coloured and the theme
     * read as garish rather than muted. The accent is carried by type and
     * glyphs; surfaces stay close to neutral.
     */
    const val SURFACE = 0.16f

    /** Strength for body text and icons -- the "white" this exists for. */
    const val TEXT = 0.42f

    /** Strength for dividers and hairlines, between the two above. */
    const val LINE = 0.26f

    /**
     * Cap on the chroma borrowed from the accent.
     *
     * This, not [TEXT], is what the visible end of the effect is limited by. The
     * accent's own chroma runs well past 0.2 for a saturated seed, and handing
     * that to a near-white text colour puts it out of sRGB, where the gamut is
     * narrow, and `linearToSrgb` clamps -- which reads as an arbitrary hue shift
     * rather than a tint. [maxChromaFor] then trims to whatever genuinely fits,
     * so raising this past the point where the clamp starts doing the work buys
     * nothing.
     */
    private const val MAX_CHROMA = 0.10f

    /** Below this the accent is effectively grey and there is no hue to borrow. */
    private const val MIN_CHROMA = 0.005f

    /**
     * The largest chroma that is still representable at [lightness] along [hue].
     *
     * Found by bisection over [oklabToColor]'s exact round-trip rather than a
     * precomputed table: it has to agree with that function's clamping behaviour,
     * and a table baked from the maths separately would drift from it.
     *
     * The round-trip tolerance is deliberately loose (2%). `oklabToColor` clamps
     * per channel, and the clamp loses a little chroma even for requests that
     * were in gamut. An earlier strict tolerance made `inGamut` fail at every
     * probe around certain lightnesses, so the bisection walked `lo` down to
     * zero and the tint silently vanished there -- muted text came out neutral.
     *
     * Cost is irrelevant: this runs while building the token set, which is
     * memoised, not per frame.
     */
    private fun maxChromaFor(lightness: Float, hue: Float): Float {
        fun inGamut(chroma: Float): Boolean {
            if (chroma <= 0f) return true
            val c = oklabToColor(lightness, chroma * cos(hue), chroma * sin(hue))
            // `oklabToColor` clamps rather than reporting, so an out-of-gamut
            // request comes back as a colour whose chroma is lower than asked
            // for. Re-measuring is what detects it.
            val back = colorToOklab(c)
            return hypot(back[1], back[2]) >= chroma * 0.98f
        }
        var lo = 0f
        var hi = 0.4f
        if (inGamut(hi)) return hi
        repeat(18) {
            val mid = (lo + hi) / 2f
            if (inGamut(mid)) lo = mid else hi = mid
        }
        return lo
    }

/**
     * [base] with [accent]'s hue at [strength].
     *
     * Returns [base] unchanged when the accent has no meaningful chroma, so a
     * grey seed cannot drag the whole palette toward nothing.
     *
     * ## [preserveLuminance], and why white needs the other mode
     *
     * With [preserveLuminance] on, chroma is applied first and Oklab lightness is
     * then solved back to the base's WCAG luminance. Chroma is not
     * luminance-neutral, so at the strong settings the user asked for, holding L
     * alone let near-white text gain so much luminance that body text dropped
     * below its contrast floor -- the one failure this whole mechanism exists to
     * prevent. Solving for luminance makes the strength settings safe to choose
     * by appearance alone.
     *
     * That is the wrong tool for a *white glyph*. Pure white sits at L 1.0, where
     * sRGB has no chroma headroom at all, so the solve collapses it back to
     * #FFFFFF and the icon stays pure white -- the tinted `OnNeutral` token would
     * silently be a no-op, leaving icons as the one thing the theme never
     * reached. Glyphs pass [preserveLuminance] `false` instead: they keep their
     * lightness and simply take the hue, landing on the accent's lightest shade.
     * The lightness that costs is irrelevant for a glyph, which only has to stay
     * visible against a surface it is already far from.
     */
    fun apply(
        base: Color,
        accent: Color,
        strength: Float,
        preserveLuminance: Boolean = true
    ): Color {
        val accentLab = colorToOklab(accent)
        val chroma = hypot(accentLab[1], accentLab[2])
        if (chroma < MIN_CHROMA) return base

        val hue = atan2(accentLab[2], accentLab[1])
        val lab = colorToOklab(base)
        val target = (chroma.coerceAtMost(MAX_CHROMA) * strength.coerceIn(0f, 1f))
            .coerceAtMost(maxChromaFor(lab[0], hue))

        if (target <= 0f) return base

        val ca = target * cos(hue)
        val cb = target * sin(hue)

        val lightness = if (!preserveLuminance) {
            lab[0]
        } else {
            // Solve for the Oklab lightness whose rendered luminance matches the
            // base's. Luminance rises monotonically with L, so bisection is safe
            // and needs no gradient.
            val want = linearLuminance(base)
            fun lumAt(l: Float) = linearLuminance(oklabToColor(l, ca, cb))
            var lo = 0f
            var hi = 1f
            when {
                lumAt(lo) > want -> lo
                lumAt(hi) < want -> hi
                else -> {
                    repeat(20) {
                        val mid = (lo + hi) / 2f
                        if (lumAt(mid) < want) lo = mid else hi = mid
                    }
                    (lo + hi) / 2f
                }
            }
        }

                // `oklabToColor` returns opaque, so alpha has to be reapplied or every
        // translucent overlay in the token set would turn into a solid panel.
        return oklabToColor(lightness, ca, cb).copy(alpha = base.alpha)
    }

    /**
     * The accent itself at a chosen lightness -- "the lightest shade of the
     * picked colour".
     *
     * Needed for glyphs because they cannot be made by tinting white. Pure white
     * sits at L 1.0, where sRGB has no chroma headroom whatsoever, so [apply]
     * correctly returns it untouched and the icon stays pure white. Starting
     * from the accent instead is what actually moves it.
     *
     * [lightness] is the caller choosing the end of the ramp: near 1 for a glyph
     * over a dark surface, low for a glyph over a light one. Chroma is trimmed
     * to what that lightness can carry, so the extremes come out as a tint
     * rather than a clamp.
     */
    fun shade(accent: Color, lightness: Float, chromaScale: Float = 1f): Color {
        val lab = colorToOklab(accent)
        val chroma = hypot(lab[1], lab[2])
        if (chroma < MIN_CHROMA) return accent
        val hue = atan2(lab[2], lab[1])
        val target = (chroma.coerceAtMost(MAX_CHROMA) * chromaScale.coerceIn(0f, 1f))
            .coerceAtMost(maxChromaFor(lightness, hue))
        if (target <= 0f) return accent
        return oklabToColor(lightness, target * cos(hue), target * sin(hue))
    }

    /**
     * Produces a tinted text ink derived from the accent's hue and chroma at the specified [lightness].
     *
     * In dark mode, [lightness] near 0.965 gives the lightest luminous tint for headers/primary text,
     * stepping down to ~0.870 for secondary, ~0.750 for muted, and ~0.650 for subtle.
     * In light mode, [lightness] near 0.130 gives the deepest crisp ink for headers/primary text,
     * stepping up to ~0.270 for secondary, ~0.430 for muted, and ~0.550 for subtle.
     */
    fun tintedInk(accent: Color, lightness: Float, isDark: Boolean): Color {
        val lab = colorToOklab(accent)
        val rawChroma = hypot(lab[1], lab[2])
        if (rawChroma < MIN_CHROMA) {
            return oklabToColor(lightness.coerceIn(0f, 1f), 0f, 0f)
        }
        val hue = atan2(lab[2], lab[1])
        return tintedInk(hue, rawChroma, lightness, isDark)
    }

    fun tintedInk(hue: Float, rawChroma: Float, lightness: Float, isDark: Boolean): Color {
        val oL = lightness.coerceIn(0f, 1f)
        val baseChroma = if (isDark) 0.046f else 0.048f
        val strength = (rawChroma / 0.09f).coerceIn(0f, 1f)
        val c = (baseChroma * strength * sin(PI.toFloat() * oL).pow(0.45f)).coerceIn(0f, 0.048f)
        return oklabToColor(oL, c * cos(hue), c * sin(hue))
    }
}