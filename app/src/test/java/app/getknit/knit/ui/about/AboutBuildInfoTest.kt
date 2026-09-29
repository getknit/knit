package app.getknit.knit.ui.about

import app.getknit.knit.legal.InstallSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** The copied block uses the crash header's line shapes, so a paste into an issue parses the way a crash report does. */
class AboutBuildInfoTest {
    @Test
    fun theBlockReadsLikeACrashHeader() {
        val info = AboutBuildInfo(environment = previewEnvironment(), installSource = InstallSource.FDROID)
        assertEquals(
            """
            app: 2.5.1 (21) release
            installed from: fdroid
            device: Google Pixel 8 (shiba)
            android: 16 (SDK 36)
            abis: arm64-v8a
            """.trimIndent(),
            info.asText(),
        )
    }

    @Test
    fun aDebugBuildNamesItsCommitUnderTheAppLine() {
        val info =
            AboutBuildInfo(
                environment = previewEnvironment(buildType = "debug", obfuscated = false),
                installSource = InstallSource.SIDELOADED,
                commit = "06dd410d",
            )
        assertEquals(
            """
            app: 2.5.1 (21) debug
            commit: 06dd410d
            installed from: sideloaded
            device: Google Pixel 8 (shiba)
            android: 16 (SDK 36)
            abis: arm64-v8a
            """.trimIndent(),
            info.asText(),
        )
    }
}
