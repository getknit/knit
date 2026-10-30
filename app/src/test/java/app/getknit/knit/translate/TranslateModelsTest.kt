package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated table of directions (ADR 2026-10.t2h6), every one to or from English; `scripts/translate-model.py
 * --check` pins its bytes against Mozilla's registry.
 */
class TranslateModelsTest {
    @Test
    fun eachDirectionPinsItsThreeFilesAndTheLicenceUnderItsOwnId() {
        for (direction in TranslateDirection.ALL) {
            assertEquals(
                listOf(direction.model, direction.shortlist, direction.vocabulary, "LICENSE"),
                direction.files.map { it.name },
            )
            assertEquals("https://getknit.app/models/translate/${direction.id}/", direction.baseUrl)
            assertTrue(direction.files.all { it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
            // base-memory: told two decoder layers, slimt loads and writes nonsense.
            assertEquals(4, direction.decoderLayers)
        }
    }

    @Test
    fun everyDirectionHasEnglishOnOneSide() {
        assertTrue(TranslateDirection.ALL.all { (it.from == TranslateLang.EN) != (it.to == TranslateLang.EN) })
    }

    @Test
    fun theTableNamesItsLanguagesBothWays() {
        val es = TranslateLang("es")
        assertEquals(TranslateDirections.EN_ES, TranslateDirection.of(TranslateLang.EN, es))
        assertEquals(TranslateDirections.ES_EN, TranslateDirection.of(es, TranslateLang.EN))
        assertNull(TranslateDirection.of(TranslateLang.EN, TranslateLang.EN))
        // The one deliberate pin of the table's contents: it changes with each `translate-model.py --write`.
        assertEquals(setOf(TranslateLang.EN, es), TranslateDirection.languages)
        assertEquals(setOf(TranslateLang.EN, es), TranslateDirection.sources)
        assertEquals(setOf(TranslateLang.EN, es), TranslateDirection.targets)
        assertEquals("model.esen.intgemm.alphas.bin", TranslateDirections.ES_EN.model)
    }

    @Test
    fun aCodeIsReadAsTheTableSpellsIt() {
        val es = TranslateLang("es")
        assertEquals(es, TranslateLang.ofCode("es"))
        assertEquals(es, TranslateLang.ofCode("ES-mx"))
        assertEquals(es, TranslateLang.ofCode("es-419"))
        // A script subtag the table has no model for falls back to the language.
        assertEquals(es, TranslateLang.ofCode("es-Latn-MX"))
        assertEquals(TranslateLang.EN, TranslateLang.ofCode("EN_gb"))
        assertEquals(TranslateLang.EN, TranslateLang.ofCode(" en "))
        // Not in the table: no model translates it, so nothing names it.
        assertNull(TranslateLang.ofCode("gl"))
        assertNull(TranslateLang.ofCode("un"))
        assertNull(TranslateLang.ofCode(""))
    }
}
