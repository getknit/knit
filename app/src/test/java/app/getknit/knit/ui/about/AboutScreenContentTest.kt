package app.getknit.knit.ui.about

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.legal.InstallSource
import app.getknit.knit.legal.License
import app.getknit.knit.ui.ISSUES_URL
import app.getknit.knit.ui.REPO_URL
import app.getknit.knit.ui.WEBSITE_URL
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** About: the version line, the five link rows each going where they say, and the Build facts with their Copy. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val licenses = mutableListOf<License>()
    private var openedLicenses = 0
    private var copied = 0

    private fun render(
        buildType: String = "release",
        obfuscated: Boolean = true,
        installSource: InstallSource = InstallSource.FDROID,
        commit: String? = null,
    ) {
        compose.setContent {
            KnitTheme {
                AboutScreenContent(
                    info = AboutBuildInfo(previewEnvironment(buildType, obfuscated), installSource, commit),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onOpenUrl = { opened += it },
                    onOpenLicense = { licenses += it },
                    onOpenLicenses = { openedLicenses++ },
                    onCopyBuildInfo = { copied++ },
                )
            }
        }
    }

    @Test
    fun theHeaderNamesTheVersion() {
        render()
        // The header merges its descendants into one TalkBack stop, so the version reads off that node.
        compose.onNodeWithTag("about_header").assertIsDisplayed().assertTextContains("Version 2.5.1 (21)")
    }

    @Test
    fun theLinkRowsGoWhereTheySay() {
        render()
        compose.onNodeWithTag("about_source").performScrollTo().performClick()
        compose.onNodeWithTag("about_report").performScrollTo().performClick()
        compose.onNodeWithTag("about_website").performScrollTo().performClick()
        assertEquals(listOf(REPO_URL, ISSUES_URL, WEBSITE_URL), opened)

        compose.onNodeWithTag("about_license").performScrollTo().performClick()
        assertEquals(listOf(License.GPL_3_0_OR_LATER), licenses)

        compose.onNodeWithTag("about_licenses").performScrollTo().performClick()
        assertEquals(1, openedLicenses)
    }

    @Test
    fun theBuildRowsReadTheArtifactNotJustTheLabel() {
        render(buildType = "release", obfuscated = true, installSource = InstallSource.FDROID)
        compose.onNodeWithTag("about_build_version").performScrollTo().assertTextContains("2.5.1 (21)")
        compose.onNodeWithTag("about_build_type").assertTextContains("release · minified")
        compose.onNodeWithTag("about_build_installed_from").assertTextContains("F-Droid")
        compose.onNodeWithTag("about_build_android").assertTextContains("16 (SDK 36)")
        compose.onNodeWithTag("about_build_device").assertTextContains("Google Pixel 8")
    }

    @Test
    fun aReleaseBuildShowsNoCommit() {
        render()
        compose.onNodeWithTag("about_build_version").performScrollTo()
        compose.onNodeWithTag("about_build_commit").assertDoesNotExist()
    }

    @Test
    fun aDebugBuildShowsItsCommit() {
        render(buildType = "debug", obfuscated = false, commit = "06dd410d")
        compose.onNodeWithTag("about_build_commit").performScrollTo().assertTextContains("06dd410d")
    }

    @Test
    fun aDebugBuildIsNotCalledMinified() {
        render(buildType = "debug", obfuscated = false, installSource = InstallSource.SIDELOADED)
        compose.onNodeWithTag("about_build_type").performScrollTo().assertTextContains("debug")
        compose.onNodeWithTag("about_build_installed_from").assertTextContains("Sideloaded", substring = true)
    }

    @Test
    fun copyBuildInfoFires() {
        render()
        compose.onNodeWithText("Copy build info").performScrollTo().performClick()
        assertEquals(1, copied)
    }
}
