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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.InvalidKeyException
import java.security.ProviderException

/**
 * [IdentityKeyStore] mints a node id only when there is none or the old one is proven lost (ADR 2026-10.47rw). A
 * Keystore refusal throws, caches nothing and stores nothing, so the next call finds the same identity — the lab Pixel
 * 3 became a stranger to every peer on 2026-10-01 because the old `load()` read a refusal as "no identity yet". A wrap
 * that opens but does not parse is unwrapped again, since a Keystore can hand back other bytes with its tag verified
 * (Play, 2.9.0, a Redmi Note 14 Pro+), and what never parses fails naming each read's shape, never minting.
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

    /** A store that re-reads without waiting. */
    private fun rereading() = IdentityKeyStore(secret, sleep = {})

    /**
     * The unwrapped bytes with [from, to) gone: inside `hybridPriv`'s array, which runs from offset 14 for ~300 bytes.
     * Where an element boundary falls depends on the random keyset, so assertions read the shape only loosely.
     */
    private fun dropped(
        from: Int,
        to: Int,
    ): (ByteArray) -> ByteArray = { it.copyOfRange(0, from) + it.copyOfRange(to, it.size) }

    private fun unparsed(store: IdentityKeyStore): String =
        assertThrows(IllegalStateException::class.java) { store.keys() }.message.orEmpty()

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
        val message = unparsed(rereading())
        assertTrue(message, message.endsWith("3 reads, the same bytes every time: 3 B i +2 B"))
        assertArrayEquals(wrap, file.readBytes())
    }

    @Test
    fun aKeystoreThatHandsBackOtherBytesOnceIsReadAgainAndKeepsTheIdentity() {
        val original = publicKey(IdentityKeyStore(secret))
        val wrap = file.readBytes()
        cipher.alterScript += dropped(40, 60)

        assertEquals(original, publicKey(rereading()))
        assertTrue(cipher.alterScript.isEmpty())
        assertArrayEquals(wrap, file.readBytes())
        assertEquals(1, cipher.generated)
    }

    @Test
    fun aWrapThatNeverParsesIsReadThreeTimesAndNamesItsShapeWithoutMinting() {
        val original = publicKey(IdentityKeyStore(secret))
        val wrap = file.readBytes()
        repeat(3) { cipher.alterScript += dropped(40, 60) }
        val opens = cipher.opens
        val waits = mutableListOf<Long>()

        val message = unparsed(IdentityKeyStore(secret, rereadDelaysMs = listOf(7L, 11L), sleep = { waits += it }))
        assertEquals(listOf(7L, 11L), waits)
        assertEquals(3, cipher.opens - opens)
        assertTrue(message, message.contains("3 reads, the same bytes every time: "))
        // The run of byte-elements breaks where the span went missing, at an offset past the array's start.
        assertTrue(message, Regex("""\{hybridPriv:\[i×\d+ .*@\d+""").containsMatchIn(message))
        assertArrayEquals(wrap, file.readBytes())
        assertFalse(lostFile.exists())
        assertEquals(1, cipher.generated)
        // The Keystore answering right again finds the same identity.
        assertEquals(original, publicKey(rereading()))
    }

    @Test
    fun readsThatDisagreeAreEachDescribed() {
        publicKey(IdentityKeyStore(secret))
        cipher.alterScript += dropped(40, 60)
        repeat(2) { cipher.alterScript += dropped(80, 100) }

        val message = unparsed(rereading())
        assertTrue(message, message.contains("3 reads, 2 different: read 1 "))
        assertTrue(message, message.contains("; read 2 "))
    }

    @Test
    fun aRereadTheKeystoreRefusesEndsTheRereadsAndMintsNothing() {
        publicKey(IdentityKeyStore(secret))
        val wrap = file.readBytes()
        cipher.alterScript += dropped(40, 60)
        repeat(3) { cipher.alterScript += { throw refusal() } }
        val waits = mutableListOf<Long>()

        val message = unparsed(IdentityKeyStore(secret, rereadDelaysMs = listOf(7L, 11L), sleep = { waits += it }))
        assertEquals(listOf(7L), waits)
        assertTrue(message, message.endsWith("; read 2 refused (InvalidKeyException)"))
        assertTrue(message, message.contains("1 read: "))
        assertArrayEquals(wrap, file.readBytes())
        assertEquals(1, cipher.generated)
    }

    @Test
    fun aMintThatDoesNotReadBackFailsInsteadOfServingAnUnstoredIdentity() {
        repeat(3) { cipher.openScript += refusal() }
        assertThrows(KeystoreUnavailableException::class.java) { IdentityKeyStore(secret).keys() }
    }
}
