package app.getknit.knit.text

import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.getknit.knit.TextLimits
import app.getknit.knit.identity.NameKey
import app.getknit.knit.normalizeSingleLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins what ART — not the desktop JDK — answers for the text predicates a name, a status and a group name
 * pass through, because that answer is the law the iOS port mirrors (ADR 2026-10.swst, interop-audit AND-13 =
 * IOS-2). JVM unit tests cannot see it: the desktop JDK's regex `\s` is ASCII, while ART's
 * `java.util.regex` is ICU, whose `\s` is Unicode White_Space (`Pattern.java`, android-36.1, the class doc's
 * `\s` row), and ART's `Character.isWhitespace` hard-codes U+1680 (and, up to API 36's ART module, U+180E) (`Character.java`,
 * android-36.1, `isWhitespace(int)`). `DesktopJdkWhitespaceTest` records the desktop answer beside this.
 *
 * Each list is every BMP code point the predicate accepts, enumerated on the device:
 * - [ART_REGEX_WHITESPACE]: `Regex("\\s")` (Java's `Pattern` under ICU) — `normalizeSingleLine`'s and
 *   `NameKey`'s whitespace run.
 * - [ART_KOTLIN_WHITESPACE]: Kotlin's `Char.isWhitespace()` (`Character.isWhitespace || Character.isSpaceChar`
 *   on ART) — `trim()`, `isBlank()`.
 */
@RunWith(AndroidJUnit4::class)
class ArtTextSemanticsTest {
    @Test
    fun regexWhitespaceIsUnicodeWhiteSpace() {
        val whitespace = Regex("\\s")
        assertCodePoints(ART_REGEX_WHITESPACE, bmp().filter { whitespace.matches(it.toChar().toString()) })
    }

    @Test
    fun kotlinIsWhitespaceIsTheArtSet() {
        val actual = bmp().filter { it.toChar().isWhitespace() }
        Log.i(TAG, "U+180E isWhitespace=${MVS in actual} on API ${Build.VERSION.SDK_INT}, ART module ${artModuleVersion()}")
        // U+180E follows the ART module, not the API level, so it is recorded above, never pinned here.
        assertCodePoints(ART_KOTLIN_WHITESPACE, actual - MVS)
    }

