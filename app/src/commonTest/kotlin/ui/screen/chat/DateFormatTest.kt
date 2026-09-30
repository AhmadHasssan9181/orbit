package ui.screen.chat

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The date-format setting and the renderer that honours it.
 *
 * This coupling is the kind that drifts quietly. `formatDate` does not reject a
 * pattern it does not recognise -- it falls back to DD/MM/YYYY -- so a picker
 * offering a pattern with no branch behind it produces a control that looks live,
 * takes a tap, and does nothing. Nothing crashes, nothing logs, and the user
 * concludes the app is broken.
 *
 * These tests close that by asserting the two sides agree, rather than by
 * asserting that a hand-typed list of strings matches a hand-typed list of
 * branches -- which is the arrangement that let them drift in the first place.
 */
class DateFormatTest {

    /** A date where day and month differ, so the two orders cannot look alike. */
    private val seventhOfMarch = LocalDate(2026, 3, 7)

    @Test
    fun everyOfferedFormatRendersSomethingDifferentFromEveryOther() {
        val rendered = DATE_FORMATS.map { formatDate(seventhOfMarch, it) }
        assertEquals(
            DATE_FORMATS.size,
            rendered.toSet().size,
            "two offered formats render identically: $rendered"
        )
    }

    @Test
    fun noOfferedFormatIsSilentlyTheFallback() {
        val fallback = formatDate(seventhOfMarch, "DD/MM/YYYY")
        // Every option other than the fallback must actually change something.
        // The failure this guards against is quiet: formatDate has no `else -> error`
        // branch, so a pattern with no arm behind it is not a crash, it is a
        // control that lies.
        DATE_FORMATS.drop(1).forEach { pattern ->
            assertTrue(
                formatDate(seventhOfMarch, pattern) != fallback,
                "'$pattern' renders as the DD/MM/YYYY fallback, so selecting it does nothing"
            )
        }
    }

    @Test
    fun theMonthAbbreviationIsTitleCaseNotTheEnumConstant() {
        // LocalDate.month.name is MARCH / SEPTEMBER, so the obvious implementation
        // of "D MMM YYYY" prints "07 MAR 2026". It did, for as long as the branch
        // existed -- but the setting that selects it had no writer, so the only
        // reachable format was the fallback and the branch was unreachable.
        assertEquals("07 Mar 2026", formatDate(seventhOfMarch, "D MMM YYYY"))
        assertTrue(
            formatDate(LocalDate(2026, 9, 4), "D MMM YYYY").contains("Sep"),
            "September must not abbreviate to SEP: ${formatDate(LocalDate(2026, 9, 4), "D MMM YYYY")}"
        )
    }

    @Test
    fun anUnrecognisedPatternFallsBackRatherThanPrintingItRaw() {
        // The fallback is the point: a stale stored value, or a future format
        // someone removed, must not reach the user as literal "YYYY/MM/DD".
        assertEquals(formatDate(seventhOfMarch, "DD/MM/YYYY"), formatDate(seventhOfMarch, "NONSENSE"))
    }

    @Test
    fun patternMatchingIsCaseInsensitive() {
        // The setting stores whatever the picker hands it, and the render
        // uppercases, so a hand-edited preferences file cannot break the display.
        DATE_FORMATS.forEach { pattern ->
            assertEquals(
                formatDate(seventhOfMarch, pattern),
                formatDate(seventhOfMarch, pattern.lowercase()),
                "'$pattern' is case sensitive"
            )
        }
    }

    @Test
    fun everyOfferedFormatIncludesTheYear() {
        DATE_FORMATS.forEach { pattern ->
            assertTrue(
                formatDate(seventhOfMarch, pattern).contains("2026"),
                "'$pattern' drops the year: ${formatDate(seventhOfMarch, pattern)}"
            )
        }
    }

    @Test
    fun singleDigitDaysAndMonthsArePadded() {
        // "07/03/2026", not "7/3/2026": the fixed width is what makes a column of
        // dates line up, and it costs nothing once every format agrees on it.
        assertEquals("07/03/2026", formatDate(seventhOfMarch, "DD/MM/YYYY"))
        assertEquals("03/07/2026", formatDate(seventhOfMarch, "MM/DD/YYYY"))
        assertEquals("2026-03-07", formatDate(seventhOfMarch, "YYYY-MM-DD"))
        assertEquals("07 Mar 2026", formatDate(seventhOfMarch, "D MMM YYYY"))
    }

    @Test
    fun theTwoSlashedFormatsActuallyDisagreeOnThisDate() {
        // The most likely way to "fix" the picker into a lie by accident: someone
        // maps MM/DD/YYYY onto the same branch as DD/MM/YYYY. On the 7th of the
        // 3rd the two orders must differ.
        assertTrue(formatDate(seventhOfMarch, "MM/DD/YYYY") != formatDate(seventhOfMarch, "DD/MM/YYYY"))
    }
}