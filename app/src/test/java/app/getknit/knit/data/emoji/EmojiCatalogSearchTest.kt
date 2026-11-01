package app.getknit.knit.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiCatalogSearchTest {
    private val thumbsUp = entry("👍", EmojiGroup.PEOPLE, "thumbs up", keywords = listOf("+1", "good", "like", "thumb"))
    private val thumbsUpLight = entry("👍🏻", EmojiGroup.PEOPLE, "thumbs up: light skin tone", tone = true)
    private val thumbsDown = entry("👎", EmojiGroup.PEOPLE, "thumbs down")
    private val redHeart = entry("❤️", EmojiGroup.SMILEYS, "red heart")
    private val heartEyes = entry("😍", EmojiGroup.SMILEYS, "smiling face with heart-eyes")
    private val greenHeart = entry("💚", EmojiGroup.SMILEYS, "green heart")
    private val upArrow = entry("⬆️", EmojiGroup.SYMBOLS, "up arrow")
    private val cupcake = entry("🧁", EmojiGroup.FOOD, "cupcake")
    private val hundred = entry("💯", EmojiGroup.SMILEYS, "hundred points", keywords = listOf("100", "full", "hundred", "score"))
    private val iceCream = entry("🍦", EmojiGroup.FOOD, "soft ice cream", keywords = listOf("cone", "dessert", "soft-serve"))
    private val catalog = EmojiCatalog(listOf(redHeart, heartEyes, greenHeart, thumbsUp, thumbsUpLight, thumbsDown, cupcake, upArrow))
    private val withKeywords = EmojiCatalog(listOf(hundred, thumbsUp, thumbsUpLight, iceCream))

    @Test
    fun blankQueryIsEmpty() {
        assertTrue(catalog.search("").isEmpty())
        assertTrue(catalog.search("   ").isEmpty())
    }

    @Test
    fun caseInsensitiveWithBaseBeforeItsToneVariants() {
        assertEquals(listOf(thumbsUp, thumbsUpLight, thumbsDown), catalog.search("THUMBS"))
    }

    @Test
    fun ranksExactThenPrefixThenWordPrefixThenSubstring() {
        // "up": exact none; prefix "up arrow"; word-prefix "thumbs up", "thumbs up: light…"; substring "cupcake".
        assertEquals(listOf(upArrow, thumbsUp, thumbsUpLight, cupcake), catalog.search("up"))
        assertEquals(redHeart, catalog.search("red heart").first())
    }

    @Test
    fun everyTokenMustMatch() {
        assertEquals(listOf(redHeart), catalog.search("red heart"))
        assertEquals(listOf(redHeart, heartEyes, greenHeart), catalog.search("heart"))
    }

    @Test
    fun aKeywordFindsAnEmojiItsNameDoesNotContain() {
        assertEquals(listOf(hundred), withKeywords.search("100"))
        assertEquals(listOf(hundred), withKeywords.search("sco"))
        assertEquals(listOf(thumbsUp), withKeywords.search("like"))
    }

    @Test
    fun keywordsArePrefixMatchedNotSubstringMatched() {
        // "one" is inside the keyword "cone", but no keyword word begins with it (the light thumbs-up still
        // matches: "one" is a substring of its *name*, "skin tone").
        assertEquals(listOf(thumbsUpLight), withKeywords.search("one"))
        assertEquals(listOf(iceCream), withKeywords.search("con"))
    }

    @Test
    fun aKeywordWordPrefixMatchesInsideAMultiWordKeyword() {
        assertEquals(listOf(iceCream), withKeywords.search("serve"))
    }

    @Test
    fun aWholeKeywordWithABreakCharacterInItStillMatches() {
        // "-1" splits into "" and "1" as words; the whole-keyword prefix is what finds it.
        val down = entry("👎", EmojiGroup.PEOPLE, "thumbs down", keywords = listOf("-1", "bad", "thumb"))
        assertEquals(listOf(down), EmojiCatalog(listOf(thumbsUp, down)).search("-1"))
    }

    @Test
    fun nameMatchesRankAboveKeywordMatchesAndTokensMayMixTheTwo() {
        // The keyword-only trophy is listed first, so only the rank can put the name prefix ahead of it.
        val trophy = entry("🏆", EmojiGroup.ACTIVITIES, "trophy", keywords = listOf("thumb"))
        assertEquals(listOf(thumbsUp, trophy), EmojiCatalog(listOf(trophy, thumbsUp)).search("thumb"))
        // A keyword prefix ranks above a mere name substring listed before it ("one" in "skin tone").
        val baby = entry("👶", EmojiGroup.PEOPLE, "baby", keywords = listOf("onesie"))
        assertEquals(listOf(baby, thumbsUpLight), EmojiCatalog(listOf(thumbsUpLight, baby)).search("one"))
        assertEquals(listOf(thumbsUp), withKeywords.search("up good"))
        assertEquals(listOf(thumbsUpLight), withKeywords.search("thumbs light"))
    }

    @Test
    fun anExactKeywordRanksAboveAKeywordPrefix() {
        val score = entry("🎯", EmojiGroup.ACTIVITIES, "bullseye", keywords = listOf("scoreboard"))
        assertEquals(listOf(hundred, score), EmojiCatalog(listOf(score, hundred)).search("score"))
    }

    @Test
    fun accentsAndCaseAreFoldedOnBothSides() {
        val heart = entry("❤️", EmojiGroup.SMILEYS, "corazón rojo", keywords = listOf("emoción"))
        val folded = EmojiCatalog(listOf(heart))
        assertEquals(listOf(heart), folded.search("corazon"))
        assertEquals(listOf(heart), folded.search("CORAZÓN"))
        assertEquals(listOf(heart), folded.search("emocion"))
    }

    @Test
    fun aLocalizedCatalogShowsTheOverlayNameAndStillAnswersEnglish() {
        val pt =
            withKeywords.localized(
                mapOf(
                    "💯" to LocalizedEmoji("cem pontos", listOf("cem", "certeza")),
                    "👍🏻" to LocalizedEmoji("polegar para cima: pele clara"),
                ),
            )
        val cem = pt.entries.first { it.emoji == "💯" }
        assertEquals("cem pontos", cem.name)
        assertEquals("hundred points", cem.englishName)
        assertEquals(listOf("cem", "certeza", "100", "full", "hundred", "score"), cem.keywords)
        // Localized name, localized keyword, English name, English keyword.
        assertEquals(listOf(cem), pt.search("pontos"))
        assertEquals(listOf(cem), pt.search("certeza"))
        assertEquals(listOf(cem), pt.search("hundred points"))
        assertEquals(listOf(cem), pt.search("score"))
        // An entry the overlay lacks stays English; order and tone flags are kept.
        assertEquals(withKeywords.entries.map { it.emoji }, pt.entries.map { it.emoji })
        assertEquals("thumbs up", pt.entries.first { it.emoji == "👍" }.name)
        assertEquals(listOf("👍🏻"), pt.search("pele clara").map { it.emoji })
        assertEquals(withKeywords.browse.map { it.emoji }, pt.browse.map { it.emoji })
    }

    @Test
    fun anEnglishNameMatchRanksLikeALocalizedOne() {
        // "thumb" is a name prefix only in English; the keyword-only trophy is listed first.
        val trophy = entry("🏆", EmojiGroup.ACTIVITIES, "troféu", keywords = listOf("thumb"))
        val pt = EmojiCatalog(listOf(trophy, thumbsUp)).localized(mapOf("👍" to LocalizedEmoji("polegar para cima")))
        assertEquals(listOf("👍", "🏆"), pt.search("thumb").map { it.emoji })
    }

    @Test
    fun anEmptyOverlayIsTheSameCatalog() {
        assertTrue(withKeywords.localized(emptyMap()) === withKeywords)
    }

    @Test
    fun aPastedEmojiComesFirst() {
        assertEquals(listOf(thumbsUp), catalog.search(" 👍 "))
    }

    @Test
    fun limitIsHonoured() {
        assertEquals(2, catalog.search("heart", limit = 2).size)
    }

    @Test
    fun browseHidesToneVariantsAndByGroupKeepsGroupOrder() {
        assertEquals(listOf(redHeart, heartEyes, greenHeart, thumbsUp, thumbsDown, cupcake, upArrow), catalog.browse)
        assertEquals(listOf(EmojiGroup.SMILEYS, EmojiGroup.PEOPLE, EmojiGroup.FOOD, EmojiGroup.SYMBOLS), catalog.byGroup.keys.toList())
        assertTrue(EmojiGroup.FLAGS !in catalog.byGroup)
    }

    private fun entry(
        emoji: String,
        group: EmojiGroup,
        name: String,
        tone: Boolean = false,
        keywords: List<String> = emptyList(),
    ) = EmojiEntry(emoji, group, name, tone, keywords)
}
