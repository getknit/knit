package app.getknit.knit.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A fallback avatar draws the first letter of a name, uppercased, passing over what is no letter of it: the
 * punctuation and direction marks a translation or the pseudolocales open "You" with drew a "[" or nothing.
 */
class AvatarInitialTest {
    @Test
    fun aNameGivesItsFirstLetterUppercased() {
        assertEquals("A", avatarInitial("ada"))
        assertEquals("B", avatarInitial("  bo"))
        assertEquals("4", avatarInitial("42"))
    }

    @Test
    fun leadingPunctuationIsPassedOver() {
        assertEquals("Y", avatarInitial("(You)"))
        assertEquals("Ŷ", avatarInitial("[Ŷôû one]"))
        assertEquals("S", avatarInitial("«Sam»"))
        assertEquals("J", avatarInitial("-_ Jo"))
        // Closing brackets and quotes, a line and a paragraph separator, a control character.
        assertEquals("Z", avatarInitial(")»\u2028\u2029\u0001Zed"))
    }

    @Test
    fun leadingDirectionMarksArePassedOver() {
        // RLM, RLE, the word, PDF: an Arabic label wrapped for a right-to-left layout.
        assertEquals("أ", avatarInitial("\u200F\u202Bأنت\u202C"))
    }

    @Test
    fun anEmojiIsKeptWholeAfterWhatIsPassedOver() {
        // A skin-toned thumb (two code points) and a ZWJ family (three) are one grapheme each.
        assertEquals("👍🏽", avatarInitial("(👍🏽 ok)"))
        assertEquals("👩\u200D👧", avatarInitial("«👩\u200D👧»"))
    }

    @Test
    fun aNameWithNoLetterIsAQuestionMark() {
        assertEquals("?", avatarInitial(""))
        assertEquals("?", avatarInitial("   "))
        assertEquals("?", avatarInitial("!?…"))
        assertEquals("?", avatarInitial("\u200F"))
    }
}
