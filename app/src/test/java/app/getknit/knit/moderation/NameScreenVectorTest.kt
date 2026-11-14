package app.getknit.knit.moderation

import app.getknit.knit.mesh.protocol.VectorFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * `vectors/name-screen-v1.json`: what the name screen answers for a set of names, over the shipped lists (ADR
 * 2026-11.y4pc). Nothing here crosses the wire. Each phone screens the names it draws, but two ports that answer
 * differently show one person's name on one phone and their alias on the other, so knit-ios checks its lists' digests
 * and every case against this file.
 *
 * Built from [NameScreen] over the shipped lists on every run and compared byte for byte; `KNIT_WRITE_VECTORS=1`
 * rewrites it. Each list's digest is the SHA-256 of its terms, one per line and sorted, so a header edit moves nothing.
 */
class NameScreenVectorTest {
    @Test
    fun theFileIsWhatTheCodeAnswers() {
        assumeFalse(VectorFiles.writing)
        assertEquals(VectorFiles.render(build(), asciiOnly = true), VectorFiles.text(FILE))
    }

    @Test
    fun write() {
        assumeTrue(VectorFiles.writing)
        VectorFiles.write(FILE, build(), asciiOnly = true)
    }

    private fun build(): JsonObject {
        val screen = ShippedWordList.nameScreen()
        return buildJsonObject {
            put("description", "NameScreen.check over the shipped word lists (ADR 2026-11.y4pc)")
            putJsonObject("lists") {
                for (list in LISTS) put(list, digest(ShippedWordList.read(list)))
            }
            putJsonArray("cases") {
                for ((name, audience) in CASES) {
                    addJsonObject {
                        put("name", name)
                        put("audience", audience.name)
                        put("verdict", screen.check(name, audience).category.name)
                    }
                }
            }
        }
    }

    private fun digest(terms: List<String>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(terms.sorted().joinToString("\n").encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val FILE = "name-screen-v1.json"
        val LISTS =
            listOf(
                ShippedWordList.SLURS,
                ShippedWordList.PROFANITY,
                ShippedWordList.NAME_EMBEDDABLE,
                ShippedWordList.NAME_EXCLUSIONS,
                ShippedWordList.NAME_ALLOWED,
            )
        private val P = NameScreen.Audience.PUBLIC
        private val M = NameScreen.Audience.MEMBERS

        // One case or more per rule: plain, whole word, the split variants, inside a word, an exclusion, leet only
        // between letters, real names in plain letters and not in leet, a slur that is also a surname, and a group name.
        val CASES =
            listOf(
                "Alice Smith" to P,
                "" to P,
                "Fuck" to P,
                "fuck_lord" to P,
                "Fuck69" to P,
                "BigShit" to P,
                "xXFuckXx" to P,
                "Sh1tHead" to P,
                "bigfuckboy" to P,
                "bigshit" to P,
                "pikeyboy" to P,
                "spikeyboy" to P,
                "darkeyes" to P,
                "stranny2004" to P,
                "Dildora" to P,
                "phonkyd" to P,
                "ash1tlord" to P,
                "ashish17tripathi" to P,
                "Dick Smith" to P,
                "BigDick" to P,
                "BigD1ck69" to P,
                "D!ck" to P,
                "Shota Tanaka" to P,
                "Mary Cumming" to P,
                "Dickson" to P,
                "Cassandra" to P,
                "Pikey" to P,
                "2 x 3" to P,
                "Fuck Mondays" to M,
                "Fuck Mondays" to P,
                "pikeyboys" to M,
            )
    }
}
