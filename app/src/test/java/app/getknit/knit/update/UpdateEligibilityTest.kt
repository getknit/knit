package app.getknit.knit.update

import app.getknit.knit.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which build may replace which over a peer update (ADR 2026-11.29m7), and the version-name grammar it reads. */
class UpdateEligibilityTest {
    private fun build(
        name: String,
        pin: Long = 7L,
        size: Long = 124_000_000L,
        minSdk: Int = 29,
    ) = AppBuild(checkNotNull(VersionNames.codeOf(name)), name, pin, size, minSdk, VersionNames.isSlim(name))

    @Test
    fun theGrammarDerivesTheCodesTheBuildDoes() {
        // The weights of app/build.gradle.kts (ADR 2026-11.7t9z): stable sorts above its own pre-releases.
        assertEquals(21_400_900L, VersionNames.codeOf("2.14.0"))
        assertEquals(21_400_103L, VersionNames.codeOf("2.14.0-beta.3"))
        assertEquals(21_400_900L, VersionNames.codeOf("2.14.0-slim"))
        assertNull(VersionNames.codeOf("2.14"))
        assertNull(VersionNames.codeOf("2.14.0-dev"))
        assertNull(VersionNames.codeOf("2.14.0 "))
        assertTrue(VersionNames.isStable(21_400_900L))
        assertFalse(VersionNames.isStable(21_400_103L))
        assertEquals("2.14.0", VersionNames.nameOf(21_400_900L))
        assertEquals("2.14.0-rc.1", VersionNames.nameOf(21_400_201L))
        // A code the grammar never makes names nothing: a stage digit past rc, a stable with a counter.
        assertNull(VersionNames.nameOf(21_400_501L))
        assertNull(VersionNames.nameOf(21_400_901L))
        assertNull(VersionNames.nameOf(0L))
        // Tied to the build that ships, not only to copied weights: a change to the Gradle derivation fails here.
        assertEquals(BuildConfig.VERSION_CODE.toLong(), VersionNames.codeOf(BuildConfig.VERSION_NAME))
    }

    @Test
    fun aNewerBuildUnderTheSameKeyOnTheSameTrackIsWorthAsking() {
        assertTrue(UpdateEligibility.worthAsking(build("2.13.0"), build("2.14.0"), sdkInt = 34))
        // A pre-release phone takes anything newer, the next stable included.
        assertTrue(UpdateEligibility.worthAsking(build("2.14.0-beta.1"), build("2.14.0-rc.1"), sdkInt = 34))
        assertTrue(UpdateEligibility.worthAsking(build("2.14.0-beta.1"), build("2.14.0"), sdkInt = 34))
    }

    @Test
    fun nothingOlderOtherwiseSignedOnTheOtherTrackOrUnfitIsWorthAsking() {
        val mine = build("2.13.0")
        assertFalse("the same version", UpdateEligibility.worthAsking(mine, build("2.13.0"), 34))
        assertFalse("older", UpdateEligibility.worthAsking(build("2.14.0"), build("2.13.0"), 34))
        assertFalse("another signer", UpdateEligibility.worthAsking(mine, build("2.14.0", pin = 8L), 34))
        assertFalse("the slim track", UpdateEligibility.worthAsking(mine, build("2.14.0-slim"), 34))
        assertFalse("a pre-release for a stable phone", UpdateEligibility.worthAsking(mine, build("2.14.0-alpha.2"), 34))
        assertFalse("a newer minSdk", UpdateEligibility.worthAsking(mine, build("2.14.0", minSdk = 35), 34))
        assertFalse("no size", UpdateEligibility.worthAsking(mine, build("2.14.0", size = 0L), 34))
        assertFalse("past the bound", UpdateEligibility.worthAsking(mine, build("2.14.0", size = UpdateEligibility.MAX_APK_BYTES + 1), 34))
    }

    @Test
    fun anArchiveInstallsOnlyAsTheVersionAskedForUnderTheInstalledKey() {
        val installed = build("2.13.0")
        val signers = setOf("aa")
        val good = ArchiveFacts("app.getknit.knit", 21_400_900L, "2.14.0", setOf("aa"))

        fun why(facts: ArchiveFacts) = UpdateEligibility.archiveRefusal(facts, "app.getknit.knit", installed, signers, 21_400_900L)
        assertNull(why(good))
        // A rotated key still proves the installed one in its lineage.
        assertNull(why(good.copy(signers = setOf("aa", "bb"))))
        assertEquals("another package", why(good.copy(packageName = "com.example")))
        assertEquals("not the version asked for", why(good.copy(versionCode = 21_500_900L, versionName = "2.15.0")))
        assertEquals("another signer", why(good.copy(signers = setOf("bb"))))
        assertEquals("a version name that does not derive its code", why(good.copy(versionName = "9.9.9")))
        assertEquals("the other track", why(good.copy(versionName = "2.14.0-slim")))
        assertEquals(
            "a pre-release",
            UpdateEligibility.archiveRefusal(
                ArchiveFacts("app.getknit.knit", 21_400_002L, "2.14.0-alpha.2", signers),
                "app.getknit.knit",
                installed,
                signers,
                21_400_002L,
            ),
        )
        assertEquals(
            "not newer than the installed build",
            UpdateEligibility.archiveRefusal(good, "app.getknit.knit", build("2.14.0"), signers, 21_400_900L),
        )
        assertEquals("another signer", UpdateEligibility.archiveRefusal(good, "app.getknit.knit", installed, emptySet(), 21_400_900L))
    }
}
