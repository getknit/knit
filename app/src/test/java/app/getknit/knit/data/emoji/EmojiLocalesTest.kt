package app.getknit.knit.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmojiLocalesTest {
    private val shipped = setOf("de", "es", "es-419", "fr", "pt", "uk")

    @Test
    fun aRegionFallsBackToItsLanguage() {
        assertEquals("pt", EmojiLocales.overlayFor(listOf("pt-BR"), shipped))
        assertEquals("pt", EmojiLocales.overlayFor(listOf("pt-PT"), shipped))
        assertEquals("de", EmojiLocales.overlayFor(listOf("de-AT"), shipped))
        assertEquals("es", EmojiLocales.overlayFor(listOf("es-ES"), shipped))
    }

    @Test
    fun latinAmericanSpanishTakesEs419() {
        assertEquals("es-419", EmojiLocales.overlayFor(listOf("es-AR"), shipped))
        assertEquals("es-419", EmojiLocales.overlayFor(listOf("es-MX"), shipped))
        assertEquals("es-419", EmojiLocales.overlayFor(listOf("es-419"), shipped))
        // Without an es-419 overlay a Latin American Spanish still gets Spanish.
        assertEquals("es", EmojiLocales.overlayFor(listOf("es-AR"), shipped - "es-419"))
    }

    @Test
    fun legacyLanguageCodesMapToTheCurrentOnes() {
        // Android's Locale.getLanguage() says `in` for Indonesian (the folder is values-in, the overlay `id`), `iw`
        // for Hebrew and `ji` for Yiddish; this JVM (JDK 17+) never hands those back, so the step is tested alone.
        assertEquals("id", EmojiLocales.currentLanguage("in"))
        assertEquals("he", EmojiLocales.currentLanguage("iw"))
        assertEquals("yi", EmojiLocales.currentLanguage("ji"))
        assertEquals("id", EmojiLocales.currentLanguage("id"))
        assertEquals("de", EmojiLocales.currentLanguage("de"))
    }

    @Test
    fun indonesianTagsLandOnTheIdOverlay() {
        // End to end on this JVM, which reads every form as `id`; the legacy step itself is the test above.
        val withIndonesian = shipped + "id"
        assertEquals("id", EmojiLocales.overlayFor(listOf("id"), withIndonesian))
        assertEquals("id", EmojiLocales.overlayFor(listOf("in"), withIndonesian))
        assertEquals("id", EmojiLocales.overlayFor(listOf("in-ID"), withIndonesian))
    }

    @Test
    fun englishFirstStopsAtTheBaseEvenWithAnOverlayLaterInTheList() {
        assertNull(EmojiLocales.overlayFor(listOf("en-US", "de-DE"), shipped))
    }

    @Test
    fun anUnshippedLanguageFallsThroughToTheNextOne() {
        assertEquals("fr", EmojiLocales.overlayFor(listOf("it-IT", "fr-CA"), shipped))
        assertNull(EmojiLocales.overlayFor(listOf("it-IT", "ja-JP"), shipped))
        assertNull(EmojiLocales.overlayFor(emptyList(), shipped))
    }

    @Test
    fun tagsComeFromTheAssetFileNamesWithoutTheBase() {
        assertEquals(
            setOf("de", "es-419"),
            EmojiLocales.tagsIn(listOf("README.md", "emoji_en.tsv", "emoji_de.tsv", "emoji_es-419.tsv", "emoji_.tsv")),
        )
        assertEquals("emoji/emoji_es-419.tsv", EmojiLocales.asset("es-419"))
    }
}
