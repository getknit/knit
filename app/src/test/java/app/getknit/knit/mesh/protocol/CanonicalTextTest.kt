package app.getknit.knit.mesh.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [CanonicalText] directly, rather than only through the codecs built on it: every `…OrNull` accepts a
 * string only when its bytes re-encode to exactly that string, so no compact codec can swap a sender's
 * string for a different one that decodes to the same bytes (upper-case hex, unpadded or non-zero-tail
 * base64, a stray prefix). And every `…Text` refuses bytes of the wrong length instead of emitting a
 * string no peer would parse back.
 */
class CanonicalTextTest {
    private fun bytes(
        n: Int,
        seed: Int = 1,
    ) = ByteArray(n) { (it * 37 + seed).toByte() }

    @Test
    fun hashesRoundTripOnlyInTheirCanonicalForm() {
        val raw = bytes(CanonicalText.HASH_BYTES)
        val text = CanonicalText.hashText(raw)
        assertEquals(64, text.length)
        assertArrayEquals(raw, CanonicalText.hashBytesOrNull(text))
        assertNull("upper case", CanonicalText.hashBytesOrNull(text.uppercase()))
        assertNull("short", CanonicalText.hashBytesOrNull(text.dropLast(2)))
        assertNull("long", CanonicalText.hashBytesOrNull(text + "00"))
        assertNull("not hex", CanonicalText.hashBytesOrNull("zz" + text.drop(2)))
        assertNull("a sign is not a digit", CanonicalText.hashBytesOrNull("+f" + text.drop(2)))
    }

    @Test
    fun groupIdsNeedTheirPrefixAndTwelveBytes() {
        val raw = bytes(CanonicalText.GROUP_ID_BYTES)
        val id = CanonicalText.groupIdText(raw)
        assertEquals("g-", id.take(2))
        assertArrayEquals(raw, CanonicalText.groupIdBytesOrNull(id))
        assertNull("no prefix", CanonicalText.groupIdBytesOrNull(id.drop(2)))
        assertNull("another prefix", CanonicalText.groupIdBytesOrNull("c-" + id.drop(2)))
        assertNull("upper case", CanonicalText.groupIdBytesOrNull("g-" + id.drop(2).uppercase()))
    }

    @Test
    fun deviceTagsAreSixteenHex() {
        val raw = bytes(CanonicalText.TAG_BYTES)
        val text = CanonicalText.hex8Text(raw)
        assertArrayEquals(raw, CanonicalText.hex8BytesOrNull(text))
        assertNull(CanonicalText.hex8BytesOrNull(text.dropLast(1)))
    }

    @Test
    fun base64IsAcceptedOnlyPaddedAndCanonical() {
        val raw = bytes(5)
        val text = CanonicalText.base64Text(raw)
        assertArrayEquals(raw, CanonicalText.base64BytesOrNull(text))
        assertNull("unpadded", CanonicalText.base64BytesOrNull(text.trimEnd('=')))
        assertNull("url alphabet", CanonicalText.base64BytesOrNull(CanonicalText.base64Text(byteArrayOf(-5, -1)).replace('+', '-')))
        // "AB==" and "AA==" decode to the same byte; only the one with zero tail bits is canonical.
        assertArrayEquals(byteArrayOf(0), CanonicalText.base64BytesOrNull("AA=="))
        assertNull("non-zero tail bits", CanonicalText.base64BytesOrNull("AB=="))
        assertNull("not base64", CanonicalText.base64BytesOrNull("!!!!"))
    }

    @Test
    fun aBundleRoundTripsThroughItsFixedFraming() {
        val raw = bytes(CanonicalText.BUNDLE_RAW_BYTES)
        val encoded = CanonicalText.bundleText(raw)
        assertArrayEquals(raw, CanonicalText.bundleRawOrNull(encoded))
        assertNull("bare keys without the CBOR framing", CanonicalText.bundleRawOrNull(CanonicalText.base64Text(raw)))
        val cut = CanonicalText.base64BytesOrNull(encoded)!!.let { it.copyOf(it.size - 1) }
        assertNull("framing cut short", CanonicalText.bundleRawOrNull(CanonicalText.base64Text(cut)))
        assertNull("not base64", CanonicalText.bundleRawOrNull("not a bundle"))
    }

    @Test
    fun bytesOfTheWrongLengthAreRefusedNotEncoded() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalText.hashText(bytes(31)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalText.groupIdText(bytes(13)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalText.hex8Text(bytes(7)) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalText.bundleText(bytes(63)) }
    }
}
