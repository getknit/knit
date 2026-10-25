package app.getknit.knit.ui.relay

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.ui.theme.KnitTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The relay editor's load-bearing behaviours: the switch reflects the stored setting, a relay's live
 * state reaches its row, a row opens its relay and its switch parks it, the empty list explains itself, and — the
 * one that protects a release build — an unacceptable URL cannot be added. A relay's own screen is
 * [RelayDetailScreenContentTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InternetRelayScreenContentTest {
    private companion object {
        /** A customer key's shape; the sheet must never show it. */
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
    }

    @Suppress("DEPRECATION") // junit4.v2 rules swap in StandardTestDispatcher — a test-semantics migration, see roadmap.md
    @get:Rule
    val compose = createComposeRule()

    private fun render(
        state: InternetRelayUiState,
        showConsent: Boolean = false,
        onToggle: (Boolean) -> Unit = {},
        onAddRelay: (String) -> Unit = {},
        onOpenRelay: (String) -> Unit = {},
        onSetRelayEnabled: (String, Boolean) -> Unit = { _, _ -> },
        onAcceptConsent: () -> Unit = {},
        invitePreview: RelayInviteApplier.Preview? = null,
        onConfirmInvite: () -> Unit = {},
        onDismissInvite: () -> Unit = {},
    ) {
        compose.setContent {
            KnitTheme {
                InternetRelayScreenContent(
                    state = state,
                    showConsent = showConsent,
                    onBack = {},
                    onToggle = onToggle,
                    onAcceptConsent = onAcceptConsent,
                    onAddRelay = onAddRelay,
                    onOpenRelay = onOpenRelay,
                    onSetRelayEnabled = onSetRelayEnabled,
                    isValidUrl = { it.startsWith("wss://") },
                    invitePreview = invitePreview,
                    onConfirmInvite = onConfirmInvite,
                    onDismissInvite = onDismissInvite,
                )
            }
        }
    }

    private fun preview(
        private: Boolean = true,
        alreadyAdded: Boolean = false,
        parked: Boolean = false,
        replaces: String? = null,
        planeOff: Boolean = false,
        consentNeeded: Boolean = false,
        room: RelayInviteApplier.Room? = null,
        ticketKey: RelayInviteApplier.TicketKey? = null,
    ) = RelayInviteApplier.Preview(
        url = "wss://home.example.org/spool/v1?k=t0ken",
        host = "home.example.org",
        private = private,
        alreadyAdded = alreadyAdded,
        parked = parked,
        replaces = replaces,
        planeOff = planeOff,
        consentNeeded = consentNeeded,
        room = room,
        ticketKey = ticketKey,
    )

    private fun ticketKey(
        alreadyHeld: Boolean = false,
        replacesKey: Boolean = false,
    ) = RelayInviteApplier.TicketKey(
        host = "home.example.org",
        held = HeldKey("https://anywhere.example.org", KEY),
        alreadyHeld = alreadyHeld,
        replacesKey = replacesKey,
    )

    private fun room(
        name: String? = "Home",
        alreadyJoined: Boolean = false,
        beside: Boolean = false,
    ) = RelayInviteApplier.Room(secret = ByteArray(32), name = name, alreadyJoined = alreadyJoined, beside = beside)

    private fun relay(
        host: String = "public-1.spool.getknit.app",
        enabled: Boolean = true,
        connected: Boolean = true,
        scopeCount: Int? = 3,
        carriesPhotos: Boolean? = true,
        lastError: String? = null,
        rooms: List<RelayRoom> = emptyList(),
        takesRooms: Boolean = false,
        calls: RelayCalls? = null,
        account: RelayAccount? = null,
    ) = RelayRow(
        url = "wss://$host/spool/v1",
        host = host,
        enabled = enabled,
        connected = connected,
        scopeCount = scopeCount,
        carriesPhotos = carriesPhotos,
        lastError = lastError,
        rooms = rooms,
        takesRooms = takesRooms,
        calls = calls,
        account = account,
    )

    @Test
    fun switchReflectsTheStoredSetting() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay())))
        compose.onNodeWithTag("relays_switch").assertIsOn()
    }

    @Test
    fun switchIsOffByDefault() {
        render(InternetRelayUiState())
        compose.onNodeWithTag("relays_switch").assertIsOff()
    }

    @Test
    fun togglingReportsTheRequestedState() {
        var requested: Boolean? = null
        render(InternetRelayUiState(), onToggle = { requested = it })
        compose.onNodeWithTag("relays_switch").performClick()
        assertEquals(true, requested)
    }

    @Test
    fun anEmptyListExplainsItselfRatherThanLookingBroken() {
        // Removing the seeded default is a legitimate thing to do, so the empty state has to read as a
        // state rather than as a failure.
        render(InternetRelayUiState(enabled = true))
        compose.onNodeWithTag("relays_empty").assertIsDisplayed()
    }

    @Test
    fun aConnectedRelayShowsItsHostAndScopeCount() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay())))
        compose.onNodeWithText("public-1.spool.getknit.app").assertIsDisplayed()
        compose.onNodeWithText("Connected · 3 conversations").assertIsDisplayed()
    }

    @Test
    fun aFramesOnlyRelaySaysSoBeforeAPhotoFailsToUpload() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay(carriesPhotos = false))))
        compose.onNodeWithText("Carries messages only — no photos").assertIsDisplayed()
    }

    @Test
    fun anUnreachableRelayShowsWhy() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "unreachable")),
            ),
        )
        compose.onNodeWithText("Cannot be reached").assertIsDisplayed()
    }

    @Test
    fun aBusyRelayIsNotConfusedWithABrokenOne() {
        // A spool at its connection cap refuses the upgrade with 503 rather than a close code (spec §7.1
        // has no "come back later"), and it comes back on its own. That asks nothing of the user, while
        // "cannot be reached" asks them to go check the URL — so the two must not read alike.
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "http 503")),
            ),
        )
        compose.onNodeWithText("Busy — it is not taking new connections right now").assertIsDisplayed()
    }

    @Test
    fun aRelayThatNeverSpokeSaysSoInsteadOfQuotingACode() {
        // The socket opened and no hello came (a proxy forwarding nowhere), or it went quiet after one:
        // both are the client's own verdict, and "Refused a request (no_hello)" names a refusal nobody made.
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "no_hello")),
            ),
        )
        compose.onNodeWithText("Not answering").assertIsDisplayed()
    }

    @Test
    fun aRelayThatWentQuietAfterItsHelloReadsTheSame() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "unresponsive")),
            ),
        )
        compose.onNodeWithText("Not answering").assertIsDisplayed()
    }

    @Test
    fun aRelayWhoseNumbersDoNotFitThisAppSaysSo() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "too_large")),
            ),
        )
        compose.onNodeWithText("Not compatible with this version of Knit").assertIsDisplayed()
    }

    @Test
    fun aPhoneWithNoInternetSaysSoInsteadOfBlamingTheRelay() {
        // Work item 50's neighbour: with no validated route at all, "Cannot be reached" would send the
        // user to check the relay's address for a fault that is the phone's. A relay that is somehow still
        // connected outranks the platform's verdict for the seconds the two disagree.
        render(
            InternetRelayUiState(
                enabled = true,
                offline = true,
                relays =
                    listOf(
                        relay(host = "dead.example", connected = false, scopeCount = null, carriesPhotos = null, lastError = "unreachable"),
                        relay(host = "live.example", connected = true, scopeCount = 2),
                    ),
            ),
        )
        compose.onNodeWithText("No Internet connection").assertIsDisplayed()
        compose.onNodeWithText("Cannot be reached").assertDoesNotExist()
        compose.onNodeWithText("Connected · 2 conversations").assertIsDisplayed()
    }

    @Test
    fun anUnknownTransportFailureStillQuotesWhatHappened() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(connected = false, scopeCount = null, carriesPhotos = null, lastError = "http 404")),
            ),
        )
        compose.onNodeWithText("Refused a request (http 404)").assertIsDisplayed()
    }

    @Test
    fun aRelayIsNotAddedUntilItsSchemeIsAcceptable() {
        // The dialer refuses a non-wss URL at dial time in a release build, so storing one would leave a
        // row that can never connect. The editor refuses it at entry instead.
        var added: String? = null
        render(InternetRelayUiState(enabled = true), onAddRelay = { added = it })
        compose.onNodeWithTag("relays_add").performClick()
        compose.onNodeWithTag("relays_add_field").performTextInput("ws://plain.example.org/spool/v1")
        compose.onNodeWithText("Must start with wss://").assertIsDisplayed()
        assertEquals(null, added)
    }

    @Test
    fun anAcceptableRelayIsAdded() {
        var added: String? = null
        render(InternetRelayUiState(enabled = true), onAddRelay = { added = it })
        compose.onNodeWithTag("relays_add").performClick()
        compose.onNodeWithTag("relays_add_field").performTextInput("wss://new.example.org/spool/v1")
        // The dialog's confirm button carries the same label as the row that opened it.
        compose.onAllNodesWithText("Add relay").onLast().performClick()
        assertEquals("wss://new.example.org/spool/v1", added)
    }

    @Test
    fun aParkedRelayReadsAsParkedRatherThanAsAnOutage() {
        // The point of the row's own switch is that it is not a failure: the status line and the switch
        // have to agree, and neither may borrow the error styling a real outage uses.
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(enabled = false, connected = false, scopeCount = null, carriesPhotos = null)),
            ),
        )
        compose.onNodeWithText("Turned off").assertIsDisplayed()
        compose.onNodeWithTag("relay_switch_public-1.spool.getknit.app").assertIsOff()
    }

    @Test
    fun intentOutranksAStillConnectedWorker() {
        // ScopeSync reconciles on a 15 s tick, so a just-parked relay stays connected for a beat. The row
        // must report what the user asked for, or the switch reads as not having worked.
        render(
            InternetRelayUiState(enabled = true, relays = listOf(relay(enabled = false, connected = true))),
        )
        compose.onNodeWithText("Turned off").assertIsDisplayed()
    }

    @Test
    fun togglingOneRelayReportsItsOwnUrl() {
        val flipped = mutableListOf<Pair<String, Boolean>>()
        render(
            InternetRelayUiState(
                enabled = true,
                relays = listOf(relay(host = "one.example.org"), relay(host = "two.example.org", enabled = false)),
            ),
            onSetRelayEnabled = { url, on -> flipped += url to on },
        )
        compose.onNodeWithTag("relay_switch_two.example.org").performClick()
        assertEquals(listOf("wss://two.example.org/spool/v1" to true), flipped)
    }

    @Test
    fun aRelayRowCannotBeFlippedWhileThePlaneIsOff() {
        // The master switch gates the sockets, so a per-relay switch that still moved would be offering a
        // choice with no effect. It greys out instead, the same way the LoRa screen's sub-switches do.
        val flipped = mutableListOf<String>()
        render(
            InternetRelayUiState(enabled = false, relays = listOf(relay())),
            onSetRelayEnabled = { url, _ -> flipped += url },
        )
        compose.onNodeWithTag("relay_switch_public-1.spool.getknit.app").assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), flipped)
        // And it says why: the whole plane is off, which is a different statement from this one relay being.
        compose.onNodeWithText("Not in use").assertIsDisplayed()
    }

    @Test
    fun tappingARowOpensItsOwnRelayAndFlipsNothing() {
        // The row opens the relay's screen; only its switch parks it.
        val opened = mutableListOf<String>()
        val flipped = mutableListOf<String>()
        render(
            InternetRelayUiState(enabled = true, relays = listOf(relay(host = "one.example.org"), relay(host = "two.example.org"))),
            onOpenRelay = { opened += it },
            onSetRelayEnabled = { url, _ -> flipped += url },
        )
        compose.onNodeWithTag("relay_row_two.example.org").performClick()
        assertEquals(listOf("wss://two.example.org/spool/v1"), opened)
        assertEquals(emptyList<String>(), flipped)
    }

    @Test
    fun aRowsSwitchIsLabelledWithItsRelay() {
        render(InternetRelayUiState(enabled = true, relays = listOf(relay())))
        compose.onNodeWithContentDescription("Use public-1.spool.getknit.app").assertIsOn()
    }

    @Test
    fun theListLeavesCallsTicketsAndTheRoomToTheRelaysOwnScreen() {
        render(
            InternetRelayUiState(
                enabled = true,
                relays =
                    listOf(
                        relay(
                            rooms = listOf(RelayRoom(conversationId = "c-1", name = "Home")),
                            takesRooms = true,
                            calls = RelayCalls(turn = true, stunServers = 2, walkie = true, ticket = RelayTicket.None),
                            account = RelayAccount("issuer.example.org", KeyAnswer.OK, endsAt = null, failures = 0),
                        ),
                    ),
            ),
        )
        compose.onAllNodesWithTag("relay_calls_public-1.spool.getknit.app").assertCountEquals(0)
        compose.onAllNodesWithText("TURN", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("issuer.example.org", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Join").assertCountEquals(0)
        compose.onAllNodesWithTag("relay_invite_public-1.spool.getknit.app").assertCountEquals(0)
    }

    @Test
    fun aFirstInviteCarriesTheWholeDisclosureAndSaysWhatConfirmingDoes() {
        // The link is a bearer credential over an unauthenticated channel: the host is the headline, the
        // private relay and the room are named, and — the plane never having been consented to — the
        // master switch's own disclosure is the body, so confirming here is that same consent.
        var confirmed = false
        render(
            InternetRelayUiState(),
            invitePreview = preview(consentNeeded = true, planeOff = true, room = room(name = "Home")),
            onConfirmInvite = { confirmed = true },
        )
        compose.onNodeWithTag("relay_invite_host").assertIsDisplayed()
        compose.onNodeWithText("home.example.org").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_private").assertIsDisplayed()
        compose.onNodeWithText("Joins the room Home.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("A relay can see").assertIsDisplayed()
        compose.onNodeWithText("A relay cannot see").assertIsDisplayed()
        compose
            .onNodeWithTag("relay_invite_confirm")
            .performScrollTo()
            .assertTextEquals("Turn on and join")
            .performClick()
        assertTrue(confirmed)
    }

    @Test
    fun aLaterInviteStatesTheRelaysOwnCostInsteadOfTheDisclosure() {
        render(InternetRelayUiState(enabled = true), invitePreview = preview(private = false))
        compose.onNodeWithTag("relay_invite_sees").assertIsDisplayed()
        compose.onAllNodesWithText("A relay can see").assertCountEquals(0)
        compose.onAllNodesWithTag("relay_invite_private").assertCountEquals(0)
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("Add relay")
    }

    @Test
    fun aRepeatedInviteAnswersRatherThanRefusing() {
        // A re-tapped link converges: the sheet says the relay is already listed, states no cost (nothing
        // new is being handed anything), and the button is a plain OK rather than a disabled Add.
        var dismissed = false
        render(
            InternetRelayUiState(enabled = true),
            invitePreview = preview(alreadyAdded = true, room = room(alreadyJoined = true)),
            onDismissInvite = { dismissed = true },
        )
        compose.onNodeWithTag("relay_invite_already").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_room_joined").assertIsDisplayed()
        compose.onAllNodesWithTag("relay_invite_sees").assertCountEquals(0)
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("OK")
        compose.onNodeWithTag("relay_invite_decline").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun aSecondRoomsInviteSaysTheRoomsAlreadyJoinedStay() {
        render(
            InternetRelayUiState(enabled = true),
            invitePreview = preview(alreadyAdded = true, room = room(beside = true)),
        )
        compose.onNodeWithTag("relay_invite_room").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_room_beside").assertIsDisplayed()
        compose.onAllNodesWithText("leave this phone", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("Join room")
    }

    @Test
    fun aParkedRelaysInviteSaysItTurnsBackOn() {
        render(InternetRelayUiState(enabled = true), invitePreview = preview(alreadyAdded = true, parked = true))
        compose.onNodeWithTag("relay_invite_parked").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("Add relay")
    }

    @Test
    fun aTokenedInviteOverAnUntokenedEntrySaysItReplacesIt() {
        val old = "wss://home.example.org/spool/v1"
        render(InternetRelayUiState(enabled = true), invitePreview = preview(replaces = old))
        compose.onNodeWithTag("relay_invite_replaces").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_sees").assertIsDisplayed()
    }

    @Test
    fun anInviteWithATicketKeyNamesTheIssuerAndWhatThePhoneWillAskIt() {
        // ADR 2026-10.3sk7: the sheet names the issuer's host and the daily ask — never the key, a price or a product.
        render(InternetRelayUiState(enabled = true), invitePreview = preview(ticketKey = ticketKey()))
        compose.onNodeWithTag("relay_invite_key").assertIsDisplayed()
        compose
            .onNodeWithText("About once a day it will contact anywhere.example.org", substring = true)
            .assertIsDisplayed()
        compose.onAllNodesWithText(KEY, substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("https://", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("Add relay")
    }

    @Test
    fun aDifferentKeyForAListedRelaySaysItReplacesTheHeldOneAndOnlySavesIt() {
        render(
            InternetRelayUiState(enabled = true),
            invitePreview = preview(alreadyAdded = true, ticketKey = ticketKey(replacesKey = true)),
        )
        compose.onNodeWithTag("relay_invite_key_replaces").assertIsDisplayed()
        compose.onNodeWithText("replacing the one this phone holds", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("Save key")
    }

    @Test
    fun theSameKeyAgainIsANoOp() {
        render(
            InternetRelayUiState(enabled = true),
            invitePreview = preview(alreadyAdded = true, ticketKey = ticketKey(alreadyHeld = true)),
        )
        compose.onNodeWithTag("relay_invite_key_held").assertIsDisplayed()
        compose.onNodeWithTag("relay_invite_confirm").assertTextEquals("OK")
    }

    @Test
    fun theConsentSheetIsShownBeforeTheFirstEnable() {
        var accepted = false
        render(InternetRelayUiState(), showConsent = true, onAcceptConsent = { accepted = true })
        // The disclosure has to name the residual leak outright; a sheet that only reassured would be
        // the dishonest version of this screen.
        compose.onNodeWithText("A relay can see").assertIsDisplayed()
        compose.onNodeWithText("A relay cannot see").assertIsDisplayed()
        compose.onNodeWithTag("relays_consent_accept").performClick()
        assertTrue(accepted)
    }
}
