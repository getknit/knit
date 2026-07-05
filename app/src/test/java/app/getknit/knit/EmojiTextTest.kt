package app.getknit.knit

import app.getknit.knit.ui.chat.emojiOnlyCount
import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiTextTest {
    // Emoji written as explicit code points (with the rendered glyph in a comment) so the cases are
    // unambiguous regardless of how the source file is encoded/displayed.
    private val grinning = "😀" // 😀 U+1F600
    private val joy = "😂" // 😂 U+1F602
    private val family = "👨‍👩‍👧" // 👨‍👩‍👧 (ZWJ sequence)
    private val thumbsUpTone = "👍🏽" // 👍🏽 (thumbs-up + medium skin tone)
    private val flagUs = "🇺🇸" // 🇺🇸 (regional-indicator pair)
    private val keycapOne = "1️⃣" // 1️⃣ (digit + VS16 + combining keycap)
    private val redHeart = "❤️" // ❤️ (heart + VS16, emoji presentation)

    // --- single emoji cluster == 1 ---

    @Test
    fun singleEmojiCountsAsOne() {
        assertEquals(1, emojiOnlyCount(grinning))
    }

    @Test
    fun zwjSequenceIsOneCluster() {
        assertEquals(1, emojiOnlyCount(family))
    }

    @Test
    fun skinToneModifierIsOneCluster() {
        assertEquals(1, emojiOnlyCount(thumbsUpTone))
    }

    @Test
    fun regionalIndicatorPairIsOneFlag() {
        assertEquals(1, emojiOnlyCount(flagUs))
    }

    @Test
    fun keycapSequenceIsOneEmoji() {
        assertEquals(1, emojiOnlyCount(keycapOne))
    }

    @Test
    fun variationSelectorEmojiCountsAsOne() {
        assertEquals(1, emojiOnlyCount(redHeart))
    }

    // --- whitespace is ignored ---

    @Test
    fun whitespaceBetweenEmojiIsIgnored() {
        assertEquals(2, emojiOnlyCount("$grinning $joy"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals(1, emojiOnlyCount("   $grinning   "))
    }

    // --- the tier cap ---

    @Test
    fun fiveEmojiAreStillCounted() {
        assertEquals(5, emojiOnlyCount(grinning.repeat(5)))
    }

    @Test
    fun sixEmojiExceedTheCapAndReturnZero() {
        assertEquals(0, emojiOnlyCount(grinning.repeat(6)))
    }

    // --- non-emoji bodies return 0 ---

    @Test
    fun plainTextIsNotEmoji() {
        assertEquals(0, emojiOnlyCount("hello"))
    }

    @Test
    fun emojiMixedWithTextIsNotEmojiOnly() {
        assertEquals(0, emojiOnlyCount("$grinning hi"))
    }

    @Test
    fun bareDigitIsNotEmoji() {
        // '1' carries the Unicode Emoji property only as a keycap base; alone it is plain text.
        assertEquals(0, emojiOnlyCount("1"))
    }

    @Test
    fun bareHashIsNotEmoji() {
        assertEquals(0, emojiOnlyCount("#"))
    }

    @Test
    fun emptyStringIsZero() {
        assertEquals(0, emojiOnlyCount(""))
    }

    @Test
    fun blankStringIsZero() {
        assertEquals(0, emojiOnlyCount("   "))
    }
}
