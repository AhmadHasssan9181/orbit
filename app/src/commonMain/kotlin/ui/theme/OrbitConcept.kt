package ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The three themes and layout metrics from the Orbit concept.
 *
 * This sits beside [OrbitPalette] rather than inside it on purpose.
 * [OrbitPalette]'s dark neutrals were already Discord's real surface greys, and
 * the concept's `:root` block turned out to be *the same values* -- Ink950 is
 * #111214 and `--bg-floating` is #111214, and so on down the ramp. So there was
 * nothing to reconcile there, only things the concept added on top:
 *
 *  - a third theme, `midnight`, which is darker than Discord dark and has no
 *    equivalent in the old palette
 *  - a light theme with real separation between its surfaces
 *  - layout metrics, which were 68/248/264/52 against the concept's
 *    72/240/240/48
 *
 * ## On the light theme
 *
 * The old light neutrals ran Paper50 -> Paper400 as a single ramp, and three
 * separate surfaces were all `Paper100`. They were therefore indistinguishable,
 * which is the reason the light theme read as flat.
 *
 * The concept's light theme separates them properly:
 *
 *     --bg-servers   #e3e5e8    the rail
 *     --bg-sidebar   #f2f3f5    the channel list
 *     --bg-chat      #ffffff    the canvas
 *     --bg-secondary #ebedef    recessed panels, and the chat input
 *
 * Note that `--bg-input` is #ebedef rather than something distinct again: the
 * composer is meant to read as part of the chat surface, not as a control sitting
 * on it. That is a deliberate decision in the concept and it is preserved here.
 */
object OrbitConceptDark {
    val servers = Color(0xFF1E1F22)      // --bg-servers
    val sidebar = Color(0xFF2B2D31)      // --bg-sidebar
    val chat = Color(0xFF313338)         // --bg-chat
    val secondary = Color(0xFF232428)    // --bg-secondary
    val input = Color(0xFF383A40)        // --bg-input
    val floating = Color(0xFF111214)     // --bg-floating
    val callCard = Color(0xFF18191C)     // --bg-call-card
    val callCardActive = Color(0xFF2B2D31)

    val textPrimary = Color(0xFFF2F3F5)  // --header-primary
    val textSecondary = Color(0xFFB5BAC1)
    val textNormal = Color(0xFFDBDEE1)   // --text-normal
    val textMuted = Color(0xFF949BA4)    // --text-muted
    val textLink = Color(0xFF00A8FC)     // --text-link

    val brand = Color(0xFF5865F2)        // --brand-blurple
    val brandHover = Color(0xFF4752C4)
    val brandActive = Color(0xFF3C45A5)
}

/**
 * Darker than Discord dark. The rail goes to true black and the chat surface only
 * just leaves it, which is what gives the rail its hard edge.
 */
object OrbitConceptMidnight {
    val servers = Color(0xFF000000)      // --bg-servers
    val sidebar = Color(0xFF090A0B)      // --bg-sidebar
    val chat = Color(0xFF101113)         // --bg-chat
    val secondary = Color(0xFF000000)    // --bg-secondary
    val input = Color(0xFF1A1B1E)        // --bg-input
    val floating = Color(0xFF050505)     // --bg-floating
    val callCard = Color(0xFF080809)     // --bg-call-card
}

/**
 * Light theme.
 *
 * The foregrounds are Discord's own dark-theme greys reused as light-theme text
 * (#313338 normal, #5c5e66 muted). That looks odd written down -- a light theme
 * with dark-theme foregrounds -- but it is what keeps the text weight identical
 * across themes, which is the point of a theme rather than a different design.
 */
object OrbitConceptLight {
    val servers = Color(0xFFE3E5E8)      // --bg-servers
    val sidebar = Color(0xFFF2F3F5)      // --bg-sidebar
    val chat = Color(0xFFFFFFFF)         // --bg-chat
    val secondary = Color(0xFFEBEDEF)    // --bg-secondary
    val input = Color(0xFFEBEDEF)        // --bg-input, deliberately == secondary
    val floating = Color(0xFFFFFFFF)     // --bg-floating

    val textPrimary = Color(0xFF060607)  // --header-primary
    val textSecondary = Color(0xFF4E5058)
    val textNormal = Color(0xFF313338)   // --text-normal
    val textMuted = Color(0xFF5C5E66)    // --text-muted

    val brand = Color(0xFF5865F2)
}

