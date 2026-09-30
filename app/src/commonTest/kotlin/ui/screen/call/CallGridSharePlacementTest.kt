package ui.screen.call

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rules behind the shared-screen layout, pinned by test rather than by eye.
 *
 * The defect these cover was a layout that changed shape depending on state: a
 * share went on top in Grid mode and on the LEFT in Focus mode, and pinning any
 * tile forced Focus's branch even while Grid was still selected. So the same
 * call, doing the same thing, looked like two different products depending on a
 * toggle and on what somebody had pinned.
 */
class CallGridSharePlacementTest {

    @Test
    fun noPinKeepsStripOrder() {
        assertEquals(
            listOf("a", "b", "c"),
            stripOrderFor(listOf("a", "b", "c"), pinnedNodeId = null) { it }
        )
    }

    @Test
    fun pinMovesItsTileToTheFrontOfTheStrip() {
        assertEquals(
            listOf("c", "a", "b"),
            stripOrderFor(listOf("a", "b", "c"), pinnedNodeId = "c") { it }
        )
    }

    @Test
    fun pinIsHonouredWithoutLosingAnyone() {
        // The failure this guards: making the pinned tile prominent by dropping
        // the others. Four people in the call means four tiles in the strip.
        val faces = listOf("a", "b", "c", "d")
        val ordered = stripOrderFor(faces, pinnedNodeId = "b") { it }
        assertEquals(4, ordered.size)
        assertEquals("b", ordered.first())
        assertEquals(faces.sorted(), ordered.sorted())
    }

    @Test
    fun pinOfSomethingNotInTheStripChangesNothing() {
        // The pinned id is usually the SHARE itself, which lives in the featured
        // panel and is not in the strip at all. That must not empty the strip or
        // reorder it.
        val faces = listOf("a", "b", "c")
        assertEquals(
            faces,
            stripOrderFor(faces, pinnedNodeId = "remote_share_TRACK123") { it }
        )
    }

    @Test
    fun pinOfTheFirstTileIsANoOp() {
        val faces = listOf("a", "b", "c")
        assertEquals(
            faces,
            stripOrderFor(faces, pinnedNodeId = "a") { it }
        )
    }

    @Test
    fun stripOrderWorksOnNodesByIdNotByIdentity() {
        // The real call site indexes by node id, so the comparison has to be
        // against the id and not against list position or object equality.
        data class Node(val id: String, val name: String)
        val faces = listOf(Node("remote_cam_1", "A"), Node("remote_cam_2", "B"))
        assertEquals(
            listOf("remote_cam_2", "remote_cam_1"),
            stripOrderFor(faces, pinnedNodeId = "remote_cam_2") { it.id }.map { it.id }
        )
    }

    @Test
    fun emptyStripIsHandled() {
        assertEquals(
            emptyList<String>(),
            stripOrderFor(emptyList<String>(), pinnedNodeId = "anything") { it }
        )
    }
}