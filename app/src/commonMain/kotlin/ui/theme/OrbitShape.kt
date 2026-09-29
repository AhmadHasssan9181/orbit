package ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Radii, spacing and edges.
 *
 * The old UI mixed 4/5/6/8/10/12/14/15/16/22/24/28/999dp corners in the same
 * screen, which is the single clearest "assembled rather than designed" tell.
 * There are now five radii and that is all.
 *
 * The Dp constants are `private` on purpose. They exist only to build the
 * `*Shape` values below, and they were public, which meant a call site could
 * reach for `OrbitRadius.md` and hand a raw corner radius to something that
 * wanted a shape — or wrap it in its own `RoundedCornerShape`, bypassing the
 * scale. Every consumer uses the `*Shape` members now.
 */
object OrbitRadius {
    private val xs: Dp = 4.dp      // Chips, tags, tiny inline badges.
    private val sm: Dp = 6.dp      // Inputs, menu rows, compact buttons.
    private val md: Dp = 10.dp     // The workhorse: cards, bubbles, list tiles.
    private val lg: Dp = 14.dp     // Panels, sheets, dialogs.
    private val xl: Dp = 20.dp     // Large containers: the quick switcher.
    private val full: Dp = 999.dp  // Pills and fully rounded avatars.

    val xsShape = RoundedCornerShape(xs)
    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val pillShape = RoundedCornerShape(full)
}

/** 4pt grid. */
object OrbitSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val huge: Dp = 48.dp
}

/**
 * Desktop window structure. The chat column is the product; these widths are
 * chosen so the reading measure of a message never exceeds a comfortable
 * ~72 characters.
 *
 * `drawerWidthWide`, `titleBarHeight` and `rowHeight` used to live here and had
 * no readers -- the drawer has one width, the title bar carries its own height,
 * and rows size themselves through `OrbitListRow`. `headerHeight` stays because
 * `ServerHeader` uses it.
 */
object OrbitMetrics {
    val railWidth: Dp = 68.dp
    val drawerWidth: Dp = 248.dp
    val sidePanelWidth: Dp = 264.dp
    val sidePanelWidthWide: Dp = 288.dp
    val headerHeight: Dp = 52.dp
    val maxMessageMeasure: Dp = 860.dp
    /**
     * Width of the chat timeline when it shares the window with a live call.
     *
     * Wider than [sidePanelWidth] on purpose. That number was sized for a list of
     * names, where a truncated display name costs nothing. A conversation is not a
     * name: at 264dp a message wraps every third or fourth word, and the composer
     * loses room for the attachment and emoji buttons. 380dp keeps the measure
     * readable and the composer intact.
     */
    val callChatPanelWidth: Dp = 380.dp
}
