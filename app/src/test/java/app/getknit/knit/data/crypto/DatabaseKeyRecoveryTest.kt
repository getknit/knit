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
 * And #351, the 2026-11-19 amendment: a StrongBox that refuses to make a key is not a Keystore that refuses — the
 * passphrase is minted under a TEE key, and a fresh install opens.
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

    /** The Xiaomi 14's StrongBox, 2026-11-19: there, and UNIMPLEMENTED for every key it is asked to make. */
    private fun strongBoxRefusal() = ProviderException("Keystore key generation failed", IllegalStateException("Not implemented"))

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
        cipher.openScript += FakeKeystoreCipher.OPENS
        repeat(3) { cipher.openScript += refusal() }
        assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
        assertTrue("the wrap was written and it was the read after that failed", keyFile.exists())
    }

    @Test
    fun aMintTheKeystoreRefusesToWrapFailsTheOpen() {
        cipher.keyScript += refusal()
        assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
        assertFalse(keyFile.exists())
    }

    @Test
    fun aStrongBoxThatRefusesEveryKeyStillOpensAFreshInstall() {
        repeat(3) { cipher.strongBoxScript += strongBoxRefusal() }
        staleDatabase()

        val passphrase = databaseKey.getOrCreate()
        assertEquals(DatabaseKey.PASSPHRASE_BYTES, passphrase.size)
        assertWiped()
        assertEquals(listOf(DatabaseKey.KEY_ALIAS to true, DatabaseKey.KEY_ALIAS to false), cipher.attempts)
        assertEquals(1, cipher.generated)

        // The next open: the TEE key is under the alias, so the StrongBox is not asked again.
        assertArrayEquals(passphrase, databaseKey.getOrCreate())
        assertEquals(2, cipher.attempts.size)
    }

    @Test
    fun aTeeThatRefusesTooIsStillARefusal() {
        val tee = ProviderException("Keystore key generation failed")
        cipher.strongBoxScript += strongBoxRefusal()
        cipher.teeScript += tee

        val thrown = assertThrows(KeystoreUnavailableException::class.java) { databaseKey.getOrCreate() }
        assertSame(tee, thrown.cause)

        // Try again, the Keystore making keys once more: a passphrase is minted and reads back.
        val passphrase = databaseKey.getOrCreate()
        assertArrayEquals(passphrase, databaseKey.getOrCreate())
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
