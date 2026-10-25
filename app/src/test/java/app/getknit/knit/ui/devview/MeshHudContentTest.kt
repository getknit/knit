package app.getknit.knit.ui.devview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The HUD's panel (ADR 2026-10.s7wg): a tap on the bubble opens it, and each of its buttons closes it as it navigates.
 * On the Pixel 9 an open panel followed the person onto the event console and covered it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeshHudContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun setHud() {
        compose.setContent {
            KnitTheme {
                Box(Modifier.size(360.dp, 640.dp)) {
                    MeshHudContent(
                        reading = HudReading(MeshPosture.Running, neighbors = 2, custody = 7),
                        onOpenDiagnostics = { opened += "diagnostics" },
                        onOpenEvents = { opened += "events" },
                    )
                }
            }
        }
    }

    @Test
    fun theBubbleOpensThePanelAndEventsClosesItAsItNavigates() {
        setHud()
        compose.onNodeWithTag("dev_view_hud_panel").assertDoesNotExist()

        compose.onNodeWithTag("dev_view_hud_bubble").performClick()
        compose.onNodeWithTag("dev_view_hud_panel").assertExists()
        compose.onNodeWithTag("dev_view_hud_events").performClick()

        compose.onNodeWithTag("dev_view_hud_panel").assertDoesNotExist()
        assertEquals(listOf("events"), opened)
    }

    @Test
    fun diagnosticsClosesThePanelAsItNavigatesToo() {
        setHud()

        compose.onNodeWithTag("dev_view_hud_bubble").performClick()
        compose.onNodeWithTag("dev_view_hud_diagnostics").performClick()

        compose.onNodeWithTag("dev_view_hud_panel").assertDoesNotExist()
        assertEquals(listOf("diagnostics"), opened)
    }
}
