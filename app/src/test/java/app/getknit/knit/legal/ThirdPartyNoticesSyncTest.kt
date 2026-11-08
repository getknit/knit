package app.getknit.knit.legal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `THIRD-PARTY-NOTICES.md` and [ThirdPartyNotices.ALL] are two renderings of one list — the file for a
 * reader of the repository, the Kotlin for the in-app Open-source licenses screen — so this pins them row
 * for row: same components, same order, same SPDX id, and the notice's URL is the one the table links.
 * Edit both together; the failure message names the first row that differs.
 */
class ThirdPartyNoticesSyncTest {
    private data class Row(
        val name: String,
        val projectCell: String,
        val spdx: String,
    )

    private val tables: Map<String, List<Row>> by lazy { parseTables(repoFile("THIRD-PARTY-NOTICES.md").readLines()) }

    @Test
    fun runtimeTableMatchesTheRuntimeNotices() {
        assertSectionMatches("Runtime components", NoticeKind.RUNTIME, Packaging.ALWAYS)
    }

    @Test
    fun liveCallsTableMatchesTheLiveCallsNotices() {
        assertSectionMatches("Live calls", NoticeKind.RUNTIME, Packaging.LIVE_CALLS)
    }

    @Test
    fun translationTableMatchesTheTranslationNotices() {
        assertSectionMatches("Offline translation", NoticeKind.RUNTIME, Packaging.TRANSLATE)
    }

    @Test
    fun speechTableMatchesTheSpeechNotices() {
        // One table for the whole speech runtime: its libraries and the model data compiled into them, in list order.
        assertSectionMatches("Speech", kind = null, Packaging.STT)
    }

    @Test
    fun bundledDataTableMatchesTheDataNotices() {
        assertSectionMatches("Bundled ML models and data", NoticeKind.DATA, Packaging.ALWAYS)
    }

    @Test
    fun aBuildShowsOnlyThePackagedRuntimesRows() {
        val gated = listOf(Packaging.LIVE_CALLS, Packaging.TRANSLATE, Packaging.STT)
        gated.forEach { packaging -> assertTrue("no $packaging row", ThirdPartyNotices.ALL.any { it.packaging == packaging }) }
        assertEquals(
            ThirdPartyNotices.ALL.filter { it.packaging == Packaging.ALWAYS },
            ThirdPartyNotices.shipped(liveCalls = false, translate = false, stt = false),
        )
        // Each flag on its own adds exactly its own rows, so a swapped or ignored flag fails here.
        gated.forEach { on ->
            assertEquals(
                "$on alone",
                ThirdPartyNotices.ALL.filter { it.packaging == Packaging.ALWAYS || it.packaging == on },
                ThirdPartyNotices.shipped(
                    liveCalls = on == Packaging.LIVE_CALLS,
                    translate = on == Packaging.TRANSLATE,
                    stt = on == Packaging.STT,
                ),
            )
        }
        assertEquals(ThirdPartyNotices.ALL, ThirdPartyNotices.shipped(liveCalls = true, translate = true, stt = true))
    }

    @Test
    fun keysAreUniqueAndArtifactPrefixesAreWellFormed() {
        val keys = ThirdPartyNotices.ALL.map { it.key }
        assertEquals("duplicate notice key", keys.size, keys.toSet().size)
        ThirdPartyNotices.ALL.flatMap { it.artifacts }.forEach { prefix ->
            assertTrue(
                "'$prefix' should name a group (trailing '.' or ':') or a group:artifact prefix",
                ':' in prefix || prefix.endsWith('.'),
            )
        }
        ThirdPartyNotices.ALL.filter { it.kind == NoticeKind.DATA }.forEach {
            assertTrue("data notice '${it.key}' has no classpath coordinate", it.artifacts.isEmpty())
        }
    }

    /** [kind] null takes notices of either kind, for a section that lists a runtime's code and data together. */
    private fun assertSectionMatches(
        sectionPrefix: String,
        kind: NoticeKind?,
        packaging: Packaging,
    ) {
        val section = tables.keys.firstOrNull { it.startsWith(sectionPrefix) } ?: error("no '## $sectionPrefix…' table")
        val rows = tables.getValue(section)
        val notices = ThirdPartyNotices.ALL.filter { (kind == null || it.kind == kind) && it.packaging == packaging }
        assertEquals(
            "'$section' rows vs ${kind ?: "any kind"}/$packaging notices (name → SPDX)",
            rows.map { it.name to it.spdx },
            notices.map { it.name to it.license.spdx },
        )
        rows.zip(notices).forEach { (row, notice) ->
            assertTrue("'${row.name}' should link ${notice.url}; got '${row.projectCell}'", "](${notice.url})" in row.projectCell)
        }
    }

    private fun parseTables(lines: List<String>): Map<String, List<Row>> {
        val result = linkedMapOf<String, MutableList<Row>>()
        var section = ""
        for (line in lines) {
            val heading = HEADING.matchEntire(line)
            if (heading != null) {
                section = heading.groupValues[1].trim()
            } else {
                dataRow(line)?.let { result.getOrPut(section, ::mutableListOf) += it }
            }
        }
        return result
    }

    /** A table's data row, or null for anything else (prose, the header row, the `|---|` separator). */
    private fun dataRow(line: String): Row? {
        val cells =
            TABLE_ROW
                .matchEntire(line)
                ?.groupValues
                ?.drop(1)
                ?.map(String::trim) ?: return null
        if (cells[0] in HEADER_CELLS || cells[0].startsWith("---")) return null
        return Row(name = componentName(cells[0]), projectCell = cells[1], spdx = spdx(cells[2]))
    }

    /** The bare component name: markup off, then everything from the first ` (` or ` —` on. */
    private fun componentName(cell: String): String =
        cell
            .replace("&nbsp;", "")
            .replace("↳", "")
            .replace("`", "")
            .trim()
            .split(" (", " —")
            .first()
            .trim()

    private fun spdx(cell: String): String = SPDX.find(cell)?.value ?: error("no SPDX id in license cell '$cell'")

    private companion object {
        val HEADING = Regex("""^## (.+)$""")
        val TABLE_ROW = Regex("""^\|(.+?)\|(.+?)\|(.+?)\|\s*$""")
        val SPDX = Regex("""^[A-Za-z0-9][A-Za-z0-9.+-]*""")
        val HEADER_CELLS = setOf("Component", "Asset")
    }
}
