package app.getknit.knit.mesh.lora.meshcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshcoreNames]: the default name and the postable rules (ADR 2026-11.f4d3) — one definition for Knit's own rename and for
 * whether a Public post may leave under a name another app wrote.
 */
class MeshcoreNamesTest {
    private fun check(name: String) = MeshcoreNames.check(name.encodeToByteArray())

    /** [codePoints] as a string: never literals, which an editor or a formatter can change unseen. */
    private fun text(vararg codePoints: Int): String = codePoints.joinToString("") { String(Character.toChars(it)) }

    /**
     * What Knit's cleaner keeps a name may hold (lora-reviewer, the f4d3 review): ZWJ emoji sequences, a subdivision flag's
     * tag characters, and a Persian name's ZWNJ.
     */
    @Test
    fun joinersAndTagCharactersAreAllowed() {
        val technologist = text(0x1F9D1, 0x200D, 0x1F4BB)
        val rainbowFlag = text(0x1F3F3, 0xFE0F, 0x200D, 0x1F308)
        val scotland = text(0x1F3F4, 0xE0067, 0xE0062, 0xE0073, 0xE0063, 0xE0074, 0xE007F)
        val persian = text(0x0645, 0x06CC, 0x200C, 0x062E, 0x0648, 0x0627, 0x0647, 0x0645)
        for (name in listOf(technologist, rainbowFlag, scotland, persian, "Wren " + technologist)) {
            assertTrue("${name.encodeToByteArray().size} B", name.encodeToByteArray().size <= MeshcoreNames.MAX_NAME_BYTES)
            assertEquals(MeshcoreNameCheck.OK, check(name))
        }
    }

    @Test
    fun theDefaultIsKnitsOwnNameByteForByte() {
        val key = ByteArray(32) { (0xA0 + it).toByte() }
        assertTrue(MeshcoreNames.isDefault("Knit a0a1".encodeToByteArray(), key))
        assertFalse("another tag", MeshcoreNames.isDefault("Knit a0a2".encodeToByteArray(), key))
        assertFalse("a trailing space", MeshcoreNames.isDefault("Knit a0a1 ".encodeToByteArray(), key))
        assertFalse("a Cyrillic а", MeshcoreNames.isDefault("Knit а0a1".encodeToByteArray(), key))
    }

    @Test
    fun aNameIsCheckedForLengthEncodingControlsAndTheSeparator() {
        assertEquals(MeshcoreNameCheck.OK, check("Wren 🐦"))
        assertEquals(MeshcoreNameCheck.OK, check("a".repeat(31)))
        assertEquals(MeshcoreNameCheck.EMPTY, check(""))
        assertEquals(MeshcoreNameCheck.TOO_LONG, check("a".repeat(32)))
        assertEquals("cut mid-character", MeshcoreNameCheck.INVALID_UTF8, MeshcoreNames.check("é".encodeToByteArray().copyOf(1)))
        assertEquals(MeshcoreNameCheck.SEPARATOR, check("Wren: hi"))
        assertEquals("a colon alone is fine", MeshcoreNameCheck.OK, check("Wren:hi"))
    }

    /**
     * Everything Knit's cleaner would change before drawing a name is refused, so the name the consent sheet and "Posting
     * as" show is the name that airs (the f4d3 security review): controls, the bidi controls, the line and paragraph
     * separators, and the invisible U+200B and U+FEFF.
     */
    @Test
    fun everyCharacterKnitWouldCleanBeforeDrawingIsAControl() {
        // Code points, never literals: an editor or a formatter can drop an invisible one from a string unseen.
        val refused =
            listOf(
                // C0, DEL, C1
                0x0000,
                0x0007,
                0x001B,
                0x007F,
                0x0085,
                0x009F,
                // bidi embeddings, overrides, isolates
                0x202A,
                0x202E,
                0x2066,
                0x2069,
                // LRM, RLM, ALM
                0x200E,
                0x200F,
                0x061C,
                // the zero-width space and the BOM, which would show as nothing
                0x200B,
                0xFEFF,
                // line and paragraph separators
                0x2028,
                0x2029,
            ).map { String(Character.toChars(it)) }
        for (c in refused) {
            assertEquals("U+%04X".format(c.codePointAt(0)), MeshcoreNameCheck.CONTROL, check("Wr${c}en"))
            assertEquals("U+%04X alone".format(c.codePointAt(0)), MeshcoreNameCheck.CONTROL, check(c))
        }
    }
}
