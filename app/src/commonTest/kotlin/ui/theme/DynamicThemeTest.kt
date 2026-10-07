package ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DynamicThemeTest {

    @Test
    fun generates7DistinctPresetSeeds() {
        assertEquals(7, MaterialYouSeeds.size)
        val names = MaterialYouSeeds.map { it.name }.toSet()
        assertEquals(7, names.size, "Preset seed names must be unique")
    }

    @Test
    fun darkColorSchemeProducesDeepSurfacesAndHighContrastAccents() {
        val seed = Color(0xFF6750A4) // Lavender seed
        val cs = generateMaterialYouColorScheme(seed, isDark = true)

        // Dark mode background/surfaces must be genuinely dark
        assertTrue(cs.background.luminance() < 0.15f, "Dark background must have low luminance")
        assertTrue(cs.surface.luminance() < 0.15f, "Dark surface must have low luminance")

        // Primary on dark mode must have high contrast and luminance for readability
        assertTrue(cs.primary.luminance() > 0.35f, "Dark mode primary accent must be legible")

        // Tokens mapped from dark scheme
        val tokens = dynamicDarkTokens(cs)
        assertTrue(tokens.Bg.luminance() < 0.15f)
        assertTrue(tokens.CardBg.luminance() < 0.20f)
        assertEquals(cs.primary, tokens.Accent)
    }

    @Test
    fun lightColorSchemeProducesCleanLightSurfacesAndDeepAccents() {
        val seed = Color(0xFF006399) // Ocean seed
        val cs = generateMaterialYouColorScheme(seed, isDark = false)

        // Light mode background/surfaces must be genuinely bright
        assertTrue(cs.background.luminance() > 0.85f, "Light background must have high luminance")
        assertTrue(cs.surface.luminance() > 0.85f, "Light surface must have high luminance")

        // Primary in light mode must be deep enough to contrast against light surfaces
        assertTrue(cs.primary.luminance() < 0.35f, "Light mode primary accent must contrast against white")

        // Tokens mapped from light scheme
        val tokens = dynamicLightTokens(cs)
        assertTrue(tokens.Bg.luminance() > 0.85f)
        assertTrue(tokens.CardBg.luminance() > 0.75f)
        assertEquals(cs.primary, tokens.Accent)
    }
}
