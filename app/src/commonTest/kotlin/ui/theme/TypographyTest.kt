package ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The type scale's weight contract.
 *
 * Orbit's look depends on body text sitting at Medium rather than Regular, so
 * the whole hierarchy reads solid rather than thin. That is a design intent
 * expressed purely in `TextStyle` values, which means it is invisible in a diff
 * and only shows up on screen -- exactly the class of regression that survives
 * review. These assertions are the thing that fails when someone tidies a
 * weight back toward the Material default.
 *
 * `typographyFor` is called with `FontFamily.Default` on purpose: the family is
 * not what is under test here, and building the real one needs a composition.
 */
class TypographyTest {

    private val scale: Typography = typographyFor(FontFamily.Default)

    /** Every slot in the scale, so a new Material slot cannot silently escape the checks. */
    private fun slots(): Map<String, TextStyle> = mapOf(
        "titleLarge" to scale.titleLarge,
        "titleMedium" to scale.titleMedium,
        "titleSmall" to scale.titleSmall,
        "bodyLarge" to scale.bodyLarge,
        "bodyMedium" to scale.bodyMedium,
        "bodySmall" to scale.bodySmall,
        "labelLarge" to scale.labelLarge,
        "labelMedium" to scale.labelMedium,
        "labelSmall" to scale.labelSmall,
    )

    /**
     * The core of the change: nothing renders lighter than Medium.
     *
     * This is the assertion that would have caught a "let's make body copy
     * Regular again, it looks cleaner" edit. Muted and secondary text is
     * expected to get there via `color`, not by thinning the face.
     */
    @Test
    fun noSlotDropsBelowMedium() {
        for ((name, style) in slots()) {
            assertTrue(
                (style.fontWeight?.weight ?: 0) >= FontWeight.W500.weight,
                "$name is ${style.fontWeight}, below the Medium floor"
            )
        }
    }

    /** Body and label slots are all exactly Medium, so the baseline is uniform. */
    @Test
    fun bodyAndLabelSlotsAreMedium() {
        val expected = listOf(
            "bodyLarge" to scale.bodyLarge,
            "bodyMedium" to scale.bodyMedium,
            "bodySmall" to scale.bodySmall,
            "labelMedium" to scale.labelMedium,
            "labelSmall" to scale.labelSmall,
        )
        for ((name, style) in expected) {
            assertEquals(FontWeight.W500, style.fontWeight, "$name should be Medium")
        }
    }

    /** Buttons and subheadings get extra push to separate an action from its surroundings. */
    @Test
    fun subheadingsAndButtonsAreSemiBoldOrBold() {
        assertEquals(FontWeight.W600, scale.titleSmall.fontWeight)
        assertEquals(FontWeight.W600, scale.labelLarge.fontWeight)
        assertEquals(FontWeight.W700, scale.titleMedium.fontWeight)
    }

    /** ExtraBold is rationed to the single most prominent header on a screen. */
    @Test
    fun onlyTitleLargeIsExtraBold() {
        val extraBold = slots().filterValues { it.fontWeight == FontWeight.W800 }.keys
        assertEquals(setOf("titleLarge"), extraBold, "ExtraBold is reserved for titleLarge")
    }

    /**
     * Every slot uses the supplied family.
     *
     * Guards the one way this could regress silently: a slot reintroduced with a
     * hardcoded family, or left at Material's default while the rest of the app
     * renders in Plus Jakarta Sans.
     */
    @Test
    fun everySlotUsesTheBundledFamily() {
        for ((name, style) in slots()) {
            assertEquals(FontFamily.Default, style.fontFamily, "$name is not on the supplied family")
        }
    }

    /**
     * Sizes are untouched by the weight pass.
     *
     * Roughly 250 call sites in the app override `fontSize` directly rather than
     * using these slots, so a size change here would shift text that nothing else
     * accounts for.
     */
    @Test
    fun sizesAreUnchangedFromThePreviousScale() {
        assertEquals(22f, scale.titleLarge.fontSize.value, 0.01f)
        assertEquals(18f, scale.titleMedium.fontSize.value, 0.01f)
        assertEquals(15f, scale.titleSmall.fontSize.value, 0.01f)
        assertEquals(16f, scale.bodyLarge.fontSize.value, 0.01f)
        assertEquals(14f, scale.bodyMedium.fontSize.value, 0.01f)
        assertEquals(13f, scale.bodySmall.fontSize.value, 0.01f)
        assertEquals(13f, scale.labelLarge.fontSize.value, 0.01f)
        assertEquals(12f, scale.labelMedium.fontSize.value, 0.01f)
        assertEquals(11f, scale.labelSmall.fontSize.value, 0.01f)
    }
}