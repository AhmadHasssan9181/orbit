package ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pastel server-tile palette.
 *
 * The pastel fills are chosen against one hard requirement -- the initials on
 * top have to stay legible -- and against one soft requirement -- they must not
 * read as bright. Both are invisible in a diff: swapping a fill for a slightly
 * more saturated neighbour changes nothing structurally, so a regression only
 * shows up as "the rail looks off" with nothing to point at. Hence the contrast
 * floor below.
 */
class PastelServerColorTest {

    /**
     * Hex for assertion messages.
     *
     * `toArgb()` rather than `Color.value`, which is a packed ULong whose layout
     * belongs to the colour space and is not a readable #AARRGGBB.
     */
    private fun Color.label(): String =
        "#" + toArgb().toLong().and(0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

    /** WCAG relative luminance. Mirrors `Color.luminance()` but kept explicit so the intent is readable. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return max(la, lb) / min(la, lb)
    }

    /**
     * Initials must be readable on every fill.
     *
     * This is the whole reason the fills are light and the glyph is dark: the
     * earlier palette drew white text on arbitrary saturated hues, which is
     * unreadable on most of them. A fill added without checking this will look
     * fine in a swatch and fail here.
     */
    @Test
    fun everyFillClearsTextContrastAgainstTheInk() {
        for (c in PastelServerSeeds) {
            val ratio = contrast(c, pastelServerInk)
            assertTrue(
                ratio >= 4.5,
                "fill ${c.label()} only reaches ${"%.2f".format(ratio)}:1 against the ink"
            )
        }
    }

    /**
     * The palette is light, so the glyph is dark rather than white.
     *
     * Guarding the relationship rather than the hex: if the fills are ever
     * darkened, `pastelServerInk` has to move with them or this test fails and
     * points at the coupling.
     */
    @Test
    fun glyphInkIsDarkerThanEveryFill() {
        for (c in PastelServerSeeds) {
            assertTrue(
                luminance(pastelServerInk) < luminance(c),
                "${c.label()} is darker than the ink"
            )
        }
    }

    /**
     * Matte, but not colourless.
     *
     * Two bands, because the failure this guards against went in both directions.
     * The original hash-to-ARGB scheme was fully saturated confetti, so there is
     * a ceiling. The first curated pass over-corrected into near-greys and the
     * tiles read as unfinished, so there is also a floor: below this the fills
     * have lost the colour the rail is supposed to have.
     *
     * The floor is what caught the first pass being too washed out.
     */
    @Test
    fun everyFillStaysLowChroma() {
        for (c in PastelServerSeeds) {
            val maxC = max(c.red, max(c.green, c.blue))
            val minC = min(c.red, min(c.green, c.blue))
            val chroma = maxC - minC
            assertTrue(chroma <= 0.30f, "${c.label()} has chroma $chroma, too vivid for a matte tile")
            assertTrue(chroma >= 0.08f, "${c.label()} has chroma $chroma, too washed out to read as a colour")
        }
    }

    /** Every fill is light enough to read as a pastel rather than a mid-tone. */
    @Test
    fun everyFillIsLight() {
        for (c in PastelServerSeeds) {
            assertTrue(
                luminance(c) > 0.55,
                "${c.label()} is too dark to read as a pastel"
            )
        }
    }

    /** A server keeps its colour: the picker is a pure function of the id. */
    @Test
    fun colorIsStableForAGivenSeed() {
        for (id in listOf("!abc:example.org", "!xyz:example.org", "!room:server.org")) {
            assertEquals(pastelServerColor(id), pastelServerColor(id), "$id changed colour between calls")
        }
    }

    /**
     * Negative hash codes must not index out of bounds.
     *
     * `hashCode()` is negative for roughly half of all strings. Masking with
     * `Int.MAX_VALUE` is what makes the modulo safe, and this is the assertion
     * that would catch its removal.
     */
    @Test
    fun everyPossibleHashMapsInsideThePalette() {
        val ids = (0 until 5000).map { "!room$it:example.org" }
        for (id in ids) {
            val c = pastelServerColor(id)
            assertTrue(
                c in PastelServerSeeds,
                "$id resolved to $c, which is not in the curated palette"
            )
        }
    }

    /** Enough distinct fills that a rail does not repeat every few tiles. */
    @Test
    fun paletteIsLargeEnoughToAvoidObviousRepetition() {
        assertTrue(PastelServerSeeds.size >= 8, "only ${PastelServerSeeds.size} fills defined")
        val seen = (0 until 500).map { pastelServerColor("!room$it:example.org") }.toSet()
        assertTrue(seen.size >= 8, "only ${seen.size} distinct fills reachable")
    }

    /** Distinct entries -- a duplicated swatch shrinks the palette invisibly. */
    @Test
    fun paletteHasNoDuplicates() {
        assertEquals(PastelServerSeeds.size, PastelServerSeeds.toSet().size, "duplicate fill in the palette")
    }
}