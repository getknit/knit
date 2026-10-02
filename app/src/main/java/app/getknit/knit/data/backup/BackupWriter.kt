package app.getknit.knit.data.backup

import android.content.Context
import android.os.storage.StorageManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.SQLiteConnection
import app.getknit.knit.BuildConfig
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.crypto.AndroidKeystoreCipher
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreCipher
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.SqlCipherKey
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.crypto.keystoreUnavailable
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Makes one backup: the identity file's plaintext, the database passphrase, the settings and the
 * database copy, sealed under a recovery key into whatever stream the user picked. The mesh keeps
 * running throughout — [DatabaseExport] reads the live database under a snapshot, never its write lock.
 *
 * The identity and the passphrase are read through the same wrap the app reads them through
 * ([KeystoreSecret], [DatabaseKey.current]) and held in memory until the archive is written; the only thing that
 * touches the disk in the clear is the database copy, under `noBackupFilesDir` (never in a cloud
 * backup) and deleted before this returns.
 */
class BackupWriter(
    private val context: Context,
    private val identity: Identity,
    private val identitySecret: KeystoreSecret,
    private val databaseKey: DatabaseKey,
    private val settings: SettingsStore,
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Writes a backup to [out] under [recoveryKey]. [onProgress] gets (bytes sealed, bytes to seal) —
     * the database dominates, so the export itself reports as an indeterminate first phase (0, 0).
     */
    suspend fun write(
        out: OutputStream,
        recoveryKey: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): BackupManifest {
        val scratch = File(context.noBackupFilesDir, SCRATCH_DIR)
        scratch.deleteRecursively()
        scratch.mkdirs()
        val live = context.getDatabasePath(KnitDatabase.DB_NAME)
        val needed = live.length() + File(live.path + "-wal").length() + SPACE_MARGIN
        // Allocatable counts the cache the system may clear for us; allocateBytes clears it now, not mid-export.
        val storage = context.getSystemService(StorageManager::class.java)
        val room =
            runCatching { storage?.getAllocatableBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
                ?: context.noBackupFilesDir.usableSpace
        if (room < needed) throw BackupException(BackupProblem.NO_SPACE, "need $needed B free")
        runCatching { storage?.allocateBytes(StorageManager.UUID_DEFAULT, needed) }
        val passphrase = secretBytes(databaseKey.current(), "database passphrase")
        try {
            val nodeId = keystoreRead("identity keys") { identity.nodeId() } // mints the identity if this phone never had one
            val identityBytes = secretBytes(identitySecret.read(), "identity file")
            try {
                val dbCopy = File(scratch, BackupFormat.ENTRY_DATABASE)
                onProgress(0, 0)
                DatabaseExport(
                    buildSchema = { DatabaseExport.createSchema(KnitDatabase.build(context, passphrase, it.absolutePath)) },
                    openRaw = { SqlCipherKey.open(it, passphrase) },
                ).export(live, passphrase, dbCopy)
                val settingsBytes = ByteArrayOutputStream().also { SettingsSnapshot.export(dataStore.data.first(), it) }.toByteArray()
                val createdAt = now()
                val manifest =
                    BackupManifest(
                        v = BackupFormat.FORMAT_VERSION,
                        schemaVersion = KnitDatabase.SCHEMA_VERSION,
                        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                        appVersionName = BuildConfig.VERSION_NAME,
                        createdAt = createdAt,
                        nodeId = nodeId,
                        displayName = settings.displayName.first().takeIf { it.isNotBlank() },
                        entries = emptyList(),
                    )
                val sources =
                    listOf(
                        BackupSource(BackupFormat.ENTRY_IDENTITY, identityBytes.size.toLong()) { ByteArrayInputStream(identityBytes) },
                        BackupSource(BackupFormat.ENTRY_DB_PASSPHRASE, passphrase.size.toLong()) { ByteArrayInputStream(passphrase) },
                        BackupSource(BackupFormat.ENTRY_SETTINGS, settingsBytes.size.toLong()) { ByteArrayInputStream(settingsBytes) },
                        BackupSource(BackupFormat.ENTRY_DATABASE, dbCopy.length()) { FileInputStream(dbCopy) },
                    )
                val total = sources.sumOf { it.size }
                return BackupArchive.write(
                    out,
                    recoveryKey,
                    BackupHeader(salt = BackupKeys.newSalt(), createdAt = createdAt),
                    manifest,
                    sources,
                    onProgress = { onProgress(it, total) },
                )
            } finally {
                identityBytes.fill(0)
            }
        } finally {
            passphrase.fill(0)
            scratch.deleteRecursively()
        }
    }

    /**
     * The bytes of a secret the live app already holds. Read, never [DatabaseKey.getOrCreate]: the database is open
     * beside this, and a refusal must fail the backup — never wipe the live database or mint a second passphrase
     * (ADR 2026-10.47rw). A refusal is [BackupProblem.KEYSTORE_UNAVAILABLE], worth another try; anything else is an
     * unreadable file.
     */
    private fun secretBytes(
        read: Unwrapped,
        what: String,
    ): ByteArray =
        when (read) {
            is Unwrapped.Present -> read.bytes
            is Unwrapped.Unavailable -> BackupArchive.fail(BackupProblem.KEYSTORE_UNAVAILABLE, "Keystore refused the $what", read.cause)
            is Unwrapped.Lost -> throw IOException("$what unreadable (${read.reason})", read.cause)
            Unwrapped.Absent -> throw IOException("no $what")
        }

    /** Runs [block], turning a Keystore refusal anywhere under it into [BackupProblem.KEYSTORE_UNAVAILABLE]. */
    private inline fun <T> keystoreRead(
        what: String,
        block: () -> T,
    ): T =
        runCatching(block).getOrElse { failure ->
            val refusal = failure.keystoreUnavailable() ?: throw failure
            BackupArchive.fail(BackupProblem.KEYSTORE_UNAVAILABLE, "Keystore refused the $what", refusal)
        }

    companion object {
        /** Under `noBackupFilesDir`; holds the database copy while the archive streams it out. */
        const val SCRATCH_DIR = "backup-scratch"

        /** Room's scratch schema plus headroom over the live file's own size. */
        private const val SPACE_MARGIN = 32L * 1024 * 1024

        /** The identity file, read through the same wrap [IdentityKeyStore] uses; DI passes this to the writer. */
        fun identitySecret(
            context: Context,
            cipher: KeystoreCipher = AndroidKeystoreCipher,
        ): KeystoreSecret = KeystoreSecret(context, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, cipher = cipher)

        /**
         * A SQLCipher file opened with the driver alone, under [passphrase]'s raw key — the stager's verifier.
         * A copy a build before ADR 2026-09.uzkm wrote is still on the passphrase key, so it is moved onto
         * the raw key first; the staged file is the stager's alone, so nothing else has it open.
         */
        fun openSqlCipher(
            file: File,
            passphrase: ByteArray,
        ): SQLiteConnection {
            System.loadLibrary("sqlcipher")
            SqlCipherKey.upgrade(file, passphrase)
            return SqlCipherKey.open(file, passphrase)
        }
    }
}
