package app.getknit.knit.ui.diagnostics

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The developer view's switch (ADR 2026-10.s7wg): offered only where the build wires the view, off until turned on. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsDevViewTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun setDevViewContent(
        offered: Boolean,
        on: Boolean,
        onSet: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = DiagnosticsUiState(myNodeId = "8f3a2b1c9d4e", myName = "Ada Lovelace"),
                    health = TransportHealth.Healthy,
                    lastCrash = null,
                    now = 0L,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRestartMesh = {},
                    onScan = {},
                    onOpenCrashLog = {},
                    moderationLatched = false,
                    onResetModeration = {},
                    devViewOffered = offered,
                    devView = on,
                    onSetDevView = onSet,
                )
            }
        }
    }

    @Test
    fun theDeveloperViewSwitchIsAbsentUnlessOffered() {
        setDevViewContent(offered = false, on = false)
        compose.onNodeWithTag("dev_view_switch").assertDoesNotExist()
    }

    @Test
    fun theDeveloperViewSwitchIsOffUntilTurnedOn() {
        val sets = mutableListOf<Boolean>()
        setDevViewContent(offered = true, on = false, onSet = { sets += it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("dev_view_switch"))
        compose.onNodeWithTag("dev_view_switch").assertIsOff().performClick()
        assertEquals(listOf(true), sets)
    }

    @Test
    fun anOnDeveloperViewSwitchTurnsOff() {
        val sets = mutableListOf<Boolean>()
        setDevViewContent(offered = true, on = true, onSet = { sets += it })
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("dev_view_switch"))
        compose.onNodeWithTag("dev_view_switch").assertIsOn().performClick()
        assertEquals(listOf(false), sets)
    }
}
