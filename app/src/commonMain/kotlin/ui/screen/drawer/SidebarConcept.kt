package ui.screen.drawer

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.theme.CosmicTokens
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptLight
import ui.theme.OrbitConceptOverlay
import ui.theme.OrbitConceptStatus
import ui.theme.rememberCosmicTokens

/**
 * The channel/DM sidebar's values from the Orbit concept, in one place.
 *
 * `ui/theme/OrbitConcept.kt` already carries everything the concept's `:root`
 * declares as a `var(--…)`. What is left is:
 *
 *  1. the `--interactive-*` ramp, which has no token of its own;
 *  2. the light-theme halves of `--border-subtle` / `--border-strong`;
 *  3. the handful of *literal* colours in `src/index.css` that are not variables
 *     at all (the screen-share teal, the "Server Boost" pink, the Spotify and
 *     Twitch hues in the friends view);
 *  4. the geometry `OrbitSpace` / `OrbitRadius` do not carry (`.server-dropdown-menu`
 *     is 8px, `.member-list-sidebar`'s neighbour `.friend-card` is 10px).
 *
 * They live here for the same reason `ChatConcept` and `CallConceptStyle` do:
 * `CosmicTokens` in `Theme.kt` is a shared surface other people are editing, and
 * these are sidebar-and-friends colours, not app colours.
 *
 * The light/dark pairs are selected with the same `luminance() < 0.5f` test
 * `rememberCosmicTokens` uses to pick its token set, so the concept's
 * `[data-theme='light']` overrides are chosen by exactly the rule that chose the
 * rest of the palette. The concept's third theme (`midnight`) is not reachable
 * that way — Orbit's token set does not vary `Bg` by theme — so midnight falls on
 * the dark values, which are within a couple of percent of its own.
 */
internal object SidebarConcept {

    // ── Surfaces ────────────────────────────────────────────────────────────

    /** `.channel-sidebar { background-color: var(--bg-sidebar) }`. */
    val sidebar = OrbitConceptDark.sidebar
    val sidebarLight = OrbitConceptLight.sidebar

    /**
     * `var(--bg-secondary)` — `.user-panel`, `.voice-connected-bar`,
     * `.mini-screen-preview-card`, the friends-view activity cards.
     *
     * Note this is *not* `t.CardBg`: the token set's card is one step UP the
     * ramp (#404249) and these are one step DOWN from the sidebar (#232428).
     */
    val secondary = OrbitConceptDark.secondary
    val secondaryLight = OrbitConceptLight.secondary

    /** `var(--bg-floating)` — `.server-dropdown-menu`. */
    val floating = OrbitConceptDark.floating
    val floatingLight = OrbitConceptLight.floating

    /** `var(--bg-chat)` — `.chat-container` / `.chat-header`, the friends view. */
    val chat = OrbitConceptDark.chat
    val chatLight = OrbitConceptLight.chat

    /** `var(--bg-servers)` — `.badge-unread`'s ring, the friends view's inputs. */
    val servers = OrbitConceptDark.servers
    val serversLight = OrbitConceptLight.servers

    // ── Foregrounds ─────────────────────────────────────────────────────────

    /** `var(--header-primary)` — `.server-header-title`, `.user-display-name`. */
    val headerPrimary = OrbitConceptDark.textPrimary
    val headerPrimaryLight = OrbitConceptLight.textPrimary

    /** `var(--header-secondary)` — `.category-header:hover`. */
    val headerSecondary = OrbitConceptDark.textSecondary
    val headerSecondaryLight = OrbitConceptLight.textSecondary

    /** `var(--text-normal)` — also `var(--interactive-hover)`, they are the same value. */
    val textNormal = OrbitConceptDark.textNormal
    val textNormalLight = OrbitConceptLight.textNormal

    /** `var(--text-muted)` — `.category-header`, `.voice-status-channel`. */
    val textMuted = OrbitConceptDark.textMuted
    val textMutedLight = OrbitConceptLight.textMuted

