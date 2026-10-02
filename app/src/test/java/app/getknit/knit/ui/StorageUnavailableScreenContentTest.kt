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

/**
 * The screen a Keystore refusal shows instead of the app (ADR 2026-10.47rw): says so, Try again opens again, and Start
 * over — the way out of a Keystore that never answers — clears the phone only past one confirmation.
 */
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
        compose.setContent { KnitTheme { StorageUnavailableScreen(trying = false, onRetry = { retries++ }, onStartOver = {}) } }

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
        compose.setContent { KnitTheme { StorageUnavailableScreen(trying = true, onRetry = {}, onStartOver = {}) } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("storage_retry").assertIsNotEnabled()
        compose.onNodeWithTag("storage_start_over").assertIsNotEnabled()
    }

    @Test
    fun startOverClearsOnlyPastItsConfirmation() {
        var startOvers = 0
        compose.setContent { KnitTheme { StorageUnavailableScreen(trying = false, onRetry = {}, onStartOver = { startOvers++ }) } }

        compose.onNodeWithText(context.getString(R.string.storage_unavailable_start_over)).performClick()
        compose.onNodeWithText(context.getString(R.string.storage_start_over_title)).assertIsDisplayed()
        assertEquals("the tap only asks", 0, startOvers)

        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithText(context.getString(R.string.storage_start_over_title)).assertDoesNotExist()
        assertEquals(0, startOvers)

        compose.onNodeWithText(context.getString(R.string.storage_unavailable_start_over)).performClick()
        compose.onNodeWithText(context.getString(R.string.storage_start_over_action)).performClick()
        assertEquals(1, startOvers)
        compose.onNodeWithText(context.getString(R.string.storage_start_over_title)).assertDoesNotExist()
    }
}
