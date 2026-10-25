@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.relay

import app.getknit.knit.data.commons.CommonsEntity
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.relay.FreeUploads
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.spool.CommonsAdverts
import app.getknit.knit.mesh.spool.CommonsInvite
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.HeldKey
import app.getknit.knit.mesh.spool.KeyAnswer
import app.getknit.knit.mesh.spool.KeyState
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolCastInfo
import app.getknit.knit.mesh.spool.SpoolCommonsInfo
import app.getknit.knit.mesh.spool.SpoolSoftware
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.mesh.spool.SpoolTicket
import app.getknit.knit.mesh.spool.SpoolTicketJournal
import app.getknit.knit.mesh.spool.SpoolTicketState
import app.getknit.knit.mesh.spool.SpoolTicketsTest
import app.getknit.knit.mesh.spool.SpoolUploads
import app.getknit.knit.mesh.spool.StoredSpoolTicket
import app.getknit.knit.mesh.spool.TicketKeyJournal
import app.getknit.knit.net.InternetGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The relays screen's driver, the invite half: a link in the inbox is consumed and previewed onto the
 * sheet, junk is refused as an event, confirming applies and clears, and the row's share action mints
 * the stored URL — token and all — plus the room this device has joined there.
 */
class InternetRelayViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val relayStatus = mockk<RelayStatusRepository>(relaxed = true)
    private val commons = mockk<CommonsRepository>(relaxed = true)
    private val mesh = mockk<MeshController>(relaxed = true)
    private val applier = mockk<RelayInviteApplier>(relaxed = true)
    private val inbox = RelayInviteInbox()

    private val rooms = MutableStateFlow(emptyList<CommonsEntity>())
    private val secret = ByteArray(32) { (it * 3).toByte() }
    private val preview =
        RelayInviteApplier.Preview(
            url = TOKENED,
            host = "home.example.org",
            private = true,
            alreadyAdded = false,
            parked = false,
            replaces = null,
            planeOff = true,
            consentNeeded = true,
            room = null,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.spoolEnabled } returns flowOf(true)
        every { settings.spoolUrls } returns flowOf(setOf(TOKENED))
        every { settings.disabledSpoolUrls } returns flowOf(emptySet())
        every { relayStatus.statuses } returns flowOf(emptyList())
        every { commons.observeAll() } returns rooms
        coEvery { commons.find(any()) } answers { rooms.value.firstOrNull { it.conversationId == firstArg() }?.toRoom() }
        coEvery { applier.preview(any(), any()) } returns preview
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(gate: InternetGate? = null) =
        InternetRelayViewModel(settings, relayStatus, commons, mesh, inbox = inbox, applier = applier, gate = gate)

    /** A gate with a settable verdict; a relaxed mock's `online` would never emit and stall the combine. */
    private class FakeGate(
        online: Boolean,
    ) : InternetGate {
        override val online = MutableStateFlow(online)

        override fun isOnline() = online.value

        override fun isDataRestricted() = false
    }

    private fun TestScope.state(vm: InternetRelayViewModel): InternetRelayUiState {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }
        advanceUntilIdle()
        return vm.state.value
    }

    @Test
    fun aLinkInTheInboxIsPreviewedAndConfirmAppliesIt() =
        runTest {
            inbox.offer(RelayInvite.url(RelayInvite.mint(TOKENED)))
            val vm = vm()
            val events = collect(vm)
            advanceUntilIdle()

            assertNull(inbox.pending.value)
            assertSame(preview, vm.invitePreview.value)
            val parsed = slot<RelayInvite.Parsed.Invite>()
            coVerify { applier.preview(capture(parsed), null) }
            assertEquals(TOKENED, parsed.captured.url)

            vm.confirmInvite()
            advanceUntilIdle()
            coVerify { applier.apply(preview) }
            assertNull(vm.invitePreview.value)
            assertEquals(listOf<InternetRelayEvent>(InternetRelayEvent.InviteApplied("home.example.org")), events)
        }

    @Test
    fun aLinkArrivingWhileTheScreenIsUpStillRaisesTheSheet() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            assertNull(vm.invitePreview.value)
            inbox.offer(RelayInvite.schemeUrl(RelayInvite.mint(TOKENED)))
            advanceUntilIdle()
            assertSame(preview, vm.invitePreview.value)
            vm.dismissInvite()
            assertNull(vm.invitePreview.value)
            coVerify(exactly = 0) { applier.apply(any()) }
        }

    @Test
    fun junkIsRefusedAsAnEventAndNeverPreviewed() =
        runTest {
            val vm = vm()
            val events = collect(vm)
            vm.previewInvite("not a link")
            vm.previewInvite(RelayInvite.url("AAAA"))
            advanceUntilIdle()
            assertEquals(
                listOf<InternetRelayEvent>(
                    InternetRelayEvent.InviteRefused(RelayInvite.Reason.NOT_AN_INVITE),
                    InternetRelayEvent.InviteRefused(RelayInvite.Reason.MALFORMED),
                ),
                events,
            )
            assertNull(vm.invitePreview.value)
            coVerify(exactly = 0) { applier.preview(any(), any()) }
        }

    @Test
    fun sharingARowMintsItsStoredUrlAndTheRoomJoinedThere() =
        runTest {
            rooms.value = listOf(CommonsEntity(conversationId = "c-1", spoolUrl = TOKENED, secret = secret, name = "Home", joinedAt = 1L))
            val vm = vm()
            val events = collect(vm)
            vm.shareInvite(TOKENED)
            vm.copyInvite(OTHER)
            advanceUntilIdle()

            val shared = (events[0] as InternetRelayEvent.ShareInvite).url
            assertTrue(shared.startsWith(RelayInvite.URL_PREFIX))
            val full = RelayInvite.parse(shared, allowCleartext = false) as RelayInvite.Parsed.Invite
            assertEquals(TOKENED, full.url)
            assertArrayEquals(secret, full.secret)
            assertEquals("Home", full.name)

            val copied = (events[1] as InternetRelayEvent.CopyInvite).url
            val bare = RelayInvite.parse(copied, allowCleartext = false) as RelayInvite.Parsed.Invite
            assertEquals(OTHER, bare.url)
            assertNull(bare.secret)
            assertNull(bare.name)
        }

    @Test
    fun withTwoRoomsJoinedTheRelaysLinkCarriesTheRelayAloneAndEachRoomItsOwn() =
        runTest {
            val other = ByteArray(32) { (it * 5).toByte() }
            rooms.value =
                listOf(
                    CommonsEntity(conversationId = "c-1", spoolUrl = TOKENED, secret = secret, name = "Home", joinedAt = 1L),
                    CommonsEntity(conversationId = "c-2", spoolUrl = TOKENED, secret = other, name = "Team", joinedAt = 2L),
                )
            val vm = vm()
            val events = collect(vm)
            vm.shareInvite(TOKENED)
            vm.copyInvite(TOKENED, room = "c-2")
            advanceUntilIdle()

            // One link names one room, so the relay's own link carries none once two are joined there.
            val relay =
                RelayInvite.parse(
                    (events[0] as InternetRelayEvent.ShareInvite).url,
                    allowCleartext = false,
                ) as RelayInvite.Parsed.Invite
            assertEquals(TOKENED, relay.url)
            assertNull(relay.secret)
            val team =
                RelayInvite.parse(
                    (events[1] as InternetRelayEvent.CopyInvite).url,
                    allowCleartext = false,
                ) as RelayInvite.Parsed.Invite
            assertArrayEquals(other, team.secret)
            assertEquals("Team", team.name)
        }

    @Test
    fun eachRoomJoinedAtARelayIsOnItsRowWithWhatTheRelaySaysOfIt() =
        runTest {
            val other = ByteArray(32) { (it * 5).toByte() }
            rooms.value =
                listOf(
                    CommonsEntity(conversationId = "c-1", spoolUrl = TOKENED, secret = secret, name = "Home", joinedAt = 1L),
                    CommonsEntity(conversationId = "c-2", spoolUrl = TOKENED, secret = other, name = null, joinedAt = 2L),
                    CommonsEntity(conversationId = "c-3", spoolUrl = OTHER, secret = ByteArray(32), name = "Elsewhere", joinedAt = 3L),
                )
            every { settings.spoolUrls } returns flowOf(setOf(TOKENED, OTHER))
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(
                        SpoolStatus(
                            url = TOKENED,
                            connected = true,
                            powBits = 0,
                            lastError = null,
                            scopes = emptyList(),
                            adverts = CommonsAdverts.of(HOME_ADVERT, rooms = null),
                            staleCommons = setOf("c-2"),
                        ),
                    ),
                )

            val rows = state(vm()).relays.associateBy { it.url }
            val home = rows.getValue(TOKENED)
            assertEquals(listOf(RelayRoom("c-1", "Home"), RelayRoom("c-2", null, stale = true)), home.rooms)
            assertTrue(home.takesRooms)
            // A room whose relay is down still shows, so its Leave stays reachable; nothing new is offered there.
            val down = rows.getValue(OTHER)
            assertEquals(listOf(RelayRoom("c-3", "Elsewhere")), down.rooms)
            assertFalse(down.takesRooms)
        }

    @Test
    fun aRelaysLiveStatusIsMappedOntoItsRow() =
        runTest {
            // The dead-route shape of work item 50: not connected, the dialer's `unreachable` verdict
            // standing, and nothing claimed about scopes or photos — a row must say exactly that, and a
            // parked relay must read as parked whatever its worker last reported.
            every { settings.spoolUrls } returns flowOf(setOf(TOKENED, OTHER))
            every { settings.disabledSpoolUrls } returns flowOf(setOf(OTHER))
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(
                        SpoolStatus(
                            url = TOKENED,
                            connected = false,
                            powBits = 0,
                            lastError = ScopeSync.UNREACHABLE,
                            scopes = emptyList(),
                            dialFailures = 4,
                        ),
                        SpoolStatus(url = OTHER, connected = true, powBits = 0, lastError = null, scopes = emptyList(), maxAttachBytes = 1),
                    ),
                )

            val rows = state(vm()).relays.associateBy { it.url }
            val dead = rows.getValue(TOKENED)
            assertFalse(dead.connected)
            assertEquals(ScopeSync.UNREACHABLE, dead.lastError)
            assertNull(dead.scopeCount)
            assertNull(dead.carriesPhotos)
            assertTrue(dead.enabled)
            val parked = rows.getValue(OTHER)
            assertFalse(parked.enabled)
            assertTrue(parked.connected)
            assertEquals(true, parked.carriesPhotos)
        }

    @Test
    fun aRelaysOwnScreenFactsComeFromTheLiveConnectionOnly() =
        runTest {
            // A status that is down keeps nothing it last heard: its size, software and stamp are a connection's facts.
            every { settings.spoolUrls } returns flowOf(setOf(TOKENED, OTHER))
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(
                        SpoolStatus(
                            url = TOKENED,
                            connected = true,
                            powBits = 12,
                            lastError = null,
                            scopes = emptyList(),
                            maxAttachBytes = 26_214_400,
                            software = SpoolSoftware(name = "knit-spool", version = "0.9.0"),
                        ),
                        SpoolStatus(
                            url = OTHER,
                            connected = false,
                            powBits = 12,
                            lastError = ScopeSync.UNREACHABLE,
                            scopes = emptyList(),
                            maxAttachBytes = 26_214_400,
                            software = SpoolSoftware(name = "knit-spool", version = "0.9.0"),
                        ),
                    ),
                )

            val rows = state(vm()).relays.associateBy { it.url }
            val live = rows.getValue(TOKENED)
            assertEquals(26_214_400, live.maxAttachBytes)
            assertEquals("knit-spool 0.9.0", live.software)
            assertEquals(12, live.powBits)
            assertEquals(RelayUploads.Open(26_214_400L), live.uploads)
            val down = rows.getValue(OTHER)
            assertNull(down.maxAttachBytes)
            assertNull(down.software)
            assertNull(down.powBits)
            assertNull(down.uploads)
        }

    @Test
    fun theStateSaysLoadedOnlyOnceTheStoresAnswered() =
        runTest {
            // A relay's own screen leaves when its row is missing, so "not read yet" must not read as "removed": with
            // one store still silent, a collected state stays unloaded, and it loads on that store's first answer.
            val statuses = MutableSharedFlow<List<SpoolStatus>>()
            every { relayStatus.statuses } returns statuses
            val vm = vm()
            val before = state(vm)
            assertFalse(before.loaded)
            assertEquals(emptyList<RelayRow>(), before.relays)
            statuses.emit(emptyList())
            advanceUntilIdle()
            assertTrue(vm.state.value.loaded)
        }

    @Test
    fun thePhonesOwnRouteReachesTheStateAndNoRow() =
        runTest {
            assertFalse("no gate reads as online", state(vm()).offline)
            assertTrue(state(vm(FakeGate(online = false))).offline)
            val gate = FakeGate(online = true)
            val vm = vm(gate)
            assertFalse(state(vm).offline)
            gate.online.value = false
            advanceUntilIdle()
            assertTrue(vm.state.value.offline)
        }

    @Test
    fun removingARelayForgetsItsKeyAndTicketUnlessAnotherRowSharesTheHost() =
        runTest {
            // C-7.6-29 (ADR 2026-10.3sk7): the key, its schedule and the held ticket go with the relay.
            val keys = mockk<TicketKeyJournal>(relaxed = true)
            every { settings.spoolUrls } returns flowOf(emptySet())
            InternetRelayViewModel(settings, relayStatus, commons, mesh, ticketKeys = keys).removeRelay(TOKENED)
            advanceUntilIdle()
            coVerify { keys.forgetHost("home.example.org") }

            val shared = mockk<TicketKeyJournal>(relaxed = true)
            every { settings.spoolUrls } returns flowOf(setOf("wss://home.example.org:9470/spool/v1"))
            InternetRelayViewModel(settings, relayStatus, commons, mesh, ticketKeys = shared).removeRelay(TOKENED)
            advanceUntilIdle()
            coVerify(exactly = 0) { shared.forgetHost(any()) }
        }

    @Test
    fun aRelaysCallFeaturesAndTicketKeyReachItsRowOnlyWhereCallsAreWired() =
        runTest {
            // The lab relay's shape: TURN, casts and STUN behind tickets, a ticket accepted for TURN and casts, and a
            // key held for its host. The plain relay offers calls nothing, so its row shows nothing about them.
            every { settings.spoolUrls } returns flowOf(setOf(LAB, OTHER))
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(
                        SpoolStatus(
                            url = LAB,
                            connected = true,
                            powBits = 0,
                            lastError = null,
                            scopes = emptyList(),
                            ent = true,
                            stun = listOf("stun:stun.lab.lax.spool.getknit.app:3478"),
                            ticket = SpoolTicketState.Accepted(exp = 1_800_086_400L, fl = SpoolTicket.FL_TURN or SpoolTicket.FL_CAST),
                            turn = true,
                            cast = SpoolCastInfo(maxBytes = 2_048, rate = 4),
                        ),
                        SpoolStatus(url = OTHER, connected = true, powBits = 0, lastError = null, scopes = emptyList()),
                    ),
                )
            val tickets = mockk<SpoolTicketJournal>()
            every { tickets.spoolTickets } returns flowOf(emptyMap())
            val keys = mockk<TicketKeyJournal>()
            every { keys.keys } returns flowOf(mapOf(LAB_HOST to HeldKey("https://issuer.example.org", KEY)))
            every { keys.keyStates } returns flowOf(mapOf(LAB_HOST to KeyState(KeyAnswer.OK, endsAt = 1_801_000_000_000L)))

            val wired = InternetRelayViewModel(settings, relayStatus, commons, mesh, ticketKeys = keys, spoolTickets = tickets)
            val rows = state(wired).relays.associateBy { it.url }
            assertEquals(
                RelayCalls(
                    turn = true,
                    stunServers = 1,
                    walkie = true,
                    ticket = RelayTicket.Live(1_800_086_400L, turn = true, walkie = true),
                ),
                rows.getValue(LAB).calls,
            )
            assertEquals(RelayAccount("issuer.example.org", KeyAnswer.OK, 1_801_000_000_000L, failures = 0), rows.getValue(LAB).account)
            assertNull(rows.getValue(OTHER).calls)
            assertNull(rows.getValue(OTHER).account)

            // A build without calls hands the screen no ticket journal: no row says anything about them, key or not.
            val dark = InternetRelayViewModel(settings, relayStatus, commons, mesh, ticketKeys = keys)
            state(dark).relays.forEach {
                assertNull(it.calls)
                assertNull(it.account)
            }
        }

    @Test
    fun aDisconnectedRelayShowsItsKeyButNoHelloFacts() =
        runTest {
            every { settings.spoolUrls } returns flowOf(setOf(LAB))
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(SpoolStatus(url = LAB, connected = false, powBits = 0, lastError = ScopeSync.UNREACHABLE, scopes = emptyList())),
                )
            val tickets = mockk<SpoolTicketJournal>()
            every { tickets.spoolTickets } returns flowOf(emptyMap())
            val keys = mockk<TicketKeyJournal>()
            every { keys.keys } returns flowOf(mapOf(LAB_HOST to HeldKey("https://issuer.example.org", KEY)))
            every { keys.keyStates } returns flowOf(emptyMap())

            val row =
                state(
                    InternetRelayViewModel(settings, relayStatus, commons, mesh, ticketKeys = keys, spoolTickets = tickets),
                ).relays.single()
            assertNull(row.calls)
            assertEquals(RelayAccount("issuer.example.org", answer = null, endsAt = null, failures = 0), row.account)
        }

    @Test
    fun aTicketLineExplainsAConnectionWithNothingPresentedFromTheStore() {
        val now = 1_800_000_000_000L
        val held = SpoolTicketsTest.ticket(exp = 1_800_086_400L).text
        // The connection's own answer wins over whatever the store says.
        assertEquals(
            RelayTicket.Live(1_800_086_400L, turn = true, walkie = false),
            relayTicket(SpoolTicketState.Accepted(1_800_086_400L, SpoolTicket.FL_TURN), StoredSpoolTicket(held, "signature"), now),
        )
        assertEquals(RelayTicket.Presenting, relayTicket(SpoolTicketState.Pending, null, now))
        assertEquals(RelayTicket.Refused("audience"), relayTicket(SpoolTicketState.Refused("audience"), null, now))
        // Nothing presented here: no ticket, a refusal that keeps it home, one past its exp, or one about to go.
        assertEquals(RelayTicket.None, relayTicket(SpoolTicketState.None, null, now))
        assertEquals(RelayTicket.Refused("signature"), relayTicket(SpoolTicketState.None, StoredSpoolTicket(held, "signature"), now))
        assertEquals(
            RelayTicket.Expired(1_800_086_400L),
            relayTicket(SpoolTicketState.None, StoredSpoolTicket(held, null), 1_800_090_000_000L),
        )
        assertEquals(RelayTicket.Presenting, relayTicket(SpoolTicketState.None, StoredSpoolTicket(held, "retry:early"), now))
    }

    @Test
    fun aFilesTicketListsFiles() {
        val now = 1_800_000_000_000L
        assertEquals(
            RelayTicket.Live(1_800_086_400L, turn = true, walkie = true, files = true),
            relayTicket(SpoolTicketState.Accepted(1_800_086_400L, 11L), null, now),
        )
    }

    @Test
    fun ourUploadsAreReadApartFromWhatTheRelayCarries() {
        // C-7.6-24: a relay that carries photos can still refuse this phone's own without a files ticket, and the
        // ticket's `am` caps them below the relay's limit.
        fun status(
            uploads: SpoolUploads,
            maxAttachBytes: Int? = 26_214_400,
            am: Long? = null,
        ) = SpoolStatus(
            url = OTHER,
            connected = true,
            powBits = 0,
            lastError = null,
            scopes = emptyList(),
            maxAttachBytes = maxAttachBytes,
            ent = true,
            entGates = 11L,
            ticket = SpoolTicketState.Accepted(1_800_086_400L, 11L, am = am),
            uploads = uploads,
        )
        assertEquals(RelayUploads.Open(26_214_400L), relayUploads(status(SpoolUploads.Open)))
        assertEquals(RelayUploads.Open(2_000_000L), relayUploads(status(SpoolUploads.Open, am = 2_000_000L)))
        assertEquals(RelayUploads.Open(26_214_400L), relayUploads(status(SpoolUploads.Open, am = 90_000_000L)))
        assertEquals(RelayUploads.Locked(pending = false), relayUploads(status(SpoolUploads.Locked(SpoolUploads.MISSING))))
        assertEquals(RelayUploads.Locked(pending = true), relayUploads(status(SpoolUploads.Locked(SpoolUploads.PENDING))))
        assertEquals(RelayUploads.Spent, relayUploads(status(SpoolUploads.Spent)))
        // A relay that carries no attachments has no uploads to speak of.
        assertNull(relayUploads(status(SpoolUploads.Open, maxAttachBytes = null)))
    }

    @Test
    fun aRelayThatTakesNoTicketsHasNoTicketLine() {
        val plain = SpoolStatus(url = OTHER, connected = true, powBits = 0, lastError = null, scopes = emptyList(), turn = true)
        assertEquals(RelayCalls(turn = true, stunServers = 0, walkie = false, ticket = null), relayCalls(plain, null, 0L))
        val ticketed = SpoolStatus(url = OTHER, connected = true, powBits = 0, lastError = null, scopes = emptyList(), ent = true)
        assertEquals(RelayTicket.None, relayCalls(ticketed, null, 0L)?.ticket)
    }

    @Test
    fun turningThePlaneOffWritesAtOnceWithoutTheSheet() =
        runTest {
            every { settings.spoolConsented } returns flowOf(false)
            val vm = vm()

            vm.onToggle(false)
            advanceUntilIdle()

            coVerify { settings.setSpoolEnabled(false) }
            assertFalse(vm.showConsent.value)
        }

    @Test
    fun turningThePlaneOnBeforeConsentRaisesTheSheetAndWritesNothing() =
        runTest {
            // ADR 063: the disclosure comes before the first socket, so the switch alone never enables the plane.
            every { settings.spoolConsented } returns flowOf(false)
            val vm = vm()

            vm.onToggle(true)
            advanceUntilIdle()

            assertTrue(vm.showConsent.value)
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
            coVerify(exactly = 0) { settings.acceptSpoolConsent() }
        }

    @Test
    fun acceptingTheSheetIsTheOneConsentWriteAndClosesIt() =
        runTest {
            // `acceptSpoolConsent` records the consent and enables the plane in one write; the sheet adds no second.
            every { settings.spoolConsented } returns flowOf(false)
            val vm = vm()
            vm.onToggle(true)
            advanceUntilIdle()

            vm.acceptConsent()
            advanceUntilIdle()

            coVerify(exactly = 1) { settings.acceptSpoolConsent() }
            assertFalse(vm.showConsent.value)
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
        }

    @Test
    fun dismissingTheSheetLeavesThePlaneOffAndUnconsented() =
        runTest {
            every { settings.spoolConsented } returns flowOf(false)
            val vm = vm()
            vm.onToggle(true)
            advanceUntilIdle()
            assertTrue(vm.showConsent.value)

            vm.dismissConsent()
            advanceUntilIdle()

            assertFalse(vm.showConsent.value)
            coVerify(exactly = 0) { settings.acceptSpoolConsent() }
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
        }

    @Test
    fun turningThePlaneOnOnceConsentedEnablesItWithoutTheSheet() =
        runTest {
            every { settings.spoolConsented } returns flowOf(true)
            val vm = vm()

            vm.onToggle(true)
            advanceUntilIdle()

            coVerify { settings.setSpoolEnabled(true) }
            assertFalse(vm.showConsent.value)
        }

    @Test
    fun anAddedRelayIsStoredInItsCanonicalFormAndDialedNow() =
        runTest {
            val vm = vm()

            vm.addRelay("  WSS://Home.Example.org/spool/v1  ")
            advanceUntilIdle()

            // The scheme lowercased and the padding gone, nothing else: the host and path are the relay's own.
            coVerify { settings.addSpoolUrl("wss://Home.Example.org/spool/v1") }
            coVerify { mesh.refreshRelays() }
        }

    @Test
    fun aUrlTheDialerWouldRefuseIsNeitherValidNorStored() =
        runTest {
            val vm = vm()

            assertFalse(vm.isValidUrl("https://home.example.org/spool/v1"))
            vm.addRelay("https://home.example.org/spool/v1")
            vm.addRelay("home.example.org")
            advanceUntilIdle()

            assertTrue(vm.isValidUrl(" wss://home.example.org/spool/v1 "))
            coVerify(exactly = 0) { settings.addSpoolUrl(any()) }
            coVerify(exactly = 0) { mesh.refreshRelays() }
        }

    @Test
    fun aCommonsInviteJoinsTheRoomAtThatRelayAndDialsNow() =
        runTest {
            val vm = vm()
            val invite = CommonsInvite.encode(secret)
            assertTrue(vm.isValidInvite(invite))

            vm.joinCommons(TOKENED, invite)
            advanceUntilIdle()

            val joined = slot<ByteArray>()
            coVerify { commons.join(TOKENED, capture(joined), null, any()) }
            assertArrayEquals(secret, joined.captured)
            coVerify { mesh.refreshRelays() }
        }

    @Test
    fun aSecondRoomsInviteJoinsBesideTheFirstUnderTheNameItsRelayListsForIt() =
        runTest {
            val other = ByteArray(32) { (it * 5).toByte() }
            rooms.value = listOf(CommonsEntity(conversationId = "c-1", spoolUrl = TOKENED, secret = secret, name = "Home", joinedAt = 1L))
            val tag = { s: ByteArray -> ScopeCrypto.commonsTag(ScopeCrypto.commonsScopeId(s)) }
            every { relayStatus.statuses } returns
                flowOf(
                    listOf(
                        SpoolStatus(
                            url = TOKENED,
                            connected = true,
                            powBits = 0,
                            lastError = null,
                            scopes = emptyList(),
                            adverts = CommonsAdverts.of(HOME_ADVERT, listOf(tag(secret) to HOME_ADVERT, tag(other) to TEAM_ADVERT)),
                        ),
                    ),
                )
            val vm = vm()

            vm.joinCommons(TOKENED, CommonsInvite.encode(other))
            advanceUntilIdle()

            coVerify { commons.join(TOKENED, other, "Team", any()) }
            coVerify(exactly = 0) { commons.leave(any()) }
            coVerify(exactly = 0) { commons.leaveBoundTo(any()) }
        }

    @Test
    fun anInviteToARoomAlreadyJoinedThereRewritesNothing() =
        runTest {
            rooms.value =
                listOf(
                    CommonsEntity(
                        conversationId = conversationIdFor(secret),
                        spoolUrl = TOKENED,
                        secret = secret,
                        name = "Home",
                        joinedAt = 1L,
                    ),
                )
            val vm = vm()

            vm.joinCommons(TOKENED, CommonsInvite.encode(secret))
            advanceUntilIdle()

            // `join` upserts the whole row: a second paste would reset the room's name and join time.
            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
            coVerify { mesh.refreshRelays() }
        }

    @Test
    fun textThatIsNoCommonsInviteJoinsNothing() =
        runTest {
            val vm = vm()

            assertFalse(vm.isValidInvite("knit-commons:v1:not-a-secret"))
            vm.joinCommons(TOKENED, "knit-commons:v1:not-a-secret")
            advanceUntilIdle()

            coVerify(exactly = 0) { commons.join(any(), any(), any(), any()) }
        }

    @Test
    fun leavingACommonsDropsTheRoomAndDialsNow() =
        runTest {
            val vm = vm()

            vm.leaveCommons("commons:abc")
            advanceUntilIdle()

            coVerify { commons.leave("commons:abc") }
            coVerify { mesh.refreshRelays() }
        }

    @Test
    fun parkingOneRelayWritesItsOwnSwitchAndNotThePlanes() =
        runTest {
            val vm = vm()

            vm.setRelayEnabled(TOKENED, false)
            advanceUntilIdle()

            coVerify { settings.setSpoolUrlEnabled(TOKENED, false) }
            coVerify(exactly = 0) { settings.setSpoolEnabled(any()) }
        }

    @Test
    fun theFreeUploadsSwitchRecordsThePersonsAnswerForThatHost() =
        runTest {
            val uploads = mockk<FreeUploads>(relaxed = true) { every { choices } returns flowOf(emptyMap()) }
            val vm =
                InternetRelayViewModel(settings, relayStatus, commons, mesh, inbox = inbox, applier = applier, freeUploads = uploads)

            vm.setFreeUploads("home.example.org", allowed = true)
            advanceUntilIdle()

            coVerify { uploads.answer("home.example.org", true) }
        }

    private fun TestScope.collect(vm: InternetRelayViewModel): List<InternetRelayEvent> {
        val events = mutableListOf<InternetRelayEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        return events
    }

    private fun CommonsEntity.toRoom() = CommonsRoom(conversationId, spoolUrl, secret, name)

    private fun conversationIdFor(secret: ByteArray) = CommonsRepository.conversationIdFor(secret)

    private companion object {
        val HOME_ADVERT = SpoolCommonsInfo("Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536)
        val TEAM_ADVERT = SpoolCommonsInfo("Team", maxFrames = 200, ttlMs = 86_400_000L, maxBlob = 65_536, attach = true)

        const val TOKENED = "wss://home.example.org/spool/v1?k=t0ken"
        const val OTHER = "wss://lax.spool.getknit.app/spool/v1"
        const val LAB_HOST = "lab.lax.spool.getknit.app"
        const val LAB = "wss://$LAB_HOST/spool/v1?k=t0ken"

        /** A customer key's shape; never shown, only its issuer is. */
        const val KEY = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
    }
}
