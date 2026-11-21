package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MeshCore One's channel reaction (ADR 2026-11.gsqu's 2026-11-21 amendment), pinned on the JVM. */
class MeshcoreReactionsTest {
    @Test
    fun theTagMatchesAnIndependentImplementation() {
        // Computed with Python's hashlib over UTF-8(text) ‖ struct.pack('<I', stamp), first 5 bytes, Crockford lowercase.
        assertEquals("v1z5wwvs", MeshcoreReactions.tag("hello".encodeToByteArray(), 1_700_000_000))
        assertEquals("vwzp3604", MeshcoreReactions.tag(ByteArray(0), 0))
        assertEquals("f7rzf75v", MeshcoreReactions.tag("Ünïcødé 👍 ".encodeToByteArray(), 4_294_967_295))
        assertEquals("8xajhey3", MeshcoreReactions.tag("@[Alpha] see you at the trailhead".encodeToByteArray(), 1_795_251_255))
        assertEquals(
            "trailing whitespace is hashed",
            "3evtq7ey",
            MeshcoreReactions.tag("trailing spaces   ".encodeToByteArray(), 1_795_000_000),
        )
    }

    @Test
    fun theStampIsTheUnsigned32BitsABoardAirs() {
        assertEquals(MeshcoreReactions.tag("hi".encodeToByteArray(), 5), MeshcoreReactions.tag("hi".encodeToByteArray(), 5 + (1L shl 32)))
    }

    @Test
    fun theBoardsNamePrefixIsNotPartOfTheText() {
        val text = "see you at the trailhead"
        assertNotEquals(
            MeshcoreReactions.tag(text.encodeToByteArray(), 1_795_000_000),
            MeshcoreReactions.tag("Knit 1a2b: $text".encodeToByteArray(), 1_795_000_000),
        )
    }

    @Test
    fun bothOrdersParse() {
        val expected = MeshcoreReactions.Parsed(targetName = "Alpha", emoji = "👍", tag = "b45pc4ek")
        assertEquals(expected, MeshcoreReactions.parse("@[Alpha]👍\nb45pc4ek"))
        assertEquals("the order before 1.4.2", expected, MeshcoreReactions.parse("👍@[Alpha]\nb45pc4ek"))
    }

    @Test
    fun theTagIsReadCaseInsensitivelyWithCrockfordsSubstitutions() {
        assertEquals("b45pc4ek", MeshcoreReactions.parse("@[Alpha]👍\nB45PC4EK")?.tag)
        assertEquals("0110abcd", MeshcoreReactions.parse("@[Alpha]👍\nOILoabcd")?.tag)
    }

    @Test
    fun aMultiCodePointEmojiIsOneCluster() {
        assertEquals("👍🏽", MeshcoreReactions.parse("@[Alpha]👍🏽\nb45pc4ek")?.emoji)
        assertEquals("❤️", MeshcoreReactions.parse("@[Alpha]❤️\nb45pc4ek")?.emoji)
        assertEquals("👩‍👩‍👧", MeshcoreReactions.parse("@[Alpha]👩‍👩‍👧\nb45pc4ek")?.emoji)
    }

    @Test
    fun anythingElseIsAPlainPost() {
        assertNull("no line break", MeshcoreReactions.parse("@[Alpha]👍 b45pc4ek"))
        assertNull("a short tag", MeshcoreReactions.parse("@[Alpha]👍\nb45pc4e"))
        assertNull("a long tag", MeshcoreReactions.parse("@[Alpha]👍\nb45pc4ekk"))
        assertNull("a U is no Crockford symbol", MeshcoreReactions.parse("@[Alpha]👍\nb45pc4eu"))
        assertNull("text after the tag", MeshcoreReactions.parse("@[Alpha]👍\nb45pc4ek "))
        assertNull("two emoji", MeshcoreReactions.parse("@[Alpha]👍👍\nb45pc4ek"))
        assertNull("words", MeshcoreReactions.parse("@[Alpha] nice\nb45pc4ek"))
        assertNull("a letter", MeshcoreReactions.parse("@[Alpha]A\nb45pc4ek"))
        assertNull("no emoji", MeshcoreReactions.parse("@[Alpha]\nb45pc4ek"))
        assertNull("no token", MeshcoreReactions.parse("👍\nb45pc4ek"))
        assertNull("an empty name", MeshcoreReactions.parse("@[]👍\nb45pc4ek"))
        assertNull("a name no board can hold", MeshcoreReactions.parse("@[${"n".repeat(32)}]👍\nb45pc4ek"))
        assertNull("a reply's space", MeshcoreReactions.parse("@[Alpha] 👍\nb45pc4ek"))
        assertNull("a two-line post", MeshcoreReactions.parse("@[Alpha]👍\nsee you\nb45pc4ek"))
    }

    @Test
    fun anInvisibleFormatCharacterIsNoEmoji() {
        assertNull("a right-to-left override", MeshcoreReactions.parse("@[Alpha]\u202E\nb45pc4ek"))
        assertNull("a zero-width space", MeshcoreReactions.parse("@[Alpha]\u200B\nb45pc4ek"))
        assertNull("a byte-order mark", MeshcoreReactions.parse("@[Alpha]\uFEFF\nb45pc4ek"))
        assertNull("an override after an emoji", MeshcoreReactions.parse("@[Alpha]👍\u202E\nb45pc4ek"))
        assertEquals(
            "a subdivision flag's tag characters",
            "🏴󠁧󠁢󠁳󠁣󠁴󠁿",
            MeshcoreReactions.parse("@[Alpha]🏴󠁧󠁢󠁳󠁣󠁴󠁿\nb45pc4ek")?.emoji,
        )
    }

    @Test
    fun whatWeAirParsesBackInTheCurrentOrder() {
        val text = MeshcoreReactions.format("Sam 🧶", "🎉", "b45pc4ek")
        assertEquals("@[Sam 🧶]🎉\nb45pc4ek", text)
        assertEquals(MeshcoreReactions.Parsed("Sam 🧶", "🎉", "b45pc4ek"), MeshcoreReactions.parse(text!!))
    }

    @Test
    fun aReactionWeCannotWriteIsNotWritten() {
        assertNull("unnamed", MeshcoreReactions.format(null, "👍", "b45pc4ek"))
        assertNull("a bracket in the name", MeshcoreReactions.format("a]b", "👍", "b45pc4ek"))
        assertNull("two emoji", MeshcoreReactions.format("Alpha", "👍👍", "b45pc4ek"))
        assertNull("a tag that is not canonical", MeshcoreReactions.format("Alpha", "👍", "B45PC4EK"))
    }
}
