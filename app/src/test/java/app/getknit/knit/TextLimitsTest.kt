package app.getknit.knit

import org.junit.Assert.assertEquals
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
}
