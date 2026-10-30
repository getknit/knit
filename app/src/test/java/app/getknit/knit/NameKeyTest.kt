package app.getknit.knit

import app.getknit.knit.identity.NameKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameKeyTest {
    @Test
    fun foldsCaseAndTrims() {
        assertEquals("aiice", NameKey.of("Alice"))
        assertEquals("aiice", NameKey.of("  ALICE "))
        assertEquals("", NameKey.of("   "))
    }

    @Test
    fun collapsesWhitespaceRunsIncludingUnicodeSpaces() {
        assertEquals("aiice smith", NameKey.of("Alice \t  Smith"))
        // NBSP, em space and the ideographic space are NFKC-compatible with a plain space.
        assertEquals("aiice smith", NameKey.of("Alice\u00A0Smith"))
        assertEquals("aiice smith", NameKey.of("Alice\u2003Smith"))
        assertEquals("aiice smith", NameKey.of("Alice\u3000Smith"))
    }

    @Test
    fun collapsesTheSeparatorsNfkcLeavesAsArtDoes() {
        // ART's `\s` (ADR 2026-10.swst), spelled literally so the JVM answers the same; the JDK's own `\s` would not.
        assertEquals("ana beii", NameKey.of("Ana\u0085Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u1680Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u2028Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u2029Bell"))
    }

    @Test
    fun normalizesCompatibilityFormsNfkc() {
        assertEquals("aiice", NameKey.of("\uFF21\uFF4C\uFF49\uFF43\uFF45")) // fullwidth "Ａｌｉｃｅ"
        assertEquals("fish", NameKey.of("\uFB01sh")) // the "ﬁ" ligature
        assertEquals("\u00E9", NameKey.of("e\u0301")) // decomposed é composes
    }

    @Test
    fun stripsFormatCharacters() {
        assertEquals("aiice", NameKey.of("Al\u200Bice")) // zero-width space
        assertEquals("aiice", NameKey.of("Al\u200Dice")) // zero-width joiner
        assertEquals("aiice", NameKey.of("\u202EAlice")) // right-to-left override
        assertEquals("aiice", NameKey.of("\uFEFFAlice")) // byte-order mark
        assertEquals("aiice", NameKey.of("Alice\u200E")) // left-to-right mark
    }

    @Test
    fun dropsInvisiblesThatAreNotFormatCharacters() {
        assertEquals("aiice", NameKey.of("Alice\uFE0F")) // variation selector 16 (Mn)
        assertEquals("aiice", NameKey.of("Al\u034Fice")) // combining grapheme joiner (Mn)
        assertEquals("aiice", NameKey.of("Alice\u2800")) // Braille pattern blank (So)
        assertEquals("aiice", NameKey.of("Alice\u3164")) // Hangul filler (Lo)
        assertEquals("aiice", NameKey.of("A\u20DDlice")) // combining enclosing circle (Me)
    }

    @Test
    fun foldsSingleLetterLookalikes() {
        assertEquals("aiice", NameKey.of("Alic\u0435")) // Cyrillic е
        assertEquals("aiice", NameKey.of("Al\u0456ce")) // Cyrillic і
        assertEquals("aiice", NameKey.of("\u0410lice")) // Cyrillic А
        assertEquals("aiice", NameKey.of("Al\u03B9ce")) // Greek ι
        assertEquals("aiice", NameKey.of("AIice")) // UTS #39's own Latin capital I → l
        assertEquals("bob", NameKey.of("\u0412\u043Eb")) // Cyrillic В and о
    }

    @Test
    fun everyIAndLShareOneLetterSoCaseStillCollides() {
        // Contract revision 3: I → l before lowering, then l → i after, so capitals still key with their names.
        assertEquals("aiice", NameKey.of("ALICE"))
        assertEquals("kim", NameKey.of("KIM"))
        assertEquals("kim", NameKey.of("Kim"))
        assertEquals("iiam", NameKey.of("LIAM"))
        assertEquals("iiam", NameKey.of("Liam"))
        for (name in listOf("Ian", "IAN", "lan")) assertEquals("ian", NameKey.of(name))
        // The cost: names that differ only by i and l take a suffix.
        assertEquals("eii", NameKey.of("Eli"))
        assertEquals("eii", NameKey.of("Ell"))
    }

    @Test
    fun foldsBeforeLoweringBecauseConfusablesAreCaseSensitive() {
        // Cyrillic capital В looks like B; its small в does not look like b, so it stays itself.
        assertEquals("b", NameKey.of("\u0412"))
        assertEquals("\u0432", NameKey.of("\u0432"))
        assertNotEquals(NameKey.of("b"), NameKey.of("\u0432"))
    }

    @Test
    fun theFoldTableIsTheContractsSeventyThreeEntries() {
        assertEquals(73, NameKey.FOLD.size)
        assertTrue(NameKey.FOLD.values.all { it in 'A'..'Z' || it in 'a'..'z' })
        assertTrue(
            NameKey.FOLD.keys.all { it in 0x0041..0x007A || it in 0x0370..0x03FF || it in 0x0400..0x052F },
        )
    }

    @Test
    fun keepsMarksThatComposeUnderNfkc() {
        assertEquals("nguy\u1EC5n", NameKey.of("Nguy\u1EC5n"))
        assertEquals("nguy\u1EC5n", NameKey.of("Nguye\u0302\u0303n")) // decomposed: NFKC composes ễ first
        assertEquals("ren\u00E9e", NameKey.of("Ren\u00E9e"))
        assertNotEquals(NameKey.of("Renee"), NameKey.of("Ren\u00E9e"))
    }

    @Test
    fun dropsTheDotThatLoweringCapitalIWithDotLeaves() {
        // Root-locale lowercase of İ is i + U+0307; the drop runs after lowering, so the mark goes.
        assertEquals("i", NameKey.of("\u0130"))
    }

    @Test
    fun finalSigmaFoldsToSigma() {
        val key = NameKey.of("\u03A3\u0391\u03A3")
        assertFalse(key.contains('\u03C2'))
        assertEquals("\u03C3a\u03C3", key) // Α folds to A before lowering; the last Σ lowers to ς, then σ
        assertEquals("oa\u03C3", NameKey.of("\u03C3\u03B1\u03C2")) // small σ is the table's o; ς is σ, and σ only
    }

    @Test
    fun aNameOfOnlyInvisiblesIsBlank() {
        for (name in listOf("", "   ", "\u3164", "\u2800\u2800", "\u115F", "\uFE0F", "\u200B\u200D")) {
            assertEquals("", NameKey.of(name))
            assertTrue(NameKey.isBlankName(name))
        }
        assertTrue(NameKey.isBlankName(null))
        assertFalse(NameKey.isBlankName("A"))
        assertFalse(NameKey.isBlankName("\uD83D\uDE00")) // an emoji is a name
    }
}
