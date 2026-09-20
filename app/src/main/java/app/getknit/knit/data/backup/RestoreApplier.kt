package app.getknit.knit.data.backup

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStoreFile
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.settings.SettingsKeys
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The last step of a restore: moves what [RestoreStager] staged into the places the app reads from.
 *
 * Runs in `KnitApplication.onCreate` **before** Koin, in the process the restart trampoline brought up —
 * the one moment nothing holds the database, the identity file or the DataStore open. Everything here
 * is a rename within the app's data directory, so each step is atomic and the whole is resumable: a
 * crash between two moves leaves [READY] in place, the next start finds the files it already moved
 * gone from staging and moves the rest, and only the final delete of [READY] retires the restore. The
 * live database's write-ahead log and journal are deleted *before* its file is replaced — SQLite
 * validates a WAL against its own header only, so a stale one would be replayed onto the restored file.
 *
 * Nothing is verified here; that was the stager's job, on the bytes it wrote. A staging directory whose
 * [MANIFEST] names a schema newer than this build (the app was downgraded between staging and the
 * restart) is discarded rather than installed, since Room has no way down.
 */
object RestoreApplier {
    private const val TAG = "RestoreApplier"

    /** Under `noBackupFilesDir`: never in a cloud backup, on the same filesystem as the targets. */
    const val STAGING_DIR = "restore-staging"

    /** Written last by the stager; its presence is the restore. */
    const val READY = "READY"

    /** The schema version of the staged database, as decimal text. */
    const val MANIFEST = "MANIFEST"

    /** Where a restore is staged on this phone. */
    fun stagingDir(context: Context): File = File(context.noBackupFilesDir, STAGING_DIR)

    /** The staged files, in the names the archive uses, and the live files they replace. */
    class Targets(
        val database: File,
        val identity: File,
        val databaseKey: File,
        val settings: File,
    )

    fun targets(context: Context): Targets =
        Targets(
            database = context.getDatabasePath(KnitDatabase.DB_NAME),
            identity = File(context.filesDir, IdentityKeyStore.FILE_NAME),
            databaseKey = File(context.filesDir, DatabaseKey.KEY_FILE),
            settings = context.preferencesDataStoreFile(SettingsKeys.DATASTORE_NAME),
        )

    /** Applies a staged restore if there is one; true when the app's files were replaced. */
    fun applyPending(context: Context): Boolean = apply(stagingDir(context), targets(context), KnitDatabase.SCHEMA_VERSION)

    internal fun apply(
        staging: File,
        targets: Targets,
        schemaVersion: Int,
    ): Boolean {
        if (!File(staging, READY).exists()) return false
        val staged =
            File(staging, MANIFEST)
                .takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.toIntOrNull()
        if (staged == null || staged > schemaVersion) {
            Log.w(TAG, "discarding a staged restore at schema $staged (this build is at $schemaVersion)")
            staging.deleteRecursively()
            return false
        }
        Log.i(TAG, "applying a staged restore")
        // The database first, with its sidecars gone before the file lands.
        listOf("-wal", "-shm", "-journal").forEach { File(targets.database.path + it).delete() }
        move(File(staging, BackupFormat.ENTRY_DATABASE), targets.database)
        move(File(staging, IdentityKeyStore.FILE_NAME), targets.identity)
        move(File(staging, DatabaseKey.KEY_FILE), targets.databaseKey)
        File(targets.settings.path + ".tmp").delete()
        move(File(staging, targets.settings.name), targets.settings)
        File(staging, READY).delete()
        staging.deleteRecursively()
        return true
    }

    /** Renames [from] over [to] when [from] is still there — a missing source is a step already taken. */
    private fun move(
        from: File,
        to: File,
    ) {
        if (!from.exists()) return
        to.parentFile?.mkdirs()
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
