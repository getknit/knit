package app.getknit.knit.ui.yourmesh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the stateless Your mesh screen: the numbers and their labels, the copy each radio state gets, the
 * fresh-install variant, and that every stat is one merged node (its number and label read as one text).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YourMeshScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: YourMeshUiState,
        onOpenDiagnostics: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                YourMeshScreenContent(state = state, onBack = {}, onOpenDiagnostics = onOpenDiagnostics)
            }
        }
    }

    @Test
    fun thePopulatedScreenShowsEveryNumberWithItsLabelAsOneNode() {
        var opened = 0
        render(
            YourMeshUiState(
                nearbyCount = 3,
                carryingNow = 6,
                passedAlong = 312,
                handedDirect = 41,
                peopleMet = 48,
                since = 1_700_000_000_000L,
            ),
            onOpenDiagnostics = { opened++ },
        )
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("3", substring = true)
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("people nearby", substring = true)
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("part of the mesh", substring = true)
        compose.onNodeWithTag("your_mesh_carrying").assertTextContains("6 messages for other people", substring = true)
        compose.onNodeWithTag("your_mesh_passed_along").assertTextContains("312", substring = true)
        compose.onNodeWithTag("your_mesh_passed_along").assertTextContains("passed along", substring = true)
        compose.onNodeWithTag("your_mesh_handed_direct").assertTextContains("41", substring = true)
        compose.onNodeWithTag("your_mesh_people_met").assertTextContains("48", substring = true)
        compose.onNodeWithText("Since ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("your_mesh_encouragement").assertTextContains("people you never meet", substring = true)

        compose.onNodeWithTag("your_mesh_diagnostics").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aFreshInstallReadsAllTimeAndTheFreshParagraph() {
        render(YourMeshUiState())
        compose.onNodeWithText("All time").assertIsDisplayed()
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("0", substring = true)
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("No one in range", substring = true)
        compose.onNodeWithTag("your_mesh_carrying").assertTextContains("Nothing waiting", substring = true)
        compose.onNodeWithTag("your_mesh_encouragement").assertTextContains("start to move", substring = true)
    }

    @Test
    fun theRadioStateChoosesTheStatusLine() {
        render(YourMeshUiState(health = TransportHealth.Unavailable))
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("Turn on Wi-Fi or Bluetooth", substring = true)
    }

    @Test
    fun aSearchRefusedOffScreenNamesTheRuleAndKeepsBluetooth() {
        // ADR 2026-09.535d: the OS's rule on Android 10-12, not a fault — so the line says what still works.
        render(YourMeshUiState(health = TransportHealth.ForegroundOnly))
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("only while Knit is open", substring = true)
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("Bluetooth keeps going", substring = true)
    }

    @Test
    fun onePersonNearbyIsSingular() {
        render(YourMeshUiState(nearbyCount = 1, carryingNow = 1))
        // The merged node carries the number and the label as two texts, read in sequence by TalkBack.
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("1")
        compose.onNodeWithTag("your_mesh_hero").assertTextContains("person nearby")
        compose.onNodeWithTag("your_mesh_carrying").assertTextContains("1 message for other people", substring = true)
    }
}
