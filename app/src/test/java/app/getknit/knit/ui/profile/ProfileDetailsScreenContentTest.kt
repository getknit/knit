package app.getknit.knit.ui.profile

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.R
import app.getknit.knit.identity.Alias
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Drives the stateless `ProfileDetailsScreenContent` (a peer's contact-details view). Its body is a
 * `verticalScroll` Column, so every child composes regardless of viewport — we can assert on the status
 * and safety-number text and click the Message action (found by its icon contentDescription).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileDetailsScreenContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state(
        openToChat: Boolean = false,
        loraNodeLabel: String? = null,
    ) = ProfileDetailsUiState(
        openToChat = openToChat,
        loraNodeLabel = loraNodeLabel,
        nodeId = "8f3a2b1c9d4e",
        displayName = "Ada Lovelace",
        status = "Hiking this weekend",
        avatarHash = null,
        online = true,
        isBlocked = false,
        hasKey = true,
        verified = true,
        safetyNumber = "12345 67890 12345 67890 12345 67890",
        myQrPayload = null,
    )

    private fun setContent(
        onMessage: (String) -> Unit = {},
        openToChat: Boolean = false,
        loraNodeLabel: String? = null,
        showLoraRadio: Boolean = true,
    ) {
        compose.setContent {
            KnitTheme {
                ProfileDetailsScreenContent(
                    state = state(openToChat, loraNodeLabel),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onMessage = onMessage,
                    onScan = {},
                    onBlock = {},
                    onUnblock = {},
                    onMarkVerified = {},
                    onClearVerification = {},
                    showLoraRadio = showLoraRadio,
                )
            }
        }
    }

    @Test
    fun rendersStatusAndSafetyNumber() {
        setContent()
        compose.onNodeWithText("Hiking this weekend").assertIsDisplayed()
        // The safety number lives in the verification section below the fold; scroll it into view.
        compose.onNodeWithText("12345 67890 12345 67890 12345 67890").performScrollTo().assertIsDisplayed()
    }

    /** The alias is always shown on a profile — it is how a person says which Ada they are (ADR 058). */
    @Test
    fun rendersTheAliasLine() {
        setContent()
        val alias = Alias.aliasFor("8f3a2b1c9d4e")
        compose.onNodeWithText(context.getString(R.string.profile_alias, alias)).performScrollTo().assertIsDisplayed()
    }

    /** The badge is their declared flag: shown when set, absent (not merely hidden) otherwise. */
    @Test
    fun theOpenToChatBadgeShowsOnlyWhenSet() {
        setContent(openToChat = true)
        compose.onNodeWithTag("profile_details_open_to_chat").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_details_open_to_chat)).assertIsDisplayed()
    }

    @Test
    fun noBadgeWhenNotOpenToChat() {
        setContent()
        compose.onNodeWithTag("profile_details_open_to_chat").assertDoesNotExist()
    }

    /** The board line is the peer's claim: shown as `!hex` when their profile names one, absent otherwise. */
    @Test
    fun theBoundBoardShowsOnlyWhenTheProfileNamesOne() {
        setContent(loraNodeLabel = "!1234abcd")
        compose
            .onNodeWithText(context.getString(R.string.profile_details_lora_node, "!1234abcd"))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun noBoardLineWhenTheProfileNamesNoBoard() {
        setContent()
        compose.onNodeWithTag("profile_details_lora_node").assertDoesNotExist()
    }

    /** A build with no LoRa plane has no radio to compare against, so the claim stays hidden there. */
    @Test
    fun noBoardLineWhereThePlaneIsDark() {
        setContent(loraNodeLabel = "!1234abcd", showLoraRadio = false)
        compose.onNodeWithTag("profile_details_lora_node").assertDoesNotExist()
    }

    @Test
    fun tappingMessageForwardsTheNodeId() {
        var messaged: String? = null
        setContent(onMessage = { messaged = it })

        compose.onNodeWithContentDescription(context.getString(R.string.profile_details_message)).performClick()
        assertEquals("8f3a2b1c9d4e", messaged)
    }
}
