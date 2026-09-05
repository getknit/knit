package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a Knit user's post is written for a stock Meshtastic client to read.
 *
 * The rule under all of it is that the **body** is what the reader came for. A name is context; if something
 * has to be cut, it is never the words.
 */
class PublicPostPolicyTest {
    @Test
    fun `a post carries its author's name so somebody can answer it`() {
        assertEquals("Alice: hello mesh", PublicPostPolicy.onAirText("Alice", "hello mesh"))
    }

    @Test
    fun `a nameless author sends the bare body rather than an empty prefix`() {
        assertEquals("hello mesh", PublicPostPolicy.onAirText(null, "hello mesh"))
        assertEquals("hello mesh", PublicPostPolicy.onAirText("", "hello mesh"))
        assertEquals("hello mesh", PublicPostPolicy.onAirText("   ", "hello mesh"))
    }

    @Test
    fun `a long post is cut to the two hundred bytes every client composes against`() {
        val text = PublicPostPolicy.onAirText("Alice", "x".repeat(500))
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, text.toByteArray().size)
        assertTrue("the name survives; the words are what get trimmed", text.startsWith("Alice: "))
    }

    @Test
    fun `the cut lands on a character boundary, never inside one`() {
        // The failure this guards is a replacement glyph on somebody else's screen, which is invisible from
        // here — a byte-count slice would leave half a multi-byte character behind and still be 200 bytes.
        val text = PublicPostPolicy.onAirText(null, "é".repeat(200))
        assertTrue(text.toByteArray().size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertEquals("100 two-byte characters, not 100.5", 100, text.length)
        assertEquals("é".repeat(100), text)
    }

    @Test
    fun `an emoji is four bytes of the budget and is kept whole`() {
        val text = PublicPostPolicy.onAirText(null, "🙂".repeat(60))
        assertTrue(text.toByteArray().size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertEquals("50 emoji at 4 bytes each", 50, text.codePointCount(0, text.length))
        assertTrue("no lone surrogate survived the cut", text.none { it.isHighSurrogate() && it == text.last() })
    }

    @Test
    fun `a name long enough to leave no room drops the prefix rather than the post`() {
        // Pathological rather than expected — TextLimits.DISPLAY_NAME is 32 — but the alternative is a
        // transmission that is nothing but somebody's name, which says strictly less than the truncated
        // sentence does.
        val text = PublicPostPolicy.onAirText("n".repeat(400), "hello mesh")
        assertEquals("hello mesh", text)
    }

    @Test
    fun `the composer's budget is the room the author's own name leaves`() {
        // What the composer caps the draft at, so the transmit path never has to trim one. The pair has to
        // agree exactly: a budget a byte too generous puts the silent cut back.
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES - "Alice: ".length, PublicPostPolicy.bodyBudget("Alice"))
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, PublicPostPolicy.bodyBudget(null))
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, PublicPostPolicy.bodyBudget("   "))
    }

    @Test
    fun `a draft written to the budget goes out whole`() {
        // The property the cap exists for, stated as the composer will use it: fill the budget exactly and
        // nothing is cut. Checked with a two-byte name, so a budget counted in characters would fail it.
        val name = "Álice"
        val body = "x".repeat(PublicPostPolicy.bodyBudget(name))
        val text = PublicPostPolicy.onAirText(name, body)
        assertEquals("$name: $body", text)
        assertTrue(text.toByteArray().size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
    }

    @Test
    fun `a name that leaves no room budgets the whole line for the words`() {
        // Matches the prefix-dropping rule above: where the name goes, the body gets the line it vacated.
        assertEquals(PublicPostPolicy.MAX_ON_AIR_BYTES, PublicPostPolicy.bodyBudget("n".repeat(400)))
    }

    @Test
    fun `a post that already fits is left exactly as it was typed`() {
        val body = "  meet at the trailhead  ".trim()
        assertEquals("Alice: $body", PublicPostPolicy.onAirText("Alice", body))
        assertEquals(body, PublicPostPolicy.trimToUtf8(body, PublicPostPolicy.MAX_ON_AIR_BYTES))
    }
}
