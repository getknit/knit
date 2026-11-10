@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.diagnostics

import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.HiddenKnownRows
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.LinkSecurity
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PRESENCE_LINGER_MS
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
import app.getknit.knit.mesh.RelayedHeard
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.TransportStatus
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.spool.ScopeStatus
import app.getknit.knit.mesh.spool.SpoolStatus
import app.getknit.knit.moderation.ModelLoadGuard
import app.getknit.knit.moderation.ModelLoadPolicy
import app.getknit.knit.ui.Reach
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Demonstrates finding #15's payoff: with the mesh behind [MeshController], a ViewModel is now testable
 * against the shared [FakeMeshController] fixture instead of the concrete, un-constructable `MeshManager`.
 * Verifies the Diagnostics actions route to the controller.
 */
class DiagnosticsViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A guard over an empty journal — nothing latched, nothing to reset. */
    private fun unlatchedGuard(journal: ModelLoadJournal = EmptyJournal()) = ModelLoadGuard(journal, { null }, STAMP)

    private open class EmptyJournal : ModelLoadJournal {
        val cleared = mutableListOf<String>()

        override fun observeModelLoad(model: String): Flow<ModelLoadState> = MutableStateFlow(ModelLoadState.NONE)

        override suspend fun modelLoadState(model: String) = ModelLoadState.NONE

        override suspend fun setModelLoadState(
            model: String,
            state: ModelLoadState,
        ) {
            cleared += model
        }
    }

    @Test
    fun `a latched model surfaces, and resetting it clears every model and reports back`() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            val journal =
                object : EmptyJournal() {
                    override fun observeModelLoad(model: String): Flow<ModelLoadState> =
                        MutableStateFlow(
                            if (model == ModelLoadGuard.TOXICITY) {
                                ModelLoadState(STAMP, 0L, ModelLoadPolicy.MAX_FAILS)
                            } else {
                                ModelLoadState.NONE
                            },
                        )
                }
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(journal),
                    radios = RadioSupport.ALL,
                    loraFacts = MutableStateFlow(LoraFacts()),
                )

            val seen = mutableListOf<Int>()
            val events = backgroundScope.launch { vm.events.collect { seen += it } }
            val latch = backgroundScope.launch { vm.moderationLatched.collect { } }
            // WhileSubscribed: the StateFlow only starts sharing once a collector is actually running.
            runCurrent()

            assertTrue(vm.moderationLatched.value)

            vm.resetModerationLatch()
            runCurrent()

            // Both models, not just the latched one: the row is a single "reduced" state, so the reset
            // has to match it or the user could clear the row and still be latched.
            assertEquals(ModelLoadGuard.ALL, journal.cleared)
            assertEquals(listOf(R.string.diagnostics_moderation_reset_done), seen)
            events.cancel()
            latch.cancel()
        }

    @Test
    fun rescanAndRestartRouteToTheController() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(),
                    radios = RadioSupport.ALL,
                    loraFacts = MutableStateFlow(LoraFacts()),
                )

            vm.rescan()
            vm.restartMesh()

            assertEquals(1, controller.healCount)
            assertEquals(1, controller.restartCount)
        }

    /** ADR 2026-09.m8kc: "Try again" on a held Wi-Fi Aware initiator goes straight to the controller, with feedback. */
    @Test
    fun releasingTheInitiatorHoldRoutesToTheControllerAndSaysSo() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(),
                    radios = RadioSupport.ALL,
                    loraFacts = MutableStateFlow(LoraFacts()),
                )
            val seen = mutableListOf<Int>()
            val events = backgroundScope.launch { vm.events.collect { seen += it } }
            runCurrent() // the collector has to be running before the one-shot emit

            vm.releaseInitiatorHold()
            runCurrent()

            assertEquals(1, controller.releaseInitiatorHoldCount)
            assertEquals(listOf(R.string.diagnostics_nan_hold_retry_done), seen)
            events.cancel()
        }

    /** The debug build's Bluetooth link limit reads and writes the one store key the transport collects. */
    @Test
    fun theBluetoothLinkLimitReadsTheStoreAndWritesThrough() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            val stored = MutableStateFlow<Int?>(2)
            every { settings.debugBleLinkCap } returns stored
            coEvery { settings.setDebugBleLinkCap(any()) } answers {
                stored.value = firstArg()
                mockk(relaxed = true)
            }
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = mockk(relaxed = true),
                    modelGuard = unlatchedGuard(),
                    radios = RadioSupport.ALL,
                    loraFacts = MutableStateFlow(LoraFacts()),
                )
            val cap = backgroundScope.launch { vm.bleLinkCap.collect { } }
            runCurrent()

            // Unit tests build the debug variant, where the row is offered.
            assertTrue(vm.bleLinkCapOffered)
            assertEquals(2, vm.bleLinkCap.value)

            vm.setBleLinkCap(1)
            runCurrent()

            coVerify { settings.setDebugBleLinkCap(1) }
            assertEquals(1, vm.bleLinkCap.value)
            cap.cancel()
        }

    @Test
    fun lastCrashSurfacesTheNewestReportAndClearsAfterADelete() =
        runTest {
            val controller = FakeMeshController()
            val settings = mockk<SettingsStore>(relaxed = true)
            every { settings.spoolEnabled } returns MutableStateFlow(false)
            every { settings.spoolUrls } returns MutableStateFlow(emptySet())
            every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            val ref =
                CrashReportRef(
                    at = 1_700_000_000_000L,
                    summary = "IllegalStateException at MeshRouter.kt:91",
                    appVersion = "2.3.0 (13) debug",
                    device = "Google Pixel 8 (shiba)",
                    androidVersion = "16 (SDK 36)",
                    file = File("crash-1700000000000-deadbeef.txt"),
                )
            val crashes = mockk<CrashReports>(relaxed = true)
            coEvery { crashes.latest() } returns ref
            val vm =
                DiagnosticsViewModel(
                    peers = mockk(relaxed = true),
                    meshManager = controller,
                    identity = mockk(relaxed = true),
                    settings = settings,
                    metrics = MeshMetrics(),
                    relayStatus = RelayStatusRepository(settings, controller),
                    crashes = crashes,
                    modelGuard = unlatchedGuard(),
                    radios = RadioSupport.ALL,
                    loraFacts = MutableStateFlow(LoraFacts()),
                )

            assertEquals(ref, vm.lastCrash.value)

            coEvery { crashes.latest() } returns null
            vm.refreshLastCrash()

            assertNull(vm.lastCrash.value)
        }

    // --- reach classification (the Diagnostics screen's four sections) ---

    private fun peer(
        nodeId: String,
        name: String,
        updatedAt: Long,
    ) = PeerEntity(nodeId = nodeId, name = name, updatedAt = updatedAt)

    private fun reachVm(
        controller: FakeMeshController,
        peers: List<PeerEntity>,
        clock: () -> Long = { NOW },
        radios: RadioSupport = RadioSupport.ALL,
        loraFacts: Flow<LoraFacts> = MutableStateFlow(LoraFacts()),
        hidden: MutableStateFlow<Map<String, Long>>? = null,
        directory: MutableStateFlow<PeerDirectory>? = null,
        keyHolders: Flow<Set<String>> = MutableStateFlow(emptySet()),
        directContacts: Flow<Set<String>>? = null,
    ): DiagnosticsViewModel {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.spoolEnabled } returns MutableStateFlow(false)
        every { settings.spoolUrls } returns MutableStateFlow(emptySet())
        every { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
        every { settings.displayName } returns MutableStateFlow("Walter")
        // A relaxed PeerRepository would hand back a flow that never emits, and the state combine would
        // simply never produce a value — a stall that looks like a wrong assertion.
        val repo = mockk<PeerRepository>()
        val labels = PeerLabels.index(peers.map { it.nodeId to it.name }, SELF to "Walter")
        every { repo.observeDirectory() } returns (directory ?: MutableStateFlow(PeerDirectory(peers, labels)))
        val identity = mockk<Identity>(relaxed = true)
        coEvery { identity.nodeId() } returns SELF
        return DiagnosticsViewModel(
            peers = repo,
            meshManager = controller,
            identity = identity,
            settings = settings,
            metrics = MeshMetrics(),
            relayStatus = RelayStatusRepository(settings, controller),
            crashes = mockk(relaxed = true),
            modelGuard = unlatchedGuard(),
            radios = radios,
            loraFacts = loraFacts,
            clock = clock,
            hiddenKnown = hidden?.let(::FakeHiddenKnown),
            presenceKeyHolders = keyHolders,
            directContacts = directContacts,
            advertDebug = null,
        )
    }

    /** The hidden-row store as DataStore runs it: one transform applied to what is stored. */
    private class FakeHiddenKnown(
        override val hidden: MutableStateFlow<Map<String, Long>>,
    ) : HiddenKnownRows {
        override suspend fun edit(transform: (Map<String, Long>) -> Map<String, Long>) {
            hidden.value = transform(hidden.value)
        }
    }

    private fun directoryOf(peers: List<PeerEntity>) =
        PeerDirectory(peers, PeerLabels.index(peers.map { it.nodeId to it.name }, SELF to "Walter"))

    /**
     * ADR 2026-11.8mhs: a row's chip is the weakest live link its section claims — the short-range links on a direct row,
     * a direct Internet link on a long-range one — and no live link is no chip, never "plain".
     */
    @Test
    fun aRowShowsTheWeakestLinkItsSectionClaimsAndNoneWithoutALink() =
        runTest {
            val controller = FakeMeshController()
            val ada = peer("ada", "Ada", 900L)
            val grace = peer("grace", "Grace", 800L)
            val edsger = peer("edsger", "Edsger", 700L)
            val frances = peer("frances", "Frances", 600L)
            val radia = peer("radia", "Radia", 500L)
            controller.neighbors.value = setOf(Peer("ada"), Peer("grace"), Peer("edsger"))
            controller.reachable.value = controller.neighbors.value + setOf(Peer("frances"), Peer("radia"))
            controller.linkSecurity.value =
                mapOf(
                    // One plain link beside a sealed one: the row must not read safer than its weakest link.
                    "ada" to mapOf(TransportKind.Bluetooth to LinkSecurity.SEALED, TransportKind.WifiAware to LinkSecurity.LEGACY),
                    "grace" to mapOf(TransportKind.Bluetooth to LinkSecurity.SEALED),
                    // A direct row never claims a direct Internet link, which is no radio.
                    "edsger" to mapOf(TransportKind.DirectLink to LinkSecurity.SEALED),
                    "frances" to mapOf(TransportKind.DirectLink to LinkSecurity.SEALED),
                )
            val vm = reachVm(controller, listOf(ada, grace, edsger, frances, radia))
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            val state = vm.state.value
            val direct = state.directNodes.associate { it.nodeId to it.link }
            assertEquals(LinkSecurity.LEGACY, direct["ada"])
            assertEquals(LinkSecurity.SEALED, direct["grace"])
            assertNull("no short-range link is no chip, never a plain one", direct["edsger"])
            val longRange = state.longRangeNodes.associate { it.nodeId to it.link }
            assertEquals(LinkSecurity.SEALED, longRange["frances"])
            assertNull(longRange["radia"])
            job.cancel()
        }

    /** The narrowing alone: an indirect or merely known row claims no link, whatever the peer holds. */
    @Test
    fun indirectAndKnownRowsClaimNoLink() {
        val links = mapOf(TransportKind.Bluetooth to LinkSecurity.SEALED, TransportKind.DirectLink to LinkSecurity.PROVEN)
        val short = setOf(TransportKind.Bluetooth, TransportKind.WifiAware)
        assertNull(DiagnosticsViewModel.rowLinkSecurity(Reach.Indirect, links, short))
        assertNull(DiagnosticsViewModel.rowLinkSecurity(Reach.Known, links, short))
        assertEquals(LinkSecurity.SEALED, DiagnosticsViewModel.rowLinkSecurity(Reach.Direct, links, short))
        assertEquals(LinkSecurity.PROVEN, DiagnosticsViewModel.rowLinkSecurity(Reach.LongRange, links, short))
        assertNull(DiagnosticsViewModel.rowLinkSecurity(Reach.Direct, emptyMap(), short))
    }

    /**
     * ADR 2026-11.xp6d: the Key chip and the "held" count read only direct contacts' keys (a stored key of anyone else is
     * never listed), and the own-key line follows the controller.
     */
    @Test
    fun presenceCountsOnlyDirectContactsKeys() =
        runTest {
            val controller = FakeMeshController()
            val holders = MutableStateFlow(setOf("ada", "grace", "stranger"))
            val contacts = MutableStateFlow(setOf("ada", "grace", "edsger"))
            val vm = reachVm(controller, emptyList(), keyHolders = holders, directContacts = contacts)
            val job = backgroundScope.launch { vm.presence.collect { } }
            runCurrent()

            assertEquals(PresenceKeyInfo(ownKeyHeld = false, keyHolders = setOf("ada", "grace"), directContacts = 3), vm.presence.value)

            controller.ownPresenceKeyHeld.value = true
            contacts.value = setOf("ada")
            runCurrent()
            assertEquals(PresenceKeyInfo(ownKeyHeld = true, keyHolders = setOf("ada"), directContacts = 1), vm.presence.value)
            job.cancel()
        }

    /** Without a direct-contact source there is no "of M", and every stored key's holder is shown as is. */
    @Test
    fun presenceWithoutAContactSourceHasNoTotal() =
        runTest {
            val controller = FakeMeshController()
            val vm = reachVm(controller, emptyList(), keyHolders = MutableStateFlow(setOf("ada")))
            val job = backgroundScope.launch { vm.presence.collect { } }
            runCurrent()

            assertNull(vm.presence.value.directContacts)
            assertEquals(setOf("ada"), vm.presence.value.keyHolders)
            job.cancel()
        }

    /**
     * The field report this fixes: on a mesh with two boards, every peer whose frames a gateway put on air
     * was listed as *directly connected* over LoRa — including a phone with no board and one that had been
     * switched off for days — while the peer that actually had a board showed no LoRa at all (its frames go
     * over the BLE link instead, ADR 054).
     */
    @Test
    fun aLoraOnlyPeerIsRelayReachableAndDirectRowsShowOnlyShortRangeRadios() =
        runTest {
            val controller = FakeMeshController()
            val alex = peer("alex", "Alex", 900L)
            val songwriter = peer("song", "I am a songwriter", 800L)
            val motog = peer("moto", "MotoG", 700L)
            controller.neighbors.value = setOf(Peer("alex"), Peer("song"))
            controller.reachable.value = setOf(Peer("alex"), Peer("song"), Peer("moto"))
            controller.peerTransports.value =
                mapOf(
                    "alex" to setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                    // The gateway relays the songwriter's frames, so the plane reports LoRa for a phone that
                    // has no board. The row must not claim it.
                    "song" to setOf(TransportKind.Bluetooth, TransportKind.WifiAware, TransportKind.LoRa),
                    "moto" to setOf(TransportKind.LoRa),
                )
            val vm = reachVm(controller, listOf(alex, songwriter, motog))
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            val state = vm.state.value
            assertEquals(listOf("alex", "song"), state.directNodes.map { it.nodeId })
            assertEquals(
                "a direct row shows the radios that saw the peer itself, never the plane that carried its frames",
                setOf(TransportKind.Bluetooth, TransportKind.WifiAware),
                state.directNodes.first { it.nodeId == "song" }.transports,
            )
            assertEquals(listOf("moto"), state.longRangeNodes.map { it.nodeId })
            assertEquals(setOf(TransportKind.LoRa), state.longRangeNodes.single().transports)
            assertEquals(Reach.LongRange, state.longRangeNodes.single().reach)
            assertEquals("nobody is left over", 0, state.knownTotal)
            job.cancel()
        }

    /**
     * The regression this earns its keep for: a scope is derived from the pairwise ratchet root, so it is
     * subscribed, connected and converged whether or not its peer has opened the app in a month. Reading
     * *that* as reach put two emulators nobody had powered on in days under "reachable via relay" the day
     * this screen shipped. Only `ScopeStatus.peerSeenAt` — that peer's own recent traffic — is evidence.
     */
    @Test
    fun onlyAScopeItsPeerRecentlyPushedToCountsAsRelayReach() =
        runTest {
            val controller = FakeMeshController()
            controller.spools =
                listOf(
                    SpoolStatus(
                        url = "wss://spool.example/spool/v1",
                        connected = true,
                        powBits = 0,
                        lastError = null,
                        scopes =
                            listOf(
                                scope("live", peerSeenAt = NOW - 60_000L),
                                // Converged and connected, but its peer has not been seen in days.
                                scope("dark"),
                                // Seen, but longer ago than the linger — the same peer, gone quiet.
                                scope("stale", peerSeenAt = NOW - PRESENCE_LINGER_MS - 1),
                                // A drained rotation carries nothing new either way (spec §3.1/§3.3).
                                scope("retired", peerSeenAt = NOW, retiring = true),
                                // A group scope's label is a group id and simply matches no peer row.
                                scope("g-abc", peerSeenAt = NOW),
                            ),
                    ),
                )
            val vm =
                reachVm(
                    controller,
                    listOf(
                        peer("live", "Alex", 800L),
                        peer("dark", "FairAurora", 500L),
                        peer("stale", "PerkyQuail", 400L),
                        peer("retired", "UpbeatBreeze", 300L),
                    ),
                )
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            val state = vm.state.value
            assertEquals(listOf("live"), state.longRangeNodes.map { it.nodeId })
            assertTrue("tagged as the Internet plane, which is not a transport at all", state.longRangeNodes.single().viaSpool)
            assertEquals(
                "everyone else is known but not reachable, newest profile first",
                listOf("dark", "stale", "retired"),
                state.knownNodes.map { it.nodeId },
            )
            job.cancel()
        }

    /**
     * The field report this answers: an iPhone two hops out was getting DMs through two Pixels, its receipts
     * coming back the same way, and Diagnostics listed it nowhere but Known. A peer another phone relayed is
     * Indirect, named by the neighbour that last handed us its frame — and a radio sighting still outranks it,
     * and it outranks a long-range plane.
     */
    @Test
    fun aPeerAnotherPhoneRelayedIsIndirectAndNamesItsHop() =
        runTest {
            val controller = FakeMeshController()
            controller.neighbors.value = setOf(Peer("alex"))
            controller.reachable.value = setOf(Peer("alex"), Peer("bea"))
            controller.peerTransports.value =
                mapOf("alex" to setOf(TransportKind.Bluetooth), "bea" to setOf(TransportKind.LoRa))
            controller.heardIndirectly.value =
                mapOf(
                    "iphone" to RelayedHeard(heardAt = NOW - 60_000L, via = "alex"),
                    // Also heard over LoRa: the radio mesh is the better evidence.
                    "bea" to RelayedHeard(heardAt = NOW - 1_000L, via = "alex"),
                    // A radio of ours sees Alex itself: Direct wins over a relayed copy of its frame.
                    "alex" to RelayedHeard(heardAt = NOW, via = "kai"),
                    // Heard, but longer ago than the linger: the sweep has not run yet, the read still ages it out.
                    "quiet" to RelayedHeard(heardAt = NOW - PRESENCE_LINGER_MS - 1, via = "alex"),
                )
            val vm =
                reachVm(
                    controller,
                    listOf(
                        peer("alex", "Alex", 900L),
                        peer("iphone", "iPhone", 800L),
                        peer("bea", "Bea", 700L),
                        peer("quiet", "Quiet", 600L),
                    ),
                )
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            val state = vm.state.value
            assertEquals(listOf("alex"), state.directNodes.map { it.nodeId })
            assertEquals(listOf("bea", "iphone"), state.indirectNodes.map { it.nodeId })
            assertEquals("named by its hop's label, not its node id", "Alex", state.indirectNodes.last().via)
            assertTrue(
                "an indirect row claims no plane: BLE/NAN would read as its own radio",
                state.indirectNodes.all { it.transports.isEmpty() && !it.viaSpool },
            )
            assertEquals(null, state.directNodes.single().via)
            assertTrue(state.longRangeNodes.isEmpty())
            assertEquals(listOf("quiet"), state.knownNodes.map { it.nodeId })
            job.cancel()
        }

    @Test
    fun theKnownSectionIsCappedNewestProfileFirst() =
        runTest {
            val controller = FakeMeshController()
            // Eight known-but-unreachable peers; only the newest few are what a field test is looking for.
            val peers = (1..8).map { peer("n$it", "Peer $it", it * 100L) }
            val vm = reachVm(controller, peers)
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            val state = vm.state.value
            assertEquals(8, state.knownTotal)
            assertEquals(DiagnosticsViewModel.KNOWN_LIMIT, state.knownNodes.size)
            assertEquals(listOf("n8", "n7", "n6", "n5", "n4"), state.knownNodes.map { it.nodeId })
            assertFalse("a known row claims no plane", state.knownNodes.any { it.transports.isNotEmpty() })
            job.cancel()
        }

    /**
     * GitHub #54 (ADR 2026-11.b37x): a hidden row leaves *Known, not reachable* while its profile is the version it was
     * hidden at, comes back when the node publishes a newer one, and a hidden node that is reachable shows regardless.
     */
    @Test
    fun aHiddenRowLeavesKnownUntilItsProfileMovesOn() =
        runTest {
            val controller = FakeMeshController()
            val rows = listOf(peer("n1", "One", 100L), peer("n2", "Two", 200L), peer("n3", "Three", 300L), peer("near", "Near", 50L))
            val directory = MutableStateFlow(directoryOf(rows))
            val hidden = MutableStateFlow(mapOf("n2" to 200L, "near" to 50L))
            controller.neighbors.value = setOf(Peer("near"))
            controller.reachable.value = setOf(Peer("near"))
            val vm = reachVm(controller, rows, hidden = hidden, directory = directory)
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            var state = vm.state.value
            assertTrue(state.hidingOffered)
            assertEquals(listOf("n3", "n1"), state.knownNodes.map { it.nodeId })
            assertEquals(2, state.knownTotal)
            assertEquals(1, state.hiddenKnownCount)
            assertEquals("a reachable node shows whatever was hidden", listOf("near"), state.directNodes.map { it.nodeId })

            directory.value = directoryOf(rows.map { if (it.nodeId == "n2") it.copy(updatedAt = 250L) else it })
            runCurrent()

            state = vm.state.value
            assertEquals(listOf("n3", "n2", "n1"), state.knownNodes.map { it.nodeId })
            assertEquals(0, state.hiddenKnownCount)
            job.cancel()
        }

    @Test
    fun hidingPinsEachShownRowToItsVersionAndPrunesHidesNoLongerInForce() =
        runTest {
            val controller = FakeMeshController()
            val rows = listOf(peer("n1", "One", 100L), peer("n2", "Two", 200L), peer("n3", "Three", 300L), peer("near", "Near", 50L))
            // n1's hide is older than its profile, and "gone" names no row: neither is in force any more.
            val hidden = MutableStateFlow(mapOf("n1" to 50L, "gone" to 5L))
            controller.neighbors.value = setOf(Peer("near"))
            controller.reachable.value = setOf(Peer("near"))
            val vm = reachVm(controller, rows, hidden = hidden)
            val job = backgroundScope.launch { vm.state.collect { } }
            val hid = mutableListOf<List<String>>()
            val events = backgroundScope.launch { vm.hid.collect { hid += it } }
            runCurrent()

            // A reachable row and an unknown id are not Known rows: only n2 is taken.
            vm.hideKnown(listOf("n2", "near", "zzz"))
            runCurrent()
            assertEquals(mapOf("n2" to 200L), hidden.value)
            assertEquals(listOf(listOf("n2")), hid)

            vm.unhideKnown(listOf("n2"))
            runCurrent()
            assertEquals(emptyMap<String, Long>(), hidden.value)

            vm.hideAllKnown()
            runCurrent()
            assertEquals(mapOf("n1" to 100L, "n2" to 200L, "n3" to 300L), hidden.value)
            assertEquals(0, vm.state.value.knownTotal)
            assertEquals(3, vm.state.value.hiddenKnownCount)

            vm.showHiddenKnown()
            runCurrent()
            assertEquals(emptyMap<String, Long>(), hidden.value)
            assertEquals(3, vm.state.value.knownTotal)
            events.cancel()
            job.cancel()
        }

    @Test
    fun withoutTheStoreNothingIsOfferedOrWritten() =
        runTest {
            val controller = FakeMeshController()
            val vm = reachVm(controller, listOf(peer("n1", "One", 100L)))
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            assertFalse(vm.state.value.hidingOffered)
            vm.hideKnown(listOf("n1"))
            runCurrent()
            assertEquals(1, vm.state.value.knownTotal)
            job.cancel()
        }

    /**
     * Work item 18: a phone without Wi-Fi Aware never gets a `WifiAware` status from the composite, so the
     * Transports section used to just have one fewer row — indistinguishable from "Wi-Fi off". The row is now
     * filled in from the device verdict, and the LoRa row (always constructed) carries the board verdict so
     * "no board" reads differently from "board out of reach".
     */
    @Test
    fun aPlaneTheCompositeCouldNotBuildIsListedWithItsReason() =
        runTest {
            val controller = FakeMeshController()
            controller.transportStatuses.value =
                listOf(
                    TransportStatus(TransportKind.LoRa, TransportHealth.Unavailable, linked = 0, nearby = 0),
                    TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2),
                )
            val lora = MutableStateFlow(LoraFacts())
            val vm =
                reachVm(
                    controller,
                    peers = emptyList(),
                    radios = RadioSupport(bluetooth = PlaneSupport.Supported, wifiAware = PlaneSupport.NoHardware),
                    loraFacts = lora,
                )
            val job = backgroundScope.launch { vm.state.collect { } }
            runCurrent()

            assertEquals(
                listOf(
                    TransportRow.Live(TransportStatus(TransportKind.Bluetooth, TransportHealth.Healthy, linked = 1, nearby = 2)),
                    TransportRow.Absent(TransportKind.WifiAware, PlaneSupport.NoHardware),
                    TransportRow.Live(
                        TransportStatus(TransportKind.LoRa, TransportHealth.Unavailable, linked = 0, nearby = 0),
                        lora = LoraPlane.Off,
                    ),
                ),
                vm.state.value.transports,
            )

            // A board comes up: the LoRa row follows the plane, the phone radios are untouched.
            lora.value = LoraFacts(plane = LoraPlane.Live)
            runCurrent()
            val rows = vm.state.value.transports
            assertEquals(LoraPlane.Live, (rows.last() as TransportRow.Live).lora)
            job.cancel()
        }

    private fun scope(
        label: String,
        peerSeenAt: Long? = null,
        retiring: Boolean = false,
    ) = ScopeStatus(
        scopeHex = "00",
        label = label,
        localCount = 1,
        spoolCount = 1,
        converged = true,
        invalidCount = 0,
        retiring = retiring,
        peerSeenAt = peerSeenAt,
    )

    private companion object {
        const val STAMP = "16|test"
        const val SELF = "self"

        /** Comfortably past every presence window, so an unset stamp can never read as recent. */
        const val NOW = 10L * 24 * 60 * 60_000L
    }
}
