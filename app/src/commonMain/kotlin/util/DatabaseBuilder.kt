package util

import androidx.room.RoomDatabase
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase

expect fun getRoomDatabaseBuilder(): RoomDatabase.Builder<TrixnityRoomDatabase>
