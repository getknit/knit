package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

/**
 * The MeshCore board key's one gate (ADR 2026-11.gsqu, Stage 4): canonical base64 of 32 bytes that decode as a
 * full-order Ed25519 point, or nothing. The low-order encodings are libsodium's `has_small_order` list, each checked
 * here to decode and to be refused for its order, not its spelling.
 */
class MeshcoreKeyTest {
    @Test
    fun realEd25519KeysPassAndKeepTheirSpelling() {
        for (hex in listOf(RFC8032_TEST1, RFC8032_TEST2, RFC8032_TEST3)) {
            val text = b64(hex(hex))
            assertEquals(hex, text, MeshcoreKey.canonicalOrNull(text))
            assertArrayEquals(hex, hex(hex), MeshcoreKey.bytesOrNull(text))
        }
    }

    @Test
    fun aKeyOfTheWrongLengthIsRefused() {
        val key = hex(RFC8032_TEST1)
        assertNull("31 bytes", MeshcoreKey.canonicalOrNull(b64(key.copyOf(31))))
        assertNull("33 bytes", MeshcoreKey.canonicalOrNull(b64(key + byteArrayOf(0))))
        assertNull("64 bytes", MeshcoreKey.canonicalOrNull(b64(key + key)))
        assertNull("empty", MeshcoreKey.canonicalOrNull(""))
        assertNull(MeshcoreKey.canonicalOrNull(null))
    }

    @Test
    fun onlyTheCanonicalSpellingOfAKeyIsAccepted() {
        val canonical = b64(hex(RFC8032_TEST1))
        assertEquals("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=", canonical)
        // Stray low bits in the last character decode to the same bytes, and are refused: one key, one spelling.
        assertNull("stray bits", MeshcoreKey.canonicalOrNull("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURp="))
        assertNull("unpadded", MeshcoreKey.canonicalOrNull(canonical.trimEnd('=')))
        assertNull("URL-safe alphabet", MeshcoreKey.canonicalOrNull(canonical.replace('/', '_')))
        assertNull("whitespace", MeshcoreKey.canonicalOrNull(" $canonical"))
        assertNull("hex", MeshcoreKey.canonicalOrNull(RFC8032_TEST1))
    }

    @Test
    fun theAllZeroKeyIsRefused() {
        assertFalse(MeshcoreKey.accepts(ByteArray(32)))
        assertNull(MeshcoreKey.canonicalOrNull(b64(ByteArray(32))))
    }

    @Test
    fun everyLowOrderPointIsRefused() {
        // Each of these decodes as a point (the curve test passes); every one is refused because 8·P is the identity.
        val lowOrder =
            mapOf(
                "the identity (order 1)" to "01" + "00".repeat(31),
                "order 2" to "ec" + "ff".repeat(30) + "7f",
                "order 4 (the all-zero y)" to "00".repeat(32),
                "order 4, sign set" to "00".repeat(31) + "80",
                "order 8" to "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
                "order 8, sign set" to "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc85",
                "order 8, the other y" to "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
                "order 8, the other y, sign set" to "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac03fa",
            )
        for ((what, hex) in lowOrder) {
            assertFalse(what, MeshcoreKey.accepts(hex(hex)))
            assertNull(what, MeshcoreKey.canonicalOrNull(b64(hex(hex))))
        }
    }

    @Test
    fun bytesThatAreNotAPointAreRefused() {
        // y = 2 has no x on the curve.
        assertFalse("not on the curve", MeshcoreKey.accepts(hex("02" + "00".repeat(31))))
        // y = p is the order-4 point's y (0) spelled out of range, and y = p + 1 the identity's (1): neither is the
        // canonical encoding of anything, whatever point it would reduce to.
        assertFalse("y = p", MeshcoreKey.accepts(hex("ed" + "ff".repeat(30) + "7f")))
        assertFalse("y = p + 1", MeshcoreKey.accepts(hex("ee" + "ff".repeat(30) + "7f")))
        // x = 0 with the sign bit set: negative zero.
        assertFalse("negative zero", MeshcoreKey.accepts(hex("01" + "00".repeat(30) + "80")))
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private companion object {
        /** RFC 8032 §7.1's public keys, TESTs 1, 2 and 3. */
        const val RFC8032_TEST1 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
        const val RFC8032_TEST2 = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
        const val RFC8032_TEST3 = "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"
    }
}
