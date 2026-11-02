package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader's language by the rule of ADR 2026-10.t2h6 and its 2026-10-29 and 2026-11-02 amendments: their own choice
 * first, then the system's list unless its language is English, then what they write in, then that English, and only a
 * language a model translates into.
 */
class ReaderLanguagesTest {
    private val es = LanguagePick.Picked(TranslateLang("es"))
    private val en = LanguagePick.Picked(TranslateLang.EN)

    @Test
    fun someoneOnAnEnglishPhoneWhoWritesSpanishReadsSpanish() {
        val estimate = ReaderLanguages.estimate(listOf(es, es, en, es, LanguagePick.Unsure), listOf("en-US"))
        assertEquals(TranslateLang("es"), estimate.target)
        assertEquals(mapOf(TranslateLang("es") to 3, TranslateLang.EN to 1), estimate.written)
        assertEquals(1, estimate.unsure)
    }

    @Test
    fun aSpanishPhoneThatWritesEnglishStillReadsSpanish() {
        // An alpha tester's report: a Spanish speaker writing to English contacts, by hand or through the composer's
        // translation, sends English, and every translation went into English, Spanish messages included.
        val own = List(5) { en } + es
        for (tag in listOf("es-AR", "es-419", "es")) {
            val estimate = ReaderLanguages.estimate(own, listOf(tag, "en-US"))
            assertEquals(tag, TranslateLang("es"), estimate.target)
            assertEquals(tag, TranslateLang("es"), estimate.automatic)
        }
    }

    @Test
    fun aPhoneInEnglishReadsWhatTheyWrite() {
        // The lab's Pixel 9: 39 English, 1 Spanish of its last 50 sends, on an English phone.
        val lab = List(39) { en } + es + List(10) { LanguagePick.Unsure }
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(lab, listOf("en-US")).target)
        // English first and Spanish after it: English says least, so what they write decides, then that English.
        val fr = LanguagePick.Picked(TranslateLang("fr"))
        assertEquals(TranslateLang("fr"), ReaderLanguages.estimate(listOf(fr, fr, fr), listOf("en-US", "es-US")).target)
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(listOf(es, es), listOf("en-US", "es-US")).target)
    }

    @Test
    fun aPhoneNoModelReachesReadsWhatTheyWrite() {
        // A registry language no model translates into, read off the table so a new model never turns this red.
        val unreached = LanguageRegistry.CODES.first { TranslateLang(it) !in TranslateDirection.targets }
        assertEquals(TranslateLang("es"), ReaderLanguages.estimate(listOf(es, es, es), listOf(unreached)).target)
        // English after it is still English: what they write comes first.
        assertEquals(TranslateLang("es"), ReaderLanguages.estimate(listOf(es, es, es), listOf(unreached, "en-US")).target)
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(listOf(es), listOf(unreached, "en-US")).target)
    }

    @Test
    fun aLaterPreferredLanguageAModelReachesBeatsWhatTheyWrite() {
        val unreached = LanguageRegistry.CODES.first { TranslateLang(it) !in TranslateDirection.targets }
        val estimate = ReaderLanguages.estimate(listOf(en, en, en), listOf(unreached, "es-MX", "en-US"))
        assertEquals(TranslateLang("es"), estimate.target)
    }

    @Test
    fun tooFewOwnMessagesFallBackToTheSystemList() {
        // English is the list's first language a model reaches, and two Spanish picks are not enough to beat it.
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(listOf(es, es), listOf("it-IT", "en-US", "es-MX")).target)
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(emptyList(), listOf("EN-gb")).target)
    }

    @Test
    fun aPhoneInAShippedLanguageReadsItWithNothingWritten() {
        val system = mapOf("pt-BR" to "pt", "uk-UA" to "uk", "de-DE" to "de", "fr-CA" to "fr", "es-MX" to "es")
        for ((tag, code) in system) {
            assertEquals(tag, TranslateLang(code), ReaderLanguages.estimate(emptyList(), listOf(tag, "en-US")).target)
        }
    }

    @Test
    fun aReaderOfNoLanguageAModelReachesHasNoTarget() {
        val unreached = LanguageRegistry.CODES.filter { TranslateLang(it) !in TranslateDirection.targets }.take(2)
        check(unreached.isNotEmpty()) { "every registry language has a model: this case needs another reader" }
        assertNull(ReaderLanguages.estimate(listOf(es), unreached).target)
    }

    @Test
    fun theChoiceInSettingsWinsAndAutomaticIsStillKnown() {
        val estimate = ReaderLanguages.estimate(listOf(en, en, en), listOf("en-US"), override = "es")
        assertEquals(TranslateLang("es"), estimate.target)
        assertEquals(TranslateLang("es"), estimate.override)
        assertEquals(TranslateLang.EN, estimate.automatic)
    }

    @Test
    fun englishChosenInSettingsWinsOverASpanishPhone() {
        val estimate = ReaderLanguages.estimate(listOf(es, es, es), listOf("es-ES"), override = "en")
        assertEquals(TranslateLang.EN, estimate.target)
        assertEquals(TranslateLang("es"), estimate.automatic)
    }

    @Test
    fun aChoiceNoModelReachesIsAutomatic() {
        // A registry language no model translates into, read off the table so a new model never turns this red.
        val unreached = LanguageRegistry.CODES.first { TranslateLang(it) !in TranslateDirection.targets }
        val estimate = ReaderLanguages.estimate(listOf(en, en, en), listOf("en-US"), override = unreached)
        assertNull(estimate.override)
        assertEquals(TranslateLang.EN, estimate.target)
    }

    @Test
    fun aReaderDeclaresTheLanguageTheyChoseNotOneTheAppFellBackTo() {
        // The Settings choice first, then the language chosen for this app, then the phone's own.
        assertEquals("es", ReaderLanguages.declaredReads("es", listOf("fr-FR"), listOf("el-GR")))
        assertEquals("fr", ReaderLanguages.declaredReads(null, listOf("fr-FR"), listOf("el-GR")))
        // A Greek phone shows Knit in English (no Greek strings ship), but its reader chose Greek.
        assertEquals("el", ReaderLanguages.declaredReads(null, emptyList(), listOf("el-GR", "en-US")))
        assertEquals("zh_hant", ReaderLanguages.declaredReads(null, emptyList(), listOf("zh-Hant-TW")))
        assertNull(ReaderLanguages.declaredReads(null, emptyList(), emptyList()))
    }
}
