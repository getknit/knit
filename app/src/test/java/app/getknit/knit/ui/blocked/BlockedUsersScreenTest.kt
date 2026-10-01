package app.getknit.knit.ui.blocked

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.ui.directoryOf
import app.getknit.knit.ui.peer
import app.getknit.knit.ui.theme.KnitTheme
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** The blocked-users screen over its real view model: the empty state, a row per blocked person, and Unblock. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlockedUsersScreenTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val settings = mockk<SettingsStore>(relaxed = true)
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val blocked = MutableStateFlow(emptySet<String>())
    private val directory = MutableStateFlow(emptyList<PeerEntity>())

    private fun render() {
        every { settings.blockedNodeIds } returns blocked
        every { peers.observeDirectory() } returns directory.map { directoryOf(it) }
        val vm = BlockedUsersViewModel(settings, peers)
        compose.setContent { KnitTheme { BlockedUsersScreen(onBack = {}, viewModel = vm) } }
    }

    @Test
    fun nobodyBlockedShowsTheEmptyState() {
        render()
        compose.onNodeWithText("No blocked users").assertIsDisplayed()
    }

    @Test
    fun aBlockedPersonIsListedAndCanBeUnblocked() {
        blocked.value = setOf(SPAMMER)
        directory.value = listOf(peer(SPAMMER, name = "Spammer"))
        render()

        compose.onNodeWithText("Spammer").assertIsDisplayed()
        compose.onNodeWithText(SPAMMER).assertIsDisplayed()
        compose.onNodeWithText("Unblock").performClick()
        compose.waitForIdle()

        coVerify { settings.unblock(SPAMMER, any()) }
    }

    private companion object {
        const val SPAMMER = "spamspamspamspamspamspamsp"
    }
}
