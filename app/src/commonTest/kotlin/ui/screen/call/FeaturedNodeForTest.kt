package ui.screen.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which tile gets the big panel, when we cannot be told what anything is.
 *
 * Written against run 47, which ended the search for a signalling-based answer.
 * Element's SDP for a shared screen in a 1:1 is a single video m-line with a bare
 * UUID and no screen-share marker anywhere in it, so the share is
 * indistinguishable from a camera by signalling alone.
 *
 * The layout therefore keys off something that IS knowable: in a two-person call,
 * exactly one tile having live video means that tile is what the caller is
 * watching. Everything else goes in the strip.
 */
class FeaturedNodeForTest {

    private data class Tile(
        val id: String,
        val isShare: Boolean = false,
        val video: Boolean = true
    )

    @Test
    fun aShareIsFeaturedWhenThereIsOne() {
        val nodes = listOf(
            Tile("remote_cam", video = true),
            Tile("remote_share", isShare = true)
        )
        assertEquals("remote_share", featuredNodeFor(nodes, { it.isShare }, { it.video })?.id)
    }

    @Test
    fun theOnlyTileWithVideoIsFeaturedInATwoPersonCall() {
        // The reported case: peer sharing, our camera off. The peer's video is
        // what the call is about and it was getting half the window.
        val nodes = listOf(
            Tile("remote", video = true),
            Tile("local", video = false)
        )
        assertEquals("remote", featuredNodeFor(nodes, { it.isShare }, { it.video })?.id)
    }

    @Test
    fun twoPeopleBothShowingVideoUsesTheGrid() {
        // Neither is more interesting than the other, so neither is promoted.
        val nodes = listOf(Tile("remote"), Tile("local"))
        assertNull(featuredNodeFor(nodes, { it.isShare }, { it.video }))
    }

    @Test
    fun twoPeopleWithNoVideoUsesTheGrid() {
        val nodes = listOf(Tile("remote", video = false), Tile("local", video = false))
        assertNull(featuredNodeFor(nodes, { it.isShare }, { it.video }))
    }

    @Test
    fun aGroupCallIsNotPromotedByThisRule() {
        // One video among five is normal in a group, not a reason to give one
        // person the panel. The rule is scoped to two-person calls precisely so it
        // cannot fire here.
        val nodes = listOf(
            Tile("a", video = true),
            Tile("b", video = false),
            Tile("c", video = false),
            Tile("d", video = false),
            Tile("e", video = false)
        )
        assertNull(featuredNodeFor(nodes, { it.isShare }, { it.video }))
    }

    @Test
    fun aShareOutranksTheTwoPersonVideoHeuristic() {
        // Known share first, always. The fallback is only for when nothing is
        // identified, so it must never outrank a real identification.
        val nodes = listOf(
            Tile("local", video = true),
            Tile("remote_share", isShare = true, video = true)
        )
        assertEquals("remote_share", featuredNodeFor(nodes, { it.isShare }, { it.video })?.id)
    }

    @Test
    fun aSingleTileUsesTheGrid() {
        // One person in the call. There is no strip to build and no comparison to
        // make, so the existing single-tile card is right.
        assertNull(featuredNodeFor(listOf(Tile("solo")), { it.isShare }, { it.video }))
    }

    @Test
    fun anEmptyCallUsesTheGrid() {
        assertNull(featuredNodeFor(emptyList<Tile>(), { it.isShare }, { it.video }))
    }

    @Test
    fun aTwoPersonCallWithTwoSharesFeaturesTheFirst() {
        val nodes = listOf(
            Tile("share_a", isShare = true),
            Tile("share_b", isShare = true)
        )
        assertEquals("share_a", featuredNodeFor(nodes, { it.isShare }, { it.video })?.id)
    }
}