package app.getknit.knit.data.backup

import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import java.io.File
import java.io.IOException

/**
 * Writes the backup's copy of the database: a fresh file at the current schema holding only the
 * [BackupTables.CARRIED] rows of the live one.
 *
 * Two connections, on purpose. [buildSchema] is Room: it creates the scratch file with the exact schema,
 * identity hash and FTS content-sync triggers the real database has, and is closed again at once.
 * [openRaw] is then the driver alone — one native connection, no pool — which is what makes `ATTACH`
 * usable: SQLite reports `ATTACH` as a read-only statement, so on the app's pooled live connection it
 * would land on a reader and the copy on the writer would never see it. The live file is attached to
 * the scratch connection and each table copied inside SQLite (`INSERT … SELECT`, column names spelled
 * out, since a migrated file's column order need not match a fresh one's) under a `SAVEPOINT`: a
 * deferred transaction that gives every table one read snapshot of the live database without taking
 * its write lock — a `BEGIN` here would, because the SQLCipher layer rewrites every `BEGIN` to
 * `EXCLUSIVE`, and that spans attached databases. The mesh keeps writing throughout.
 *
 * The result is checked (`quick_check`) and left in rollback-journal mode as one file, so the archive
 * carries it whole; the transient tables exist and are empty.
 */
class DatabaseExport(
    private val buildSchema: suspend (File) -> Unit,
    private val openRaw: (File) -> SQLiteConnection,
) {
    /**
     * Exports [live] to [dest] (replaced if present; its `-wal`/`-shm`/`-journal` siblings removed).
     * [passphrase] keys the attached live file — SQLCipher reads a bound blob as a passphrase exactly as
     * it reads the bytes the driver was opened with; an unkeyed SQLite ignores the clause.
     */
    suspend fun export(
        live: File,
        passphrase: ByteArray,
        dest: File,
    ) {
        clear(dest)
        buildSchema(dest)
        openRaw(dest).use { scratch ->
            scratch.prepare("ATTACH DATABASE ? AS live KEY ?").use { attach ->
                attach.bindText(1, live.absolutePath)
                attach.bindBlob(2, passphrase)
                attach.step()
            }
            try {
                scratch.execSQL("SAVEPOINT knit_export")
                var ok = false
                try {
                    for (table in BackupTables.CARRIED) copyTable(scratch, table)
                    ok = true
                } finally {
                    scratch.execSQL(if (ok) "RELEASE knit_export" else "ROLLBACK TO knit_export")
                    if (!ok) scratch.execSQL("RELEASE knit_export")
                }
            } finally {
                scratch.execSQL("DETACH DATABASE live")
            }
            scratch.execSQL("PRAGMA journal_mode=DELETE")
            val verdict = scratch.prepare("PRAGMA quick_check").use { if (it.step()) it.getText(0) else "" }
            if (verdict != "ok") throw IOException("exported database failed quick_check: $verdict")
        }
        if (File(dest.path + "-wal").exists() || File(dest.path + "-shm").exists()) {
            throw IOException("exported database left a write-ahead log")
        }
    }

    private fun copyTable(
        scratch: SQLiteConnection,
        table: String,
    ) {
        val columns =
            scratch.prepare("PRAGMA main.table_info($table)").use { info ->
                buildList {
                    while (info.step()) add(info.getText(NAME_COLUMN))
                }
            }
        require(columns.isNotEmpty()) { "no such table in the export schema: $table" }
        val list = columns.joinToString(", ") { "\"$it\"" }
        scratch.execSQL("INSERT INTO main.$table ($list) SELECT $list FROM live.$table")
    }

    private fun clear(dest: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(dest.path + it).delete() }
    }

    companion object {
        /** `PRAGMA table_info` columns: cid, name, type, notnull, dflt_value, pk. */
        private const val NAME_COLUMN = 1

        /**
         * A [buildSchema] body: opens [db] once (Room creates its tables, triggers and identity row on the
         * first connection) and closes it, so the file holds the schema and nothing holds the file.
         */
        suspend fun createSchema(db: RoomDatabase) {
            try {
                db.useReaderConnection { }
            } finally {
                db.close()
            }
        }
    }
}
