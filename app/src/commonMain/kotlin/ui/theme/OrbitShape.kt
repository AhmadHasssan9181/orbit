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
 */
object OrbitRadius {
    /** Chips, tags, tiny inline badges. */
    val xs: Dp = 4.dp
    /** Inputs, menu rows, compact buttons. */
    val sm: Dp = 6.dp
    /** The workhorse: cards, message bubbles, list tiles. */
    val md: Dp = 10.dp
    /** Panels, sheets, dialogs. */
    val lg: Dp = 14.dp
    /** Large containers: the quick switcher, modals. */
    val xl: Dp = 20.dp
    /** Pills and fully rounded avatars. */
    val full: Dp = 999.dp

    val xsShape = RoundedCornerShape(xs)
    val smShape = RoundedCornerShape(sm)
    val mdShape = RoundedCornerShape(md)
    val lgShape = RoundedCornerShape(lg)
    val xlShape = RoundedCornerShape(xl)
    val pillShape = RoundedCornerShape(full)
}

/** 4pt grid. */
object OrbitSpace {
    val none: Dp = 0.dp
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
 */
object OrbitMetrics {
    val railWidth: Dp = 68.dp
    val railWidthLite: Dp = 60.dp
    val drawerWidth: Dp = 248.dp
    val drawerWidthWide: Dp = 272.dp
    val sidePanelWidth: Dp = 264.dp
    val sidePanelWidthWide: Dp = 288.dp
    val titleBarHeight: Dp = 36.dp
    val headerHeight: Dp = 52.dp
    val rowHeight: Dp = 34.dp
    val maxMessageMeasure: Dp = 860.dp
}
