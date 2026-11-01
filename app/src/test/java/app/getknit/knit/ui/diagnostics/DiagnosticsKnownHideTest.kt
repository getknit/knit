package app.getknit.knit.ui.diagnostics

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.Reach
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Hiding rows from Diagnostics' *Known, not reachable* (GitHub #54, ADR 2026-11.b37x), on the stateless screen with
 * its selection held the way `DiagnosticsScreen` holds it: a long-press picks a row, the top bar counts and hides,
 * and the section offers Hide all and Show hidden.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiagnosticsKnownHideTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val alan = NodeInfo(nodeId = "n1", displayName = "Alan Turing", reach = Reach.Known, profileUpdatedAt = null)

    private fun state(hidingOffered: Boolean = true) =
        DiagnosticsUiState(
            myNodeId = "8f3a2b1c9d4e",
            myName = "Ada Lovelace",
            knownNodes = listOf(alan),
            knownTotal = 3,
            hidingOffered = hidingOffered,
            hideableKnown = mapOf("n1" to 1L, "n2" to 1L, "n3" to 1L),
            hiddenKnownCount = 2,
        )

    /** The selection and the hide callbacks, counted. */
    private class Hides {
        var selected by mutableStateOf(emptySet<String>())
        var hideSelected = 0
        var hideAll = 0
        var showHidden = 0
    }

    private fun setContent(
        hides: Hides,
        state: DiagnosticsUiState = state(),
    ) {
        compose.setContent {
            KnitTheme {
                DiagnosticsScreenContent(
                    state = state,
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
                    selected = hides.selected,
                    onToggleSelected = { id -> hides.selected = if (id in hides.selected) hides.selected - id else hides.selected + id },
                    onClearSelection = { hides.selected = emptySet() },
                    onHideSelected = { hides.hideSelected++ },
                    onHideAll = { hides.hideAll++ },
                    onShowHidden = { hides.showHidden++ },
                )
            }
        }
    }

    private fun scrollTo(tag: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(tag))

    @Test
    fun aLongPressPicksARowAndTheTopBarCountsAndHides() {
        val hides = Hides()
        setContent(hides)

        scrollTo("known_row_n1")
        compose.onNodeWithTag("known_row_n1").performTouchInput { longClick() }

        assertEquals(setOf("n1"), hides.selected)
        val title = context.resources.getQuantityString(R.plurals.diagnostics_hide_selected, 1, 1)
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithTag("known_row_n1").assertIsOn()
        compose.onNodeWithTag("diagnostics_hide_all").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_hide_selected").performClick()
        assertEquals(1, hides.hideSelected)
    }

    @Test
    fun whilePickingATapTogglesAndTheWayOutEmptiesTheSelection() {
        val hides = Hides()
        setContent(hides)

        scrollTo("known_row_n1")
        compose.onNodeWithTag("known_row_n1").performClick()
        assertEquals("a tap outside a selection picks nothing", emptySet<String>(), hides.selected)

        compose.onNodeWithTag("known_row_n1").performTouchInput { longClick() }
        compose.onNodeWithTag("known_row_n1").performClick()
        assertEquals(emptySet<String>(), hides.selected)

        compose.onNodeWithTag("known_row_n1").performTouchInput { longClick() }
        compose.onNodeWithTag("diagnostics_clear_selection").performClick()
        assertEquals(emptySet<String>(), hides.selected)
        compose.onNodeWithText(context.getString(R.string.diagnostics_title)).assertIsDisplayed()
    }

    @Test
    fun theAccessibilityActionPicksARow() {
        val hides = Hides()
        setContent(hides)

        scrollTo("known_row_n1")
        val row = compose.onNodeWithTag("known_row_n1")
        val select = context.getString(R.string.diagnostics_hide_select)
        row.assert(SemanticsMatcher("offers $select") { node -> node.config[SemanticsActions.CustomActions].any { it.label == select } })
        row.performSemanticsAction(SemanticsActions.OnLongClick)

        assertEquals(setOf("n1"), hides.selected)
    }

    @Test
    fun hideAllCountsEveryShownRowAndShowHiddenCountsTheHiddenOnes() {
        val hides = Hides()
        setContent(hides)

        scrollTo("diagnostics_hide_all")
        compose.onNodeWithText(context.getString(R.string.diagnostics_hide_all, 3)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.diagnostics_show_hidden, 2)).assertIsDisplayed().performClick()

        assertEquals(1, hides.hideAll)
        assertEquals(1, hides.showHidden)
    }

    /** With every row hidden the section stays, so its Show hidden button can bring them back. */
    @Test
    fun withEveryRowHiddenTheSectionKeepsShowHidden() {
        val hides = Hides()
        setContent(hides, state().copy(knownNodes = emptyList(), knownTotal = 0, hideableKnown = emptyMap()))

        scrollTo("diagnostics_show_hidden")
        compose.onNodeWithTag("diagnostics_show_hidden").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics_hide_all").assertDoesNotExist()
    }

    /** Where no hidden-row store is wired, a Known row is a plain row: no long-press, no section actions. */
    @Test
    fun withoutTheStoreNothingIsOffered() {
        val hides = Hides()
        setContent(hides, state(hidingOffered = false))

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Alan Turing"))
        compose.onNodeWithTag("known_row_n1").assertDoesNotExist()
        compose.onNodeWithTag("diagnostics_hide_all").assertDoesNotExist()
    }
}
