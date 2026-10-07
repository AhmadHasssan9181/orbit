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

    // SECURITY: `trixnity.db` is the most sensitive file the app writes. It holds
    // the Olm account (ed25519 + curve25519 identity keys), every megolm inbound
    // and outbound session, and the cross-signing keys -- all in cleartext, because
    // `BundledSQLiteDriver` is plain SQLite.
    //
    // Encrypting it means SQLCipher, which is a dependency plus a migration for an
    // existing store, so that is deliberately NOT done here: silently introducing
    // it would lock a user out of their own history if the migration went wrong,
    // and it is a decision for the project rather than a patch.
    //
    // What IS done is the half that is safe and immediate: owner-only permissions
    // on the file, its -wal and its -shm siblings. Without it, on a POSIX host any
    // local account could read every megolm session key in the account.
    //
    // On Windows this is a no-op -- %USERPROFILE% is already per-user ACL'd, which
    // is the platform Orbit actually ships on.
    restrictDatabaseFilesToOwner(dbFile)

    Room.databaseBuilder<TrixnityRoomDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
}

/**
 * Narrow [dbFile] and its SQLite sidecars to owner-only.
 *
 * `-wal` and `-shm` are written continuously while the app runs, so they are
 * restricted here too and again on every builder creation; the SQLite driver
 * recreates them with the database file's mode on most platforms, but not all.
 *
 * Best-effort: this must never be able to stop the database opening.
 */
private fun restrictDatabaseFilesToOwner(dbFile: File) {
    runCatching {
        val candidates = listOf(
            dbFile,
            File(dbFile.parentFile, "${dbFile.name}-wal"),
            File(dbFile.parentFile, "${dbFile.name}-shm"),
            File(dbFile.parentFile, "${dbFile.name}-journal")
        )
        for (file in candidates) {
            if (!file.exists()) continue
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }
}