    /**
     * `var(--interactive-normal)` (#b5bac1 / #4e5058) — the resting colour of
     * `.channel-row` and every `.user-control-btn`.
     *
     * Spelled out rather than approximated with a neighbour: it sits between
     * `--header-secondary` and `--text-normal` on the dark ramp, so borrowing
     * either of them moves the resting weight of the whole sidebar.
     */
    val interactiveNormal = Color(0xFFB5BAC1)
    val interactiveNormalLight = Color(0xFF4E5058)

    /** `var(--interactive-hover)` (#dbdee1 / #313338) — the same two values as `--text-normal`. */
    val interactiveHover = OrbitConceptDark.textNormal
    val interactiveHoverLight = OrbitConceptLight.textNormal

    /**
     * `var(--interactive-active)` (#ffffff / #060607) — a selected `.channel-row`.
     *
     * One step brighter than `--header-primary` in dark and the only foreground
     * in the concept's `:root` with no named equivalent.
     */
    val interactiveActive = Color(0xFFFFFFFF)
    val interactiveActiveLight = OrbitConceptLight.textPrimary

    /**
     * `var(--interactive-muted)` (#4e5058) — `.channel-icon` at rest. The light
     * theme does not override it, so one value serves both. Equals
     * `ui.theme.Ink650`; kept here so the sidebar reads off one list.
     */
    val interactiveMuted = Color(0xFF4E5058)

    // ── Lines and interaction washes ────────────────────────────────────────

    /**
     * `[data-theme='light'] --border-subtle` (rgba(0,0,0,.08)).
     * `OrbitConceptBorder.subtle()` is only the white-at-8% half.
     */
    val borderSubtleLight = Color.Black.copy(alpha = OrbitConceptBorder.SUBTLE)

    /** `[data-theme='light'] --border-strong` (rgba(0,0,0,.16)). */
    val borderStrongLight = Color.Black.copy(alpha = OrbitConceptBorder.STRONG)

    /** `[data-theme='light'] --bg-modifier-active` (rgba(0,0,0,.12)). */
    val overlayActiveLight = Color.Black.copy(alpha = 0.12f)

    // ── Literals in the concept's CSS / JSX with no `var(--…)` ──────────────

    /**
     * `.channel-live-badge { background-color: #f23f43 }` and the inline
     * `style={{ color: '#23a55a' }}` on a connected channel's call timer. Both
     * are the two `--status-*` values, so they are read from the token object
     * rather than copied.
     */
    // The concept's `.channel-live-badge` has no counterpart here. It marks a
    // streamer in the voice roster, and Matrix has no streaming presence for
    // Orbit to read -- OrbitUser carries no such field -- so there was nothing
    // real to drive it. The token was declared and never used, which is dead code
    // of exactly the kind this file's own comments complain about elsewhere, so
    // it is gone rather than left as a promise nothing delivers.
    //
    // If streaming presence is ever plumbed through, the colour is already
    // OrbitConceptStatus.streaming (#593695, --status-streaming).

    /**
     * `.screen-icon-box { background-color: rgba(26, 188, 156, 0.15) }` and the
     * inline `color="#1abc9c"` on its `Monitor` glyph. A third-party brand teal,
     * so it is not one of the concept's `--status-*` values.
     */
    val screenShareTeal = Color(0xFF1ABC9C)
    val screenShareTealWash = screenShareTeal.copy(alpha = OrbitConceptOverlay.BRAND_SUBTLE)

    /**
     * The friends view's two third-party activity accents, from inline styles:
     * `rgba(29, 185, 84, 0.15)` / `#1db954` (Spotify) and
     * `rgba(145, 70, 255, 0.15)` / `#9146ff` / `#bf94ff` (Twitch).
     */
    val spotifyGreen = Color(0xFF1DB954)
    val spotifyWash = spotifyGreen.copy(alpha = OrbitConceptOverlay.BRAND_SUBTLE)
    val twitchPurple = Color(0xFF9146FF)
    val twitchWash = twitchPurple.copy(alpha = OrbitConceptOverlay.BRAND_SUBTLE)
    val twitchPurpleText = Color(0xFFBF94FF)

