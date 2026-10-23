package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a translation must hand back untouched (links, positions, mentions) and what output is a model stuck in a loop
 * (ADR 2026-10.t2h6).
 */
class VerbatimSpansTest {
    @Test
    fun linksPositionsAndMentionsAreProtectedInOrder() {
        val body = "@Ana meet me at geo:40.416775,-3.703790;u=12 or see https://getknit.app/ now"
        val mention = 0 until 4
        assertEquals(
            listOf("@Ana", "geo:40.416775,-3.703790;u=12", "https://getknit.app/"),
            VerbatimSpans.spans(body, listOf(mention)),
        )
    }

    @Test
    fun theBodyIsCutAtItsSpansAndBlankRunsStayVerbatim() {
        val body = "check https://a.example/x https://b.example/y now"
        assertEquals(
            listOf(
                BodyPiece.Words("check "),
                BodyPiece.Verbatim("https://a.example/x"),
                BodyPiece.Verbatim(" "),
                BodyPiece.Verbatim("https://b.example/y"),
                BodyPiece.Words(" now"),
            ),
            VerbatimSpans.pieces(body),
        )
        assertEquals(body, VerbatimSpans.pieces(body).joinToString("") { it.text })
    }

    @Test
    fun overlappingRangesMergeAndOutOfBoundsMentionsAreIgnored() {
        val body = "hi @Ana Maria there"
        assertEquals(listOf(3..12), VerbatimSpans.ranges(body, listOf(3 until 7, 3 until 13, 40 until 44)))
    }

    @Test
    fun theIdentifierReadsTheWordsAlone() {
        assertEquals("mira esto", VerbatimSpans.wordsOnly("mira esto https://getknit.app/"))
        assertEquals("", VerbatimSpans.wordsOnly("https://getknit.app/"))
    }

    @Test
    fun aRunOfOneCharacterTheSourceNeverHadIsALoop() {
        // The corpus's own: "congrats!! so happy for you" came back as twenty-three of these.
        assertFalse(TranslationCheck.usable("congrats!! so happy for you", "¡".repeat(23)))
        // A run the source had itself is fine.
        assertTrue(TranslationCheck.usable("!!!!!!!!!! yes", "¡¡¡¡¡¡¡¡¡¡ sí"))
    }

    @Test
    fun aRepeatedWordTheSourceNeverHadIsALoop() {
        // Short enough to pass the length rule: the repeated word alone refuses it.
        assertFalse(
            TranslationCheck.usable("Hey, are you coming to the park later with the others?", "adelante " + "acusación ".repeat(10)),
        )
        assertTrue(TranslationCheck.usable("no no no no", "no no no no"))
    }

    @Test
    fun anOutputFarLongerThanItsSourceOrEmptyIsRefused() {
        // Distinct words, no run of anything: only the length rule can refuse it. Its neighbour just inside passes.
        val words = (1..40).joinToString(" ") { "w$it" }
        assertFalse(TranslationCheck.usable("hola amigo", words))
        val limit = "hola amigo".length * TranslationCheck.MAX_GROWTH + 24
        assertTrue(TranslationCheck.usable("hola amigo", words.take(limit).trimEnd()))
        assertFalse(TranslationCheck.usable("hola", ""))
        assertTrue(TranslationCheck.usable("¿Vienes al parque más tarde?", "Are you coming to the park later?"))
    }
}
