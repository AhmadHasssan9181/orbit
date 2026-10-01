package ui.screen.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import ui.model.OrbitMessage
import ui.model.UserStatus
import ui.theme.OrbitConceptBorder
import ui.theme.OrbitConceptDark
import ui.theme.OrbitConceptLight
import ui.theme.rememberCosmicTokens

/** Time-of-day, honouring the user's 12/24h preference. */
@Composable
internal fun clockTime(dt: kotlinx.datetime.LocalDateTime, use24Hour: Boolean): String =
    if (use24Hour) {
        "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    } else {
        val amPm = if (dt.hour >= 12) "PM" else "AM"
        val h12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        "$h12:${dt.minute.toString().padStart(2, '0')} $amPm"
    }

@Composable
internal fun formatTimestamp(ts: Instant): String {
    val use24Hour = ui.theme.LocalUse24HourClock.current
    val dateFormat = ui.theme.LocalDateFormat.current
    val tz = TimeZone.currentSystemDefault()
    val dt = ts.toLocalDateTime(tz)
    val today = Instant
        .fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(tz)
        .date

    val timeStr = clockTime(dt, use24Hour)

    return when {
        dt.date == today -> "Today at $timeStr"
        dt.date == today.minus(1, DateTimeUnit.DAY) -> "Yesterday at $timeStr"
        else -> "${formatDate(dt.date, dateFormat)} $timeStr"
    }
}

/**
 * Compact form for the message identity line.
 *
 * The full form spells out "Today at 5:20 PM" next to every single message,
 * which is a lot of repeated words doing no work — the date separator above
 * the group already says which day it is.
 */
@Composable
internal fun formatTimestampCompact(ts: Instant): String {
    val use24Hour = ui.theme.LocalUse24HourClock.current
    val dateFormat = ui.theme.LocalDateFormat.current
    val tz = TimeZone.currentSystemDefault()
    val dt = ts.toLocalDateTime(tz)
    val today = Instant
        .fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(tz)
        .date

    val timeStr = clockTime(dt, use24Hour)
    return when {
        dt.date == today -> timeStr
        dt.date == today.minus(1, DateTimeUnit.DAY) -> "Yesterday $timeStr"
        else -> "${formatDate(dt.date, dateFormat)} $timeStr"
    }
}

/**
 * Every date pattern [formatDate] can render, in the order the settings card
 * offers them.
 *
 * This is the single source of truth on purpose. The picker used to be a
 * hand-typed `listOf(...)` in the settings pane while the patterns lived in a
 * private `when` here, so the two could only ever agree by accident: add a branch
 * down here and the new format was unselectable, remove one and the picker offered
 * a choice that silently produced DD/MM/YYYY. An unrecognised pattern is not an
 * error -- it falls back -- which is exactly what makes the drift invisible.
 *
 * DD/MM/YYYY is not in this list of `when` branches because it IS the fallback,
 * and it is listed here because it is a real choice a user can make. [DATE_FORMATS]
 * is what the picker shows; [formatDate] is what renders it.
 */
internal val DATE_FORMATS = listOf("DD/MM/YYYY", "MM/DD/YYYY", "YYYY-MM-DD", "D MMM YYYY")

