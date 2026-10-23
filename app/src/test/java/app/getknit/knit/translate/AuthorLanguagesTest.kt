package app.getknit.knit.translate

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Each author's usual language per chat (ADR 2026-10.t2h6's short-line amendment): a majority of their confident
 * messages there, held for a while and read again after.
 */
class AuthorLanguagesTest {
    private var now = 0L
    private val asked = mutableListOf<Triple<String, String, Int>>()

    // A body's language is its first word, here: "es …", "en …", anything else unsure.
    private fun languages(written: Map<Pair<String, String>, List<String>>) =
        AuthorLanguages(
            bodies = { conversation, author, limit ->
                asked += Triple(conversation, author, limit)
                written[conversation to author].orEmpty()
            },
            read = { body -> TranslateLang.ofCode(body.substringBefore(' '))?.let { LanguagePick.Picked(it) } ?: LanguagePick.Unsure },
            clock = { now },
        )

    @Test
    fun anAuthorsLanguageIsTheMajorityOfWhatTheyWroteThere() =
        runTest {
            val langs =
                languages(
                    mapOf(
                        ("g1" to "lucia") to listOf("es a", "es b", "en c", "es d", "ok"),
                        ("g2" to "lucia") to listOf("en a", "en b", "en c"),
                    ),
                )
            assertEquals(TranslateLang.ES, langs.of("g1", "lucia"))
            // The same person, another chat, another language.
            assertEquals(TranslateLang.EN, langs.of("g2", "lucia"))
            assertEquals(AuthorLanguages.SAMPLE, asked.first().third)
        }

    @Test
    fun tooFewSureMessagesOrNoMajorityGiveNoLanguage() =
        runTest {
            val langs =
                languages(
                    mapOf(
                        ("g1" to "a") to listOf("es a", "es b", "ok", "lol"),
                        ("g1" to "b") to listOf("es a", "es b", "es c", "en d", "en e", "en f"),
                    ),
                )
            assertNull(langs.of("g1", "a"))
            assertNull(langs.of("g1", "b"))
            assertNull(langs.of("g1", "nobody"))
        }

    @Test
    fun aLanguageIsHeldForItsTtlThenReadAgain() =
        runTest {
            val langs = languages(mapOf(("g1" to "lucia") to listOf("es a", "es b", "es c")))
            langs.of("g1", "lucia")
            now = AuthorLanguages.TTL_MS - 1
            langs.of("g1", "lucia")
            assertEquals(1, asked.size)
            now = AuthorLanguages.TTL_MS
            langs.of("g1", "lucia")
            assertEquals(2, asked.size)
        }
}
