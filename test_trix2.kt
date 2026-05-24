import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.core.model.RoomId

suspend fun test(trixnity: MatrixClient, roomId: RoomId) {
    val state = trixnity.api.room.getState(roomId)
}
