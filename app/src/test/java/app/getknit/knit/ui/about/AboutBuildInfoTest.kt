package app.getknit.knit.ui.about

import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.CrashIssueUrl
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.ui.ISSUES_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier
import java.net.URLDecoder

/**
 * The copied block uses the crash header's line shapes, so a paste into an issue parses the way a crash report does,
 * and Report a problem's link carries the same facts into the form's own fields.
 */
class AboutBuildInfoTest {
    @Test
    fun theBlockReadsLikeACrashHeader() {
        val info = AboutBuildInfo(environment = previewEnvironment(), installSource = InstallSource.FDROID)
        assertEquals(
            """
            app: 2.5.1 (21) release
            installed from: fdroid
            device: Google Pixel 8 (shiba)
            board: shiba  hardware: shiba  soc: Google Tensor G3
            android: 16 (SDK 36)
            abis: arm64-v8a
            fingerprint: google/shiba/shiba:16/BP2A/0:user/release-keys
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
            board: shiba  hardware: shiba  soc: Google Tensor G3
            android: 16 (SDK 36)
            abis: arm64-v8a
            fingerprint: google/shiba/shiba:16/BP2A/0:user/release-keys
            """.trimIndent(),
            info.asText(),
        )
    }

    @Test
    fun theFlagsThatAreOnFollowTheInstallSource() {
        val info =
            AboutBuildInfo(
                environment = previewEnvironment(),
                installSource = InstallSource.PLAY,
                flags = listOf("LORA_PLANE", "LIVE_CALLS"),
            )
        assertEquals(
            listOf("app: 2.5.1 (21) release", "installed from: play", "flags: LORA_PLANE, LIVE_CALLS"),
            info.asText().lines().take(3),
        )
    }

    @Test
    fun onlyTheFlagsThatAreOnAreNamedInTheirOrder() {
        assertEquals(
            listOf("COMMONS", "STT"),
            enabledBuildFlags(listOf("LORA_PLANE" to false, "COMMONS" to true, "TRANSLATE" to false, "STT" to true)),
        )
    }

    @Test
    fun theListNamesEveryBooleanFlagByItsOwnConstant() {
        // Read off BuildConfig itself, so a flag added to app/build.gradle.kts and not to the list fails here.
        val fields =
            BuildConfig::class.java.declaredFields
                .filter { it.type == java.lang.Boolean.TYPE && Modifier.isStatic(it.modifiers) && it.name != "DEBUG" }
                .associate { it.name to it.getBoolean(null) }
        assertEquals(fields.keys, BUILD_FLAGS.map { it.first }.toSet())
        val on = enabledBuildFlags()
        fields.forEach { (name, value) -> assertEquals(name, value, name in on) }
    }

    @Test
    fun theCapabilityLineFollowsTheFlagsWithTheMaskAndItsNames() {
        val info =
            AboutBuildInfo(
                environment = previewEnvironment(),
                installSource = InstallSource.PLAY,
                flags = listOf("LORA_PLANE"),
                capabilities = Protocol.CAP_E2E or Protocol.CAP_CALL,
            )
        assertEquals(
            listOf("installed from: play", "flags: LORA_PLANE", "capabilities: 0x4001 (CAP_E2E, CAP_CALL)"),
            info.asText().lines().subList(1, 4),
        )
    }

    @Test
    fun aBitWithNoNameIsNamedByItsValue() {
        assertEquals(
            listOf("CAP_GROUPS" to 0x2L, "0x10000" to 0x10000L),
            capabilityBits(Protocol.CAP_GROUPS or 0x10000L),
        )
    }

    @Test
    fun theTableNamesEveryCapabilityBitByItsOwnConstant() {
        // Read off Protocol itself, so a bit added there and not to the table fails here.
        val fields =
            Protocol::class.java.declaredFields
                .filter { it.name.startsWith("CAP_") && it.type == java.lang.Long.TYPE && Modifier.isStatic(it.modifiers) }
                .associate { it.name to it.getLong(null) }
        assertEquals(fields, CAPABILITY_BITS.toMap())
        assertEquals(fields.size, CAPABILITY_BITS.size)
        assertEquals(CAPABILITY_BITS.sortedBy { it.second }, CAPABILITY_BITS)
    }

    @Test
    fun theReportLinkFillsTheVersionTheDeviceAndTheBlock() {
        val info = AboutBuildInfo(environment = previewEnvironment(), installSource = InstallSource.FDROID)
        val url = info.issueUrl()
        val fields = params(url)
        assertTrue(url.startsWith("$ISSUES_URL/new?"))
        assertEquals("2.5.1 (21) release", fields["version"])
        assertEquals("Google Pixel 8 (shiba), 16 (SDK 36)", fields["devices"])
        assertTrue(fields.getValue("logs").startsWith(CrashIssueUrl.PROBLEM_MARKER))
        assertTrue(fields.getValue("logs").contains(info.asText()))
    }

    @Test
    fun aDebugBuildsReportLinkNamesItsCommitInTheVersionField() {
        val info =
            AboutBuildInfo(
                environment = previewEnvironment(buildType = "debug", obfuscated = false),
                installSource = InstallSource.SIDELOADED,
                commit = "06dd410d",
            )
        assertEquals("2.5.1 (21) debug, commit 06dd410d", params(info.issueUrl())["version"])
    }

    private fun params(url: String): Map<String, String> =
        url
            .substringAfter('?')
            .split('&')
            .associate { pair ->
                pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8.name())
            }
}
