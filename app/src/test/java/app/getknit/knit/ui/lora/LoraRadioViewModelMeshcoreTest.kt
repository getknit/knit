package app.getknit.knit.ui.lora

import app.getknit.knit.data.settings.KnitBoardSetup
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.mesh.lora.BoardDirectory
import app.getknit.knit.mesh.lora.BoardInfo
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardRef
import app.getknit.knit.mesh.lora.BoardSettings
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.LinkState
import app.getknit.knit.mesh.lora.LoraPlaneStatus
import app.getknit.knit.mesh.lora.LoraStatus
import app.getknit.knit.mesh.lora.MeshcoreBoardFace
import app.getknit.knit.mesh.lora.MeshcoreRenameResult
import app.getknit.knit.mesh.lora.PairingCause
import app.getknit.knit.mesh.lora.ProvisionMode
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ReplaceToken
import app.getknit.knit.mesh.lora.SetupPreview
import app.getknit.knit.mesh.lora.meshcore.MeshcoreForget
import app.getknit.knit.mesh.lora.meshcore.MeshcoreHandle
import app.getknit.knit.mesh.lora.meshcore.MeshcoreUnbind
import app.getknit.knit.moderation.NameScreen
import app.getknit.knit.moderation.ShownNames
import app.getknit.knit.notifications.Notifier
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The radio screen's MeshCore half (ADR 2026-11.gsqu's Stage 7 amendment) over a fake MeshCore plane and a real
 * [MeshcoreForget] whose session answers are scripted: the rows off the board's own face, a pick or a Forget that runs the
 * Forget first and waits on its answer, the setup's erase preview and its replace, a restore that turns the plane off — and,
 * with the flag off, a screen that reaches none of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraRadioViewModelMeshcoreTest {
    private val meshcoreAddress = "C0:FF:EE:00:00:01"
    private val meshtasticAddress = "AA:BB:CC:DD:EE:01"

    private val enabled = MutableStateFlow(true)
    private val bound = MutableStateFlow<BoardKind?>(BoardKind.MESHCORE)
    private val loraAddress = MutableStateFlow<String?>(null)
    private val meshcoreAddressFlow = MutableStateFlow<String?>(meshcoreAddress)
    private val settings =
        mockk<SettingsStore>(relaxed = true) {
            every { loraEnabled } returns enabled
            every { loraDmEnabled } returns MutableStateFlow(true)
            every { loraRoomEnabled } returns MutableStateFlow(true)
            every { meshcoreRoomEnabled } returns MutableStateFlow(true)
            every { boundBoardKind } returns bound
            every { loraDeviceAddress } returns loraAddress
            every { meshcoreDeviceAddress } returns meshcoreAddressFlow
            every { loraChannelIndex } returns MutableStateFlow(0)
            every { loraBoardSetup } returns MutableStateFlow<KnitBoardSetup?>(null)
        }

    private val meshtasticStatus = MutableStateFlow(LoraStatus())
    private val meshtastic =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = meshtasticStatus

            override suspend fun provisionKnitChannel(
                mode: ProvisionMode,
                previous: BoardSettings?,
            ): ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
        }

    private val meshcoreStatus = MutableStateFlow(LoraStatus())
    private val provisions = mutableListOf<ProvisionMode>()
    private val replaces = mutableListOf<ReplaceToken>()
    private var previews = 0
    private var provisionResult: ProvisionResult = ProvisionResult.NotReady(LinkState.Idle)
    private val replaceResult: ProvisionResult = ProvisionResult.Provisioned(index = 1, alreadyPresent = false)

    // A first setup: this phone holds no record for the board, so the setup names it `Knit abcd` (ADR 2026-11.f4d3).
    private val preview: SetupPreview? = SetupPreview(contacts = 12, slots = 2, renames = true)
    private val meshcore =
        object : LoraPlaneStatus {
            override val status: StateFlow<LoraStatus> = meshcoreStatus
            override val boardKind: BoardKind get() = BoardKind.MESHCORE

            override suspend fun provisionKnitChannel(
                mode: ProvisionMode,
                previous: BoardSettings?,
            ): ProvisionResult {
                provisions += mode
                return provisionResult
            }

            override suspend fun provisionReplacing(token: ReplaceToken): ProvisionResult {
                replaces += token
                return replaceResult
            }

            override suspend fun setupPreview(): SetupPreview? {
                previews++
                return preview
            }

            override suspend fun renameMeshcore(name: String): MeshcoreRenameResult {
                renames += name
                return renameResult
            }
        }

    // The names the radio screen asked the board to take, and what the plane answers (ADR 2026-11.f4d3).
    private val renames = mutableListOf<String>()
    private var renameResult = MeshcoreRenameResult.DONE

    // The shipped screen's shape over a one-word list: a name typed here is screened as a display name is (ADR 2026-11.y4pc).
    private val names = ShownNames(NameScreen(emptyList(), listOf("fuck"), emptyList(), emptyList(), emptyList()))

    // The Forget's session answer, scripted per call: the first unbind's, then each later one's.
    private val unbinds = ArrayDeque<MeshcoreUnbind>()
    private val unbound = mutableListOf<String>()
    private val cleared = mutableListOf<String>()

    // On the test's own scope, never `backgroundScope`: `advanceUntilIdle` stops once no foreground work is left, so a Forget
    // the view model awaits on a background child would never run.
    private fun TestScope.forget() =
        MeshcoreForget(
            scope = this,
            boundAddress = { meshcoreAddressFlow.value },
            unbind = { address ->
                unbound += address
                unbinds.removeFirstOrNull() ?: MeshcoreUnbind.CLEARED
            },
            fenceClaim = { _, edit -> edit() },
            clearBinding = { address ->
                cleared += address
                meshcoreAddressFlow.value = null
                bound.value = null
            },
            withdrawClaim = {},
        )

    private val meshcoreBoard = BoardRef(meshcoreAddress, "MeshCore-0001", BoardKind.MESHCORE)
    private val meshtasticBoard = BoardRef(meshtasticAddress, "Meshtastic_ee01", BoardKind.MESHTASTIC)
    private val boards =
        object : BoardDirectory {
            override fun bonded(): List<BoardRef> = listOf(meshcoreBoard, meshtasticBoard)
        }
    private val notifier = mockk<Notifier>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.start(meshcorePlane: Boolean = true): LoraRadioViewModel {
        val vm = LoraRadioViewModel(settings, meshtastic, boards, notifier, meshcore, forget(), names, meshcorePlane)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    private val ready =
        LinkState.Ready(
            board = BoardInfo(myNodeNum = 0u, pioEnv = "meshcore", firmwareVersion = "ignored", owner = null),
            channels = emptyList(),
            mtu = 172,
        )

    private val face =
        MeshcoreBoardFace(
            firmware = "v1.18.0",
            freqKhz = 869_618,
            spreadFactor = 8,
            bandwidthHz = 62_500,
            codingRate = 8,
            defaultScope = false,
            batteryMv = 4_012,
            sharesPosition = true,
            setup = BoardSetupState.SET_UP,
        )

    private fun option(ref: BoardRef) = (ref.kind ?: BoardKind.MESHTASTIC).let { BoardOption(ref.address, ref.name, false, true, it) }

    @Test
    fun `a bound MeshCore board shows its own rows and none of Meshtastic's`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, boardNodeNum = 42u, lastSnr = 5f, lastRssi = -90, meshcore = face)
            val vm = start()

            val state = vm.state.value
            assertTrue(state.anyKind)
            assertEquals(meshcoreAddress, state.boardAddress)
            assertEquals(LoraConnState.Ready, state.connection)
            val rows = state.meshcore!!
            assertEquals("v1.18.0", rows.firmware)
            assertEquals(869_618L, rows.freqKhz)
            assertEquals(62_500, rows.bandwidthHz)
            assertEquals(4_012, rows.batteryMv)
            assertEquals(true, rows.sharesPosition)
            assertEquals(MeshcoreSetup.SetUp, rows.setup)
            assertTrue(state.boardSetUp)
            assertEquals("the board's own firmware, never the link's", "v1.18.0", state.firmware)
            assertNull("no Meshtastic node number", state.boardNodeNum)
            assertNull(state.snr)
            assertNull(state.rssi)
            assertNull(state.radioConfig)
            assertNull(state.dedicatedSlot)
            assertTrue("a MeshCore board is pickable where its plane is built", state.boards.all { it.pickable })
            assertEquals(BoardKind.MESHCORE, state.boards.first { it.address == meshcoreAddress }.kind)
        }

    @Test
    fun `the setup state shows while the board is away`() =
        runTest {
            meshcoreStatus.value =
                LoraStatus(
                    state = LinkState.Disconnected("gone", retryAtMs = 0, streak = 1),
                    meshcore = MeshcoreBoardFace(setup = BoardSetupState.PENDING),
                )
            val vm = start()

            assertEquals(
                MeshcoreSetup.Pending,
                vm.state.value.meshcore
                    ?.setup,
            )
            assertEquals(LoraConnState.Reconnecting, vm.state.value.connection)
        }

    @Test
    fun `a board with no service of its kind reads as the wrong firmware, a refused bond does not`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = LinkState.NeedsPairing(meshcoreAddress, PairingCause.NO_SERVICE))
            val vm = start()
            assertEquals(LoraConnState.NeedsPairing, vm.state.value.connection)
            assertTrue(vm.state.value.wrongFirmware)

            // A MeshCore session with no NUS ends here first (`MeshcoreSession`'s no-service end).
            meshcoreStatus.value = LoraStatus(state = LinkState.StaleBond(meshcoreAddress, PairingCause.NO_SERVICE))
            advanceUntilIdle()
            assertTrue(vm.state.value.wrongFirmware)

            meshcoreStatus.value = LoraStatus(state = LinkState.StaleBond(meshcoreAddress))
            advanceUntilIdle()
            assertFalse(vm.state.value.wrongFirmware)
        }

    @Test
    fun `with the flag off no MeshCore row, pick or action is reached`() =
        runTest {
            loraAddress.value = meshtasticAddress
            bound.value = BoardKind.MESHTASTIC
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = face)
            val vm = start(meshcorePlane = false)

            val state = vm.state.value
            assertFalse(state.anyKind)
            assertNull(state.meshcore)
            val listed = state.boards.first { it.address == meshcoreAddress }
            assertFalse("listed but greyed, as before", listed.pickable)

            vm.pickBoard(listed)
            vm.askSetup()
            vm.forgetBoard()
            advanceUntilIdle()

            coVerify(exactly = 0) { settings.bindBoard(BoardKind.MESHCORE, any(), any()) }
            coVerify(exactly = 1) { settings.clearLoraDevice() }
            assertEquals("the preview is never read", 0, previews)
            assertTrue("the Forget never runs", unbound.isEmpty())
            // The view model never subscribes to the MeshCore address: the picker reads Meshtastic's alone.
            verify(exactly = 0) { settings.meshcoreDeviceAddress }

            // Even a MeshCore binding left by another build stays Meshtastic's screen: no rows, no Forget.
            bound.value = BoardKind.MESHCORE
            advanceUntilIdle()
            assertNull(vm.state.value.meshcore)
            vm.forgetBoard()
            advanceUntilIdle()
            assertTrue(unbound.isEmpty())
        }

    @Test
    fun `picking another board forgets the MeshCore one first, then binds it and keeps the switch`() =
        runTest {
            val vm = start()

            vm.pickBoard(option(meshtasticBoard))
            advanceUntilIdle()

            assertEquals(listOf(meshcoreAddress), unbound)
            assertEquals(listOf(meshcoreAddress), cleared)
            coVerifyOrder {
                settings.setLoraDevice(meshtasticAddress, "Meshtastic_ee01")
                settings.setLoraEnabled(true)
            }
            assertNull(vm.state.value.forgetNotice)
        }

    @Test
    fun `a Forget the board is away for asks first, and the pick waits on the answer`() =
        runTest {
            unbinds += MeshcoreUnbind.PENDING
            val vm = start()

            vm.pickBoard(option(meshtasticBoard))
            advanceUntilIdle()
            assertTrue(vm.state.value.forgetPending)
            assertTrue("PENDING keeps the binding", cleared.isEmpty())
            coVerify(exactly = 0) { settings.setLoraDevice(any(), any()) }

            vm.keepBoard()
            advanceUntilIdle()
            assertFalse(vm.state.value.forgetPending)
            coVerify(exactly = 0) { settings.setLoraDevice(any(), any()) }

            unbinds += MeshcoreUnbind.PENDING
            vm.pickBoard(option(meshtasticBoard))
            advanceUntilIdle()
            assertTrue(vm.state.value.forgetPending)

            unbinds += MeshcoreUnbind.PENDING
            vm.forgetAnyway()
            advanceUntilIdle()
            assertFalse(vm.state.value.forgetPending)
            assertEquals("forgotten anyway: the binding goes", listOf(meshcoreAddress), cleared)
            coVerify(exactly = 1) { settings.setLoraDevice(meshtasticAddress, "Meshtastic_ee01") }
        }

    @Test
    fun `a failed Forget binds nothing and says so`() =
        runTest {
            unbinds += MeshcoreUnbind.FAILED
            val vm = start()

            vm.pickBoard(option(meshtasticBoard))
            advanceUntilIdle()

            assertEquals(MeshcoreForgetNotice.Failed, vm.state.value.forgetNotice)
            assertTrue(cleared.isEmpty())
            coVerify(exactly = 0) { settings.setLoraDevice(any(), any()) }
            assertFalse(vm.state.value.forgetting)
        }

    @Test
    fun `forgetting a board Knit did not set up forgets it and says so`() =
        runTest {
            unbinds += MeshcoreUnbind.MISMATCH
            val vm = start()

            vm.forgetBoard()
            advanceUntilIdle()

            assertEquals(MeshcoreForgetNotice.Mismatch, vm.state.value.forgetNotice)
            assertEquals(listOf(meshcoreAddress), cleared)
            coVerify(exactly = 0) { settings.clearLoraDevice() }
            coVerify(exactly = 0) { settings.setLoraEnabled(true) }
        }

    @Test
    fun `the setup confirmation reads what the setup would erase`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = face.copy(setup = BoardSetupState.NONE))
            val vm = start()

            vm.askSetup()
            advanceUntilIdle()
            assertTrue(vm.state.value.confirmSetup)
            assertEquals(1, previews)
            assertEquals(MeshcoreErase(contacts = 12, slots = 2, renames = true), vm.state.value.erase)
            assertFalse(vm.state.value.eraseLoading)

            vm.dismissSetup()
            advanceUntilIdle()
            assertFalse(vm.state.value.confirmSetup)
            assertNull(vm.state.value.erase)
        }

    @Test
    fun `another board's record asks before a replace, which carries its token`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = face.copy(setup = BoardSetupState.NONE))
            val token = ReplaceToken(meshcoreAddress, byteArrayOf(1, 2, 3))
            provisionResult = ProvisionResult.DifferentBoard(token)
            val vm = start()

            vm.askSetup()
            vm.setUpBoard()
            advanceUntilIdle()
            assertEquals(listOf(ProvisionMode.Setup), provisions)
            assertTrue(vm.state.value.confirmReplace)
            assertNull(vm.state.value.provisionOutcome)
            assertFalse(vm.state.value.provisioning)

            vm.replaceBoard()
            advanceUntilIdle()
            assertSame(token, replaces.single())
            assertFalse(vm.state.value.confirmReplace)
            assertEquals(LoraProvisionOutcome.Provisioned, vm.state.value.provisionOutcome)
            // The record is the plane's own: nothing of Meshtastic's is written.
            coVerify(exactly = 0) { settings.setLoraChannelIndex(any()) }
            coVerify(exactly = 0) { settings.setLoraBoardSetup(any()) }
        }

    @Test
    fun `a MeshCore restore switches the plane off and leaves Meshtastic's record alone`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = face)
            provisionResult = ProvisionResult.Restored
            val vm = start()

            vm.restoreBoard()
            advanceUntilIdle()

            assertEquals(listOf(ProvisionMode.Restore), provisions)
            assertEquals(LoraProvisionOutcome.Restored, vm.state.value.provisionOutcome)
            coVerify(exactly = 1) { settings.setLoraEnabled(false) }
            coVerify(exactly = 0) { settings.clearLoraBoardSetup() }
        }

    // --- The board's name, its owner's handle (ADR 2026-11.f4d3) ----------------------------------------------------------

    private fun named(
        name: String,
        default: Boolean = false,
        unpostable: Boolean = false,
    ) = face.copy(
        handle = MeshcoreHandle(name.encodeToByteArray()),
        namedDefault = default,
        handleUnpostable = unpostable,
        defaultName = "Knit 312b",
    )

    @Test
    fun `the name row shows the handle cleaned and marks the default by its bytes alone`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Sam\u202E 🧶\nx"))
            val vm = start()
            val rows = vm.state.value.meshcore!!
            assertEquals("drawn cleaned, on one line", "Sam 🧶 x", rows.handle)
            assertFalse(rows.namedDefault)
            assertEquals("Knit 312b", rows.defaultName)
            assertFalse("the rows never print the handle", rows.toString().contains("Sam"))

            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Knit 312b", default = true))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.meshcore!!
                    .namedDefault,
            )

            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Sam: hi", unpostable = true))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.meshcore!!
                    .handleUnpostable,
            )

            meshcoreStatus.value = LoraStatus(meshcore = face)
            advanceUntilIdle()
            assertNull(
                "away: no name",
                vm.state.value.meshcore!!
                    .handle,
            )
        }

    @Test
    fun `the rename dialog says what the board will refuse before anything is written`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Knit 312b", default = true))
            val vm = start()
            vm.askRename()
            assertEquals(
                "prefilled with the board's name",
                "Knit 312b",
                vm.state.value.meshcoreRename!!
                    .draft,
            )

            vm.setRenameDraft("Riverside Community Radio Club 2")
            assertEquals(
                32,
                vm.state.value.meshcoreRename!!
                    .bytes,
            )
            assertEquals(
                MeshcoreNameProblem.TooLong,
                vm.state.value.meshcoreRename!!
                    .problem,
            )
            vm.setRenameDraft("Sam: hi")
            assertEquals(
                MeshcoreNameProblem.Separator,
                vm.state.value.meshcoreRename!!
                    .problem,
            )
            vm.saveRename()
            advanceUntilIdle()
            assertEquals(
                MeshcoreNameProblem.Separator,
                vm.state.value.meshcoreRename!!
                    .problem,
            )

            vm.setRenameDraft("   ")
            assertNull(
                "an empty field says nothing until Rename",
                vm.state.value.meshcoreRename!!
                    .problem,
            )
            vm.saveRename()
            advanceUntilIdle()
            assertEquals(
                MeshcoreNameProblem.Empty,
                vm.state.value.meshcoreRename!!
                    .problem,
            )

            vm.setRenameDraft("fuck off")
            vm.saveRename()
            advanceUntilIdle()
            assertEquals(
                "screened as a display name is",
                MeshcoreNameProblem.Flagged,
                vm.state.value.meshcoreRename!!
                    .problem,
            )

            assertTrue("nothing reached the board", renames.isEmpty())
            coVerify(exactly = 0) { settings.acceptMeshcoreCustomNameDisclosure() }
        }

    @Test
    fun `a name the board took covers the post consent and the default never does`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Knit 312b", default = true))
            val vm = start()
            vm.askRename()
            vm.setRenameDraft("  Sam 🧶  ")
            vm.saveRename()
            advanceUntilIdle()
            assertEquals("trimmed to one line", listOf("Sam 🧶"), renames)
            assertNull(vm.state.value.meshcoreRename)
            assertEquals(MeshcoreRenameNotice.Done, vm.state.value.meshcoreRenameNotice)
            // The dialog carried the post sheet's disclosure: a consent given before covers the name now.
            coVerify(exactly = 1) { settings.acceptMeshcoreCustomNameDisclosure() }

            vm.askRename()
            vm.useDefaultName()
            advanceUntilIdle()
            assertEquals(listOf("Sam 🧶", "Knit 312b"), renames)
            coVerify(exactly = 1) { settings.acceptMeshcoreCustomNameDisclosure() }
        }

    @Test
    fun `a rename the board did not take records nothing and says why`() =
        runTest {
            meshcoreStatus.value = LoraStatus(state = ready, meshcore = named("Knit 312b", default = true))
            val vm = start()
            val notices =
                mapOf(
                    MeshcoreRenameResult.FAILED to MeshcoreRenameNotice.Failed,
                    MeshcoreRenameResult.NOT_READY to MeshcoreRenameNotice.NotReady,
                    MeshcoreRenameResult.NO_BOARD to MeshcoreRenameNotice.NotReady,
                    MeshcoreRenameResult.NOT_SET_UP to MeshcoreRenameNotice.NotSetUp,
                )
            for ((result, notice) in notices) {
                renameResult = result
                vm.askRename()
                vm.setRenameDraft("Sam")
                vm.saveRename()
                advanceUntilIdle()
                assertNull(result.name, vm.state.value.meshcoreRename)
                assertEquals(result.name, notice, vm.state.value.meshcoreRenameNotice)
            }
            coVerify(exactly = 0) { settings.acceptMeshcoreCustomNameDisclosure() }
        }
}
