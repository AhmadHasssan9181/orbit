import de.connect2x.trixnity.client.room.RoomDisplayName
import kotlin.reflect.full.memberProperties

fun main() {
    RoomDisplayName::class.memberProperties.forEach { println(it.name) }
}
