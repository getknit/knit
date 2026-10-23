package app.getknit.knit.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spike's two pinned directions (ADR 2026-10.t2h6); `scripts/translate-model.py --check` pins the bytes. */
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
    fun theDirectionsAreTheTwoWaysOfThePair() {
        assertEquals(TranslateDirection.EN_ES, TranslateDirection.of(TranslateLang.EN, TranslateLang.ES))
        assertEquals(TranslateDirection.ES_EN, TranslateDirection.of(TranslateLang.ES, TranslateLang.EN))
        assertNull(TranslateDirection.of(TranslateLang.EN, TranslateLang.EN))
        assertEquals(TranslateLang.ES, TranslateLang.EN.other)
        assertEquals(TranslateLang.EN, TranslateLang.ES.other)
        assertEquals("model.esen.intgemm.alphas.bin", TranslateDirection.ES_EN.model)
    }
}
