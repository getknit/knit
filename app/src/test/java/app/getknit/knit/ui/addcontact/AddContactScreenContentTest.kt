package app.getknit.knit.ui.addcontact

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.ui.theme.KnitTheme
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The stateless Add contact screen. Pinned: Continue is offered only with something typed and never while an
 * import runs; the preview shows the sender-chosen name beside the key-derived alias and the safety number;
 * a blocked person's confirm reads "Unblock and add" and says so to the caller; and a relay the card names is
 * only ever an Add the user taps (it opens the invite sheet upstream), never applied by the preview itself.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddContactScreenContentTest {
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val typed = mutableListOf<String>()
    private var lookups = 0
    private var pastes = 0
    private val confirms = mutableListOf<Boolean>()
    private val relaysAdded = mutableListOf<String>()

    private fun ready(
        blocked: Boolean = false,
        alreadyContact: Boolean = false,
        unknownRelays: List<String> = emptyList(),
    ) = ContactImporter.Preview.Ready(
        nodeId = NODE,
        displayName = "Rosalind",
        alias = "Amber Otter",
        safetyNumber = "12345 67890 12345 67890",
        alreadyContact = alreadyContact,
        blocked = blocked,
        relaysOff = false,
        unknownRelays = unknownRelays,
        card = mockk(relaxed = true),
    )

    private fun render(
        state: AddContactUiState,
        input: String = "",
    ) {
        compose.setContent {
            KnitTheme {
                AddContactScreenContent(
                    state = state,
                    input = input,
                    myQrPayload = null,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onScan = {},
                    onShareLink = {},
                    onCopyLink = {},
                    onInputChange = { typed += it },
                    onPaste = { pastes++ },
                    onLookup = { lookups++ },
                    onConfirm = { confirms += it },
                    onAddRelay = { relaysAdded += it },
                )
            }
        }
    }

    @Test
    fun continueWaitsForInput() {
        render(AddContactUiState.Idle)
        compose.onNodeWithTag("add_contact_lookup").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("add_contact_input").performScrollTo().performTextInput("https://getknit.app/c#x")
        compose.onNodeWithTag("add_contact_paste").performScrollTo().performClick()
        assertEquals(listOf("https://getknit.app/c#x"), typed)
        assertEquals(1, pastes)
    }

    @Test
    fun continueLooksUpWhatWasTyped() {
        render(AddContactUiState.Idle, input = "https://getknit.app/c#x")
        compose
            .onNodeWithTag("add_contact_lookup")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, lookups)
    }

    @Test
    fun anImportInFlightDisablesContinue() {
        render(AddContactUiState.Importing, input = "https://getknit.app/c#x")
        compose.onNodeWithTag("add_contact_lookup").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Adding…").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thePreviewShowsNameAliasAndSafetyNumberAndConfirms() {
        render(AddContactUiState.Preview(ready()))
        compose.onNodeWithText("Add Rosalind?").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Amber Otter", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("12345 67890 12345 67890").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("add_contact_confirm")
            .performScrollTo()
            .assertTextContains("Add contact")
            .performClick()
        assertEquals(listOf(false), confirms)
    }

    @Test
    fun aBlockedPersonIsUnblockedOnlyByTheirOwnButton() {
        render(AddContactUiState.Preview(ready(blocked = true)))
        compose.onNodeWithText("You've blocked this person.").performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithTag("add_contact_confirm")
            .performScrollTo()
            .assertTextContains("Unblock and add")
            .performClick()
        assertEquals(listOf(true), confirms)
    }

    @Test
    fun anExistingContactIsToldAddingRefreshes() {
        render(AddContactUiState.Preview(ready(alreadyContact = true)))
        compose.onNodeWithText("already in your contacts", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aRelayTheCardNamesIsOnlyAnAddTheUserTaps() {
        val relay = "https://relay.example.org/spool?k=secret"
        render(AddContactUiState.Preview(ready(unknownRelays = listOf(relay))))
        compose.onNodeWithText("relay.example.org").performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), relaysAdded)
        compose.onNodeWithTag("add_contact_relay_add_relay.example.org").performScrollTo().performClick()
        assertEquals(listOf(relay), relaysAdded)
    }

    private companion object {
        const val NODE = "rrrrrrrrrrrrrrrrrrrrrrrrrr"
    }
}
