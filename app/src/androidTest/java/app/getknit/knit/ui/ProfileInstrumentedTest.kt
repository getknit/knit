package app.getknit.knit.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile editor accepts an edit on-device: typing a name reflects in the field and enables Save.
 *
 * We deliberately do NOT assert the seeded name pre-loads: `ProfileViewModel` reads the display name once in
 * `init` (`settings.displayName.first()`), so deep-linking straight to profile races the async seed (unlike
 * every other screen, which observes repository flows continuously). And we stop short of tapping Save —
 * `ProfileScreen` navigates back on a completed save (`viewModel.saved.collect { onBack() }`), and here the
 * screen is the nav root (deep-linked via `demo_route=profile`), so popping it has nothing to return to. The
 * edit round-trip + dirty-enables-Save is the core editor behavior and is independent of the seed timing.
 */
@RunWith(AndroidJUnit4::class)
class ProfileInstrumentedTest : SeededUiTest() {
    @Test
    fun editingTheNameReflectsAndEnablesSave() {
        launch("profile")

        awaitTag("profile_name")
        compose.onNodeWithTag("profile_name").performTextReplacement(NEW_NAME)
        compose.onNodeWithTag("profile_name").assertTextContains(NEW_NAME)
        compose.onNodeWithTag("profile_save").assertIsEnabled()
    }

    private companion object {
        const val NEW_NAME = "Trail Tester"
    }
}
