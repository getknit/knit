package app.getknit.knit.data.crypto

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.sqlite.SQLiteConnection
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import java.io.Closeable
import java.io.File

/**
 * How [DatabaseKey]'s passphrase keys SQLCipher: as a **raw key**, never through SQLCipher's KDF.
 *
 * Handed to SQLCipher as plain bytes, the passphrase goes through PBKDF2-HMAC-SHA512 at 256,000 iterations
 * on *every* connection the pool opens, and the pool opens them while holding its own lock — so a cold
 * start's first queries wait for every derivation in turn (~850 ms each on a Pixel 3, ~4.2 s on a 32-bit
 * Galaxy A10, eleven of them before the chat list could draw). The passphrase is already 32 uniformly
 * random bytes, so a KDF adds nothing to it; SQLCipher's `x'<64 hex>'` form uses those bytes as the key
 * itself and skips the derivation. ADR 2026-09.uzkm.
 *
 * Every database a build before that ADR wrote is keyed the old way, so [upgrade] moves a file onto the
 * raw key once, in place, before anything else opens it.
 */
internal object SqlCipherKey {
    private const val TAG = "SqlCipherKey"

    /** Where [upgrade] found the file, and what it did about it. */
    enum class Form {
        /** No file yet: Room creates it under the raw key. */
        ABSENT,

        /** Already on the raw key. The steady state — one cheap open, no derivation. */
        RAW,

        /** Was on the passphrase; now rekeyed onto the raw key (one derivation, once per install). */
        REKEYED,

        /** Opens under neither form. Left exactly as found, never wiped: Room's own open fails loudly. */
        UNREADABLE,
    }

    /**
     * [passphrase] in SQLCipher's raw-key form: the ASCII of `x'<64 lowercase hex>'`. Built straight into a
     * byte array, never a `String`, so the key has no immutable copy on the heap. The caller owns the result.
     */
    fun raw(passphrase: ByteArray): ByteArray {
        require(passphrase.size == DatabaseKey.PASSPHRASE_BYTES) { "raw key needs ${DatabaseKey.PASSPHRASE_BYTES} bytes" }
        val out = ByteArray(passphrase.size * 2 + RAW_FRAME)
        out[0] = 'x'.code.toByte()
        out[1] = '\''.code.toByte()
        passphrase.forEachIndexed { i, b ->
            val at = 2 + i * 2
            out[at] = HEX[(b.toInt() shr NIBBLE_BITS) and NIBBLE_MASK]
            out[at + 1] = HEX[b.toInt() and NIBBLE_MASK]
        }
        out[out.size - 1] = '\''.code.toByte()
        return out
    }

    /**
     * Moves [file] onto the raw key if it is still keyed with [passphrase] through the KDF. Runs before
     * Room (or anything else) has the file open: it needs the only connection. A raw open that fails costs
     * no derivation, so the steady state pays one plain open; only a file on the old key pays the KDF, once.
     *
     * The rekey runs under a rollback journal: a connection opened without `ENABLE_WRITE_AHEAD_LOGGING`
     * puts the file in `delete` mode on open (folding any WAL in), exactly as Room's own driver open does
     * before Room turns WAL back on — so SQLCipher's rekey rewrites every page in one journalled transaction,
     * and a crash part-way leaves the file on the old key, where the next start finds it again.
     */
    fun upgrade(
        file: File,
        passphrase: ByteArray,
        open: KeyedOpen = NativeOpen,
    ): Form {
        if (!file.exists()) return Form.ABSENT
        val raw = raw(passphrase)
        try {
            if (runCatching { open.open(file, raw).close() }.isSuccess) return Form.RAW
            val legacy =
                runCatching { open.open(file, passphrase) }.getOrElse {
                    Log.w(TAG, "${file.name} opens under neither key form; leaving it for Room to refuse", it)
                    return Form.UNREADABLE
                }
            legacy.use { it.rekey(raw) }
            Log.i(TAG, "${file.name} rekeyed onto the raw key")
            return Form.REKEYED
        } finally {
            raw.fill(0)
        }
    }

    /** A single-connection SQLCipher open of [file] under [passphrase]'s raw key — the backup's copies. */
    fun open(
        file: File,
        passphrase: ByteArray,
    ): SQLiteConnection = SQLCipherDriver(raw(passphrase), null, KeepFiles).open(file.absolutePath)

    /** The seam [upgrade] opens through, so its decisions test on the JVM with no `libsqlcipher.so`. */
    fun interface KeyedOpen {
        /** Opens [file] keyed with [key] exactly as given, throwing if the key does not read it. */
        fun open(
            file: File,
            key: ByteArray,
        ): KeyedFile
    }

    /** An open, keyed file; [rekey] re-encrypts every page under [key]. */
    interface KeyedFile : Closeable {
        fun rekey(key: ByteArray)
    }

    private object NativeOpen : KeyedOpen {
        override fun open(
            file: File,
            key: ByteArray,
        ): KeyedFile {
            // Keying is verified inside the open (SQLCipher reads sqlite_schema), so a wrong key throws here.
            val db = SQLiteDatabase.openDatabase(file.absolutePath, key, null, SQLiteDatabase.OPEN_READWRITE, KeepFiles, null)
            return object : KeyedFile {
                override fun rekey(key: ByteArray) = db.changePassword(key)

                override fun close() = db.close()
            }
        }
    }

    /**
     * A wrong key reads as corruption (`SQLITE_NOTADB`). SQLCipher's default handler already declines to
     * delete a file in a codec build, but a probe that is *expected* to fail says so itself.
     */
    private object KeepFiles : DatabaseErrorHandler {
        override fun onCorruption(
            dbObj: SQLiteDatabase?,
            exception: SQLiteException?,
        ) = Unit
    }

    /** `x'` + `'`. */
    private const val RAW_FRAME = 3
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0f
    private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
}
