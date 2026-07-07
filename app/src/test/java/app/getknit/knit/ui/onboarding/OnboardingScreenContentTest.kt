package app.getknit.knit.ui.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The app's front door: Start is gated until permissions are granted. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun startIsDisabledUntilPermissionsGranted() {
        var grants = 0
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    meshSupported = true,
                    granted = false,
                    onGrantPermissions = { grants++ },
                    onAllowBattery = {},
                    onReady = {},
                )
            }
        }

        compose.onNodeWithTag("onboarding_start").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_grant").performClick()
        assertEquals(1, grants)
    }

    @Test
    fun startIsEnabledAndProceedsOnceGranted() {
        var ready = 0
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    meshSupported = true,
                    granted = true,
                    onGrantPermissions = {},
                    onAllowBattery = {},
                    onReady = { ready++ },
                )
            }
        }

        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
        compose.onNodeWithTag("onboarding_start").performClick()
        assertEquals(1, ready)
    }

    @Test
    fun unsupportedHardwareDoesNotBlockStart() {
        compose.setContent {
            KnitTheme {
                OnboardingScreenContent(
                    meshSupported = false,
                    granted = true,
                    onGrantPermissions = {},
                    onAllowBattery = {},
                    onReady = {},
                )
            }
        }

        // No mesh radio hardware (the radio-less Firebase Test Lab / single-radio-missing reality), yet
        // Start gates only on permissions (enabled = granted, independent of meshSupported) — the app
        // degrades gracefully rather than dead-ending, so the user can still reach the app.
        compose.onNodeWithTag("onboarding_start").assertIsEnabled()
    }
}
