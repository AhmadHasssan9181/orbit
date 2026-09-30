package ui.screen.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cap on the call grid.
 *
 * The old grid was `nodes.chunked(2)` with no limit and every row at `weight(1f)`,
 * so nothing overflowed -- each tile just took an ever-smaller share of the column.
 * A participant at position twenty was still composed, still fed frames, and
 * occupying a twentieth of the height: invisible, and not free. These tests pin the
 * two things that have to be true instead: the visible rows stop growing, and the
 * overflow is counted rather than silently dropped.
 */
class CallGridRowsTest {

    @Test
    fun aSmallCallFillsTheGridExactly() {
        val (rows, hidden) = gridRowsFor((1..6).toList())
        assertEquals(listOf(2, 2, 2), rows.map { it.size })
        assertEquals(0, hidden)
    }

    @Test
    fun anOddLastRowKeepsItsOddWidth() {
        val (rows, hidden) = gridRowsFor((1..5).toList())
        assertEquals(listOf(2, 2, 1), rows.map { it.size })
        assertEquals(0, hidden)
    }

    @Test
    fun theGridStopsGrowingAtTheCap() {
        val (rows, hidden) = gridRowsFor((1..40).toList())
        assertEquals(CALL_GRID_MAX_ROWS, rows.size)
        assertEquals(40 - CALL_GRID_MAX_ROWS * 2, hidden)
    }

    @Test
    fun everyOverflowedNodeIsCountedExactlyOnce() {
        // The count is what the "+N" tile says, and it is derived by subtraction
        // rather than by counting the tail. If the two ever disagreed the tile would
        // claim a number that does not match the room, which is the sort of quiet
        // lie this codebase keeps producing.
        for (total in 0..40) {
            val nodes = (1..total).toList()
            val (rows, hidden) = gridRowsFor(nodes)
            val drawn = rows.flatten()
            assertEquals(total, drawn.size + hidden, "total=$total is fully accounted for")
            // The drawn rows are the PREFIX of the list, not an arbitrary subset:
            // dropping a tile from the middle would mean the person who got dropped
            // silently disappears from the call while still being in it.
            assertEquals(nodes.take(drawn.size), drawn, "total=$total keeps order and content")
        }
    }

    @Test
    fun theCapCoversEightFaces() {
        // Stated as a number because "four rows" is an implementation detail and
        // "eight faces" is the thing the limit is for.
        assertEquals(8, CALL_GRID_MAX_ROWS * 2)
    }

    @Test
    fun aSingleTileIsNeverDropped() {
        val (rows, hidden) = gridRowsFor(listOf("only"))
        assertEquals(1, rows.single().size)
        assertEquals(0, hidden)
    }

    @Test
    fun anEmptyCallIsHandledWithoutArithmeticOnNothing() {
        val (rows, hidden) = gridRowsFor(emptyList<String>())
        assertTrue(rows.isEmpty())
        assertEquals(0, hidden)
    }

    @Test
    fun aDegenerateColumnCountHidesRatherThanDividesByZero() {
        // `chunked(0)` throws IllegalArgumentException. This cannot happen from the
        // composable, which passes the default, but the function is now shared and
        // the failure mode is worth being explicit about rather than discovering
        // inside a call.
        val (rows, hidden) = gridRowsFor((1..5).toList(), columns = 0)
        assertTrue(rows.isEmpty())
        assertEquals(5, hidden)
    }
}