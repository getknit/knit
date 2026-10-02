package app.getknit.knit.ui

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The screen a Keystore refusal shows instead of the app (ADR 2026-10.47rw): says so, and Try again opens again. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StorageUnavailableScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun itNamesTheStateAndTryAgainRetries() {
        var retries = 0
        compose.setContent { KnitTheme { StorageUnavailableScreen(trying = false, onRetry = { retries++ }) } }

        compose
            .onNodeWithText(context.getString(R.string.storage_unavailable_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(context.getString(R.string.storage_unavailable_body)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.storage_unavailable_retry)).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun aRetryInFlightCannotBeTappedAgain() {
        compose.mainClock.autoAdvance = false // the progress ring spins while a retry runs
        compose.setContent { KnitTheme { StorageUnavailableScreen(trying = true, onRetry = {}) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("storage_retry").assertIsNotEnabled()
    }
}
