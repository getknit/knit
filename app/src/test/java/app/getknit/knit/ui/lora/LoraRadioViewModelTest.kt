package app.getknit.knit.ui.lora

import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.AirtimeSnapshot
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardInfo
import app.getknit.knit.mesh.lora.BoardOwner
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.ChannelInfo
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraGatewayPolicy
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraRadioConfig
import app.getknit.knit.mesh.lora.LoraRegion
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.ModemPreset
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The LoRa settings ViewModel over a fake directory and a fake plane: the picker rules and the channel verdict. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraRadioViewModelTest {
    private val enabled = MutableStateFlow(true)
    private val dms = MutableStateFlow(true)
    private val bridge = MutableStateFlow(true)
    private val address = MutableStateFlow<String?>("AA:BB:CC:DD:EE:01")
    private val channel = MutableStateFlow(1)
    private val boardSetup = MutableStateFlow<KnitBoardSetup?>(null)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { loraEnabled } returns enabled
            every { loraDmEnabled } returns dms
            every { loraBridgeEnabled } returns bridge
            every { loraDeviceAddress } returns address
            every { loraChannelIndex } returns channel
            every { loraBoardSetup } returns boardSetup
        }
    private val status = MutableStateFlow(LoraStatus())
    private val provisionCalls = mutableListOf<Pair<ProvisionMode, BoardSettings?>>()
    private var provisionResult: ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
    private val lora =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = this@LoraRadioViewModelTest.status

            override suspend fun provisionKnitChannel(
                mode: ProvisionMode,
                previous: BoardSettings?,
            ): ProvisionResult {
                provisionCalls += mode to previous
                return provisionResult
            }
        }
    private var bonded =
        listOf(
            BoardRef("AA:BB:CC:DD:EE:01", "Meshtastic_ee01"),
            BoardRef("11:22:33:44:55:66", "Pixel Buds", meshtastic = false),
        )
    private var reads = 0
    private val boards =
        object : BoardDirectory {
            override fun bonded(): List<BoardRef> {
                reads++
                return bonded
            }
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.start(): LoraRadioViewModel {
        val vm = LoraRadioViewModel(settings, lora, boards)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private fun ready(
        channels: List<ChannelInfo>,
        owner: BoardOwner? = null,
        firmware: String = "2.5.0",
    ) = LinkState.Ready(
        board = BoardInfo(myNodeNum = 42u, pioEnv = "heltec-v4", firmwareVersion = firmware, owner = owner),
        channels = channels,
        mtu = 512,
    )

    /** Firmware new enough to store the unmonitored mark (ADR 2026-09.emd7); the default above is not. */
    private val marking = "2.6.9.f223b8a"

    @Test
    fun `the picker hides devices that do not look like boards, and show-all reveals them`() =
        runTest {
            val vm = start()

            assertEquals(
                listOf("Meshtastic_ee01"),
                vm.state.value.boards
                    .map { it.name },
            )
            assertEquals(1, vm.state.value.hiddenBoards)
            assertTrue(vm.state.value.anyBonded)
            assertFalse(vm.state.value.showAllBoards)

            vm.setShowAllBoards(true)
            advanceUntilIdle()

            assertEquals(
                listOf("Meshtastic_ee01", "Pixel Buds"),
                vm.state.value.boards
                    .map { it.name },
            )
            assertTrue(vm.state.value.showAllBoards)
        }

    @Test
    fun `link churn never re-reads the bonded list, a refresh does`() =
        runTest {
            val vm = start()
            val readsAtStart = reads

            // The bonded list is a Binder call: a heard-peer tick or a reconnect must not re-issue it.
            status.value = LoraStatus(state = LinkState.Connecting)
            status.value = LoraStatus(state = LinkState.Connecting, heard = 2)
            advanceUntilIdle()
            assertEquals(readsAtStart, reads)

            // Back from the system Bluetooth settings with a freshly paired (renamed) board.
            bonded = bonded + BoardRef("AA:BB:CC:DD:EE:02", "WALT_ee02")
            vm.refreshBoards()
            advanceUntilIdle()

            assertEquals(readsAtStart + 1, reads)
            assertEquals(
                listOf("Meshtastic_ee01", "WALT_ee02"),
                vm.state.value.boards
                    .map { it.name },
            )
        }

    @Test
    fun `a connected board names the selected channel and flags a slot that is not Knit`() =
        runTest {
            val vm = start()
            status.value =
                LoraStatus(
                    state =
                        ready(
                            channels = listOf(ChannelInfo(index = 0, name = "", role = 1), ChannelInfo(index = 1, name = "Knit", role = 2)),
                        ),
                    heard = 3,
                    boardsHeard = 1,
                )
            advanceUntilIdle()

            assertEquals(LoraConnState.Ready, vm.state.value.connection)
            assertEquals("Knit", vm.state.value.channelName)
            assertEquals("2.5.0", vm.state.value.firmware)
            assertEquals("people reachable through the mesh", 3, vm.state.value.heard)
            assertEquals("radios actually in range", 1, vm.state.value.boardsHeard)
            // Knit lives in a secondary slot; the board's own primary is left alone, so this board is set up.
            assertTrue(vm.state.value.boardSetUp)
            assertFalse(vm.state.value.customPrimary)

            // A slot the board has no entry for at all.
            channel.value = 2
            advanceUntilIdle()
            assertNull(vm.state.value.channelName)
        }

    @Test
    fun `the bridge switch reflects the stored setting and a tap writes it back`() =
        runTest {
            val vm = start()
            assertTrue(vm.state.value.bridgeEnabled)
            bridge.value = false
            advanceUntilIdle()
            assertFalse(vm.state.value.bridgeEnabled)
            vm.onToggleBridge(true)
            advanceUntilIdle()
            io.mockk.coVerify { settings.setLoraBridgeEnabled(true) }
        }

    @Test
    fun `a spare board reports the passive role, an active one does not`() =
        runTest {
            val vm = start()
            status.value = LoraStatus(state = ready(emptyList()), role = LoraGatewayPolicy.Role.PASSIVE)
            advanceUntilIdle()
            assertTrue(vm.state.value.bridgePassive)

            status.value = LoraStatus(state = ready(emptyList()), role = LoraGatewayPolicy.Role.ACTIVE)
            advanceUntilIdle()
            assertFalse(vm.state.value.bridgePassive)
        }

    @Test
    fun `a connected board names its primary channel`() =
        runTest {
            val vm = start()
            val radio =
                LoraRadioConfig(
                    usePreset = true,
                    modemPreset = ModemPreset.LONG_FAST,
                    region = LoraRegion.US,
                    hopLimit = 3,
                    overrideDutyCycle = false,
                )
            status.value = LoraStatus(state = ready(listOf(ChannelInfo(0, "", role = 1))).copy(radio = radio))
            advanceUntilIdle()
            assertEquals("an unnamed slot 0 is the preset's name", "LongFast", vm.state.value.publicChannel)

            status.value = LoraStatus(state = ready(listOf(ChannelInfo(0, "Ridge", role = 1))).copy(radio = radio))
            advanceUntilIdle()
            assertEquals("Ridge", vm.state.value.publicChannel)

            status.value = LoraStatus(state = ready(listOf(ChannelInfo(0, "", role = 1))))
            advanceUntilIdle()
            assertNull("unnamed and no preset reported: nothing to call it", vm.state.value.publicChannel)
        }

    @Test
    fun `the airtime ledger is a percentage of the budget, and only while connected`() =
        runTest {
            val vm = start()
            val air =
                AirtimeSnapshot(
                    preset = ModemPreset.LONG_FAST,
                    region = LoraRegion.EU_868,
                    known = true,
                    liveUsedMs = 40_000,
                    liveBudgetMs = 100_000,
                    bridgeUsedMs = 10_000,
                    bridgeBudgetMs = 30_000,
                    bootstrapUsedMs = 0,
                    bootstrapBudgetMs = 25_000,
                )
            status.value = LoraStatus(state = ready(emptyList()), airtime = air)
            advanceUntilIdle()
            assertEquals("both buckets spend the one allowance", 50, vm.state.value.airtimePercent)

            // No board, no reading — the number would be about an hour that is not being spent.
            status.value = LoraStatus(state = LinkState.Idle, airtime = air)
            advanceUntilIdle()
            assertNull(vm.state.value.airtimePercent)
        }

    @Test
    fun `any spending at all rounds up to one percent rather than reading as zero`() =
        runTest {
            val vm = start()
            status.value =
                LoraStatus(
                    state = ready(emptyList()),
                    airtime =
                        AirtimeSnapshot(
                            preset = ModemPreset.LONG_FAST,
                            region = LoraRegion.OTHER,
                            known = true,
                            liveUsedMs = 1,
                            liveBudgetMs = 100_000,
                            bridgeUsedMs = 0,
                            bridgeBudgetMs = 30_000,
                            bootstrapUsedMs = 0,
                            bootstrapBudgetMs = 25_000,
                        ),
                )
            advanceUntilIdle()
            assertEquals(1, vm.state.value.airtimePercent)
        }

    @Test
    fun `no connected board means no channel verdict`() =
        runTest {
            val vm = start()

            assertEquals(LoraConnState.Off, vm.state.value.connection)
            assertNull(vm.state.value.channelName)
            assertFalse(vm.state.value.boardSetUp)
            assertNull(vm.state.value.firmware)
        }

    @Test
    fun `a connected board reports its battery, a disconnected one does not`() =
        runTest {
            val vm = start()
            val battery = BoardBattery(percent = 78, voltage = 3.92f, powered = false)
            status.value = LoraStatus(state = ready(emptyList()), battery = battery)
            advanceUntilIdle()
            assertEquals(battery, vm.state.value.battery)

            status.value = LoraStatus(state = LinkState.Disconnected("gatt", retryAtMs = 5_000, streak = 1), battery = battery)
            advanceUntilIdle()
            assertNull(vm.state.value.battery)
        }

    @Test
    fun `a renamed primary channel is flagged, since it parks the radio on its own frequency`() =
        runTest {
            val vm = start()
            val radio = LoraRadioConfig(usePreset = true, ModemPreset.LONG_FAST, LoraRegion.OTHER, hopLimit = 3, overrideDutyCycle = false)
            status.value =
                LoraStatus(
                    state =
                        ready(listOf(ChannelInfo(0, "MyGroup", 1), ChannelInfo(1, "Knit", 2))).copy(radio = radio),
                )
            advanceUntilIdle()
            assertTrue(vm.state.value.customPrimary)

            // The stock primary is unnamed, and naming it exactly the preset's own name is the same slot.
            status.value =
                LoraStatus(state = ready(listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2))).copy(radio = radio))
            advanceUntilIdle()
            assertFalse(vm.state.value.customPrimary)
        }

    @Test
    fun `a board on a preset its region does not default to is noticed, since every other board must match`() =
        runTest {
            val vm = start()

            fun connect(
                preset: ModemPreset,
                region: LoraRegion,
                usePreset: Boolean = true,
            ) {
                status.value =
                    LoraStatus(
                        state =
                            ready(listOf(ChannelInfo(0, "", 1), ChannelInfo(1, "Knit", 2)))
                                .copy(radio = LoraRadioConfig(usePreset, preset, region, hopLimit = 3, overrideDutyCycle = false)),
                    )
            }

            // 2.8's new US default: a board nobody on LongFast can hear. The notice reports it rather than
            // prescribing LongFast — which preset is right depends on the local mesh, not on the region.
            connect(ModemPreset.LONG_TURBO, LoraRegion.US)
            advanceUntilIdle()
            assertEquals(PresetMismatch(board = "LongTurbo", stock = "LongFast"), vm.state.value.presetMismatch)

            connect(ModemPreset.LONG_FAST, LoraRegion.US)
            advanceUntilIdle()
            assertNull(vm.state.value.presetMismatch)

            // EU_866 defaults to LiteFast, so there the warning is the other way round.
            connect(ModemPreset.LITE_FAST, LoraRegion.EU_866)
            advanceUntilIdle()
            assertNull(vm.state.value.presetMismatch)
            connect(ModemPreset.LONG_FAST, LoraRegion.EU_866)
            advanceUntilIdle()
            assertEquals(PresetMismatch(board = "LongFast", stock = "LiteFast"), vm.state.value.presetMismatch)
        }

    @Test
    fun `the preset notice stays quiet where the answer would be a guess`() =
        runTest {
            val vm = start()
            // OTHER buckets regions with different stock presets (the ham carve-outs default to TinyFast), so
            // there is nothing here Knit can say without risking a notice to somebody whose board is fine.
            status.value =
                LoraStatus(
                    state =
                        ready(listOf(ChannelInfo(1, "Knit", 2)))
                            .copy(
                                radio =
                                    LoraRadioConfig(
                                        usePreset = true,
                                        ModemPreset.TINY_FAST,
                                        LoraRegion.OTHER,
                                        hopLimit = 3,
                                        overrideDutyCycle = false,
                                    ),
                            ),
                )
            advanceUntilIdle()
            assertNull(vm.state.value.presetMismatch)

            // Hand-rolled radio settings: modem_preset says nothing about what the board actually transmits.
            status.value =
                LoraStatus(
                    state =
                        ready(listOf(ChannelInfo(1, "Knit", 2)))
                            .copy(
                                radio =
                                    LoraRadioConfig(
                                        usePreset = false,
                                        ModemPreset.LONG_TURBO,
                                        LoraRegion.US,
                                        hopLimit = 3,
                                        overrideDutyCycle = false,
                                    ),
                            ),
                )
            advanceUntilIdle()
            assertNull(vm.state.value.presetMismatch)

            // And a board that has not reported its radio config at all.
            status.value = LoraStatus(state = ready(listOf(ChannelInfo(1, "Knit", 2))))
            advanceUntilIdle()
            assertNull(vm.state.value.presetMismatch)
        }

    @Test
    fun `a board carrying Knit in a secondary slot reads as set up`() =
        runTest {
            val vm = start()
            status.value = LoraStatus(state = ready(listOf(ChannelInfo(0, "", 1), ChannelInfo(1, "Knit", 2))))
            advanceUntilIdle()

            assertTrue(vm.state.value.boardSetUp)
            assertEquals("Knit", vm.state.value.channelName)
        }

    @Test
    fun `setting up asks first, and the tap alone changes nothing`() =
        runTest {
            val vm = start()

            vm.askSetup()
            advanceUntilIdle()
            assertTrue(vm.state.value.confirmSetup)
            assertTrue("no provision until the user confirms", provisionCalls.isEmpty())

            vm.dismissSetup()
            advanceUntilIdle()
            assertFalse(vm.state.value.confirmSetup)
            assertTrue(provisionCalls.isEmpty())
        }

    @Test
    fun `a setup binds the slot it landed in and records the board's own settings`() =
        runTest {
            val previous =
                BoardSettings(
                    nodeInfoSecs = 900,
                    positionSecs = 600,
                    smartPosition = true,
                    telemetrySecs = 1_800,
                    rebroadcastMode = 0,
                    owner = BoardOwner("Meshtastic abcd", "abcd"),
                )
            provisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = false, previous = previous)
            val vm = start()

            vm.askSetup()
            vm.setUpBoard()
            advanceUntilIdle()

            assertEquals(listOf(ProvisionMode.Setup to null), provisionCalls)
            assertFalse("the confirmation closes when the action runs", vm.state.value.confirmSetup)
            assertEquals(LoraProvisionOutcome.Provisioned, vm.state.value.provisionOutcome)
            io.mockk.coVerify { settings.setLoraChannelIndex(1) }
            io.mockk.coVerify {
                settings.setLoraBoardSetup(
                    KnitBoardSetup(
                        address = "AA:BB:CC:DD:EE:01",
                        nodeInfoSecs = 900,
                        positionSecs = 600,
                        smartPosition = true,
                        telemetrySecs = 1_800,
                        rebroadcastMode = 0,
                        longName = "Meshtastic abcd",
                        shortName = "abcd",
                    ),
                )
            }
        }

    @Test
    fun `re-running the setup on a set-up board never overwrites the recorded intervals`() =
        runTest {
            provisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = true)
            val vm = start()

            vm.setUpBoard()
            advanceUntilIdle()

            assertEquals(LoraProvisionOutcome.AlreadyPresent, vm.state.value.provisionOutcome)
            io.mockk.coVerify(exactly = 0) { settings.setLoraBoardSetup(any()) }
        }

    @Test
    fun `a restore hands the recorded intervals back, forgets the setup, and switches the plane off`() =
        runTest {
            val recorded =
                KnitBoardSetup(
                    address = "AA:BB:CC:DD:EE:01",
                    nodeInfoSecs = 900,
                    positionSecs = 600,
                    smartPosition = true,
                    telemetrySecs = 1_800,
                    rebroadcastMode = 3,
                    longName = "Meshtastic abcd",
                    shortName = "abcd",
                )
            boardSetup.value = recorded
            provisionResult = ProvisionResult.Restored
            val vm = start()

            vm.restoreBoard()
            advanceUntilIdle()

            assertEquals(
                ProvisionMode.Restore to
                    BoardSettings(900, 600, true, 1_800, rebroadcastMode = 3, owner = BoardOwner("Meshtastic abcd", "abcd")),
                provisionCalls.single(),
            )
            assertEquals(LoraProvisionOutcome.Restored, vm.state.value.provisionOutcome)
            io.mockk.coVerify { settings.clearLoraBoardSetup() }
            // The board carries no Knit channel afterwards, so leaving the plane on would fan frames out
            // over whatever channel it landed back on.
            io.mockk.coVerify { settings.setLoraEnabled(false) }
        }
    // --- the rename a board set up before ADR 049 still needs ---

    @Test
    fun `a set-up board still under its old name is offered the rename`() =
        runTest {
            status.value =
                LoraStatus(
                    state =
                        ready(
                            listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2)),
                            owner = BoardOwner("Meshtastic 002a", "002a"),
                        ),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertTrue(vm.state.value.boardSetUp)
            assertTrue(vm.state.value.needsRename)
            assertEquals("Meshtastic 002a", vm.state.value.meshName)
            assertEquals("Knit 002a", vm.state.value.knitName)
        }

    @Test
    fun `a board already named for Knit is not offered the rename`() =
        runTest {
            status.value =
                LoraStatus(
                    state =
                        ready(
                            listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2)),
                            owner = BoardOwner("Knit 002a", "Knit"),
                        ),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertTrue(vm.state.value.boardSetUp)
            assertFalse(vm.state.value.needsRename)
            assertEquals("Knit 002a", vm.state.value.meshName)
        }

    @Test
    fun `a set-up board that never told the mesh it is unmonitored is offered the action`() =
        runTest {
            // Named by an older Knit but never marked (ADR 2026-09.emd7). The names match, which is how the
            // screen knows to offer the mark rather than a rename.
            status.value =
                LoraStatus(
                    state =
                        ready(
                            listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2)),
                            owner = BoardOwner("Knit 002a", "Knit"),
                            firmware = marking,
                        ),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertTrue(vm.state.value.needsRename)
            assertEquals(vm.state.value.knitName, vm.state.value.meshName)
        }

    @Test
    fun `a board carrying the whole Knit identity is left alone`() =
        runTest {
            status.value =
                LoraStatus(
                    state =
                        ready(
                            listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2)),
                            owner = BoardOwner("Knit 002a", "Knit", unmessagable = true),
                            firmware = marking,
                        ),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertFalse(vm.state.value.needsRename)
        }

    @Test
    fun `a board too old to store the mark is never nagged about it`() =
        runTest {
            // 2.6.8 drops field 9 and never reports it back, so the prompt would never go away. Writing to
            // somebody's hardware on a hunch is the worse failure here, exactly as an unreadable name is.
            status.value =
                LoraStatus(
                    state =
                        ready(
                            listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2)),
                            owner = BoardOwner("Knit 002a", "Knit"),
                            firmware = "2.6.8.ef9d0d7",
                        ),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertFalse(vm.state.value.needsRename)
        }

    @Test
    fun `a board whose firmware never says what it is called is left alone`() =
        runTest {
            // Going on offering a rename that may already be done is the worse failure, so silence wins.
            status.value =
                LoraStatus(state = ready(listOf(ChannelInfo(0, "LongFast", 1), ChannelInfo(1, "Knit", 2))), boardNodeNum = 42u)
            val vm = start()

            assertTrue(vm.state.value.boardSetUp)
            assertFalse(vm.state.value.needsRename)
            assertNull(vm.state.value.meshName)
        }

    @Test
    fun `a board that is not set up at all is never offered a bare rename`() =
        runTest {
            status.value =
                LoraStatus(
                    state = ready(listOf(ChannelInfo(0, "LongFast", 1)), owner = BoardOwner("Meshtastic 002a", "002a")),
                    boardNodeNum = 42u,
                )
            val vm = start()

            assertFalse(vm.state.value.boardSetUp)
            assertFalse("the full setup is the action there, not a rename", vm.state.value.needsRename)
        }
}
