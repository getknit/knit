package app.getknit.knit.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Records that the desktop JDK these JVM tests run on does **not** answer the way a phone does: its regex `\s`
 * is ASCII, while ART's (ICU) is Unicode White_Space. The law for both ports is ART's, pinned on a device by
 * the instrumented `ArtTextSemanticsTest` (ADR 2026-10.swst). If this test fails, the JDK changed — never
 * "fix" the ART lists from a JVM run. Our own functions no longer read the JDK's `\s`: `normalizeSingleLine` and
 * `NameKey` collapse a literal class of ART's set (`ART_WHITESPACE_RUN`, ADR 2026-10.2kfr), so they answer the same
 * on the JVM as on a phone, and this test records only the JDK's own difference.
 */
class DesktopJdkWhitespaceTest {
    @Test
    fun desktopRegexWhitespaceIsAsciiOnly() {
        val whitespace = Regex("\\s")
        val matched = (0x0000..0xFFFF).filter { whitespace.matches(it.toChar().toString()) }
        assertEquals(listOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20), matched)
    }

    @Test
    fun desktopKotlinIsWhitespaceMissesMongolianVowelSeparator() {
        // ART's Character.isWhitespace hard-codes U+180E; the desktop JDK follows Unicode 6.3+ (a format char).
        assertEquals(false, '\u180E'.isWhitespace())
    }
}
