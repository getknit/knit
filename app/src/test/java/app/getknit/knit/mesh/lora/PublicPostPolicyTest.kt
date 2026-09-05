package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Knit user's post is written for a stock Meshtastic client to read.
 *
 * The rule under all of it is that the **body** is what the reader came for, and since ADR 2026-09.9469 it is
 * all there is: the author's radio says who spoke, so nothing but the words goes on the air.
 */
class PublicPostPolicyTest {
    @Test
    fun `a post goes out as the words alone, with no author name in front of them`() {
        // The whole of ADR 2026-09.9469, stated as a stock client reads it. The board is `Knit abcd` to
        // everyone listening and a Knit contact resolves it by node number, so a name here would be a human
        // identity in cleartext buying nothing.
        assertEquals("hello mesh", PublicPostPolicy.onAirText("hello mesh"))
        assertEquals("a name a user typed is content, not a prefix", "Bob: hi", PublicPostPolicy.onAirText("Bob: hi"))
    }

    @Test
    fun `a long post is cut to the two hundred bytes every client composes against`() {
        val text = PublicPostPolicy.onAirText("x".repeat(500))
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, text.toByteArray().size)
    }

    @Test
    fun `the cut lands on a character boundary, never inside one`() {
        // The failure this guards is a replacement glyph on somebody else's screen, which is invisible from
        // here — a byte-count slice would leave half a multi-byte character behind and still be 200 bytes.
        val text = PublicPostPolicy.onAirText("é".repeat(200))
        assertTrue(text.toByteArray().size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertEquals("100 two-byte characters, not 100.5", 100, text.length)
        assertEquals("é".repeat(100), text)
    }

    @Test
    fun `an emoji is four bytes of the budget and is kept whole`() {
        val text = PublicPostPolicy.onAirText("🙂".repeat(60))
        assertTrue(text.toByteArray().size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertEquals("50 emoji at 4 bytes each", 50, text.codePointCount(0, text.length))
        assertTrue("no lone surrogate survived the cut", text.none { it.isHighSurrogate() && it == text.last() })
    }

    @Test
    fun `a draft written to the composer's cap goes out whole`() {
        // The property the cap exists for, stated as the composer uses it: the field refuses the 201st byte
        // against the same figure the transmit path trims at, so a post the user was allowed to type is
        // never silently cut. Checked with two-byte characters, so a cap counted in characters would fail it.
        val body = "é".repeat(PublicPostPolicy.MAX_ON_AIR_BYTES / 2)
        assertEquals(body, PublicPostPolicy.onAirText(body))
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, PublicPostPolicy.onAirText(body).toByteArray().size)
    }

    @Test
    fun `a post that already fits is left exactly as it was typed`() {
        val body = "  meet at the trailhead  ".trim()
        assertEquals(body, PublicPostPolicy.onAirText(body))
        assertEquals(body, PublicPostPolicy.trimToUtf8(body, PublicPostPolicy.MAX_ON_AIR_BYTES))
    }
}
