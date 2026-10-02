package app.getknit.knit.data.backup

import android.content.Context
import androidx.sqlite.SQLiteConnection
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.crypto.DatabaseKey
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.settings.SettingsKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads a backup into the staging directory [RestoreApplier] installs from, and proves every piece
 * before it writes [RestoreApplier.READY] — because the two readers on the other side fail
 * *destructively*: [DatabaseKey] wipes the database on a passphrase whose wrap is proven lost, and
 * [IdentityKeyStore] mints a fresh identity over one. So the secrets are wrapped here,
 * under the live Keystore aliases, into the staging directory, read back and compared; the identity is
 * parsed and its node id checked against the manifest; the database is opened under the passphrase and
 * integrity-checked; the settings are parsed. The plaintext identity and passphrase live in memory for
 * the duration and never touch the disk.
 *
 * [openDatabase] opens a SQLCipher file under a passphrase with the driver alone (the real one in the
 * app, an unkeyed driver in tests). [secretAt] is the wrap a staged secret goes through (alias, file name,
 * directory) — the Keystore's in the app, a stand-in on the JVM, where there is no AndroidKeyStore.
 */
class RestoreStager(
    private val context: Context,
    private val openDatabase: (File, ByteArray) -> SQLiteConnection,
    private val secretAt: (String, String, File) -> KeystoreSecret = { alias, fileName, dir ->
        KeystoreSecret(context, alias, fileName, dir)
    },
) {
    /** What the backup is, from its manifest alone — the summary the restore screen shows before asking. */
    fun peek(
        input: InputStream,
        recoveryKey: String,
    ): BackupManifest {
        var seen: BackupManifest? = null
        BackupArchive.read(input, recoveryKey, onManifest = {
            seen = it
            false
        }, sink = { throw IllegalStateException("no entry is read on a peek") })
        return checkNotNull(seen)
    }

    /**
     * Decrypts [input] under [recoveryKey] into the staging directory, verifies it, and marks it ready.
     * [onProgress] gets (bytes read, bytes in the archive). Throws a [BackupException] with the problem
     * to name; on any failure the staging directory is removed.
     */
    suspend fun stage(
        input: InputStream,
        recoveryKey: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): BackupManifest {
        val staging = RestoreApplier.stagingDir(context)
        staging.deleteRecursively()
        staging.mkdirs()
        val identity = ByteArrayOutputStream()
        val passphrase = ByteArrayOutputStream()
        val settings = ByteArrayOutputStream()
        var manifest: BackupManifest? = null
        try {
            var total = 0L
            BackupArchive.read(
                input,
                recoveryKey,
                onManifest = {
                    checkManifest(it)
                    total = it.entries.sumOf { e -> e.size }
                    manifest = it
                    true
                },
                sink = { entry -> sinkFor(entry, staging, identity, passphrase, settings) },
                onProgress = { onProgress(it, total) },
            )
            val seen = checkNotNull(manifest)
            val identityBytes = identity.toByteArray()
            val passphraseBytes = passphrase.toByteArray()
            try {
                verifyAndWrap(staging, seen, identityBytes, passphraseBytes, settings.toByteArray())
            } finally {
                identityBytes.fill(0)
                passphraseBytes.fill(0)
            }
            File(staging, RestoreApplier.MANIFEST).writeText("${seen.schemaVersion}\n")
            File(staging, RestoreApplier.READY).writeText("")
            return seen
        } catch (e: IOException) {
            staging.deleteRecursively()
            throw e
        } finally {
            identity.reset()
            passphrase.reset()
        }
    }

    private fun checkManifest(manifest: BackupManifest) {
        BackupArchive.expect(manifest.schemaVersion <= KnitDatabase.SCHEMA_VERSION, BackupProblem.NEWER_APP) {
            "backup at schema ${manifest.schemaVersion}, this build reads ${KnitDatabase.SCHEMA_VERSION}"
        }
        val missing = REQUIRED.filter { manifest.entry(it) == null }
        BackupArchive.expect(missing.isEmpty(), BackupProblem.NOT_A_BACKUP) { "no ${missing.joinToString()} entry" }
    }

    /**
     * The chain the app's own readers will walk, walked here first; every failure is a [BackupProblem.MISMATCH],
     * except this phone's Keystore refusing a wrap ([BackupProblem.KEYSTORE_UNAVAILABLE]).
     */
    private suspend fun verifyAndWrap(
        staging: File,
        manifest: BackupManifest,
        identityBytes: ByteArray,
        passphraseBytes: ByteArray,
        settingsBytes: ByteArray,
    ) {
        BackupArchive.expect(passphraseBytes.size == DatabaseKey.PASSPHRASE_BYTES, BackupProblem.MISMATCH) { "passphrase length" }
        val nodeId =
            runCatching { IdentityKeyStore.nodeIdOf(identityBytes) }
                .getOrElse { BackupArchive.fail(BackupProblem.MISMATCH, "identity does not parse", it) }
        BackupArchive.expect(nodeId == manifest.nodeId, BackupProblem.MISMATCH) { "identity is not the manifest's" }
        wrap(staging, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, identityBytes)
        wrap(staging, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, passphraseBytes)
        checkDatabase(File(staging, BackupFormat.ENTRY_DATABASE), passphraseBytes, manifest.schemaVersion)
        val prefs =
            runCatching { SettingsSnapshot.forRestore(ByteArrayInputStream(settingsBytes)) }
                .getOrElse { BackupArchive.fail(BackupProblem.MISMATCH, "settings do not parse", it) }
        val prefsBytes = ByteArrayOutputStream().also { SettingsSnapshot.write(prefs, it) }.toByteArray()
        File(staging, "${SettingsKeys.DATASTORE_NAME}.preferences_pb").writeBytes(prefsBytes)
    }

    /** Whether a staged, verified restore is waiting for the restart. */
    fun isStaged(): Boolean = File(RestoreApplier.stagingDir(context), RestoreApplier.READY).exists()

    /** Drops a staged restore the user backed out of. */
    fun discard() {
        RestoreApplier.stagingDir(context).deleteRecursively()
    }

    private fun sinkFor(
        entry: BackupEntry,
        staging: File,
        identity: ByteArrayOutputStream,
        passphrase: ByteArrayOutputStream,
        settings: ByteArrayOutputStream,
    ): OutputStream =
        when (entry.name) {
            BackupFormat.ENTRY_IDENTITY -> bounded(identity, entry, MAX_SMALL_ENTRY)
            BackupFormat.ENTRY_DB_PASSPHRASE -> bounded(passphrase, entry, MAX_SMALL_ENTRY)
            BackupFormat.ENTRY_SETTINGS -> bounded(settings, entry, MAX_SETTINGS_ENTRY)
            BackupFormat.ENTRY_DATABASE -> FileOutputStream(File(staging, BackupFormat.ENTRY_DATABASE))
            else -> BackupArchive.fail(BackupProblem.NOT_A_BACKUP, "unknown entry ${entry.name}")
        }

    private fun bounded(
        sink: OutputStream,
        entry: BackupEntry,
        max: Long,
    ): OutputStream {
        BackupArchive.expect(entry.size <= max, BackupProblem.NOT_A_BACKUP) { "${entry.name} is ${entry.size} B" }
        return sink
    }

    /**
     * Wraps [plain] under [alias] into [staging]/[fileName] and proves the wrap reads back. A wrap that fails or
     * reads back refused is this phone's Keystore, never the file, so it is [BackupProblem.KEYSTORE_UNAVAILABLE];
     * a read-back that proves anything else wrong is a [BackupProblem.MISMATCH] (ADR 2026-10.47rw).
     */
    private fun wrap(
        staging: File,
        alias: String,
        fileName: String,
        plain: ByteArray,
    ) {
        val secret = secretAt(alias, fileName, staging)
        // A Keystore refusal is a GeneralSecurityException, which the restore screen does not catch.
        runCatching { secret.store(plain) }
            .onFailure { BackupArchive.fail(BackupProblem.KEYSTORE_UNAVAILABLE, "$fileName did not wrap", it) }
        when (val back = secret.read()) {
            is Unwrapped.Present -> {
                val same = back.bytes.contentEquals(plain)
                back.bytes.fill(0)
                BackupArchive.expect(same, BackupProblem.MISMATCH) { "$fileName did not read back" }
            }

            is Unwrapped.Unavailable -> {
                BackupArchive.fail(BackupProblem.KEYSTORE_UNAVAILABLE, "$fileName did not read back", back.cause)
            }

            is Unwrapped.Lost -> {
                BackupArchive.fail(BackupProblem.MISMATCH, "$fileName did not read back (${back.reason})", back.cause)
            }

            Unwrapped.Absent -> {
                BackupArchive.fail(BackupProblem.MISMATCH, "$fileName was not written")
            }
        }
    }

    private fun checkDatabase(
        file: File,
        passphrase: ByteArray,
        schemaVersion: Int,
    ) {
        val connection =
            runCatching { openDatabase(file, passphrase) }
                .getOrElse { BackupArchive.fail(BackupProblem.MISMATCH, "database does not open under its passphrase", it) }
        connection.use { c ->
            val version = c.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else -1L }
            BackupArchive.expect(version == schemaVersion.toLong(), BackupProblem.MISMATCH) {
                "database at schema $version, manifest says $schemaVersion"
            }
            val verdict = c.prepare("PRAGMA quick_check").use { if (it.step()) it.getText(0) else "" }
            BackupArchive.expect(verdict == "ok", BackupProblem.MISMATCH) { "database failed quick_check: $verdict" }
        }
    }

    private companion object {
        val REQUIRED =
            listOf(
                BackupFormat.ENTRY_IDENTITY,
                BackupFormat.ENTRY_DB_PASSPHRASE,
                BackupFormat.ENTRY_SETTINGS,
                BackupFormat.ENTRY_DATABASE,
            )
        const val MAX_SMALL_ENTRY = 64L * 1024
        const val MAX_SETTINGS_ENTRY = 4L * 1024 * 1024
    }
}
