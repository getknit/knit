package app.getknit.knit.ui.invite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pure-logic tests for the split-path collection + cache-key helpers in ApkMerger.kt. */
class ApkMergerTest {
    @Test
    fun `collectSplitPaths puts base first then splits`() {
        val result =
            collectSplitPaths(
                "/data/app/x/base.apk",
                arrayOf("/data/app/x/split_config.arm64_v8a.apk", "/data/app/x/split_config.en.apk"),
            )
        assertEquals(
            listOf(
                File("/data/app/x/base.apk"),
                File("/data/app/x/split_config.arm64_v8a.apk"),
                File("/data/app/x/split_config.en.apk"),
            ),
            result,
        )
    }

    @Test
    fun `collectSplitPaths returns just the base when there are no splits`() {
        assertEquals(listOf(File("/data/app/x/base.apk")), collectSplitPaths("/data/app/x/base.apk", null))
        assertEquals(listOf(File("/data/app/x/base.apk")), collectSplitPaths("/data/app/x/base.apk", emptyArray()))
    }

    @Test
    fun `collectSplitPaths is empty when there is no source at all`() {
        assertTrue(collectSplitPaths(null, null).isEmpty())
    }

    @Test
    fun `collectSplitPaths drops blanks and duplicates`() {
        val result =
            collectSplitPaths(
                "/data/app/x/base.apk",
                arrayOf("", "/data/app/x/base.apk", "/data/app/x/split_config.en.apk"),
            )
        assertEquals(
            listOf(File("/data/app/x/base.apk"), File("/data/app/x/split_config.en.apk")),
            result,
        )
    }

    @Test
    fun `mergedApkFileName encodes version name and code`() {
        val name = mergedApkFileName("1.2", 34)
        assertTrue(name, name.startsWith("Knit-1.2-34-merged-"))
        assertTrue(name, name.endsWith(".apk"))
    }

    @Test
    fun `mergedApkFileName is stable for the same version and distinct across versions`() {
        assertEquals(mergedApkFileName("1.0", 1), mergedApkFileName("1.0", 1))
        assertNotEquals(mergedApkFileName("1.0", 1), mergedApkFileName("1.0", 2))
        assertNotEquals(mergedApkFileName("1.0", 1), mergedApkFileName("1.1", 1))
    }
}
