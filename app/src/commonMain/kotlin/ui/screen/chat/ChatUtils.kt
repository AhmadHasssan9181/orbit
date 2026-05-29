package ui.screen.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ui.model.OrbitMessage
import ui.model.UserStatus

import ui.screen.auth.*
import ui.screen.settings.*
import ui.screen.drawer.*
import ui.screen.server.*
import ui.screen.call.*
import ui.screen.media.*
import ui.screen.main.*

@Composable
internal fun formatTimestamp(ts: Instant): String {
    val use24Hour = ui.theme.LocalUse24HourClock.current
    val dt    = ts.toLocalDateTime(TimeZone.currentSystemDefault())
    
    val timeStr = if (use24Hour) {
        val h24 = dt.hour.toString().padStart(2, '0')
        val min = dt.minute.toString().padStart(2, '0')
        "$h24:$min"
    } else {
        val amPm  = if (dt.hour >= 12) "PM" else "AM"
        val h12   = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val min   = dt.minute.toString().padStart(2, '0')
        "$h12:$min $amPm"
    }
    return "Today at $timeStr"
}

@Composable
internal fun resolveUsernameColor(message: OrbitMessage): Color {
    val showRoleColors = ui.theme.LocalShowRoleColors.current
    val fallbackColor = Text_White
    return remember(message.userId, showRoleColors) { 
        if (showRoleColors) hslToColor(kotlin.math.abs(message.userId.hashCode()).toFloat() % 360f) 
        else fallbackColor
    }
}

internal fun hslToColor(hue: Float): Color {
    val s = 0.70f; val l = 0.68f
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    val r = hueToRgb(p, q, hue / 360f + 1f / 3f).coerceIn(0f, 1f)
    val g = hueToRgb(p, q, hue / 360f).coerceIn(0f, 1f)
    val b = hueToRgb(p, q, hue / 360f - 1f / 3f).coerceIn(0f, 1f)
    return Color(r, g, b)
}

internal fun hueToRgb(p: Float, q: Float, t: Float): Float {
    var x = t
    if (x < 0) x += 1f; if (x > 1) x -= 1f
    return when {
        x < 1f / 6f -> p + (q - p) * 6f * x
        x < 0.5f    -> q
        x < 2f / 3f -> p + (q - p) * (2f / 3f - x) * 6f
        else        -> p
    }
}

internal fun memberStatusColor(status: UserStatus) = when (status) {
    UserStatus.ONLINE         -> Color(0xFF34D399)
    UserStatus.IDLE           -> Color(0xFFFBBF24)
    UserStatus.DO_NOT_DISTURB -> Color(0xFFF87171)
    UserStatus.OFFLINE        -> Color(0xFF3D4160)
}

internal fun statusText(status: UserStatus) = when (status) {
    UserStatus.ONLINE -> "Online"
    UserStatus.IDLE -> "Idle"
    UserStatus.DO_NOT_DISTURB -> "Do not disturb"
    UserStatus.OFFLINE -> "Offline"
}