    /**
     * `.voice-tool-btn` / `.voice-tool-compact-btn { background-color: rgba(255,255,255,0.05) }`
     * and their `:hover` step. The 10% hover step is `OrbitConceptOverlay`'s
     * `SELECTED`; the 5% rest fill has no token.
     */
    val toolRest = Color(0xFFFFFFFF).copy(alpha = 0.05f)
    val toolHover = Color(0xFFFFFFFF).copy(alpha = 0.10f)

    // ── Geometry the spacing / radius scales do not carry ───────────────────

    /**
     * `.channel-sidebar { border-top-left-radius: 8px }` — the one rounded corner
     * on the panel. `OrbitRadius` stops at 6 and 10, so it is literal.
     */
    val sidebarShape = RoundedCornerShape(topStart = 8.dp)

    /** `.server-dropdown-menu { border-radius: 8px; padding: 6px; width: 220px }`. */
    val dropdownShape = RoundedCornerShape(8.dp)
    val dropdownPadding: Dp = 6.dp
    val dropdownWidth: Dp = 220.dp

    /** `.menu-item { padding: 8px 10px; border-radius: 4px }`. */
    val menuItemPaddingH: Dp = 10.dp
    val menuItemPaddingV: Dp = 8.dp
    val menuItemShape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)

    /** `.menu-divider { margin: 4px 0 }`. */
    val menuDividerMargin: Dp = 4.dp

    /**
     * `.activity-card { border-radius: 10px }` in the friends view's "Active
     * Now" column. 10 is in `OrbitRadius` but the radius itself is private
     * there, so it is spelled out with its rule.
     */
    val activityCardShape = RoundedCornerShape(10.dp)

    /**
     * `.add-friend-form > div { border-radius: 8px; padding: 8px 14px }` and the
     * friend-search bar's `6px`.
     */
    val friendsFormShape = RoundedCornerShape(8.dp)
    val friendsSearchShape = RoundedCornerShape(6.dp)
}

/**
 * True while the sidebar is drawn on a dark surface.
 *
 * The same test `rememberCosmicTokens` uses to pick its token set, so the
 * concept's `[data-theme='light']` overrides are selected by exactly the rule
 * that selected the rest of the palette.
 */
@Composable
internal fun isDarkSidebar(): Boolean = rememberCosmicTokens().Bg.luminance() < 0.5f

// ── Composables that pick the half ───────────────────────────────────────────
// Each is one line, but having them named means a call site reads as
// `conceptTextMuted()` rather than as a ternary on a luminance test, and there
// is exactly one place to change if the test ever moves.

@Composable
internal fun conceptSidebarSurface(): Color =
    if (isDarkSidebar()) SidebarConcept.sidebar else SidebarConcept.sidebarLight

/** `var(--bg-secondary)`. */
@Composable
internal fun conceptSecondarySurface(): Color =
    if (isDarkSidebar()) SidebarConcept.secondary else SidebarConcept.secondaryLight

/** `var(--bg-floating)`. */
@Composable
internal fun conceptFloatingSurface(): Color =
    if (isDarkSidebar()) SidebarConcept.floating else SidebarConcept.floatingLight

/** `var(--bg-chat)`. */
@Composable
internal fun conceptChatSurface(): Color =
    if (isDarkSidebar()) SidebarConcept.chat else SidebarConcept.chatLight

/** `var(--bg-servers)`. */
@Composable
internal fun conceptServersSurface(): Color =
    if (isDarkSidebar()) SidebarConcept.servers else SidebarConcept.serversLight

/** `var(--header-primary)`. */
@Composable
internal fun conceptHeaderPrimary(): Color =
    if (isDarkSidebar()) SidebarConcept.headerPrimary else SidebarConcept.headerPrimaryLight

