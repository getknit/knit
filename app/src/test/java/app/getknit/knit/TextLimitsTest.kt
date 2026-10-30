package app.getknit.knit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLimitsTest {
    @Test
    fun `trims leading and trailing whitespace`() {
        assertEquals("hi", normalizeSingleLine("  hi  "))
    }

    @Test
    fun `collapses interior whitespace runs to a single space`() {
        assertEquals("hello world", normalizeSingleLine("hello    world"))
    }

    @Test
    fun `collapses stray newlines and tabs from a paste`() {
        assertEquals("a b c", normalizeSingleLine("a\n\tb  \r\n c"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", normalizeSingleLine(""))
    }

    @Test
    fun `all-whitespace input normalizes to empty`() {
        assertEquals("", normalizeSingleLine("   \n\t "))
    }

    @Test
    fun `a single token is unchanged`() {
        assertEquals("knit", normalizeSingleLine("knit"))
    }

    @Test
    fun `collapses the separators ART calls whitespace, on the JVM too`() {
        // The literal ART `\s` class (ADR 2026-10.swst): the JDK's own `\s` would leave these four alone.
        assertEquals("a b c d", normalizeSingleLine("a\u0085b\u1680c\u2029d"))
        assertEquals("Ana Bell Cruz", normalizeSingleLine("\u00A0 Ana\u00A0\u00A0Bell\u2028Cruz\u3000"))
    }

    // --- cleanReceived: received display text made single-line and control-free ---

    @Test
    fun `cleanReceived turns a line break into one space`() {
        assertEquals("Alice Smith", TextLimits.cleanReceived("Alice\nSmith", TextLimits.DISPLAY_NAME))
        assertEquals("a b", TextLimits.cleanReceived("a\u2028\u2029b", TextLimits.DISPLAY_NAME))
    }

    @Test
    fun `cleanReceived removes controls and bidi overrides`() {
        assertEquals("Bobevil", TextLimits.cleanReceived("Bob\u202Eevil", TextLimits.DISPLAY_NAME))
        assertEquals("Bobevil", TextLimits.cleanReceived("Bob\u0000\u0007\u001Bevil\u007F\u009B", TextLimits.DISPLAY_NAME))
        assertEquals("ab", TextLimits.cleanReceived("a\u061C\u200E\u200F\u2066\u2069b", TextLimits.DISPLAY_NAME))
        assertEquals("", TextLimits.cleanReceived("\u0001\u0007", TextLimits.DISPLAY_NAME))
    }

    @Test
    fun `cleanReceived trims a leading U+0085, which is regex whitespace but not isWhitespace`() {
        assertEquals("Bob", TextLimits.cleanReceived("\u0085Bob", TextLimits.DISPLAY_NAME))
    }

    @Test
    fun `cleanReceived keeps the joiners emoji sequences and Persian names need`() {
        val dev = "\uD83D\uDC69\u200D\uD83D\uDCBB Dev" // 👩‍💻 Dev
        assertEquals(dev, TextLimits.cleanReceived(dev, TextLimits.DISPLAY_NAME))
        assertEquals(
            "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
            TextLimits.cleanReceived("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645", 32),
        )
    }

    @Test
    fun `cleanReceived cuts to the limit and leaves no trailing space or half a pair`() {
        val spaced = "a".repeat(31) + " " + "b".repeat(8) // 40 units, the cut lands after the space
        assertEquals("a".repeat(31), TextLimits.cleanReceived(spaced, TextLimits.DISPLAY_NAME))
        val paired = "a".repeat(31) + "\uD83D\uDE00" + "b".repeat(7) // the cut would split the emoji
        assertEquals("a".repeat(31), TextLimits.cleanReceived(paired, TextLimits.DISPLAY_NAME))
    }

    @Test
    fun `cleanReceived is idempotent`() {
        val inputs =
            listOf(
                "\u0085Bob",
                "Bob\u0085",
                " \u0085 Bob \u0085 ",
                "Alice\nSmith",
                "Bob\u202Eevil",
                "\u0001\u0007",
                "\u0001 \u0002 x",
                "a".repeat(31) + " " + "b".repeat(8),
                "a".repeat(31) + "\uD83D\uDE00" + "b".repeat(7),
                "\u3164\u2800",
                "Ana\u2028\u2028Bell\u1680",
            )
        for (input in inputs) {
            for (limit in listOf(TextLimits.DISPLAY_NAME, TextLimits.STATUS, 5)) {
                val once = TextLimits.cleanReceived(input, limit)
                assertEquals("<$input> at $limit", once, TextLimits.cleanReceived(once, limit))
            }
        }
    }

    // --- isValidReactionEmoji: the length-only cap shared by the sender and both inbound paths ---

    @Test
    fun `the longest RGI sequences are well under the reaction cap`() {
        // The cap's rationale: ~2x the worst case Unicode ships today, so it is never the limiting factor.
        assertEquals(15, KISS_WITH_TONES.length)
        assertEquals(14, ENGLAND_FLAG.length)
        assertTrue(isValidReactionEmoji("👍"))
        assertTrue(isValidReactionEmoji("❤️"))
        assertTrue(isValidReactionEmoji(KISS_WITH_TONES))
        assertTrue(isValidReactionEmoji(ENGLAND_FLAG))
    }

    @Test
    fun `exactly the cap is accepted and one more unit is refused`() {
        assertTrue(isValidReactionEmoji("👍".repeat(TextLimits.REACTION / 2))) // 32 units
        assertFalse(isValidReactionEmoji("👍".repeat(TextLimits.REACTION / 2 + 1))) // 34 units
    }

    @Test
    fun `blank is refused rather than read as a retraction`() {
        assertFalse(isValidReactionEmoji(""))
        assertFalse(isValidReactionEmoji(" "))
    }

    @Test
    fun `a 32-unit cut through an emoji keeps whole code points`() {
        // 31 units of name, then 👋 (a surrogate pair): take(32) would keep a lone high surrogate.
        val name = "x".repeat(31) + "\uD83D\uDC4B"
        assertEquals("x".repeat(31), name.clampToUtf16(TextLimits.GROUP_NAME))
        assertEquals("\uD83D", name.take(TextLimits.GROUP_NAME).takeLast(1)) // what the old cut kept
        // A cut that lands between two pairs keeps both whole, and a short string is returned as it is.
        assertEquals("x".repeat(30) + "\uD83D\uDC4B", ("x".repeat(30) + "\uD83D\uDC4B\uD83D\uDC4B").clampToUtf16(32))
        assertEquals("ok", "ok".clampToUtf16(32))
        assertEquals("", "\uD83D\uDC4B".clampToUtf16(1))
        assertEquals("", "abc".clampToUtf16(0))
    }

    @Test
    fun `an ellipsized cut ends in an ellipsis inside the limit and a fitting string is untouched`() {
        assertEquals("ok", "ok".ellipsizeToUtf16(5))
        assertEquals("hello", "hello".ellipsizeToUtf16(5))
        assertEquals("hell…", "hello!".ellipsizeToUtf16(5))
        // A cut landing after a space drops it, and one through a surrogate pair keeps whole code points.
        assertEquals("one…", "one two".ellipsizeToUtf16(5))
        assertEquals("x…", "x\uD83D\uDC4Bz".ellipsizeToUtf16(3))
    }

    @Test
    fun `a cut of a zwj sequence keeps whole code points, never a lone surrogate`() {
        for (limit in 1..KISS_WITH_TONES.length) {
            val cut = KISS_WITH_TONES.clampToUtf16(limit)
            assertTrue(cut.length <= limit)
            assertTrue("no split pair at $limit", cut.isEmpty() || !Character.isHighSurrogate(cut.last()))
            assertTrue(KISS_WITH_TONES.startsWith(cut))
        }
    }

    private companion object {
        /** 👩🏽‍❤️‍💋‍👨🏼 — a two-person kiss with skin tones, the longest RGI ZWJ sequence (10 code points, 35 B UTF-8). */
        const val KISS_WITH_TONES = "\uD83D\uDC69\uD83C\uDFFD\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83C\uDFFC"

        /** 🏴󠁧󠁢󠁥󠁮󠁧󠁿 — England, a tag-sequence subdivision flag (7 code points, 28 B UTF-8). */
        const val ENGLAND_FLAG = "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F"
    }
}
