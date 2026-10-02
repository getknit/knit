package app.getknit.knit.data.crypto

import android.content.Context
import android.util.Log
import java.io.File
import java.security.SecureRandom

/**
 * Supplies the SQLCipher passphrase for [app.getknit.knit.data.KnitDatabase].
 *
 * A random 256-bit passphrase is generated on first run and stored on disk wrapped (AES-256-GCM) by a
 * hardware-backed key held in the AndroidKeyStore, through a [KeystoreSecret] under [KEY_ALIAS]. The Keystore key
 * never leaves the device and is excluded from cloud backup, so the encrypted DB cannot be decrypted off-device
 * even if the wrapped passphrase file and the database are copied elsewhere.
 *
 * SQLCipher is keyed with these bytes as a **raw key** ([SqlCipherKey.raw]), not as a passphrase through its
 * KDF: they are already uniformly random, and the KDF cost ~850 ms to ~4.2 s per pooled connection
 * (ADR 2026-09.uzkm).
 *
 * Opening is transparent — no user authentication is required. What a failed unwrap does depends on what it
 * proves (ADR 2026-10.47rw):
 * - **no wrap file** (first run, or the first encryption over an old build's plaintext `knit.db`, which SQLCipher
 *   cannot open): the database files are deleted and a passphrase is minted;
 * - **proven lost** ([Unwrapped.Lost] — the key gone, the tag rejected, the file malformed): the passphrase is
 *   gone and with it every byte of the database, so the same, after the old wrap is kept aside
 *   ([KeystoreSecret.keepAside]);
 * - **refused** ([Unwrapped.Unavailable]): nothing is touched and [KeystoreUnavailableException] is thrown — the
 *   open fails, and the next one tries again. This used to wipe too: a keystore2 KM_ERROR_UNKNOWN_ERROR on a
 *   locked Pixel 3 deleted its database on 2026-10-01.
 *
 * The wrap file's layout (`iv ‖ AES-256-GCM ciphertext` under [KEY_ALIAS]) is [KeystoreSecret]'s, on purpose: a
 * backup restore installs a passphrase it carried by writing it through
 * `KeystoreSecret(context, KEY_ALIAS, KEY_FILE, dir)` and moving that file into place, and this class reads it
 * as its own (`app.getknit.knit.data.backup.RestoreStager`).
 */
class DatabaseKey(
    private val context: Context,
    private val secret: KeystoreSecret = KeystoreSecret(context, KEY_ALIAS, KEY_FILE),
) {
    /**
     * Returns the 32-byte SQLCipher passphrase, generating and persisting it on first use. Throws
     * [KeystoreUnavailableException], with every file as it was, when the Keystore refuses.
     */
    @Synchronized
    fun getOrCreate(): ByteArray =
        when (val read = secret.read()) {
            is Unwrapped.Present -> {
                checkLength(read.bytes)
            }

            Unwrapped.Absent -> {
                // First encryption: whatever is on disk is plaintext from an old build, which SQLCipher cannot open.
                wipeDatabase()
                mint(freshKey = false)
            }

            is Unwrapped.Lost -> {
                Log.w(TAG, "DB passphrase lost (${read.reason}); wiping and regenerating", read.cause)
                secret.keepAside()
                wipeDatabase()
                mint(freshKey = read.reason == Loss.KEY_INVALIDATED)
            }

            is Unwrapped.Unavailable -> {
                throw KeystoreUnavailableException("database passphrase", read.cause)
            }
        }

    /**
     * The passphrase as it stands, read without ever wiping or minting — for a reader beside the open database
     * (the backup writer), where a refusal must be an error and never a fresh, empty database.
     */
    @Synchronized
    fun current(): Unwrapped = secret.read()

    /**
     * A wrap that verified but holds the wrong number of bytes was written by *some* Knit build under our key:
     * a code or version mismatch, not a lost key. Loud, and nothing is wiped.
     */
    private fun checkLength(passphrase: ByteArray): ByteArray =
        passphrase.also { check(it.size == PASSPHRASE_BYTES) { "unwrapped DB passphrase is ${it.size} bytes; not wiping" } }

    /** Mints a passphrase and proves its wrap reads back before anything is keyed with it. */
    private fun mint(freshKey: Boolean): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        runCatching { secret.store(passphrase, freshKey) }
            .onFailure { throw KeystoreUnavailableException("new database passphrase", it) }
        // A Keystore that seals but never opens again would otherwise have the next start read the wrap as lost and
        // wipe the database this one is about to fill. Read back now, and fail the open if it does not.
        val back = secret.read()
        val same = back is Unwrapped.Present && back.bytes.contentEquals(passphrase)
        if (back is Unwrapped.Present) back.bytes.fill(0)
        if (!same) throw KeystoreUnavailableException("new database passphrase", (back as? Unwrapped.Unavailable)?.cause)
        return passphrase
    }

    /** Deletes the database and its WAL/SHM/journal sidecars so it can be recreated encrypted. */
    private fun wipeDatabase() {
        val dbPath = context.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val file = File(dbPath.parentFile, dbPath.name + suffix)
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Could not delete stale DB file ${file.name}")
            }
        }
    }

    internal companion object {
        const val TAG = "DatabaseKey"
        const val KEY_ALIAS = "knit_db_key"
        const val KEY_FILE = "db.key"
        const val DB_NAME = "knit.db"

        /** The passphrase is 32 random bytes — what a backup carries and a restore hands back to [KeystoreSecret]. */
        const val PASSPHRASE_BYTES = 32
    }
}
