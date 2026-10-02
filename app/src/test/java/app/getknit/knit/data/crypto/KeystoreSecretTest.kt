package app.getknit.knit.data.crypto

import android.content.Context
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
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/**
 * [KeystoreSecret]'s verdicts over a scripted [FakeKeystoreCipher] (ADR 2026-10.47rw): a loss counts only when every
 * attempt proves one, any refusal makes the read [Unwrapped.Unavailable] with the file untouched, and a store never
 * generates a key over one a lookup merely failed to see.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = FakeKeystoreCipher()
    private val sleeps = mutableListOf<Long>()
    private val file = File(context.filesDir, FILE)
    private val lost = File(context.noBackupFilesDir, FILE + KeystoreSecret.LOST_SUFFIX)
    private val staging = File(context.cacheDir, "staging")

    private fun secret(dir: File = context.filesDir) = KeystoreSecret(context, ALIAS, FILE, dir, cipher, sleep = { sleeps += it })

    /** The lab Pixel 3's refusal, 2026-10-01. */
    private fun refusal() = InvalidKeyException("Keystore operation failed", ProviderException("KM_ERROR_UNKNOWN_ERROR"))

    @After
    fun tearDown() {
        file.delete()
        lost.delete()
        staging.deleteRecursively()
    }

    private fun stored(plain: ByteArray = PLAIN): KeystoreSecret = secret().also { it.store(plain) }

    @Test
    fun noFileIsAbsentAndAsksTheKeystoreNothing() {
        assertSame(Unwrapped.Absent, secret().read())
        assertEquals(0, cipher.lookups)
    }

    @Test
    fun aWrapRoundTrips() {
        val secret = stored()
        assertArrayEquals(PLAIN, (secret.read() as Unwrapped.Present).bytes)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aFileTooShortToBeAWrapIsMalformedAtOnce() {
        file.writeBytes(ByteArray(KeystoreCipher.MIN_WRAP_BYTES - 1))
        assertEquals(Loss.MALFORMED, (secret().read() as Unwrapped.Lost).reason)
        assertEquals(0, cipher.lookups)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aRefusalTheNextAttemptOutlastsIsPresent() {
        val secret = stored()
        cipher.openScript += refusal()
        assertArrayEquals(PLAIN, (secret.read() as Unwrapped.Present).bytes)
        assertEquals(listOf(500L), sleeps)
    }

    @Test
    fun aRefusalOnEveryAttemptIsUnavailableAndTouchesNothing() {
        val secret = stored()
        val wrap = file.readBytes()
        repeat(3) { cipher.openScript += refusal() }
        val read = secret.read()
        assertTrue(read is Unwrapped.Unavailable)
        assertEquals(KeystoreSecret.RETRY_DELAYS_MS, sleeps)
        assertArrayEquals(wrap, file.readBytes())
        assertFalse(lost.exists())
    }

    @Test
    fun aKeyMissingOnEveryLookIsLost() {
        val secret = stored()
        cipher.keys.clear()
        val before = cipher.lookups
        assertEquals(Loss.KEY_MISSING, (secret.read() as Unwrapped.Lost).reason)
        assertEquals("one look per attempt", 3, cipher.lookups - before)
    }

    @Test
    fun aKeyMissingOnceIsADeadDaemonNotALoss() {
        val secret = stored()
        cipher.keyScript += FakeKeystoreCipher.MISSING
        assertArrayEquals(PLAIN, (secret.read() as Unwrapped.Present).bytes)
    }

    @Test
    fun missingThenRefusedIsUnavailable() {
        val secret = stored()
        cipher.keyScript += FakeKeystoreCipher.MISSING
        cipher.keyScript += UnrecoverableKeyException("Failed to obtain information about key")
        cipher.keyScript += FakeKeystoreCipher.MISSING
        assertTrue(secret.read() is Unwrapped.Unavailable)
    }

    @Test
    fun aTagMismatchOnEveryAttemptIsLost() {
        val secret = stored()
        cipher.keys[ALIAS] = FakeKeystoreCipher.newKey()
        val read = secret.read() as Unwrapped.Lost
        assertEquals(Loss.TAG_MISMATCH, read.reason)
        assertTrue(read.cause is AEADBadTagException)
    }

    @Test
    fun aTagMismatchOnceIsAGlitch() {
        val secret = stored()
        cipher.openScript += AEADBadTagException()
        assertArrayEquals(PLAIN, (secret.read() as Unwrapped.Present).bytes)
    }

    @Test
    fun aFirstStoreGeneratesWithoutWaiting() {
        stored()
        assertEquals(1, cipher.generated)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun aStoreNeverGeneratesOverAKeyThatIsThere() {
        val secret = stored()
        secret.store(byteArrayOf(4))
        assertEquals(1, cipher.generated)
    }

    @Test
    fun aStoreNeverGeneratesOverAKeyALookupMissedWhileTheLiveWrapExists() {
        val secret = stored()
        cipher.keyScript += FakeKeystoreCipher.MISSING
        secret.store(byteArrayOf(4))
        assertEquals(1, cipher.generated)
        assertEquals(listOf(500L), sleeps)
        assertArrayEquals(byteArrayOf(4), (secret.read() as Unwrapped.Present).bytes)
    }

    @Test
    fun aStagedStoreUnderTheLiveAliasNeverReplacesTheLiveKey() {
        val live = stored()
        cipher.keyScript += FakeKeystoreCipher.MISSING
        cipher.keyScript += FakeKeystoreCipher.MISSING
        secret(dir = staging.apply { mkdirs() }).store(byteArrayOf(5))
        assertEquals(1, cipher.generated)
        assertArrayEquals("the live wrap still opens", PLAIN, (live.read() as Unwrapped.Present).bytes)
    }

    @Test
    fun aStoreGeneratesOnceAbsenceHoldsOnEveryLook() {
        val secret = stored()
        cipher.keys.clear()
        secret.store(byteArrayOf(6))
        assertEquals(2, cipher.generated)
        assertEquals(KeystoreSecret.RETRY_DELAYS_MS, sleeps)
    }

    @Test
    fun aStoreWhoseLookupIsRefusedThrowsAndGeneratesNothing() {
        val secret = stored()
        val wrap = file.readBytes()
        cipher.keyScript += UnrecoverableKeyException("Failed to obtain information about key")
        assertThrows(UnrecoverableKeyException::class.java) { secret.store(byteArrayOf(7)) }
        assertEquals(1, cipher.generated)
        assertArrayEquals(wrap, file.readBytes())
    }

    @Test
    fun aFreshKeyIsGeneratedOnlyWhenAsked() {
        val secret = stored()
        secret.store(byteArrayOf(8), freshKey = true)
        assertEquals(2, cipher.generated)
        assertArrayEquals(byteArrayOf(8), (secret.read() as Unwrapped.Present).bytes)
    }

    @Test
    fun keepAsideMovesTheWrapIntoTheOneLostSlot() {
        val secret = stored()
        lost.parentFile!!.mkdirs()
        lost.writeText("an older loss")
        val wrap = file.readBytes()
        secret.keepAside()
        assertFalse(file.exists())
        assertArrayEquals(wrap, lost.readBytes())
        assertSame(Unwrapped.Absent, secret.read())
    }

    private companion object {
        const val ALIAS = "test_alias"
        const val FILE = "test.key"
        val PLAIN = byteArrayOf(1, 2, 3)
    }
}
