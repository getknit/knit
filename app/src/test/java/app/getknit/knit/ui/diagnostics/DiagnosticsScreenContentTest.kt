package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless `DiagnosticsScreenContent` on the JVM. The screen has no testTags, so assertions
 * target the self-identity text (top of the LazyColumn, always composed) and the resolved control-button
 * strings. Follows the Compose-on-Robolectric pattern in `ChatListScreenContentTest`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state() = DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace")

    @Test
    fun rendersSelfIdentity() {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                )
            }
        }

        compose.onNodeWithText("Ada Lovelace").assertIsDisplayed()
    }

    @Test
    fun tappingRestartInvokesTheCallback() {
        var restarts = 0
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state(),
                    health = TransportHealth.Healthy,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = { restarts++ },
                    onScan = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.diagnostics_restart_mesh)).performClick()
        assertEquals(1, restarts)
    }
}
