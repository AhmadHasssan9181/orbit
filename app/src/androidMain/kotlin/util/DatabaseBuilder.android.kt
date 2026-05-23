package util

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase

lateinit var appContext: Context

actual fun getRoomDatabaseBuilder(): RoomDatabase.Builder<TrixnityRoomDatabase> {
    val dbFile = appContext.getDatabasePath("trixnity.db")
    return Room.databaseBuilder<TrixnityRoomDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    ).setDriver(BundledSQLiteDriver())
}