@file:Suppress("MagicNumber") // sizes and fixture times are the test's subject

package app.getknit.knit.usbcontrol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.crypto.FakeKeystoreCipher
import app.getknit.knit.data.crypto.KeystoreSecret
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidKeyException
import java.security.ProviderException

/**
 * The phone's paired computers over a real [KeystoreSecret] (a fake cipher: Robolectric has no AndroidKeyStore): a
 * round trip, a Keystore that refuses (nothing written over the list), a key proven gone (every computer pairs again,
 * the old wrap kept aside), and a blob that does not parse.
 */
@RunWith(AndroidJUnit4::class)
class KeystorePairingStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = FakeKeystoreCipher()
    private val file = File(context.filesDir, KeystorePairingStore.FILE)

    private fun store() =
        KeystorePairingStore(KeystoreSecret(context, KeystorePairingStore.ALIAS, KeystorePairingStore.FILE, cipher = cipher, sleep = {}))

    private fun pairing(seed: Int) =
        Pairing(
            handle = ByteArray(16) { seed.toByte() },
            psk = ByteArray(32) { (seed + 1).toByte() },
            name = "desk $seed",
            pairedAt = 5L,
            lastUsedAt = 6L,
        )

    /** The lab Pixel 3's refusal, 2026-10-01 (KeystoreSecretTest's): proves nothing, so the read is Unavailable. */
    private fun refuseEveryAttempt() =
        repeat(3) {
            cipher.openScript +=
                InvalidKeyException("Keystore operation failed", ProviderException("KM_ERROR_UNKNOWN_ERROR"))
        }

    @After
    fun clean() {
        file.delete()
        File(context.noBackupFilesDir, KeystorePairingStore.FILE + KeystoreSecret.LOST_SUFFIX).delete()
    }

    @Test
    fun whatIsWrittenReadsBackWhole() {
        store().update { it + pairing(1) + pairing(2) }
        val read = (store().read() as Stored.Ok).pairings
        assertEquals(listOf("desk 1", "desk 2"), read.map { it.name })
        assertArrayEquals(pairing(2).handle, read[1].handle)
        assertArrayEquals(pairing(2).psk, read[1].psk)
        assertEquals(6L, read[0].lastUsedAt)
    }

    @Test
    fun aKeystoreThatRefusesIsNeverWrittenOver() {
        store().update { it + pairing(1) }
        val before = file.readBytes()
        refuseEveryAttempt()
        assertEquals(Stored.Unavailable, store().read())
        refuseEveryAttempt()
        assertEquals(Updated.Unavailable, store().update { emptyList() })
        assertArrayEquals("the wrap is as it was", before, file.readBytes())
        assertEquals(1, (store().read() as Stored.Ok).pairings.size)
    }

    @Test
    fun aKeyProvenGoneMeansEveryComputerPairsAgain() {
        store().update { it + pairing(1) }
        cipher.keys.clear()
        assertTrue((store().read() as Stored.Ok).pairings.isEmpty())
        assertTrue(
            "the old wrap is kept aside, not overwritten",
            File(context.noBackupFilesDir, KeystorePairingStore.FILE + KeystoreSecret.LOST_SUFFIX).exists(),
        )
        store().update { it + pairing(3) }
        assertEquals(listOf("desk 3"), (store().read() as Stored.Ok).pairings.map { it.name })
    }

    @Test
    fun aBlobThatDoesNotParseReadsAsNoPairings() {
        assertTrue(PairingsJson.decode("not json".encodeToByteArray()).isEmpty())
        assertTrue(PairingsJson.decode("""{"pairings":[{"handle":"!!"}]}""".encodeToByteArray()).isEmpty())
    }
}
