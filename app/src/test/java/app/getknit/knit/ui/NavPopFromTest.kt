package app.getknit.knit.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A screen's Back pops only its own entry (GitHub #55). The screen leaving stays drawn and tappable through its exit
 * transition, so a second tap there must not pop the chat list under it, while a tap on a screen still arriving must
 * still leave.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavPopFromTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private val entries = mutableMapOf<String, NavBackStackEntry>()

    private fun setHost() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            nav = rememberNavController()
            NavHost(
                navController = nav,
                startDestination = LIST,
                enterTransition = { fadeIn(tween(TRANSITION_MS)) },
                exitTransition = { fadeOut(tween(TRANSITION_MS)) },
                popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
                popExitTransition = { fadeOut(tween(TRANSITION_MS)) },
            ) {
                composable(LIST) { Text("list") }
                for (route in listOf(CHAT, SETTINGS)) {
                    composable(route) { entry ->
                        entries[route] = entry
                        Text("back", Modifier.testTag("back-$route").clickable { nav.popFrom(entry) })
                    }
                }
            }
        }
        settle()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(TRANSITION_MS * 3L)

    private fun routes() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun aSecondBackTapOnTheLeavingScreenKeepsTheList() {
        setHost()
        compose.runOnUiThread { nav.navigate(CHAT) }
        settle()

        compose.onNodeWithTag("back-$CHAT").performClick()
        compose.mainClock.advanceTimeBy(TRANSITION_MS / 6L)
        compose.onNodeWithTag("back-$CHAT").performClick()
        settle()

        assertEquals(listOf(LIST), routes())
    }

    @Test
    fun backDuringTheEnterTransitionStillLeaves() {
        setHost()
        compose.runOnUiThread { nav.navigate(CHAT) }
        compose.mainClock.advanceTimeBy(TRANSITION_MS / 6L)

        compose.onNodeWithTag("back-$CHAT").performClick()
        settle()

        assertEquals(listOf(LIST), routes())
    }

    @Test
    fun aLateEventFromALeftScreenDoesNotPopTheScreenUnderIt() {
        setHost()
        compose.runOnUiThread { nav.navigate(CHAT) }
        settle()
        compose.runOnUiThread { nav.navigate(SETTINGS) }
        settle()
        val settings = entries.getValue(SETTINGS)

        // The user leaves Settings; its saved event then lands and asks to pop it again.
        compose.runOnUiThread {
            nav.popFrom(settings)
            nav.popFrom(settings)
        }
        settle()

        assertEquals(listOf(LIST, CHAT), routes())
    }

    private companion object {
        const val LIST = "list"
        const val CHAT = "chat"
        const val SETTINGS = "settings"
        const val TRANSITION_MS = 300
    }
}
