package app.getknit.knit.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerdictTest {
    @Test
    fun `TextVerdict ALLOWED is a permissive, unflagged default`() {
        val v = TextVerdict.ALLOWED
        assertTrue(v.allowed)
        assertFalse(v.flagged)
        assertEquals(TextVerdict.Category.NONE, v.category)
        assertEquals(0f, v.score, 0f)
        assertNull(v.label)
    }

    @Test
    fun `TextVerdict flagged is the inverse of allowed`() {
        val blocked = TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY, score = 0.9f, label = "toxicity")
        assertTrue(blocked.flagged)
        assertFalse(TextVerdict(allowed = true).flagged)
    }

    @Test
    fun `ImageVerdict ALLOWED is a permissive, unflagged default`() {
        val v = ImageVerdict.ALLOWED
        assertTrue(v.allowed)
        assertFalse(v.flagged)
        assertEquals(0f, v.score, 0f)
    }

    @Test
    fun `ImageVerdict flagged is the inverse of allowed`() {
        assertTrue(ImageVerdict(allowed = false, score = 0.8f).flagged)
        assertFalse(ImageVerdict(allowed = true).flagged)
    }

    @Test
    fun `text categories cover none, profanity, and toxicity`() {
        assertEquals(
            listOf(TextVerdict.Category.NONE, TextVerdict.Category.PROFANITY, TextVerdict.Category.TOXICITY),
            TextVerdict.Category.entries.toList(),
        )
    }
}
