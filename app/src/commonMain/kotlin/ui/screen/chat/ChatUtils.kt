package ui.screen.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import ui.model.OrbitMessage
import ui.model.UserStatus
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

private fun formatDate(date: LocalDate, pattern: String): String {
    val d = date.dayOfMonth.toString().padStart(2, '0')
    val m = date.monthNumber.toString().padStart(2, '0')
    val y = date.year.toString()
    return when (pattern.uppercase()) {
        "MM/DD/YYYY" -> "$m/$d/$y"
        "YYYY-MM-DD" -> "$y-$m-$d"
        "D MMM YYYY" -> "$d ${date.month.name.take(3)} $y"
        else -> "$d/$m/$y"
    }
}

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
    if (!showRoleColors) return t.Text_White
    return remember(message.userId, t.Text_White) {
        ui.theme.avatarSeedColor(message.userId)
    }
}

internal fun hueToRgb(p: Float, q: Float, t: Float): Float {
    var x = t
    if (x < 0) x += 1f
    if (x > 1) x -= 1f
    return when {
        x < 1f / 6f -> p + (q - p) * 6f * x
        x < 0.5f -> q
        x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
        else -> p
    }
}

internal fun memberStatusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> ui.theme.PresenceOnline
    UserStatus.IDLE -> ui.theme.PresenceIdle
    UserStatus.DO_NOT_DISTURB -> ui.theme.PresenceDnd
    UserStatus.OFFLINE -> ui.theme.PresenceOffline
}

internal fun statusText(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}
