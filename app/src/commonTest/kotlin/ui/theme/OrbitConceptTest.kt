package ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The concept's theme values, pinned against the CSS they came from.
 *
 * These look like trivial assertions on constants, and most of them are. The ones
 * that are not are the last group, which exist because "three surfaces share one
 * colour" is invisible in a diff and obvious on screen: it is the reason the light
 * theme read as flat, and it can only be prevented by something that fails.
 */
class OrbitConceptTest {

    /**
     * ARGB as a Long, so it can be compared against a `#AARRGGBB` literal.
     *
     * Uses `toArgb()` rather than reading `Color.value`. `value` is a packed
     * ULong whose layout belongs to the colour space, so masking it does not give
     * you a value comparable to #FF1E1F22 -- which is exactly what the first run
     * of this file did, failing nine assertions about colours that were all
     * correct.
     */
    private fun argb(c: androidx.compose.ui.graphics.Color): Long =
        c.toArgb().toLong() and 0xFFFFFFFFL

    @Test
    fun darkMatchesTheConceptRootBlock() {
        assertEquals(0xFF1E1F22L, argb(OrbitConceptDark.servers))
        assertEquals(0xFF2B2D31L, argb(OrbitConceptDark.sidebar))
        assertEquals(0xFF313338L, argb(OrbitConceptDark.chat))
        assertEquals(0xFF232428L, argb(OrbitConceptDark.secondary))
        assertEquals(0xFF383A40L, argb(OrbitConceptDark.input))
        assertEquals(0xFF111214L, argb(OrbitConceptDark.floating))
    }

    @Test
    fun brandMatchesTheConcept() {
        assertEquals(0xFF5865F2L, argb(OrbitConceptDark.brand))
        assertEquals(0xFF4752C4L, argb(OrbitConceptDark.brandHover))
        assertEquals(0xFF3C45A5L, argb(OrbitConceptDark.brandActive))
    }

    @Test
    fun midnightIsDarkerThanDarkOnEverySurface() {
        // The point of the theme. If any pair is equal it is not a third theme,
        // it is a dark theme with one background changed.
        val dark = listOf(
            OrbitConceptDark.servers, OrbitConceptDark.sidebar, OrbitConceptDark.chat,
            OrbitConceptDark.secondary, OrbitConceptDark.input, OrbitConceptDark.floating
        )
        val midnight = listOf(
            OrbitConceptMidnight.servers, OrbitConceptMidnight.sidebar,
            OrbitConceptMidnight.chat, OrbitConceptMidnight.secondary,
            OrbitConceptMidnight.input, OrbitConceptMidnight.floating
        )
        for (i in dark.indices) {
            assertTrue(
                argb(midnight[i]) < argb(dark[i]),
                "surface $i is not darker in midnight"
            )
        }
    }

    @Test
    fun midnightRailIsTrueBlack() {
        assertEquals(0xFF000000L, argb(OrbitConceptMidnight.servers))
    }

    @Test
    fun lightSurfacesAreAllDistinguishable() {
        // The regression this whole layer exists to prevent. The old light theme
        // had Input_Bg, Sunken and CardBg all on Paper100, so the composer, the
        // recessed wells and the cards were the same colour and the theme read as
        // one flat sheet.
        val surfaces = listOf(
            "servers" to OrbitConceptLight.servers,
            "sidebar" to OrbitConceptLight.sidebar,
            "chat" to OrbitConceptLight.chat,
            "secondary" to OrbitConceptLight.secondary
        )
        for (i in surfaces.indices) {
            for (j in i + 1 until surfaces.size) {
                assertNotEquals(
                    argb(surfaces[i].second),
                    argb(surfaces[j].second),
                    "${surfaces[i].first} and ${surfaces[j].first} are the same colour"
                )
            }
        }
    }

    @Test
    fun lightThemeSeparatesTheRailFromTheSidebar() {
        // These are adjacent and both light, so they are the pair most likely to
        // collapse into each other by accident.
        assertNotEquals(argb(OrbitConceptLight.servers), argb(OrbitConceptLight.sidebar))
    }

