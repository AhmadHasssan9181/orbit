package webrtc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Resolving a chosen share source without enumerating the desktop again.
 *
 * The duplicate enumeration was the measured cause of a ~1 second audio dropout
 * every time a share started: the picker walked every monitor and window through
 * COM, and `startScreenShare` walked them all again moments later while also
 * building a whole `PeerConnectionFactory`. On a machine running a 10 ms-deadline
 * audio mixer, that is a second of the box doing nothing useful.
 *
 * So the share path now reads a cached list. That makes the cache the thing that
 * has to be safe, and the dangerous failure is not a crash -- it is resolving to
 * the *wrong* source, which shares somebody's window to the whole call.
 */
class ResolveDesktopSourceTest {

    private val screens = listOf(
        DesktopSourceModel("0", "Screen 0", false),
        DesktopSourceModel("1", "Screen 1", false)
    )
    private val windows = listOf(
        DesktopSourceModel("100", "Discord", true),
        DesktopSourceModel("101", "Chrome", true)
    )
    private val all = screens + windows

    @Test
    fun aScreenResolvesToItsIdAndIsNotAWindow() {
        assertEquals(1L to false, resolveDesktopSource(all, "1"))
    }

    @Test
    fun aWindowResolvesToItsIdAndIsAWindow() {
        assertEquals(101L to true, resolveDesktopSource(all, "101"))
    }

    @Test
    fun anUnknownIdYieldsNullRatherThanTheNearestThing() {
        // The whole point. Guessing here would share the wrong window.
        assertNull(resolveDesktopSource(all, "9999"))
    }

    @Test
    fun aColdCacheYieldsNullSoTheCallerEnumerates() {
        assertNull(resolveDesktopSource(null, "1"))
        assertNull(resolveDesktopSource(emptyList(), "1"))
    }

    @Test
    fun noIdYieldsNull() {
        assertNull(resolveDesktopSource(all, null))
        assertNull(resolveDesktopSource(all, ""))
        assertNull(resolveDesktopSource(all, "   "))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // The id arrives as a string parsed from the picker's model; a stray space
        // should not cost a full enumeration.
        assertEquals(101L to true, resolveDesktopSource(all, " 101 "))
    }

    @Test
    fun anIdThatIsNotANumberYieldsNull() {
        // DesktopSourceModel.id is a String for display. If one ever stopped being
        // numeric, returning null keeps the caller's enumeration fallback honest
        // instead of silently coercing to 0 -- and 0 is a real screen id here.
        assertNull(resolveDesktopSource(listOf(DesktopSourceModel("abc", "Odd", true)), "abc"))
    }

    @Test
    fun theWindowFlagIsNotInferredFromTheIdRange() {
        // webrtc-java numbers monitors and windows from separate spaces, so a
        // high id being a window is a convention, not a rule. If a low-numbered
        // window ever appears, guessing by magnitude would misclassify it.
        val oddWindow = listOf(DesktopSourceModel("7", "Notepad", true))
        assertEquals(7L to true, resolveDesktopSource(oddWindow, "7"))
    }

    @Test
    fun resolutionIsExactAndIdempotent() {
        repeat(3) {
            assertEquals(100L to true, resolveDesktopSource(all, "100"))
        }
    }
}