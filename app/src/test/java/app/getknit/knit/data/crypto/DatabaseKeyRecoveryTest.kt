package app.getknit.knit.data.crypto

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.security.InvalidKeyException
import java.security.ProviderException

/**
 * What [DatabaseKey] does with each verdict, on the JVM over a scripted [FakeKeystoreCipher] (the instrumented
 * `DatabaseKeyTest` runs the same rules against the real Keystore). The case this exists for — issue #110, ADR
 * 2026-10.47rw: a Keystore that refuses on every attempt leaves the database, its sidecars and the wrap exactly as
 * they were and throws [KeystoreUnavailableException]. Only a proven loss wipes, after keeping the old wrap aside.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseKeyRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = FakeKeystoreCipher()
    private val database = context.getDatabasePath(DatabaseKey.DB_NAME)
    private val keyFile = File(context.filesDir, DatabaseKey.KEY_FILE)
    private val lostFile = File(context.noBackupFilesDir, DatabaseKey.KEY_FILE + KeystoreSecret.LOST_SUFFIX)
    private val secret = KeystoreSecret(context, DatabaseKey.KEY_ALIAS, DatabaseKey.KEY_FILE, cipher = cipher, sleep = {})
    private val databaseKey = DatabaseKey(context, secret)

    @After
    fun tearDown() {
        for (suffix in SUFFIXES) File(database.path + suffix).delete()
        keyFile.delete()
        lostFile.delete()
    }

    private fun refusal() = InvalidKeyException("Keystore operation failed", ProviderException("KM_ERROR_UNKNOWN_ERROR"))

    private fun staleDatabase() {
        database.parentFile!!.mkdirs()
        for (suffix in SUFFIXES) File(database.path + suffix).writeText("stale")
    }

    private fun assertWiped() {
        for (suffix in SUFFIXES) assertFalse("$suffix survived", File(database.path + suffix).exists())
    }

    private fun assertUntouched() {
        for (suffix in SUFFIXES) assertEquals("$suffix touched", "stale", File(database.path + suffix).readText())
    }

    @Test
    fun aRefusalWipesNothingAndThrows() {
        val passphrase = databaseKey.getOrCreate()
        val wrap = keyFile.readBytes()
        staleDatabase()
        repeat(3) { cipher.openScript += refusal() }

        val thrown = assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
        assertTrue(thrown.cause is InvalidKeyException)
        assertUntouched()
        assertArrayEquals(wrap, keyFile.readBytes())
        assertFalse(lostFile.exists())
        assertEquals(1, cipher.generated)

        // The next open, the Keystore answering again: the same passphrase over the same database.
        assertArrayEquals(passphrase, databaseKey.getOrCreate())
        assertUntouched()
    }

    @Test
    fun noWrapWipesThePlaintextDatabaseAndMints() {
        staleDatabase()
        val passphrase = databaseKey.getOrCreate()
        assertEquals(DatabaseKey.PASSPHRASE_BYTES, passphrase.size)
        assertWiped()
        assertArrayEquals(passphrase, databaseKey.getOrCreate())
    }

    @Test
    fun aProvenLossKeepsTheWrapAsideWipesAndMintsUnderTheSameKey() {
        val old = databaseKey.getOrCreate()
        val wrap = keyFile.readBytes()
        cipher.keys[DatabaseKey.KEY_ALIAS] = FakeKeystoreCipher.newKey()
        staleDatabase()

        val fresh = databaseKey.getOrCreate()
        assertWiped()
        assertArrayEquals(wrap, lostFile.readBytes())
        assertFalse(old.contentEquals(fresh))
        assertEquals("a tag mismatch keeps the alias's key", 1, cipher.generated)
        assertArrayEquals(fresh, databaseKey.getOrCreate())
    }

    @Test
    fun anInvalidatedKeyMintsUnderAFreshOne() {
        databaseKey.getOrCreate()
        repeat(3) { cipher.openScript += KeyPermanentlyInvalidatedException() }
        staleDatabase()
        databaseKey.getOrCreate()
        assertWiped()
        assertEquals(2, cipher.generated)
    }

    @Test
    fun aMintThatDoesNotReadBackFailsTheOpen() {
        staleDatabase()
        repeat(3) { cipher.openScript += refusal() }
        assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
    }

    @Test
    fun aMintTheKeystoreRefusesToWrapFailsTheOpen() {
        cipher.keyScript += refusal()
        assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
        assertFalse(keyFile.exists())
    }

    @Test
    fun aWrapOfTheWrongLengthIsABugNotALoss() {
        secret.store(ByteArray(16))
        staleDatabase()
        assertThrows(IllegalStateException::class.java) { databaseKey.getOrCreate() }
        assertUntouched()
    }

    @Test
    fun currentNeverWipesOrMints() {
        staleDatabase()
        assertSame(Unwrapped.Absent, databaseKey.current())
        assertFalse(keyFile.exists())
        assertUntouched()

        databaseKey.getOrCreate()
        staleDatabase()
        repeat(3) { cipher.openScript += refusal() }
        assertTrue(databaseKey.current() is Unwrapped.Unavailable)
        cipher.keys.clear()
        assertTrue(databaseKey.current() is Unwrapped.Lost)
        assertUntouched()
        assertTrue(keyFile.exists())
    }

    private companion object {
        val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
    }
}
