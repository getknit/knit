package app.getknit.knit

import app.getknit.knit.identity.NameKey
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

/**
 * `vectors/name-key-v1.json`: the collision key, the display-blank test and the received-text cleaning that both
 * ports run on a display name (ADR 2026-10.2kfr). Nothing here crosses the wire — each device keys and cleans its
 * own copy — but two ports that answer differently draw different discriminators for the same people, so knit-ios
 * asserts its fold and drop constants and every case against this file.
 *
 * Built from [NameKey] and [TextLimits.cleanReceived] on every run and compared byte for byte; `KNIT_WRITE_VECTORS=1`
 * rewrites it. Every case uses only code points Unicode 11 assigned (API 29's ICU), so the general categories
 * [NameKey] reads agree on every phone and on the JVM. Strings are written with `\uXXXX` escapes for every non-ASCII
 * unit, so the invisible code points read in a diff. `ArtTextSemanticsTest` replays the cases on ART.
 */
class NameKeyVectorTest {
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

    private fun build(): JsonObject =
        buildJsonObject {
            put("description", "NameKey.of, NameKey.isBlankName and TextLimits.cleanReceived (ADR 2026-10.2kfr)")
            putJsonArray("fold") {
                for ((from, to) in NameKey.FOLD) {
                    addJsonObject {
                        put("from", from.u())
                        put("to", to.toString())
                    }
                }
            }
            putJsonArray("drop") {
                for (range in NameKey.DROP + listOf(BRAILLE_BLANK..BRAILLE_BLANK)) {
                    addJsonObject {
                        put("first", range.first.u())
                        put("last", range.last.u())
                    }
                }
            }
            putJsonObject("limits") {
                put("displayName", TextLimits.DISPLAY_NAME)
                put("status", TextLimits.STATUS)
                put("groupName", TextLimits.GROUP_NAME)
            }
            putJsonArray("cases") {
                for ((label, input) in CASES) {
                    addJsonObject {
                        put("label", label)
                        put("input", input)
                        put("key", NameKey.of(input))
                        put("displayBlank", NameKey.isBlankName(input))
                        put("cleanedName", TextLimits.cleanReceived(input, TextLimits.DISPLAY_NAME))
                        put("cleanedStatus", TextLimits.cleanReceived(input, TextLimits.STATUS))
                    }
                }
            }
        }

    private fun Int.u(): String = "U+%04X".format(this)

    companion object {
        const val FILE = "name-key-v1.json"
        private const val BRAILLE_BLANK = 0x2800

        private val LONG_SPACED = "Abcdefghij".repeat(3) + "k " + "Lmnopqrs" // 40 units, the 32nd a space
        private val LONG_PAIRED = "Abcdefghij".repeat(3) + "k\uD83D\uDE00" + "Lmnopqr" // the cut splits the emoji

        /** Label to input. Inputs use only code points assigned by Unicode 11. */
        val CASES: List<Pair<String, String>> =
            listOf(
                "plain" to "Alice",
                "variation selector 16" to "Alice\uFE0F",
                "combining grapheme joiner" to "Al\u034Fice",
                "Cyrillic small ie" to "Alic\u0435",
                "Braille blank" to "Alice\u2800",
                "Hangul filler inside" to "Ali\u3164ce",
                "Latin capital I for l" to "AIice",
                "Cyrillic small byelorussian-ukrainian i" to "Al\u0456ce",
                "Cyrillic capital a" to "\u0410lice",
                "Greek small iota" to "Al\u03B9ce",
                "all capitals" to "ALICE",
                "Kim" to "Kim",
                "KIM" to "KIM",
                "Liam" to "Liam",
                "LIAM" to "LIAM",
                "Ian" to "Ian",
                "IAN" to "IAN",
                "lan" to "lan",
                "Eli" to "Eli",
                "Ell" to "Ell",
                "Cyrillic capital ve and small o" to "\u0412\u043Eb",
                "Cyrillic small ve is not b" to "\u0432ob",
                "precomposed Vietnamese" to "Nguy\u1EC5n",
                "decomposed Vietnamese" to "Nguye\u0302\u0303n",
                "decomposed e acute" to "e\u0301",
                "French accent" to "Ren\u00E9e",
                "capital I with dot above" to "\u0130",
                "Greek capitals with final sigma" to "\u03A3\u0391\u03A3",
                "Greek small final sigma" to "\u03C3\u03B1\u03C2",
                "empty" to "",
                "spaces" to "   ",
                "Hangul filler only" to "\u3164",
                "Braille blanks only" to "\u2800\u2800",
                "Hangul choseong filler only" to "\u115F",
                "variation selector only" to "\uFE0F",
                "zero-width space and joiner only" to "\u200B\u200D",
                "controls only" to "\u0001\u0007",
                "newline" to "Alice\nSmith",
                "right-to-left override" to "Bob\u202Eevil",
                "C0, C1 and DEL" to "Bob\u0000\u001Bevil\u007F\u009B",
                "bidi marks and isolates" to "Bo\u061C\u200E\u200F\u2066\u2069b",
                "leading next line" to "\u0085Bob",
                "40 units, cut after a space" to LONG_SPACED,
                "cut inside a surrogate pair" to LONG_PAIRED,
                "emoji ZWJ sequence" to "\uD83D\uDC69\u200D\uD83D\uDCBB Dev",
                "Persian ZWNJ" to "\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
                "fullwidth" to "\uFF21\uFF4C\uFF49\uFF43\uFF45",
                "fi ligature" to "\uFB01sh",
                "zero-width space" to "Al\u200Bice",
                "zero-width joiner" to "Al\u200Dice",
                "right-to-left override leading" to "\u202EAlice",
                "byte-order mark" to "\uFEFFAlice",
                "left-to-right mark" to "Alice\u200E",
                "whitespace run" to "Alice \t  Smith",
                "no-break space" to "Alice\u00A0Smith",
                "next line" to "Ana\u0085Bell",
                "ogham space mark" to "Ana\u1680Bell",
                "line separator" to "Ana\u2028Bell",
                "paragraph separator" to "Ana\u2029Bell",
            )
    }
}