    @Test
    fun aLoneSurrogateEncodesAsAQuestionMark() {
        assertArrayEquals(byteArrayOf(0x3F), "\uD800".toByteArray(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x3F), "\uDC00".toByteArray(Charsets.UTF_8))
        assertArrayEquals(byteArrayOf(0x61, 0x3F, 0x62), "a\uDBFFb".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun normalizeSingleLineFoldsUnicodeWhitespaceOnArt() {
        assertEquals("Ana Bell Cruz", normalizeSingleLine("\u00A0 Ana\u00A0\u00A0Bell\u2028Cruz\u3000"))
        assertEquals("a b c d", normalizeSingleLine("a\u0085b\u1680c\u2029d"))
        // The shared law keeps U+180E (it is not whitespace); an older ART module still trims it — accepted residue.
        val mvsIsWhitespace = MVS.toChar().isWhitespace()
        assertEquals(mvsIsWhitespace, "\u180E".isBlank())
        assertEquals(if (mvsIsWhitespace) "" else "\u180E", normalizeSingleLine(" \u180E "))
    }

    @Test
    fun nameKeyFoldsTheSeparatorsNfkcLeaves() {
        // NFKC leaves U+0085, U+1680, U+2028 and U+2029 alone; ART's `\s` collapses them.
        assertEquals("ana beii", NameKey.of("Ana\u0085Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u1680Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u2028Bell"))
        assertEquals("ana beii", NameKey.of("Ana\u2029Bell"))
    }

    /**
     * `vectors/name-key-v1.json` (ADR 2026-10.2kfr), written on the JVM, holds on ART: the key's categories come from
     * the device's ICU here, and the whitespace from ART's own regex — the answer knit-ios mirrors.
     */
    @Test
    fun nameKeyVectorCasesHoldOnArt() {
        val file =
            InstrumentationRegistry.getInstrumentation().context.assets.open("name-key-v1.json").use {
                Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
            }
        val limits = file.getValue("limits").jsonObject
        val nameLimit = limits.getValue("displayName").jsonPrimitive.int
        val statusLimit = limits.getValue("status").jsonPrimitive.int
        val cases = file.getValue("cases").jsonArray
        val disagreements =
            cases.mapNotNull { element ->
                val case = element.jsonObject

                fun text(field: String) = case.getValue(field).jsonPrimitive.content
                val input = text("input")
                val got =
                    listOf(
                        NameKey.of(input),
                        NameKey.isBlankName(input).toString(),
                        TextLimits.cleanReceived(input, nameLimit),
                        TextLimits.cleanReceived(input, statusLimit),
                    )
                val want =
                    listOf(
                        text("key"),
                        case
                            .getValue("displayBlank")
                            .jsonPrimitive.boolean
                            .toString(),
                        text("cleanedName"),
                        text("cleanedStatus"),
                    )
                if (got == want) null else "${text("label")}: want ${want.escaped()}, ART ${got.escaped()}"
            }
        Log.i(TAG, "name-key-v1.json: ${cases.size} cases on API ${Build.VERSION.SDK_INT}, ${disagreements.size} differ")
        assertEquals(emptyList<String>(), disagreements)
    }

    /** Each string with every unit outside printable ASCII spelled `U+XXXX`, so an invisible difference reads in the log. */
    private fun List<String>.escaped(): String = joinToString(prefix = "[", postfix = "]") { it.escaped() }

    private fun String.escaped(): String = map { c -> if (c.code in PRINTABLE_ASCII) "$c" else "U+%04X".format(c.code) }.joinToString("")

    /** The ART mainline module's version, which decides U+180E (see [MVS]); -1 where neither name resolves. */
    private fun artModuleVersion(): Long {
        val pm = InstrumentationRegistry.getInstrumentation().context.packageManager
        return listOf("com.google.android.art", "com.android.art").firstNotNullOfOrNull { name ->
            runCatching { pm.getPackageInfo(name, PackageManager.MATCH_APEX).longVersionCode }.getOrNull()
        } ?: -1L
    }

    private fun bmp(): List<Int> = (0x0000..0xFFFF).toList()

    private fun assertCodePoints(
        expected: List<Int>,
        actual: List<Int>,
    ) {
        assertEquals("ART reported ${actual.asKotlinList()}", expected.asKotlinList(), actual.asKotlinList())
    }

    private fun List<Int>.asKotlinList(): String = joinToString(", ", "listOf(", ")") { "0x%04X".format(it) }

    private companion object {
        fun codePoints(vararg ranges: IntRange): List<Int> = ranges.flatMap { it }

        /**
         * Unicode White_Space, as ICU's `\s` has it: U+0009..U+000D, U+0020, U+0085, U+00A0, U+1680,
         * U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000. Same on API 33, 34, 36 and 37.
         */
        val ART_REGEX_WHITESPACE =
            codePoints(
                0x0009..0x000D,
                0x0020..0x0020,
                0x0085..0x0085,
                0x00A0..0x00A0,
                0x1680..0x1680,
                0x2000..0x200A,
                0x2028..0x2029,
                0x202F..0x202F,
                0x205F..0x205F,
                0x3000..0x3000,
            )

        /**
         * `Character.isWhitespace` (U+0009..U+000D, U+001C..U+001F, the Zs/Zl/Zp separators bar the no-break
         * ones, U+1680) or `Character.isSpaceChar` (adds U+00A0, U+2007, U+202F), on every API — plus [MVS]
         * on an older ART module. Note what differs from the regex set: U+001C..U+001F are in, U+0085 is out.
         */
        val ART_KOTLIN_WHITESPACE =
            codePoints(
                0x0009..0x000D,
                0x001C..0x0020,
                0x00A0..0x00A0,
                0x1680..0x1680,
                0x2000..0x200A,
                0x2028..0x2029,
                0x202F..0x202F,
                0x205F..0x205F,
                0x3000..0x3000,
            )

        /**
         * U+180E MONGOLIAN VOWEL SEPARATOR: a format character since Unicode 6.3, and not whitespace in the shared
         * law. ART's `Character` (it ships in the updatable ART module) hard-coded it as whitespace up to API 36's
         * module — observed on API 33, 34 and 36 at modules 330400000, 342090000 and 360527520 — and dropped it
         * in API 37's (370399999), so a phone answers by its module, not its API level. Logged by
         * [kotlinIsWhitespaceIsTheArtSet], never pinned.
         */
        const val MVS = 0x180E

        const val TAG = "ArtTextSemantics"

        val PRINTABLE_ASCII = 0x20..0x7E
    }
}
