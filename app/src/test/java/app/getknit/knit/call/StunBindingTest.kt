package app.getknit.knit.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The probe's STUN Binding (C-7.6-52; RFC 8489): a 20-byte request, and an answer accepted only in one shape. */
class StunBindingTest {
    private val tx = ByteArray(12) { (it + 1).toByte() }

    @Test
    fun aRequestIsTwentyBytesOfHeaderAndNoAttribute() {
        // Type 0x0001, length 0, the magic cookie, then the transaction ID (RFC 8489 §5), written out by hand.
        val expected =
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x21, 0x12, 0xA4.toByte(), 0x42) + tx
        assertArrayEquals(expected, StunBinding.request(tx))
    }

    @Test
    fun aBindingSuccessForTheRequestWithAMappedAddressIsAccepted() {
        val answer = success(tx, attribute(0x0020, 8))
        assertTrue(StunBinding.accepts(answer, answer.size, tx))
        // Another attribute ahead of it, padded to four bytes (a SOFTWARE of 5), changes nothing.
        val padded = success(tx, attribute(0x8022, 5) + attribute(0x0020, 20))
        assertTrue(StunBinding.accepts(padded, padded.size, tx))
    }

    @Test
    fun aMismatchedTransactionIdIsRefused() {
        val other = tx.copyOf().also { it[11] = 0x7F }
        val answer = success(other, attribute(0x0020, 8))
        assertFalse(StunBinding.accepts(answer, answer.size, tx))
    }

    @Test
    fun aSuccessWithNoXorMappedAddressIsRefused() {
        // A MAPPED-ADDRESS (0x0001) is not the attribute asked for, and an empty answer carries nothing.
        val plain = success(tx, attribute(0x0001, 8))
        assertFalse(StunBinding.accepts(plain, plain.size, tx))
        val bare = success(tx, ByteArray(0))
        assertFalse(StunBinding.accepts(bare, bare.size, tx))
    }

    @Test
    fun anythingButABindingSuccessIsRefused() {
        val error =
            success(tx, attribute(0x0020, 8)).also {
                it[0] = 0x01
                it[1] = 0x11
            }
        assertFalse(StunBinding.accepts(error, error.size, tx))
        val wrongCookie = success(tx, attribute(0x0020, 8)).also { it[4] = 0x00 }
        assertFalse(StunBinding.accepts(wrongCookie, wrongCookie.size, tx))
        // Cut short of its own declared length, or of the header.
        val whole = success(tx, attribute(0x0020, 8))
        assertFalse(StunBinding.accepts(whole, whole.size - 4, tx))
        assertFalse(StunBinding.accepts(whole, 19, tx))
        // An attribute claiming more than the message holds.
        val overlong = success(tx, attribute(0x0020, 8)).also { it[23] = 40 }
        assertFalse(StunBinding.accepts(overlong, overlong.size, tx))
    }

    // A Binding success (0x0101) under [transaction] carrying [attributes].
    private fun success(
        transaction: ByteArray,
        attributes: ByteArray,
    ): ByteArray {
        val length = attributes.size
        return byteArrayOf(0x01, 0x01, (length shr 8).toByte(), length.toByte(), 0x21, 0x12, 0xA4.toByte(), 0x42) +
            transaction + attributes
    }

    // One attribute of [type] with a [size]-byte value of zeros, padded to four bytes.
    private fun attribute(
        type: Int,
        size: Int,
    ): ByteArray {
        val padded = (size + 3) / 4 * 4
        return byteArrayOf((type shr 8).toByte(), type.toByte(), (size shr 8).toByte(), size.toByte()) + ByteArray(padded)
    }
}
