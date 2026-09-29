package app.getknit.knit.ui.profile

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import app.getknit.knit.ui.Reach
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
    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun state(
        openToChat: Boolean = false,
        loraNodeLabel: String? = null,
        reach: Reach = Reach.Direct,
        verified: Boolean = true,
        isBlocked: Boolean = false,
        inCommon: InCommon = InCommon.EMPTY,
        removal: ContactRemoval = ContactRemoval.NotOffered,
    ) = ProfileDetailsUiState(
        openToChat = openToChat,
        loraNodeLabel = loraNodeLabel,
        nodeId = "8f3a2b1c9d4e",
        displayName = "Ada Lovelace",
        status = "Hiking this weekend",
        avatarHash = null,
        reach = reach,
        isBlocked = isBlocked,
        hasKey = true,
        verified = verified,
        safetyNumber = "12345 67890 12345 67890 12345 67890",
        myQrPayload = null,
        inCommon = inCommon,
        removal = removal,
    )

    private fun setContent(
        onMessage: (String) -> Unit = {},
        onOpenGroup: (String) -> Unit = {},
        openToChat: Boolean = false,
        loraNodeLabel: String? = null,
        showLoraRadio: Boolean = true,
        reach: Reach = Reach.Direct,
        verified: Boolean = true,
        isBlocked: Boolean = false,
        inCommon: InCommon = InCommon.EMPTY,
        removal: ContactRemoval = ContactRemoval.NotOffered,
        onRemoveContact: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                ProfileDetailsScreenContent(
                    state = state(openToChat, loraNodeLabel, reach, verified, isBlocked, inCommon, removal),
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onMessage = onMessage,
                    onOpenGroup = onOpenGroup,
                    onScan = {},
                    onBlock = {},
                    onUnblock = {},
                    onRemoveContact = onRemoveContact,
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

    /**
     * The presence line names all three tiers, so a contact reachable only through a relay (a LoRa board or
     * an Internet spool) reads as such rather than as offline — the same split Diagnostics draws.
     */
    @Test
    fun thePresenceLineNamesEachReachTier() {
        setContent(reach = Reach.Relay)
        compose.onNodeWithText(context.getString(R.string.profile_details_via_relay)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_details_offline)).assertDoesNotExist()
    }

    @Test
    fun aKnownPeerNobodyReachesReadsOffline() {
        setContent(reach = Reach.Known)
        compose.onNodeWithText(context.getString(R.string.profile_details_offline)).assertIsDisplayed()
    }

    @Test
    fun aNearbyPeerReadsOnline() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.profile_details_online)).assertIsDisplayed()
    }

    /** The alias is always shown on a profile — it is how a person says which Ada they are (ADR 058). */
    @Test
    fun rendersTheAliasRow() {
        setContent()
        val row = compose.onNodeWithTag("profile_details_alias")
        row.performScrollTo().assertIsDisplayed()
        row.assertTextContains(context.getString(R.string.profile_alias_label), substring = true)
        row.assertTextContains(Alias.aliasFor("8f3a2b1c9d4e"), substring = true)
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
        val row = compose.onNodeWithTag("profile_details_lora_node")
        row.performScrollTo().assertIsDisplayed()
        row.assertTextContains(context.getString(R.string.profile_details_lora_label), substring = true)
        row.assertTextContains("!1234abcd", substring = true)
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

        // A full-width button carrying its own label now, not a bare icon found by contentDescription.
        compose.onNodeWithTag("profile_details_message").performClick()
        assertEquals("8f3a2b1c9d4e", messaged)
    }

    /**
     * Verification used to be visible only at the foot of the screen. The badge reports it up top — and
     * only when it is true: "not verified" is the ordinary case, and the Encryption section still names it.
     */
    @Test
    fun theVerifiedBadgeShowsOnlyWhenVerified() {
        setContent(verified = true)
        compose.onNodeWithTag("profile_details_verified").assertIsDisplayed()
    }

    @Test
    fun noVerifiedBadgeForAnUnverifiedPeer() {
        setContent(verified = false)
        compose.onNodeWithTag("profile_details_verified").assertDoesNotExist()
    }

    /** Blocked was invisible on this screen before — the only tell was the overflow item's flipped label. */
    @Test
    fun theBlockedBadgeShowsOnlyWhenBlocked() {
        setContent(isBlocked = true)
        compose.onNodeWithTag("profile_details_blocked").assertIsDisplayed()
    }

    @Test
    fun noBlockedBadgeForAnOrdinaryContact() {
        setContent()
        compose.onNodeWithTag("profile_details_blocked").assertDoesNotExist()
    }

    /** No shared group: the whole section is absent, not an empty heading. */
    @Test
    fun theInCommonSectionIsAbsentWithNoSharedGroup() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.profile_details_section_in_common)).assertDoesNotExist()
    }

    /** Never met: Details keeps its identifier rows and simply has no met rows above them. */
    @Test
    fun theMetRowsAreAbsentForAPeerNeverMet() {
        setContent()
        compose.onNodeWithText(context.getString(R.string.profile_details_section_details)).assertIsDisplayed()
        compose.onNodeWithTag("profile_details_first_met").assertDoesNotExist()
        compose.onNodeWithTag("profile_details_last_met").assertDoesNotExist()
    }

    /**
     * The met stamps live under Details, not "In common": when your radios first saw each other is a fact
     * about the contact, not something the two of you share. A peer met but in no shared group still shows
     * them, and shows no "In common" heading at all.
     */
    @Test
    fun theMetRowsSitUnderDetailsRatherThanInCommon() {
        setContent(inCommon = InCommon(firstMetAt = FIRST_MET, lastMetAt = LAST_MET))
        compose.onNodeWithText(context.getString(R.string.profile_details_section_in_common)).assertDoesNotExist()
        compose.onNodeWithTag("profile_details_first_met").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_details_last_met").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun eachSharedGroupGetsItsOwnRow() {
        setContent(inCommon = InCommon(groups = SHARED, firstMetAt = FIRST_MET, lastMetAt = LAST_MET))
        val row = compose.onNodeWithTag("profile_details_group_g-trail")
        row.performScrollTo().assertIsDisplayed()
        row.assertTextContains("Trail Crew", substring = true)
        compose.onNodeWithTag("profile_details_group_g-book").performScrollTo().assertIsDisplayed()
    }

    /** Each shared group is its own tap target, opening that group rather than anything about the peer. */
    @Test
    fun tappingASharedGroupOpensThatGroup() {
        var opened: String? = null
        setContent(inCommon = InCommon(groups = SHARED), onOpenGroup = { opened = it })
        compose.onNodeWithTag("profile_details_group_g-book").performScrollTo().performClick()
        assertEquals("g-book", opened)
    }

    /**
     * An unnamed group carries a blank `name` on the wire by design, and each device renders its own
     * title from the members. Reading the column raw made three shared groups render as ", , Sihaya";
     * the row shows whatever title the ViewModel resolved, never an empty string.
     */
    @Test
    fun anUnnamedGroupStillShowsATitle() {
        setContent(inCommon = InCommon(groups = listOf(SharedGroup("g-x", "Priya & Theo", null, emptyList()))))
        compose
            .onNodeWithTag("profile_details_group_g-x")
            .performScrollTo()
            .assertTextContains("Priya & Theo", substring = true)
    }

    /**
     * Met once: the row collapses. The stamps are *not* equal — a first sighting writes `firstMetAt` and
     * `lastMetAt` a few milliseconds apart — so the rule has to compare the rendered date, not the longs.
     * Testing it with identical stamps would pass against the bug this guards.
     */
    @Test
    fun aPeerMetOnceShowsNoLastMetRow() {
        setContent(inCommon = InCommon(firstMetAt = FIRST_MET, lastMetAt = FIRST_MET + 40L))
        compose.onNodeWithTag("profile_details_first_met").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile_details_last_met").assertDoesNotExist()
    }

    private companion object {
        val SHARED =
            listOf(
                SharedGroup("g-trail", "Trail Crew", photoHash = null, faces = emptyList()),
                SharedGroup("g-book", "Book Club", photoHash = null, faces = emptyList()),
            )
        const val FIRST_MET = 1_755_000_000_000L
        const val LAST_MET = 1_757_900_000_000L
    }

    // --- Remove contact (ADR 2026-09.adgd) ---

    private fun sharedGroup(
        id: String,
        title: String,
    ) = SharedGroup(id, title, photoHash = null, faces = emptyList())

    private fun openMenu() {
        compose.onNodeWithContentDescription(context.getString(R.string.chat_more_options)).performClick()
    }

    private fun openRemove() {
        openMenu()
        compose.onNodeWithTag("profile_details_remove_contact").performClick()
    }

    @Test
    fun noRemoveItemWhenRemovalIsNotOffered() {
        setContent()
        openMenu()
        compose.onNodeWithText(context.getString(R.string.chat_action_block)).assertIsDisplayed()
        compose.onNodeWithTag("profile_details_remove_contact").assertDoesNotExist()
    }

    @Test
    fun removeAsksFirstAndCancelChangesNothing() {
        var removed = 0
        setContent(removal = ContactRemoval.Removes(keptBy = emptyList(), clearsVerification = false), onRemoveContact = { removed++ })

        openRemove()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_title, "Ada Lovelace")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_body, "Ada Lovelace")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_verified)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithTag("profile_details_remove_confirm").assertDoesNotExist()
        assertEquals(0, removed)

        openRemove()
        compose.onNodeWithTag("profile_details_remove_confirm").performClick()
        compose.onNodeWithTag("profile_details_remove_confirm").assertDoesNotExist()
        assertEquals(1, removed)
    }

    @Test
    fun theConfirmNamesTheGroupsThatKeepThemAndTheVerificationItClears() {
        setContent(
            removal = ContactRemoval.Removes(keptBy = listOf(sharedGroup("g-trail", "Trail Crew")), clearsVerification = true),
        )
        openRemove()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_body_kept, "Ada Lovelace")).assertIsDisplayed()
        compose.onNodeWithText("Trail Crew").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_verified)).assertIsDisplayed()
        compose.onNodeWithTag("profile_details_remove_confirm").assertIsDisplayed()
    }

    @Test
    fun aContactOnlyThroughAGroupIsToldWhyAndOfferedNoRemove() {
        var removed = 0
        setContent(removal = ContactRemoval.GroupsOnly(listOf(sharedGroup("g-trail", "Trail Crew"))), onRemoveContact = { removed++ })

        openRemove()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_kept_title, "Ada Lovelace")).assertIsDisplayed()
        compose.onNodeWithText("Trail Crew").assertIsDisplayed()
        compose.onNodeWithTag("profile_details_remove_confirm").assertDoesNotExist()
        compose.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        compose.onNodeWithText(context.getString(R.string.profile_details_remove_kept_title, "Ada Lovelace")).assertDoesNotExist()
        assertEquals(0, removed)
    }

    @Test
    fun moreThanThreeGroupsCollapseIntoACount() {
        val groups = (1..5).map { sharedGroup("g-$it", "Group $it") }
        setContent(removal = ContactRemoval.Removes(keptBy = groups, clearsVerification = false))
        openRemove()
        compose.onNodeWithText("Group 3").assertIsDisplayed()
        compose.onNodeWithText("Group 4").assertDoesNotExist()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.profile_details_remove_more_groups, 2, 2)).assertIsDisplayed()
    }
}
