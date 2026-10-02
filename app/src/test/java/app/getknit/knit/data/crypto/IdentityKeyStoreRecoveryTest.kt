package app.getknit.knit.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidKeyException
import java.security.ProviderException

/**
 * [IdentityKeyStore] mints a node id only when there is none or the old one is proven lost (ADR 2026-10.47rw). A
 * Keystore refusal throws, caches nothing and stores nothing, so the next call finds the same identity — the lab Pixel
 * 3 became a stranger to every peer on 2026-10-01 because the old `load()` read a refusal as "no identity yet".
 */
@RunWith(AndroidJUnit4::class)
class IdentityKeyStoreRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = FakeKeystoreCipher()
    private val file = File(context.filesDir, IdentityKeyStore.FILE_NAME)
    private val lostFile = File(context.noBackupFilesDir, IdentityKeyStore.FILE_NAME + KeystoreSecret.LOST_SUFFIX)
    private val secret =
        KeystoreSecret(context, IdentityKeyStore.KEYSTORE_ALIAS, IdentityKeyStore.FILE_NAME, cipher = cipher, sleep = {})

    @After
    fun tearDown() {
        file.delete()
        lostFile.delete()
    }

    private fun refusal() = InvalidKeyException("Keystore operation failed", ProviderException("KM_ERROR_UNKNOWN_ERROR"))

    private fun publicKey(store: IdentityKeyStore): String = store.keys().publicBundle.encoded

    @Test
    fun aRefusalThrowsMintsNothingAndTheNextCallFindsTheSameIdentity() {
        val original = publicKey(IdentityKeyStore(secret))
        val wrap = file.readBytes()
        val store = IdentityKeyStore(secret)
        repeat(3) { cipher.openScript += refusal() }

        assertThrows(KeystoreUnavailableException::class.java) { store.keys() }
        assertArrayEquals(wrap, file.readBytes())
        assertEquals(1, cipher.generated)
        assertFalse(lostFile.exists())

        // Nothing was cached on the failure: the same instance, asked again, reads the same identity.
        assertEquals(original, publicKey(store))
    }

    @Test
    fun aProvenLossKeepsTheOldFileAsideAndMintsANewIdentity() {
        val original = publicKey(IdentityKeyStore(secret))
        val wrap = file.readBytes()
        cipher.keys.clear()

        val fresh = publicKey(IdentityKeyStore(secret))
        assertNotEquals(original, fresh)
        assertArrayEquals(wrap, lostFile.readBytes())
        assertEquals(fresh, publicKey(IdentityKeyStore(secret)))
    }

    @Test
    fun aWrapThatOpensButDoesNotParseIsABugNotALoss() {
        secret.store(byteArrayOf(1, 2, 3))
        val wrap = file.readBytes()
        assertThrows(IllegalStateException::class.java) { IdentityKeyStore(secret).keys() }
        assertArrayEquals(wrap, file.readBytes())
    }

    @Test
    fun aMintThatDoesNotReadBackFailsInsteadOfServingAnUnstoredIdentity() {
        repeat(3) { cipher.openScript += refusal() }
        assertThrows(KeystoreUnavailableException::class.java) { IdentityKeyStore(secret).keys() }
    }
}
