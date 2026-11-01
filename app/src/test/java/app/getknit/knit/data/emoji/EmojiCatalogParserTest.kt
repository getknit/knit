package app.getknit.knit.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiCatalogParserTest {
    @Test
    fun parsesDataLinesAndSkipsCommentsAndBlanks() {
        val parsed =
            EmojiCatalogParser.parse(
                sequenceOf("# header", "", "😀\t0\t0\tgrinning face", "   ", "👍🏻\t1\t1\tthumbs up: light skin tone\r"),
            )
        assertEquals(
            listOf(
                EmojiEntry("😀", EmojiGroup.SMILEYS, "grinning face", toneVariant = false),
                EmojiEntry("👍🏻", EmojiGroup.PEOPLE, "thumbs up: light skin tone", toneVariant = true),
            ),
            parsed,
        )
    }

    @Test
    fun theKeycapHashRowIsDataNotAComment() {
        val parsed = EmojiCatalogParser.parse(sequenceOf("# a comment", "#️⃣\t7\t0\tkeycap: #\tkeycap|hash"))
        assertEquals(listOf("#️⃣"), parsed.map { it.emoji })
    }

    @Test
    fun anOverlayRowIsEmojiNameAndOptionalKeywords() {
        val overlay =
            EmojiCatalogParser.parseOverlay(
                sequenceOf(
                    "# comment",
                    "💯\tcem pontos\t100|cem\r",
                    "👍🏽\tpolegar para cima: pele morena",
                    "#️⃣\ttecla: #\ttecla",
                    "🦄", // no name
                    "\tsem emoji",
                ),
            )
        assertEquals(
            mapOf(
                "💯" to LocalizedEmoji("cem pontos", listOf("100", "cem")),
                "👍🏽" to LocalizedEmoji("polegar para cima: pele morena"),
                "#️⃣" to LocalizedEmoji("tecla: #", listOf("tecla")),
            ),
            overlay,
        )
    }

    @Test
    fun namesKeepTheirColonsAndCommas() {
        val e = EmojiCatalogParser.parse(sequenceOf("👨‍❤️‍👨\t1\t0\tcouple with heart: man, man")).single()
        assertEquals("couple with heart: man, man", e.name)
        assertEquals("couple with heart: man, man", e.searchKey)
    }

    @Test
    fun theOptionalFifthColumnCarriesPipeSeparatedKeywords() {
        val parsed = EmojiCatalogParser.parse(sequenceOf("💯\t0\t0\thundred points\t100|full|score\r", "😀\t0\t0\tgrinning face"))
        assertEquals(listOf("100", "full", "score"), parsed[0].keywords)
        assertEquals(emptyList<String>(), parsed[1].keywords)
    }

    @Test
    fun malformedOrUnknownGroupLinesAreDroppedNotThrown() {
        val parsed =
            EmojiCatalogParser.parse(
                sequenceOf(
                    "😀\t0\t0", // three columns
                    "😀\t9\t0\tfrom a future group", // id this build doesn't know
                    "😀\tx\t0\tnon-numeric group",
                    "\t0\t0\tno emoji",
                    "😀\t0\t0\t", // no name
                    "🙏\t1\t0\tfolded hands",
                ),
            )
        assertEquals("🙏", parsed.single().emoji)
    }
}
