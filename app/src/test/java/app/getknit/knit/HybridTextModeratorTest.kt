package app.getknit.knit

import app.getknit.knit.moderation.HybridTextModerator
import app.getknit.knit.moderation.LexicalTextFilter
import app.getknit.knit.moderation.TextModerator
import app.getknit.knit.moderation.TextVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridTextModeratorTest {

    /** Records how many times it was consulted, so we can assert the lexical short-circuit. */
    private class FakeTextModerator(private val verdict: TextVerdict) : TextModerator {
        var calls = 0
        override suspend fun classify(text: String): TextVerdict {
            calls++
            return verdict
        }
    }

    private val flagAll = TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

    @Test
    fun lexicalHitShortCircuitsTheMlClassifier() = runTest {
        val ml = FakeTextModerator(flagAll)
        val hybrid = HybridTextModerator(LexicalTextFilter(blockedWords = listOf("fuck")), ml)

        val verdict = hybrid.classify("fuck this")

        assertTrue(verdict.flagged)
        assertEquals("ML must not run once the lexical pass already flagged", 0, ml.calls)
    }

    @Test
    fun cleanTextFallsThroughToTheMlClassifier() = runTest {
        val ml = FakeTextModerator(flagAll)
        // Empty lexical list -> lexical always passes, so the ML verdict decides.
        val hybrid = HybridTextModerator(LexicalTextFilter(blockedWords = emptyList()), ml)

        val verdict = hybrid.classify("you are awful")

        assertTrue(verdict.flagged)
        assertEquals(1, ml.calls)
    }

    @Test
    fun bothPassMeansAllowed() = runTest {
        val ml = FakeTextModerator(TextVerdict.ALLOWED)
        val hybrid = HybridTextModerator(LexicalTextFilter(blockedWords = listOf("fuck")), ml)

        assertFalse(hybrid.classify("hello there").flagged)
        assertEquals(1, ml.calls)
    }

    @Test
    fun withoutAnMlModelItIsLexicalOnly() = runTest {
        val hybrid = HybridTextModerator(LexicalTextFilter(blockedWords = listOf("fuck")), ml = null)

        assertFalse(hybrid.classify("hello there").flagged)
        assertTrue(hybrid.classify("fuck").flagged)
    }
}
