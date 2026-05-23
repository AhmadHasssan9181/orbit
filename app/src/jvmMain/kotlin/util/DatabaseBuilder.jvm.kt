package util

import androidx.room.Room
import androidx.room.RoomDatabase
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import java.io.File

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual fun getRoomDatabaseBuilder(): RoomDatabase.Builder<TrixnityRoomDatabase> {
    val dbFile = File(System.getProperty("user.home"), ".orbit/trixnity.db")
    dbFile.parentFile?.mkdirs()
    return Room.databaseBuilder<TrixnityRoomDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
}
