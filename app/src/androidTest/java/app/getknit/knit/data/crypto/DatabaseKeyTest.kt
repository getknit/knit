package app.getknit.knit.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.backup.SandboxContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The two Keystore wraps, on a device. [DatabaseKey] is the destructive one: a wrap proven lost means the database
 * cannot be opened either, so it wipes the database and mints a new passphrase. Pinned here: a good wrap returns the
 * same passphrase every time and never wipes; a missing, truncated or corrupt wrap wipes the old files (sidecars
 * included), keeps the old wrap aside and leaves a fresh 32-byte passphrase that reads back; and a Keystore that
 * merely refuses — the debug [FaultyKeystoreCipher] over the real one, throwing what keystore2 threw on the lab
 * Pixel 3 — wipes nothing at all (ADR 2026-10.47rw). [KeystoreSecret] reads a bad file as [Unwrapped.Lost] with the
 * reason, under a test-only alias that is deleted afterwards.
 *
 * Sandboxed ([SandboxContext]): the database and key files are a temp directory's; the Keystore alias
 * [DatabaseKey] uses is the live one, reused and never replaced.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyTest {
    private val root =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "dbkey-${System.nanoTime()}")
    private val context = SandboxContext(InstrumentationRegistry.getInstrumentation().targetContext, root)
    private val database = context.getDatabasePath(KnitDatabase.DB_NAME)
    private val keyFile = File(context.filesDir, DatabaseKey.KEY_FILE)
    private val lostFile = File(context.noBackupFilesDir, DatabaseKey.KEY_FILE + KeystoreSecret.LOST_SUFFIX)
    private val faults = File(root, FaultyKeystoreCipher.MARKER)

    @After
    fun tearDown() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
    }

    /** The live alias through the real Keystore, failing the next N unwraps while [faults] holds N; no waits. */
    private fun databaseKey(): DatabaseKey =
        DatabaseKey(
            context,
            KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, cipher = FaultyKeystoreCipher(faults), sleep = {}),
        )

    private fun secret(): KeystoreSecret = KeystoreSecret(context, TEST_ALIAS, "test.secret", sleep = {})

    /** The database files a wipe must remove, written as plain sentinels. */
    private fun staleDatabase() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(database.path + suffix).writeText("stale")
    }

    private fun assertWiped() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) assertFalse("$suffix survived", File(database.path + suffix).exists())
    }

    private fun assertUntouched() {
        for (suffix in listOf(
            "",
            "-wal",
            "-shm",
            "-journal",
        )) {
            assertEquals("$suffix touched", "stale", File(database.path + suffix).readText())
        }
    }

    @Test
    fun aFirstRunWipesWhateverIsThereAndMintsAPassphrase() {
        staleDatabase()
        val passphrase = databaseKey().getOrCreate()
        assertEquals(DatabaseKey.PASSPHRASE_BYTES, passphrase.size)
        assertWiped()
        assertTrue(keyFile.exists())
        assertFalse("nothing was lost, so nothing is kept aside", lostFile.exists())
    }

    @Test
    fun aGoodWrapReturnsTheSamePassphraseAndNeverWipes() {
        val first = databaseKey().getOrCreate()
        staleDatabase()
        val second = databaseKey().getOrCreate()
        assertArrayEquals(first, second)
        assertUntouched()
    }

    @Test
    fun aTruncatedWrapWipesKeepsItAsideAndStartsOver() {
        databaseKey().getOrCreate()
        keyFile.writeBytes(ByteArray(8))
        staleDatabase()
        val fresh = databaseKey().getOrCreate()
        assertWiped()
        assertArrayEquals(ByteArray(8), lostFile.readBytes())
        assertArrayEquals(fresh, databaseKey().getOrCreate())
    }

    @Test
    fun aCorruptWrapWipesKeepsItAsideAndStartsOver() {
        val old = databaseKey().getOrCreate()
        val corrupt = keyFile.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        keyFile.writeBytes(corrupt)
        staleDatabase()
        val fresh = databaseKey().getOrCreate()
        assertWiped()
        assertArrayEquals(corrupt, lostFile.readBytes())
        assertFalse(old.contentEquals(fresh))
        assertArrayEquals(fresh, databaseKey().getOrCreate())
    }

    @Test
    fun aKeystoreRefusalNeverWipes() {
        val passphrase = databaseKey().getOrCreate()
        val wrap = keyFile.readBytes()
        staleDatabase()

        faults.writeText("100")
        assertThrows(KeystoreUnavailableException::class.java) { databaseKey().getOrCreate() }
        assertUntouched()
        assertArrayEquals("the wrap is untouched", wrap, keyFile.readBytes())
        assertFalse(lostFile.exists())

        // A refusal the retries outlast is no refusal at all: two failed unwraps, then the same passphrase.
        faults.writeText("2")
        assertArrayEquals(passphrase, databaseKey().getOrCreate())
        assertEquals("0", faults.readText())
        assertUntouched()
    }

    @Test
    fun aKeystoreSecretRoundTripsAndReadsABadFileAsLost() {
        val secret = secret()
        assertSame(Unwrapped.Absent, secret.read())

        secret.store(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), (secret.read() as Unwrapped.Present).bytes)
        secret.store(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), (secret.read() as Unwrapped.Present).bytes)

        val file = File(context.filesDir, "test.secret")
        file.writeBytes(ByteArray(12))
        assertEquals("short of an IV and a tag", Loss.MALFORMED, (secret.read() as Unwrapped.Lost).reason)
        file.writeBytes(ByteArray(64) { 7 })
        assertEquals("not ours", Loss.TAG_MISMATCH, (secret.read() as Unwrapped.Lost).reason)
    }

    @Test
    fun aWrapIsBoundToItsAlias() {
        val secret = secret()
        secret.store(byteArrayOf(9, 9))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
        assertEquals("the key is gone, so the wrap is lost", Loss.KEY_MISSING, (secret.read() as Unwrapped.Lost).reason)
    }

    private companion object {
        const val TEST_ALIAS = "knit_test_secret"
    }
}
