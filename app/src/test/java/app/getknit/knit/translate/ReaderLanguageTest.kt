package app.getknit.knit.translate

import app.getknit.knit.data.message.OwnSend
import app.getknit.knit.mesh.protocol.LanguageRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reader's language over their own sends (ADR 2026-10.t2h6's 2026-11-02 amendment): a body the composer translated
 * counts as the language its draft was in, never as the translation's, which is the chat's.
 */
class ReaderLanguageTest {
    private val es = TranslateLang("es")

    // [bodies] is what the identifier reads every body below as; [preferred] the system's list.
    private fun reader(
        sends: List<OwnSend>,
        bodies: TranslateLang,
        preferred: List<String> = listOf("en-US"),
    ): ReaderLanguage =
        ReaderLanguage(
            ownSends = { limit -> sends.take(limit) },
            read = { LanguagePick.Picked(bodies) },
            preferred = { preferred },
            clock = { 0L },
        )

    @Test
    fun anEnglishPhoneThatTranslatesDraftsIntoSpanishStillReadsEnglish() =
        runTest {
            // The composer's commonest use: an English speaker writing to a Spanish contact through it. The bodies are
            // Spanish; the drafts were English. Read by the body, three of them made this phone a Spanish reader, and
            // the contact's Spanish replies offered no Translate.
            val sends = List(5) { OwnSend("¿nos vemos a las cinco?", draftTranslatedFrom = "en") }
            val estimate = reader(sends, bodies = es).estimate(override = null)
            assertEquals(TranslateLang.EN, estimate.target)
            assertEquals(mapOf(TranslateLang.EN to 5), estimate.written)
        }

    @Test
    fun aSpanishWriterOnAnEnglishPhoneWhoTranslatesEveryDraftReadsSpanish() =
        runTest {
            val sends = List(4) { OwnSend("see you at five?", draftTranslatedFrom = "es") }
            assertEquals(es, reader(sends, bodies = TranslateLang.EN).estimate(override = null).target)
        }

    @Test
    fun aBodyTypedAsItIsSentIsReadByTheIdentifier() =
        runTest {
            val sends = List(3) { OwnSend("¿nos vemos a las cinco?", draftTranslatedFrom = null) }
            val estimate = reader(sends, bodies = es).estimate(override = null)
            assertEquals(es, estimate.target)
            assertEquals(mapOf(es to 3), estimate.written)
        }

    @Test
    fun sendsThatNameNoLanguageYetAreReadAgainAtTheNextAsk() =
        runTest {
            // The chats read the reader's language as the process starts, before its first send. Held, that empty read
            // let nothing this person wrote count for ten minutes; read again, three sends settle it at once.
            val sends = mutableListOf<OwnSend>()
            var reads = 0
            val reader =
                ReaderLanguage(
                    ownSends = { limit ->
                        reads++
                        sends.take(limit)
                    },
                    read = { LanguagePick.Picked(es) },
                    preferred = { listOf("en-US") },
                    clock = { 0L },
                )
            assertEquals(TranslateLang.EN, reader.estimate(override = null).target)

            sends += List(ReaderLanguages.MIN_WRITTEN) { OwnSend("¿nos vemos a las cinco?", draftTranslatedFrom = null) }
            assertEquals(es, reader.estimate(override = null).target)
            assertEquals(2, reads)

            // Once they name a language it is held for the TTL: a third ask reads nothing.
            sends.clear()
            assertEquals(es, reader.estimate(override = null).target)
            assertEquals(2, reads)
        }

    @Test
    fun aDraftLanguageNoModelReachesIsUnsureNotItsTranslation() =
        runTest {
            // Read off the table, so a new model never turns this red.
            val unreached = LanguageRegistry.CODES.first { TranslateLang.ofCode(it) == null }
            val sends = List(3) { OwnSend("¿nos vemos a las cinco?", draftTranslatedFrom = unreached) }
            val estimate = reader(sends, bodies = es).estimate(override = null)
            assertEquals(emptyMap<TranslateLang, Int>(), estimate.written)
            assertEquals(3, estimate.unsure)
            assertEquals(TranslateLang.EN, estimate.target)
        }
}