    @Test
    fun lightComposerSharesTheSecondarySurfaceOnPurpose() {
        // Documented exception to the rule above. The composer is meant to read as
        // part of the chat surface rather than as a control sitting on it, so this
        // equality is the design and must not be "fixed" later.
        assertEquals(argb(OrbitConceptLight.input), argb(OrbitConceptLight.secondary))
    }

    @Test
    fun lightTextIsDarkerThanLightSurfaces() {
        // Trivially true for a light theme, and that is the point: the concept
        // reuses Discord's dark-theme greys as light-theme foregrounds so text
        // weight is identical across themes.
        val surfaces = listOf(
            OrbitConceptLight.servers, OrbitConceptLight.sidebar,
            OrbitConceptLight.chat, OrbitConceptLight.secondary
        )
        for (s in surfaces) {
            assertTrue(
                argb(OrbitConceptLight.textNormal) < argb(s),
                "text would be invisible on ${s}"
            )
        }
    }

    @Test
    fun overlayAlphasFollowTheConceptSequence() {
        assertEquals(0.06f, OrbitConceptOverlay.HOVER)
        assertEquals(0.10f, OrbitConceptOverlay.SELECTED)
        assertEquals(0.14f, OrbitConceptOverlay.ACTIVE)
        // Hover < selected < active, always. An overlay scale that inverts is
        // invisible in the token definitions and obvious on screen.
        assertTrue(OrbitConceptOverlay.HOVER < OrbitConceptOverlay.SELECTED)
        assertTrue(OrbitConceptOverlay.SELECTED < OrbitConceptOverlay.ACTIVE)
    }

    @Test
    fun lightOverlaysUseBlackAndAreWeakerThanDarkOnes() {
        // White on a light surface would be invisible, so the light theme has to
        // invert the tint -- and does so at lower alpha, or the effect would be
        // far heavier than in the dark theme.
        assertTrue(OrbitConceptOverlay.hoverLight().alpha < OrbitConceptOverlay.hover().alpha)
        assertEquals(0xFF000000L, argb(OrbitConceptOverlay.hoverLight().copy(alpha = 1f)))
    }

    @Test
    fun layoutMetricsMatchTheConcept() {
        assertEquals(72.dp, OrbitConceptMetrics.railWidth)
        assertEquals(240.dp, OrbitConceptMetrics.sidebarWidth)
        assertEquals(240.dp, OrbitConceptMetrics.memberListWidth)
        assertEquals(48.dp, OrbitConceptMetrics.headerHeight)
        assertEquals(52.dp, OrbitConceptMetrics.userBarHeight)
    }

    @Test
    fun railIsWideEnoughForAnOrbBadge() {
        // Not a concept value -- a consequence of one. The concept's rail is 72px,
        // and Orbit puts a circular server badge with a status dot on it. At 68dp
        // that pair was tight; 72dp is where it has room.
        assertTrue(
            OrbitConceptMetrics.railWidth >= 72.dp,
            "rail too narrow for badge + presence dot"
        )
    }

    @Test
    fun statusColoursMatchTheConcept() {
        assertEquals(0xFF23A55AL, argb(OrbitConceptStatus.online))
        assertEquals(0xFFF0B232L, argb(OrbitConceptStatus.idle))
        assertEquals(0xFFF23F43L, argb(OrbitConceptStatus.dnd))
        assertEquals(0xFF80848EL, argb(OrbitConceptStatus.offline))
        assertEquals(0xFF593695L, argb(OrbitConceptStatus.streaming))
    }

    @Test
    fun statusColoursAgreeWithTheExistingPresencePalette() {
        // The concept and the old palette independently landed on the same values.
        // If they ever diverge, one of the two is being used in one place and the
        // other somewhere else, which is exactly the drift this is checking for.
        assertEquals(argb(PresenceOnline), argb(OrbitConceptStatus.online))
        assertEquals(argb(PresenceIdle), argb(OrbitConceptStatus.idle))
        assertEquals(argb(PresenceDnd), argb(OrbitConceptStatus.dnd))
        assertEquals(argb(PresenceOffline), argb(OrbitConceptStatus.offline))
    }
}