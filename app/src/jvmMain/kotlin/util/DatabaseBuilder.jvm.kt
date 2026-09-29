package util

import androidx.room.Room
import androidx.room.RoomDatabase
import de.connect2x.trixnity.client.store.repository.room.TrixnityRoomDatabase
import java.io.File

import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Returns the process-wide Trixnity Room builder.
 *
 * MatrixClient.create(...) is called from four places, and each call used to get a
 * FRESH builder: Room builds the database on `build()`, so every create() opened
 * another connection pool plus WAL handle on the same ~/.orbit/trixnity.db and
 * none of them was ever closed. Room caches the built instance per builder, so
 * handing out one shared builder makes the whole process use a single database.
 *
 * The signature is unchanged (a Builder), so every existing caller keeps working.
 */
actual fun getRoomDatabaseBuilder(): RoomDatabase.Builder<TrixnityRoomDatabase> = sharedRoomDatabaseBuilder

private val sharedRoomDatabaseBuilder: RoomDatabase.Builder<TrixnityRoomDatabase> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    val dbFile = File(System.getProperty("user.home"), ".orbit/trixnity.db")
    dbFile.parentFile?.mkdirs()
    Room.databaseBuilder<TrixnityRoomDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
}