/** `var(--header-secondary)`. */
@Composable
internal fun conceptHeaderSecondary(): Color =
    if (isDarkSidebar()) SidebarConcept.headerSecondary else SidebarConcept.headerSecondaryLight

/** `var(--text-normal)`. */
@Composable
internal fun conceptTextNormal(): Color =
    if (isDarkSidebar()) SidebarConcept.textNormal else SidebarConcept.textNormalLight

/** `var(--text-muted)`. */
@Composable
internal fun conceptTextMuted(): Color =
    if (isDarkSidebar()) SidebarConcept.textMuted else SidebarConcept.textMutedLight

/** `var(--interactive-normal)`. */
@Composable
internal fun conceptInteractiveNormal(): Color =
    if (isDarkSidebar()) SidebarConcept.interactiveNormal else SidebarConcept.interactiveNormalLight

/** `var(--interactive-hover)`. */
@Composable
internal fun conceptInteractiveHover(): Color =
    if (isDarkSidebar()) SidebarConcept.interactiveHover else SidebarConcept.interactiveHoverLight

/** `var(--interactive-active)`. */
@Composable
internal fun conceptInteractiveActive(): Color =
    if (isDarkSidebar()) SidebarConcept.interactiveActive else SidebarConcept.interactiveActiveLight

/**
 * `var(--bg-modifier-hover)` — white at 6% in dark, black at 4% in light.
 * Read from the token objects rather than spelled out, because both halves are
 * already there.
 */
@Composable
internal fun conceptHoverOverlay(): Color =
    if (isDarkSidebar()) OrbitConceptOverlay.hover() else OrbitConceptOverlay.hoverLight()

/** `var(--bg-modifier-selected)` — white at 10%, black at 8%. */
@Composable
internal fun conceptSelectedOverlay(): Color =
    if (isDarkSidebar()) OrbitConceptOverlay.selected() else OrbitConceptOverlay.selectedLight()

/** `var(--bg-modifier-active)` — white at 14%, black at 12%. */
@Composable
internal fun conceptActiveOverlay(): Color =
    if (isDarkSidebar()) OrbitConceptOverlay.active() else SidebarConcept.overlayActiveLight

/** `var(--border-subtle)`. */
@Composable
internal fun conceptBorderSubtle(): Color =
    if (isDarkSidebar()) OrbitConceptBorder.subtle() else SidebarConcept.borderSubtleLight

/** `var(--border-strong)`. */
@Composable
internal fun conceptBorderStrong(): Color =
    if (isDarkSidebar()) OrbitConceptBorder.strong() else SidebarConcept.borderStrongLight

/** `var(--brand-blurple)` — the concept's accent, which is also Orbit's default accent. */
internal val conceptBrand: Color = OrbitConceptDark.brand

/** `var(--brand-blurple-hover)`. */
internal val conceptBrandHover: Color = OrbitConceptDark.brandHover

/** `var(--brand-blurple-subtle)` — rgba(88, 101, 242, 0.15). */
internal val conceptBrandSubtle: Color = OrbitConceptDark.brand.copy(alpha = OrbitConceptOverlay.BRAND_SUBTLE)

/** The token set, for call sites that need a colour the concept does not name. */
@Composable
internal fun sidebarTokens(): CosmicTokens = rememberCosmicTokens()

/**
 * `animateColorAsState` under the app's shared motion spec.
 *
 * Every hover / selected / pressed wash in the sidebar runs on [OrbitMotion.SnappyColor]
 * (a no-bounce spring), which is what makes the four concept states feel like one
 * model rather than four separately-tuned ones.
 */
@Composable
internal fun animateColorAsStateCompat(
    targetValue: Color,
    label: String = "conceptColor"
): State<Color> =
    animateColorAsState(
        targetValue = targetValue,
        animationSpec = ui.theme.OrbitMotion.SnappyColor,
        label = label
    )