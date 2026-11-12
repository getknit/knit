@file:Suppress("MagicNumber") // byte counts are the test's subject

package app.getknit.knit.usbcontrol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class UsbPairingCryptoTest {
    private val eH = ByteArray(32) { 1 }
    private val eP = ByteArray(32) { 2 }
    private val ss = ByteArray(32) { 3 }
    private val client = "knit-cli on desk".encodeToByteArray()

    @Test
    fun aCodeIsTenSymbolsOfCrockfordsAlphabetShownAsTwoGroups() {
        val rng = SecureRandom()
        repeat(200) {
            val code = UsbPairingCrypto.drawCode(rng)
            assertEquals(10, code.length)
            assertTrue(code, code.all { ch -> ch in UsbPairingCrypto.ALPHABET })
            assertFalse("no I, L, O or U to misread", code.any { ch -> ch in "ILOU" })
        }
        assertEquals("7KQFM 2XD9R", UsbPairingCrypto.display("7KQFM2XD9R"))
    }

    @Test
    fun whatAPersonTypesReadsAsTheCodeItStandsFor() {
        assertEquals("7KQFM2XD9R", UsbPairingCrypto.normalize("7kqfm 2xd9r"))
        assertEquals("7KQFM2XD9R", UsbPairingCrypto.normalize(" 7KQFM-2XD9R "))
        assertEquals("letters read as the digits they look like", "1010000000", UsbPairingCrypto.normalize("iOlo000000"))
        assertNull("too short", UsbPairingCrypto.normalize("7KQFM2XD9"))
        assertNull("too long", UsbPairingCrypto.normalize("7KQFM2XD9RR"))
        assertNull("U is no symbol", UsbPairingCrypto.normalize("7KQFM2XD9U"))
        assertNull("nor is punctuation", UsbPairingCrypto.normalize("7KQFM2XD9!"))
    }

    @Test
    fun theScheduleSplitsIntoTwoChecksAPskAndAHandleAndAnyInputChangesThemAll() {
        val base = UsbPairingCrypto.schedule(ss, CODE, eH, eP, client)
        assertEquals(32, base.confirmHost.size)
        assertEquals(32, base.confirmPhone.size)
        assertEquals(32, base.psk.size)
        assertEquals(16, base.handle.size)
        assertArrayEquals("deterministic", base.psk, UsbPairingCrypto.schedule(ss, CODE, eH, eP, client).psk)
        val variants =
            listOf(
                "another code" to UsbPairingCrypto.schedule(ss, "7KQFM2XD9S", eH, eP, client),
                "another name" to UsbPairingCrypto.schedule(ss, CODE, eH, eP, "knit-cli on lap".encodeToByteArray()),
                "the shares swapped" to UsbPairingCrypto.schedule(ss, CODE, eP, eH, client),
                "another secret" to UsbPairingCrypto.schedule(ByteArray(32) { 4 }, CODE, eH, eP, client),
            )
        for ((what, other) in variants) {
            assertFalse(what, base.confirmHost contentEquals other.confirmHost)
            assertFalse(what, base.confirmPhone contentEquals other.confirmPhone)
            assertFalse(what, base.psk contentEquals other.psk)
            assertFalse(what, base.handle contentEquals other.handle)
        }
    }

    @Test
    fun theStretchIsSaltedByTheKeyShares() {
        assertFalse(UsbPairingCrypto.stretch(CODE, eH, eP) contentEquals UsbPairingCrypto.stretch(CODE, eP, eH))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aClaimedNameOverTheCapIsRefused() {
        UsbPairingCrypto.info(eH, eP, ByteArray(UsbPairingCrypto.MAX_CLIENT_BYTES + 1))
    }

    private companion object {
        const val CODE = "7KQFM2XD9R"
    }
}
