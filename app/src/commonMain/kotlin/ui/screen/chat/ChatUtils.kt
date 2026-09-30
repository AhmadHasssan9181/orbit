package ui.screen.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import ui.model.OrbitMessage
import ui.model.UserStatus
import ui.theme.OrbitSpace
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

/**
 * Diameter of the avatar in the gutter beside a first message.
 *
 * A named constant rather than a literal because the gutter arithmetic below
 * depends on it, and a literal in two files is exactly how they drift.
 */
internal val MessageAvatarSize = 36.dp

/**
 * Where the text column begins inside a message row.
 *
 * Discord aligns every message body in a run: the first message carries an avatar
 * and a name, and the follow-on lines sit in a column that starts exactly where
 * that first line of text starts. Not "roughly level with" -- the same column.
 *
 * That column is `row padding + avatar + gap`, so this is
 * `OrbitSpace.lg (16) + 36 + OrbitSpace.md (12) = 64dp`.
 *
 * ## Why it was 72dp, and why that was a bug
 *
 * `DenseMessageBody` hardcoded `padding(start = 72.dp)` while
 * `MessageBubbleFull` derived its own gutter from the tokens above. Nothing
 * connected the two numbers, so every grouped message sat **8dp to the right** of
 * the message it belonged to. Visible on any run of two or more messages from one
 * person, which is most of a conversation.
 *
 * The `-56dp` offset on the hover timestamp in that same file was already tuned
 * for a 64dp gutter -- it lands the timestamp at 8dp, which is where the avatar
 * starts. So the padding was the wrong number and the offset was the right one,
 * which is why the timestamp looked fine and the text did not.
 */
internal val messageTextGutter: Dp = OrbitSpace.lg + MessageAvatarSize + OrbitSpace.md

/**
 * Name colours.
 *
 * This used to be `hslToColor(hash)`, i.e. fully saturated HSL at 70% — every
 * name in a busy room landed on a different neon, which turned a text column
 * into a paint chart. It now draws from a small curated set of desaturated
 * hues: still individually recognisable, never louder than the accent.
 */
@Composable
internal fun resolveUsernameColor(message: OrbitMessage): Color {
    val showRoleColors = ui.theme.LocalShowRoleColors.current
    val t = rememberCosmicTokens()
    // The remember has to run before the early return: returning above it meant
    // toggling "Show role colors" changed the number of remembered slots.
    val seedColor = remember(message.userId) { ui.theme.avatarSeedColor(message.userId) }
    return if (showRoleColors) seedColor else t.Text_White
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