/**
 * Interaction overlays, as alphas over whatever surface is beneath.
 *
 * The concept expresses hover/selected/active as white at 6%, 10% and 14%, so one
 * value works on every surface in every theme. The old palette instead baked solid
 * colours per surface -- Ink750 for message hover, Ink700 for a selected channel --
 * which means each new surface needs its own hover colour invented for it, and any
 * that was forgotten simply had no hover state. That is the same failure that left
 * three light-theme surfaces sharing one value.
 */
object OrbitConceptOverlay {
    const val HOVER = 0.06f
    const val SELECTED = 0.10f
    const val ACTIVE = 0.14f

    /** Brand-tinted ghost, for "this row relates to the accent". */
    const val BRAND_SUBTLE = 0.15f

    fun hover(): Color = Color.White.copy(alpha = HOVER)
    fun selected(): Color = Color.White.copy(alpha = SELECTED)
    fun active(): Color = Color.White.copy(alpha = ACTIVE)

    /** The light-theme equivalents: black at a much lower alpha. */
    fun hoverLight(): Color = Color.Black.copy(alpha = 0.04f)
    fun selectedLight(): Color = Color.Black.copy(alpha = 0.08f)
}

/** Hairlines, also as alphas so they work on any surface. */
object OrbitConceptBorder {
    const val SUBTLE = 0.08f
    const val STRONG = 0.15f

    fun subtle(): Color = Color.White.copy(alpha = SUBTLE)
    fun strong(): Color = Color.White.copy(alpha = STRONG)
}

/**
 * Layout metrics from the concept.
 *
 * Held separately from [OrbitMetrics] rather than overwriting it, because
 * `OrbitMetrics.callChatPanelWidth` (380dp) is a decision that was made about
 * this app's call layout specifically and the concept does not speak to it. Merging
 * the two sets would quietly undo that.
 */
object OrbitConceptMetrics {
    val railWidth: Dp = 72.dp          // --server-rail-width
    val sidebarWidth: Dp = 240.dp      // --sidebar-width
    val memberListWidth: Dp = 240.dp   // --member-list-width
    val headerHeight: Dp = 48.dp       // --header-height
    val userBarHeight: Dp = 52.dp      // --user-bar-height
}

/** Presence dot colours, matching the concept's `--status-*` set. */
object OrbitConceptStatus {
    val online = Color(0xFF23A55A)     // --status-online
    val idle = Color(0xFFF0B232)       // --status-idle
    val dnd = Color(0xFFF23F43)         // --status-dnd
    val offline = Color(0xFF80848E)    // --status-offline
    val streaming = Color(0xFF593695)  // --status-streaming
}
/**
 * Resolved theme-aware surfaces and foregrounds from the Orbit concept.
 * Avoids hard-coding OrbitConceptDark in modals, dialogs, and overlays.
 */
object OrbitConceptTheme {
    val isLight: Boolean @Composable get() = rememberCosmicTokens().Bg.luminance() > 0.5f

    val chat: Color @Composable get() = if (isLight) OrbitConceptLight.chat else OrbitConceptDark.chat

    val secondary: Color @Composable get() = if (isLight) OrbitConceptLight.secondary else OrbitConceptDark.secondary

    val floating: Color @Composable get() = if (isLight) OrbitConceptLight.floating else OrbitConceptDark.floating

    val textPrimary: Color @Composable get() = if (isLight) OrbitConceptLight.textPrimary else OrbitConceptDark.textPrimary

    val textNormal: Color @Composable get() = if (isLight) OrbitConceptLight.textNormal else OrbitConceptDark.textNormal

    val textMuted: Color @Composable get() = if (isLight) OrbitConceptLight.textMuted else OrbitConceptDark.textMuted

    val hairline: Color @Composable get() = if (isLight) Color(0x14000000) else OrbitConceptBorder.subtle()

    fun isLight(t: CosmicTokens): Boolean = t.Bg.luminance() > 0.5f
    fun chat(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.chat else OrbitConceptDark.chat
    fun secondary(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.secondary else OrbitConceptDark.secondary
    fun floating(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.floating else OrbitConceptDark.floating
    fun textPrimary(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.textPrimary else OrbitConceptDark.textPrimary
    fun textNormal(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.textNormal else OrbitConceptDark.textNormal
    fun textMuted(t: CosmicTokens): Color = if (isLight(t)) OrbitConceptLight.textMuted else OrbitConceptDark.textMuted
    fun hairline(t: CosmicTokens): Color = if (isLight(t)) Color(0x14000000) else OrbitConceptBorder.subtle()
}