import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId
import kotlinx.coroutines.flow.Flow
import de.connect2x.trixnity.core.model.events.StateEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent

fun test(trixnity: MatrixClient, roomId: RoomId) {
    val a: Flow<Map<String, StateEvent<UnknownEventContent>>> = trixnity.room.getAllState(roomId, UnknownEventContent::class)
}
