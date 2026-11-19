package app.getknit.knit.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MeshCore's `@[name]` reply and mention form (ADR 2026-11.gsqu's 2026-11-19 amendment), pinned on the JVM. */
class MeshcoreMentionsTest {
    @Test
    fun aReplyIsTheNameInBracketsAndASpace() {
        assertEquals("@[Bob] ", MeshcoreMentions.replyPrefix("Bob"))
        assertEquals("@[Sam 🧶] ", MeshcoreMentions.replyPrefix("Sam 🧶"))
        assertEquals("a full board name", "@[${"n".repeat(31)}] ", MeshcoreMentions.replyPrefix("n".repeat(31)))
    }

    @Test
    fun aNameTheTokenCannotCarryRepliesAsAPlainPost() {
        assertNull("unnamed", MeshcoreMentions.replyPrefix(null))
        assertNull("blank", MeshcoreMentions.replyPrefix(" "))
        assertNull("only invisible characters", MeshcoreMentions.replyPrefix("​"))
        assertNull("a closing bracket ends the token early", MeshcoreMentions.replyPrefix("a]b"))
        assertNull("an opening bracket", MeshcoreMentions.replyPrefix("a[b"))
        assertNull("longer than any board's name", MeshcoreMentions.replyPrefix("n".repeat(32)))
        assertNull("31 units but 62 bytes", MeshcoreMentions.replyPrefix("é".repeat(31)))
    }

    @Test
    fun everyReplyPrefixReadsBackAsItsOwnName() {
        for (name in listOf("Bob", "Sam 🧶", "Knit 1a2b", "n".repeat(31))) {
            assertEquals(name, MeshcoreMentions.names(MeshcoreMentions.replyPrefix(name) + "hi").single())
        }
    }

    @Test
    fun namesAreEveryWholeTokenInOrder() {
        assertEquals(listOf("Bob", "Sam"), MeshcoreMentions.names("@[Bob] ask @[Sam] too"))
        assertEquals("anywhere in a word", listOf("x"), MeshcoreMentions.names("me@[x]"))
        assertEquals("no empty token", emptyList<String>(), MeshcoreMentions.names("@[] hi"))
        assertEquals("no nested bracket", emptyList<String>(), MeshcoreMentions.names("@[a[b] hi"))
        assertEquals("no line break inside", emptyList<String>(), MeshcoreMentions.names("@[a\nb] hi"))
        assertEquals("no unclosed token", emptyList<String>(), MeshcoreMentions.names("@[Bob hi"))
        assertEquals("not a bare @", emptyList<String>(), MeshcoreMentions.names("@Bob hi"))
        assertEquals("over a board's name", emptyList<String>(), MeshcoreMentions.names("@[${"n".repeat(32)}]"))
        assertEquals("over 31 bytes in fewer units", emptyList<String>(), MeshcoreMentions.names("@[${"é".repeat(16)}]"))
    }

    @Test
    fun aMentionOfTheHandleMatchesByNameKeyAsWritten() {
        assertEquals("Walter", MeshcoreMentions.mentionOf("@[Walter] on my way", "Walter"))
        assertEquals("case", "walter", MeshcoreMentions.mentionOf("hey @[walter]", "Walter"))
        assertEquals("a lookalike letter", "Wаlter", MeshcoreMentions.mentionOf("@[Wаlter] hi", "Walter"))
        assertEquals("the first that names it", "WALTER", MeshcoreMentions.mentionOf("@[Sam] @[WALTER] @[walter]", "Walter"))
    }

    @Test
    fun noMentionWithoutTheHandle() {
        assertNull("someone else", MeshcoreMentions.mentionOf("@[Sam] hi", "Walter"))
        assertNull("a longer name", MeshcoreMentions.mentionOf("@[Walter W] hi", "Walter"))
        assertNull("the bare name", MeshcoreMentions.mentionOf("Walter, hi", "Walter"))
        assertNull("no handle known", MeshcoreMentions.mentionOf("@[Walter] hi", null))
        assertNull("a handle of only invisible characters", MeshcoreMentions.mentionOf("@[​] hi", "​"))
    }
}
