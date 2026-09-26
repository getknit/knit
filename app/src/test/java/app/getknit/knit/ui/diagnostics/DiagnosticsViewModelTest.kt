@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.diagnostics

import app.getknit.knit.R
import app.getknit.knit.crash.CrashReportRef
import app.getknit.knit.crash.CrashReports
import app.getknit.knit.data.PeerDirectory
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.relay.RelayStatusRepository
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.MeshController
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PRESENCE_LINGER_MS
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PlaneSupport
import app.getknit.knit.mesh.RadioSupport
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

    // --- reach classification (the Diagnostics screen's three sections) ---

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
        every { repo.observeDirectory() } returns MutableStateFlow(PeerDirectory(peers, labels))
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
        )
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
            assertEquals(listOf("moto"), state.relayNodes.map { it.nodeId })
            assertEquals(setOf(TransportKind.LoRa), state.relayNodes.single().transports)
            assertEquals(Reach.Relay, state.relayNodes.single().reach)
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
            assertEquals(listOf("live"), state.relayNodes.map { it.nodeId })
            assertTrue("tagged as the Internet plane, which is not a transport at all", state.relayNodes.single().viaSpool)
            assertEquals(
                "everyone else is known but not reachable, newest profile first",
                listOf("dark", "stale", "retired"),
                state.knownNodes.map { it.nodeId },
            )
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
