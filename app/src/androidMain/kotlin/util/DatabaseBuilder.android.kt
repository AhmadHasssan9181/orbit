package util

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase

lateinit var appContext: Context

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual fun getRoomDatabaseBuilder(): RoomDatabase.Builder<TrixnityRoomDatabase> {
    val dbFile = appContext.getDatabasePath("trixnity.db")
    return Room.databaseBuilder<TrixnityRoomDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    ).setDriver(BundledSQLiteDriver())
}
