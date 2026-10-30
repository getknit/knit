package app.getknit.knit.translate

import app.getknit.knit.mesh.protocol.LanguageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader's language by the rule of ADR 2026-10.t2h6 and its 2026-10-29 amendment: their own choice first, then
 * what they write in, then the system's list, and only a language a model translates into.
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
    fun tooFewOwnMessagesFallBackToTheSystemList() {
        assertEquals(TranslateLang("es"), ReaderLanguages.estimate(listOf(en, en), listOf("fr-FR", "es-MX", "en-US")).target)
        assertEquals(TranslateLang.EN, ReaderLanguages.estimate(emptyList(), listOf("EN-gb")).target)
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
