package app.getknit.knit.data.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.backup.SandboxContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * The two Keystore wraps, on a device. [DatabaseKey] is the destructive one: a wrap it cannot open means the
 * database cannot be opened either, so it wipes the database and mints a new passphrase. Pinned here: a good
 * wrap returns the same passphrase every time and never wipes; a missing, truncated or corrupt wrap wipes the
 * old files (sidecars included) and leaves a fresh 32-byte passphrase that reads back. [KeystoreSecret] never
 * throws on a bad file — it reads as absent — under a test-only alias that is deleted afterwards.
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

    @After
    fun tearDown() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
    }

    /** The database files a wipe must remove, written as plain sentinels. */
    private fun staleDatabase() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(database.path + suffix).writeText("stale")
    }

    private fun assertWiped() {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) assertFalse("$suffix survived", File(database.path + suffix).exists())
    }

    @Test
    fun aFirstRunWipesWhateverIsThereAndMintsAPassphrase() {
        staleDatabase()
        val passphrase = DatabaseKey(context).getOrCreate()
        assertEquals(DatabaseKey.PASSPHRASE_BYTES, passphrase.size)
        assertWiped()
        assertTrue(keyFile.exists())
    }

    @Test
    fun aGoodWrapReturnsTheSamePassphraseAndNeverWipes() {
        val first = DatabaseKey(context).getOrCreate()
        staleDatabase()
        val second = DatabaseKey(context).getOrCreate()
        assertArrayEquals(first, second)
        assertTrue("a good wrap must not touch the database", database.exists())
    }

    @Test
    fun aTruncatedWrapWipesAndStartsOver() {
        DatabaseKey(context).getOrCreate()
        keyFile.writeBytes(ByteArray(8))
        staleDatabase()
        val fresh = DatabaseKey(context).getOrCreate()
        assertWiped()
        assertArrayEquals(fresh, DatabaseKey(context).getOrCreate())
    }

    @Test
    fun aCorruptWrapWipesAndStartsOver() {
        val old = DatabaseKey(context).getOrCreate()
        keyFile.writeBytes(keyFile.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        staleDatabase()
        val fresh = DatabaseKey(context).getOrCreate()
        assertWiped()
        assertFalse(old.contentEquals(fresh))
        assertArrayEquals(fresh, DatabaseKey(context).getOrCreate())
    }

    @Test
    fun aKeystoreSecretRoundTripsAndReadsABadFileAsAbsent() {
        val secret = KeystoreSecret(context, TEST_ALIAS, "test.secret")
        assertNull(secret.load())
        assertFalse(secret.exists())

        secret.store(byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), secret.load())
        secret.store(byteArrayOf(4))
        assertArrayEquals(byteArrayOf(4), secret.load())

        val file = File(context.filesDir, "test.secret")
        file.writeBytes(ByteArray(12))
        assertNull("short of an IV and a tag", secret.load())
        file.writeBytes(ByteArray(64) { 7 })
        assertNull("not ours", secret.load())

        secret.delete()
        assertFalse(secret.exists())
    }

    @Test
    fun aWrapIsBoundToItsAlias() {
        val secret = KeystoreSecret(context, TEST_ALIAS, "test.secret")
        secret.store(byteArrayOf(9, 9))
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_ALIAS)
        assertNull("the key is gone, so the wrap is unreadable", secret.load())
    }

    private companion object {
        const val TEST_ALIAS = "knit_test_secret"
    }
}
