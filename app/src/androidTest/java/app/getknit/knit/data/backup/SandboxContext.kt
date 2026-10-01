package app.getknit.knit.data.backup

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * The app's context with every directory the backup code reaches for moved under [root]: `filesDir` (the
 * wrapped key files), `noBackupFilesDir` (the staging and scratch directories) and the database path. So a
 * test can run the real writer, stager and [app.getknit.knit.data.crypto.DatabaseKey] — real Keystore, real
 * SQLCipher — without touching the installed app's `knit.db`, `db.key`, `identity.key` or a pending restore.
 * The Keystore aliases are the live ones, as in `DatabaseExportSqlCipherTest`: a key that exists is reused,
 * never replaced, so the live wraps keep opening.
 */
class SandboxContext(
    base: Context,
    private val root: File,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

    override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }

    /** An absolute [name] is returned as it is, as the platform's own `getDatabasePath` does (the export's scratch schema is one). */
    override fun getDatabasePath(name: String): File =
        if (name.startsWith(File.separator)) File(name) else File(File(root, "databases").apply { mkdirs() }, name)
}
