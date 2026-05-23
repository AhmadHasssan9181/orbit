package ui.model

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Represents a message in a channel conversation.
 */
data class OrbitMessage(
    val id: String,
    val userId: String,
    val username: String,
    val body: String,
    val timestamp: Instant,              // <-- No default here
    val serverId: String? = null,
    val channelId: String? = null
) {
    fun formatTime(): String {
        val dt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val amPm = if (dt.hour >= 12) "PM" else "AM"
        val hour12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val minStr = dt.minute.toString().padStart(2, '0')
        return "${dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${dt.dayOfMonth}, ${dt.year} $hour12:$minStr $amPm"
    }

    fun formatShortTime(): String {
        val dt = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
        val amPm = if (dt.hour >= 12) "PM" else "AM"
        val hour12 = if (dt.hour % 12 == 0) 12 else dt.hour % 12
        val minStr = dt.minute.toString().padStart(2, '0')
        return "$hour12:$minStr $amPm"
    }
}