internal fun formatDate(date: LocalDate, pattern: String): String {
    val d = date.dayOfMonth.toString().padStart(2, '0')
    val m = date.monthNumber.toString().padStart(2, '0')
    val y = date.year.toString()
    // `LocalDate.month.name` is the enum constant -- MARCH, SEPTEMBER -- so the
    // old `name.take(3)` produced "07 MAR 2026". Nobody had ever seen it, because
    // "D MMM YYYY" was one of the branches of a setting that could not be selected
    // from anywhere: the date-format setting had a reader and no writer, so the
    // only reachable value was the fallback and this branch was unreachable code.
    // Turning the setting on is what exposed it. Title case, not upper.
    val monthAbbr = date.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return when (pattern.uppercase()) {
        "MM/DD/YYYY" -> "$m/$d/$y"
        "YYYY-MM-DD" -> "$y-$m-$d"
        "D MMM YYYY" -> "$d $monthAbbr $y"
        else -> "$d/$m/$y"
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Concept chat-area geometry and colour
//
// Every value below is lifted from the concept's stylesheet, rule by rule, and
// the rule is named so it can be checked. They live here rather than in
// `CosmicTokens` for two reasons: `CosmicTokens` is a shared theme surface, and
// the concept's chat-area colours are *chat-area* colours; and adding to a
// shared token object is the kind of edit that collides with anyone else
// working in the theme layer.
//
// The light/dark pairs are resolved against the surface the chat is actually
// drawn on, using the same luminance test `rememberCosmicTokens` uses to pick
// its token set. The concept has a third theme (`midnight`) whose surfaces are
// not reachable that way — the app's token set does not vary `Bg` by theme, so
// there is nothing to read — so midnight chat falls on the dark values, which
// are within a few percent of its own.
// ─────────────────────────────────────────────────────────────────────────

internal object ChatConcept {

    // ── Surfaces ────────────────────────────────────────────────
    /** `.chat-container` / `.chat-header { background-color: var(--bg-chat) }` → `t.Bg`. */
    // (Bg is read from the token set rather than listed here; it is already right.)
    // Likewise `var(--bg-floating)` is `t.Elevated`, the token set's own
    // "floats above everything" surface.

    /**
     * `var(--bg-secondary)` — code blocks, reaction pills, the reply banner.
     *
     * Only the dark half has a consumer. Every light-theme rule that used
     * `--bg-secondary` overrides it with a *different* value anyway
     * (`#ebedef` for the reply banner, `#f2f3f5` for a pill, `#f6f8fa` for a
     * code block), so a "secondaryLight" member would never be the right answer.
     * Those three overrides are named individually below.
     */
    val secondary = OrbitConceptDark.secondary

    /** `var(--bg-input)` — the composer frame. `[data-theme=light]` is `#ebedef`. */
    val input = OrbitConceptDark.input
    val inputLight = OrbitConceptLight.input

    /** `var(--bg-sidebar)` — the channel-welcome roundel. Light is `#e3e5e8`. */
    val sidebar = OrbitConceptDark.sidebar
    val sidebarLight = OrbitConceptLight.sidebar

    /**
     * `var(--bg-servers)` — the header search field. Light is `#e3e5e8`.
     *
     * Only the dark half again, and for the same reason as [secondary]: the light
     * override is a concrete value, [pillHoverLight], which reads better where the
     * reason is a name.
     */
    val servers = OrbitConceptDark.servers

    // ── Foregrounds ─────────────────────────────────────────────
    /**
     * `var(--header-primary)` in the light theme, `#060607`.
     *
     * Only the light half is named. The dark half is `#f2f3f5`, which *is*
     * `CosmicTokens.Text_White` (DarkFgStrong), so a second copy of it here would
     * be a second source of truth for a value the token set already owns.
     */
    val headerPrimaryLight = OrbitConceptLight.textPrimary

    /**
     * `var(--interactive-normal)` (#b5bac1 / #4e5058) and
     * `var(--interactive-hover)` (#dbdee1 / #313338).
     *
     * `--interactive-hover` is exactly `--text-normal`, so that one is read from
     * `t.Text_Secondary`. `--interactive-normal` has no token equivalent: it sits
     * between `--header-primary` and `--text-normal` on the dark ramp, which is
     * why it is spelled out here rather than approximated with a neighbour.
     */
    val interactiveNormal = Color(0xFFB5BAC1)
    val interactiveNormalLight = Color(0xFF4E5058)

    /**
     * `var(--interactive-muted)` (#4e5058) — the 24dp `#` in the channel header.
     * Equals `ui.theme.Ink650`; kept local so the header reads off one list.
     *
     * The same hex as [interactiveNormalLight], and it has to stay a separate name
     * anyway: the stylesheet gives `--interactive-muted` no light-theme override at
     * all, so the light header's `#` is #4e5058 by *omission*, not by rule. Merging
     * the two would be asserting a relationship the CSS does not make.
     */
    val interactiveMuted = Color(0xFF4E5058)

    /** `var(--brand-blurple)` → `t.Accent`. */

    // ── Lines ───────────────────────────────────────────────────
    /**
     * `var(--border-subtle)` (rgba(255,255,255,.08) dark, rgba(0,0,0,.08) light).
     *
     * `OrbitConceptBorder.subtle()` is the white-at-8% half;
     * `OrbitConceptOverlay.hoverLight()` is black at 4%, so the light half is
     * spelled out.
     */
    val borderSubtleLight = Color.Black.copy(alpha = OrbitConceptBorder.SUBTLE)

    // ── Chat-area specifics, hard-coded in the stylesheet ───────
    /**
     * `.code-block { color: #e0e0e0 }`; `[data-theme=light] .code-block` overrides
     * it to `#24292f`, together with `background: #f6f8fa` and
     * `border: 1px solid #d0d7de`.
     */
    val codeText = Color(0xFFE0E0E0)
    val codeTextLight = Color(0xFF24292F)
    val codeSurfaceLight = Color(0xFFF6F8FA)
    val codeBorderLight = Color(0xFFD0D7DE)

    /**
     * `.reaction-pill:hover { background: rgba(255,255,255,0.08) }` and
     * `[data-theme=light] .reaction-pill:hover { background: #e3e5e8 }`.
     */
    const val PILL_HOVER_ALPHA = 0.08f
    val pillSurfaceLight = Color(0xFFF2F3F5)
    val pillBorderLight = Color(0xFFE3E5E8)
    val pillHoverLight = Color(0xFFE3E5E8)

    /**
     * `.reaction-pill.reacted { background: rgba(88, 101, 242, 0.2); color: #fff }`
     * and the light override's `rgba(88, 101, 242, 0.15)` + brand text.
     */
    const val PILL_REACTED_ALPHA_DARK = 0.20f
    const val PILL_REACTED_ALPHA_LIGHT = 0.15f

    /**
     * `--elevation-high: 0 8px 16px rgba(0, 0, 0, 0.24)`, light
     * `0 8px 16px rgba(0, 0, 0, 0.1)` — the hover message toolbar's shadow.
     */
    val shadowHigh = Color.Black.copy(alpha = 0.24f)
    val shadowHighLight = Color.Black.copy(alpha = 0.10f)
}

/**
 * True when the chat is being drawn on a light surface.
 *
 * The same `luminance() < 0.5f` test `rememberCosmicTokens` uses, so the
 * concept's `[data-theme='light']` overrides are selected by exactly the same
 * rule that selected the token set. Reading it anywhere else would be how the
 * light theme ends up with dark-theme overrides.
 */
internal fun isLightChatSurface(tokens: ui.theme.CosmicTokens): Boolean =
    tokens.Bg.luminance() >= 0.5f

// ─── Message row geometry ──────────────────────────────────────────────────

/**
 * `.message-item { padding: 6px 12px; border-radius: 6px; margin: 0 -12px }`.
 *
 * The negative margin is what lets the hover fill reach past the stream's own
 * padding, so the horizontal inset is expressed as two numbers: [messageStreamInset]
 * on the list, `messageRowInset` inside the row. 4 + 12 = 16, which is the
 * `padding: … 16px` the concept's stream actually applies.
 */
internal val messageRowInset: Dp = 12.dp
internal val messageRowPaddingV: Dp = 6.dp

/** `.message-item { gap: 16px }` — the space between avatar and text column. */
internal val messageRowGap: Dp = 16.dp

/** `.message-item { border-radius: 6px }` */
internal val messageRowRadius = RoundedCornerShape(6.dp)

/** `.messages-stream { padding: 20px 16px 12px }` minus the row's `-12px` margin. */
internal val messageStreamInset: Dp = 4.dp

/** `.messages-stream { padding: 20px … 12px }` — the list's content padding. */
internal val messageStreamPaddingTop: Dp = 20.dp
internal val messageStreamPaddingBottom: Dp = 12.dp

/** `.messages-stream { gap: 16px }` — the stack gap between message rows. */
internal val messageStackGap: Dp = 16.dp

/**
 * Diameter of the avatar in the gutter beside a first message.
 *
 * `.message-avatar-wrap { width: 40px; height: 40px; border-radius: 50% }`.
 *
 * A named constant rather than a literal because the gutter arithmetic below
 * depends on it, and a literal in two files is exactly how they drift.
 *
 * It was 36dp. The concept's avatar is 40dp, and the row inset is 12dp rather
 * than Orbit's old 16dp, so the text column below moved with it.
 */
internal val MessageAvatarSize = 40.dp

/**
 * Where the text column begins inside a message row.
 *
 * Discord aligns every message body in a run: the first message carries an avatar
 * and a name, and the follow-on lines sit in a column that starts exactly where
 * that first line of text starts. Not "roughly level with" -- the same column.
 *
 * The three numbers are the concept's own, taken from `.message-item` and
 * `.message-avatar-wrap`:
 *
 *     row padding-inline (12) + avatar (40) + gap (16) = 68dp
 *
 * ## Why it was 64dp, and what that was
 *
 * `OrbitSpace.lg (16) + 36 + OrbitSpace.md (12) = 64dp`, which matched nothing in
 * the concept — 16 is the *stream's* padding, not the row's, and the avatar was
 * 4dp under size. The gutter was self-consistent, which is the only thing the old
 * value had going for it: `DenseMessageBody` hardcoded `padding(start = 72.dp)`
 * while `MessageBubbleFull` derived its own, so every grouped message sat 8dp to
 * the right of the message it belonged to. Both sides now read this constant, and
 * the test pins the arithmetic, so neither can drift from the other again.
 */
internal val messageTextGutter: Dp = messageRowInset + MessageAvatarSize + messageRowGap

/**
 * Where a grouped message's hover timestamp is drawn from the row's left edge.
 *
 * Sits in the avatar gutter, where there is nothing on a grouped message -- so
 * hovering swaps the time in where the avatar would be on a first message.
 *
 * It is drawn in the PARENT Box rather than as a child of the body Row, and that
 * is the part that matters. As a Row child it reserved 48dp of layout whatever
 * its offset said, pushing every grouped body 48dp right of the message above it.
 */
internal val hoverTimestampInset: Dp = 8.dp

/**
 * The concept's role colours.
 *
 * `.message-author { color: msg.user.roleColor || var(--header-primary) }` — the
 * display name is painted in the sender's ROLE colour, and falls back to the
 * header's primary text colour when they have none.
 *
 * Matrix has nothing that corresponds to the concept's per-user `roleColor`: the
 * closest thing is the power level in `repository.MemberRoleInfo`, and this chat
 * surface is not given one. So the hue is drawn deterministically from the user
 * id out of the same four values the concept's mock data uses for its roles.
 * That reproduces the *effect*, which is the point: in a busy room every person
 * reads as a different, still-familiar hue instead of one accent for everybody.
 *
 * These are the concept's values, not Orbit's avatar seeds. The old behaviour fed
 * the display name from `avatarSeedColor`, so a person's name was painted in the
 * same colour as their generated avatar — which meant the name was doing the
 * avatar's job twice and neither could change independently.
 */
private val ConceptRoleColors = listOf(
    Color(0xFFF1C40F), // Admin     — mockData CyberSamurai roleColor
    Color(0xFF9B59B6), // Moderator — mockData NeonValkyrie roleColor
    Color(0xFF5865F2), // Bot       — mockData MikoBot roleColor
    Color(0xFF2ECC71)  // Member    — mockData Dev_Shadow / NexusEcho roleColor
)

/**
 * Colour of a sender's display name, per `.message-author`.
 *
 * ## The light theme
 *
 * `[data-theme='light'] .message-author { color: #060607 !important }`. The
 * `!important` is load-bearing: it beats the inline `style={{ color: roleColor }}`
 * the component writes, so in the light theme the concept pins *every* name to
 * the header's primary text colour regardless of role. That is reproduced rather
 * than "fixed" — a 40%-luminance role hue on white is not a name colour, it is a
 * smudge, and the concept's own author saw the same thing.
 *
 * ## Why there is no `remember` any more
 *
 * The previous version wrapped the seed lookup in `remember(message.userId)` and
 * carried a comment explaining that the `remember` had to run before the early
 * return, because returning above it changed the number of remembered slots when
 * "Show role colors" was toggled. The fix is to have no remembered slot: a string
 * hash and a list index are not worth remembering, so the hazard is gone rather
 * than defended against.
 */
@Composable
internal fun resolveUsernameColor(message: OrbitMessage): Color {
    val showRoleColors = ui.theme.LocalShowRoleColors.current
    val t = rememberCosmicTokens()
    // The concept's fallback: `roleColor || var(--header-primary)`.
    val fallback = if (isLightChatSurface(t)) ChatConcept.headerPrimaryLight else t.Text_White
    if (!showRoleColors) return fallback
    return ConceptRoleColors[(message.userId.hashCode() and Int.MAX_VALUE) % ConceptRoleColors.size]
}

/**
 * Token-sourced presence colour. The previous copies of this used the raw
 * `ui.theme.Presence*` palette constants, which bypass the theme token set: in
 * the light theme that is a different green from the DM list's, so the same
 * person showed two different online colours depending on which surface you
 * looked at. See `ui.screen.drawer.presenceColor`, the single source of truth.
 */
@Composable
internal fun memberStatusColor(status: UserStatus): Color =
    ui.screen.drawer.presenceColor(rememberCosmicTokens(), status)

internal fun statusText(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}
