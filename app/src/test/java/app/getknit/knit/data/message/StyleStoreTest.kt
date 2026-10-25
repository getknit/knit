package app.getknit.knit.data.message

import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `messages.styles` / `drafts.styles` column (ADR 2026-10.p95v): readable triples, nothing stored for none, and a
 * value that is no span decoding to nothing, since the chat fold decodes every row it draws.
 */
class StyleStoreTest {
    @Test
    fun spansRoundTripAsReadableTriples() {
        val spans = listOf(StyleSpan(0, 4, BodyStyle.BOLD), StyleSpan(5, 8, BodyStyle.MONOSPACE))

        val stored = StyleStore.encode(spans)

        assertEquals("[[0,4,0],[5,3,3]]", stored)
        assertEquals(spans, StyleStore.decode(stored))
    }

    @Test
    fun noSpansStoreNothing() {
        assertNull(StyleStore.encode(emptyList()))
        assertEquals(emptyList<StyleSpan>(), StyleStore.decode(null))
        assertEquals(emptyList<StyleSpan>(), StyleStore.decode(""))
    }

    @Test
    fun aValueThatIsNoSpanDecodesToNothing() {
        for (stored in listOf("not json", "[[0,4]]", "[[0,4,0,1]]", "[[0,4,9]]", "[[-1,4,0]]", "[[0,0,0]]", "[[2147483647,5,0]]")) {
            assertEquals(stored, emptyList<StyleSpan>(), StyleStore.decode(stored))
        }
    }

    @Test
    fun aBadTripleDropsOnlyItself() {
        assertEquals(listOf(StyleSpan(6, 9, BodyStyle.ITALIC)), StyleStore.decode("[[0,4,9],[6,3,1]]"))
    }
}
