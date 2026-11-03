package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            // Told the wrong depth, slimt loads and writes nonsense: "base-memory" has four, "tiny" (uk-en only) two.
            // translate-model.py checks each against the weights' own layer names before it writes the table.
            assertEquals(if (direction == TranslateDirections.UK_EN) 2 else 4, direction.decoderLayers)
        }
    }

    @Test
    fun eachDirectionsPinIsItsIdsSuffixAndNamesItAlone() {
        // The pin a direct transfer offers (ADR 2026-11.ejn8): the weights' hash prefix, which translate-model.py
        // also writes into the id. Distinct across the table, so a pin picks one row.
        for (direction in TranslateDirection.ALL) {
            assertEquals(direction.id.substringAfterLast('-'), "%08x".format(direction.pin))
            assertTrue(direction.pin in 0..0xFFFF_FFFFL)
            assertEquals(direction, TranslateDirection.ofPin(direction.pin))
        }
        assertEquals(
            TranslateDirection.ALL.size,
            TranslateDirection.ALL
                .map { it.pin }
                .toSet()
                .size,
        )
        assertEquals(0x3b1c3995L, TranslateDirections.EN_ES.pin)
        assertNull(TranslateDirection.ofPin(0L))
        // Every direction's files fit a ustar header's name, so the whole table travels as one archive.
        val plan = TranslateDirection.ALL.map { it.id to it.files }
        assertTrue(ModelArchive.size(plan) > TranslateDirection.ALL.sumOf { it.bytes })
    }

    @Test
    fun everyLanguageShipsBothDirections() {
        for (lang in TranslateDirection.languages - TranslateLang.EN) {
            assertNotNull("$lang->en", TranslateDirection.of(lang, TranslateLang.EN))
            assertNotNull("en->$lang", TranslateDirection.of(TranslateLang.EN, lang))
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
        val every = listOf("en", "es", "fr", "uk", "de", "pt").map(::TranslateLang).toSet()
        assertEquals(every, TranslateDirection.languages)
        assertEquals(every, TranslateDirection.sources)
        assertEquals(every, TranslateDirection.targets)
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
        // One Portuguese model serves Brazil and Portugal; a region never splits a language the table names once.
        for (tag in listOf("pt-BR", "pt_BR", "pt-PT")) assertEquals(TranslateLang("pt"), TranslateLang.ofCode(tag))
        assertEquals(TranslateLang("uk"), TranslateLang.ofCode("uk-UA"))
        assertEquals(TranslateLang("de"), TranslateLang.ofCode("de-AT"))
        assertEquals(TranslateLang("de"), TranslateLang.ofCode("de-CH"))
        assertEquals(TranslateLang("fr"), TranslateLang.ofCode("fr-CA"))
        // Not in the table: no model translates it, so nothing names it, a neighbour of a shipped language included.
        assertNull(TranslateLang.ofCode("ru"))
        assertNull(TranslateLang.ofCode("gl"))
        assertNull(TranslateLang.ofCode("un"))
        assertNull(TranslateLang.ofCode(""))
    }
}
