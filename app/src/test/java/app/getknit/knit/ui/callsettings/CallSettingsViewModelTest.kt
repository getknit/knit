@file:OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher / setMain / advanceUntilIdle are experimental kotlinx APIs

package app.getknit.knit.ui.callsettings

import app.getknit.knit.call.IceProbe
import app.getknit.knit.call.OwnIceServers
import app.getknit.knit.call.ProbeResult
import app.getknit.knit.call.ProbeVerdict
import app.getknit.knit.call.SttModel
import app.getknit.knit.call.SttModelState
import app.getknit.knit.call.TurnServer
import app.getknit.knit.call.VoxSensitivity
import app.getknit.knit.data.settings.CallPreferenceStore
import app.getknit.knit.data.settings.OwnIceStore
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TranscriptStore
import app.getknit.knit.ui.call.stt.SttModelStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

/** The Calls screen (§3.14): switches write through, fields are a draft written by Save, and Test tries the draft. */
class CallSettingsViewModelTest {
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val store = mockk<OwnIceStore>(relaxed = true)
    private val relayAll = MutableStateFlow(false)
    private val ownIce = MutableStateFlow(SAVED)
    private val relayTurn = MutableStateFlow(false)
    private val transcriptsOn = MutableStateFlow(true)
    private val modelState = MutableStateFlow<SttModelState>(SttModelState.Absent)
    private val stt =
        mockk<SttModelStore>(relaxed = true) {
            every { state } returns modelState
            every { model } returns SttModel.MOONSHINE_SMALL_STREAMING_EN
            every { metered() } returns true
        }
    private val transcriptStore = mockk<TranscriptStore>(relaxed = true) { every { enabled } returns transcriptsOn }
    private val preferMesh = MutableStateFlow(false)
    private val voxSensitivity = MutableStateFlow(VoxSensitivity.DEFAULT)
    private val preference =
        mockk<CallPreferenceStore>(relaxed = true).also {
            every { it.preferMesh } returns preferMesh
            every { it.voxSensitivity } returns voxSensitivity
        }

    private var probed: Pair<List<String>, TurnServer?>? = null
    private var probeAnswer: ProbeResult? = ProbeResult(ProbeVerdict.Reached, ProbeVerdict.AuthFailed)
    private val probe =
        IceProbe { stun, turn ->
            probed = stun to turn
            probeAnswer
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settings.callsRelayAll } returns relayAll
        every { store.ownIce } returns ownIce
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun kotlinx.coroutines.test.TestScope.vm(
        speech: SttModelStore? = null,
        live: Boolean = true,
        ownServers: Boolean = live,
        walkie: Boolean = false,
    ): CallSettingsViewModel =
        CallSettingsViewModel(
            settings,
            store,
            probe.takeIf { live && ownServers },
            speech,
            transcriptStore.takeIf { speech != null },
            preference = preference,
            walkie = walkie,
            live = live,
            relayTurn = relayTurn,
        ).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.transcripts.collect {} }
        }

    /** A build without live calls (ADR 2026-10.swvp) hands no probe: the live rows are off and a Test asks nothing. */
    @Test
    fun withoutLiveCallsThereAreNoLiveRowsAndNoTest() =
        runTest {
            assertTrue(vm().live)
            val vm = vm(stt, live = false)
            advanceUntilIdle()
            assertFalse(vm.live)
            vm.editDraft { it.copy(turnPass = "new") }
            vm.test()
            advanceUntilIdle()
            assertNull(probed)
            assertFalse(vm.state.value.testing)
        }

    /**
     * A release build with live calls (`OWN_ICE_WIRED` off, ADR 2026-10.mr88's amendment) hands no probe: "Relay all
     * calls" stays, the own servers' rows go, and neither their switches, Save nor Test write or ask anything.
     */
    @Test
    fun withoutOwnServersTheLiveRowsStayAndTheServerRowsWriteNothing() =
        runTest {
            val vm = vm(live = true, ownServers = false)
            advanceUntilIdle()
            assertTrue(vm.live)
            assertFalse(vm.ownServers)
            vm.setRelayAll(true)
            vm.setStunOn(true)
            vm.setTurnOn(true)
            vm.editDraft { it.copy(stunText = "stun:new.example.org") }
            // Savable, so the refused write below is the guard's and not the draft's rule.
            assertTrue(vm.state.value.canSave)
            vm.save()
            vm.test()
            advanceUntilIdle()
            coVerify { settings.setCallsRelayAll(true) }
            coVerify(exactly = 0) { store.setStunOn(any()) }
            coVerify(exactly = 0) { store.setTurnOn(any()) }
            coVerify(exactly = 0) { store.setStunUrls(any()) }
            assertNull(probed)
        }

    /**
     * "Relay all calls" is offered only with a TURN server to relay through: a connected relay that issues this phone
     * TURN, or the person's own where a call uses it (own servers wired, switched on, valid). A free ticket's relay
     * issues none, and a relay-only call with no TURN never connects.
     */
    @Test
    fun relayAllIsOfferedOnlyWithTurnToRelayThrough() =
        runTest {
            val release = vm(live = true, ownServers = false)
            advanceUntilIdle()
            // An own TURN server saved counts for nothing where no call uses it.
            assertFalse(release.state.value.turnAvailable)
            assertFalse(release.state.value.relayAllToggles)
            relayTurn.value = true
            advanceUntilIdle()
            assertTrue(release.state.value.turnAvailable)
            assertTrue(release.state.value.relayAllToggles)

            relayTurn.value = false
            val debug = vm()
            advanceUntilIdle()
            assertTrue(debug.state.value.turnAvailable)
            ownIce.value = SAVED.copy(turnOn = false)
            advanceUntilIdle()
            assertFalse(debug.state.value.turnAvailable)
            ownIce.value = SAVED.copy(turnUrl = "")
            advanceUntilIdle()
            assertFalse(debug.state.value.turnAvailable)

            // Left on from before: it can still be switched off.
            relayAll.value = true
            advanceUntilIdle()
            assertFalse(debug.state.value.turnAvailable)
            assertTrue(debug.state.value.relayAllToggles)
        }

    @Test
    fun withoutTheWalkieThereIsNoTranscriptsSection() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            assertNull(vm.transcripts.value)
        }

    @Test
    fun theTranscriptsSectionFollowsTheSwitchAndTheModel() =
        runTest {
            val vm = vm(stt)
            advanceUntilIdle()
            val bytes = SttModel.MOONSHINE_SMALL_STREAMING_EN.bytes
            assertEquals(TranscriptsUi(on = true, model = SttModelState.Absent, bytes = bytes, metered = true), vm.transcripts.value)
            transcriptsOn.value = false
            modelState.value = SttModelState.Ready
            advanceUntilIdle()
            assertEquals(TranscriptsUi(on = false, model = SttModelState.Ready, bytes = bytes, metered = true), vm.transcripts.value)

            vm.setTranscripts(true)
            vm.downloadSpeechModel()
            vm.cancelSpeechModel()
            vm.removeSpeechModel()
            advanceUntilIdle()
            coVerify { transcriptStore.setEnabled(true) }
            verify { stt.download() }
            verify { stt.cancel() }
            coVerify { stt.delete() }
        }

    @Test
    fun theDraftIsLoadedOnceFromTheStore() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            assertTrue(vm.loaded.value)
            assertEquals(CallServerDraft.of(SAVED), vm.state.value.draft)
            assertFalse(vm.state.value.dirty)

            // A later store change does not overwrite what is being typed.
            vm.editDraft { it.copy(turnUser = "typed") }
            ownIce.value = SAVED.copy(turnUser = "elsewhere")
            advanceUntilIdle()
            assertEquals("typed", vm.state.value.draft.turnUser)
        }

    @Test
    fun switchesWriteThrough() =
        runTest {
            val vm = vm()
            vm.setRelayAll(true)
            vm.setStunOn(true)
            vm.setTurnOn(false)
            advanceUntilIdle()
            coVerify { settings.setCallsRelayAll(true) }
            coVerify { store.setStunOn(true) }
            coVerify { store.setTurnOn(false) }
        }

    /** "Prefer mesh calls" shows only where the build wires both kinds, and writes through like every switch. */
    @Test
    fun preferMeshShowsWithBothKindsAndWritesThrough() =
        runTest {
            assertFalse(vm(walkie = false).choosesFirstCall)
            assertFalse(vm(live = false, walkie = true).choosesFirstCall)
            val vm = vm(walkie = true)
            assertTrue(vm.choosesFirstCall)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.preferMesh.collect {} }

            preferMesh.value = true
            assertTrue(vm.preferMesh.value)
            preferMesh.value = false
            assertFalse(vm.preferMesh.value)
            vm.setPreferMesh(true)
            advanceUntilIdle()
            coVerify { preference.setPreferMesh(true) }
        }

    /** "Hands-free sensitivity" (C-3.11-11) reads the store and writes through. */
    @Test
    fun theHandsFreeSensitivityReadsAndWritesThrough() =
        runTest {
            val vm = vm(walkie = true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.voxSensitivity.collect {} }
            assertEquals(VoxSensitivity.MEDIUM, vm.voxSensitivity.value)
            voxSensitivity.value = VoxSensitivity.LOW
            assertEquals(VoxSensitivity.LOW, vm.voxSensitivity.value)
            vm.setVoxSensitivity(VoxSensitivity.HIGH)
            advanceUntilIdle()
            coVerify { preference.setVoxSensitivity(VoxSensitivity.HIGH) }
        }

    @Test
    fun saveWritesTheTrimmedFields() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(stunText = "stun:a.example.org\n\n stun:b.example.org:3478 ", turnUrl = " turn:t.example.org ") }
            assertTrue(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            coVerify { store.setStunUrls(listOf("stun:a.example.org", "stun:b.example.org:3478")) }
            coVerify { store.setTurn(url = "turn:t.example.org", user = "ada", pass = "pw") }
        }

    @Test
    fun anInvalidFieldCannotBeSaved() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(stunText = "stun:ada@a.example.org") }
            assertFalse(vm.state.value.canSave)
            vm.save()
            advanceUntilIdle()
            coVerify(exactly = 0) { store.setStunUrls(any()) }

            // A half-typed TURN server is no more savable than a malformed one.
            vm.editDraft { it.copy(stunText = "", turnPass = "") }
            assertFalse(vm.state.value.canSave)
        }

    /** The Test tries what is typed, before Save, and an edit clears the answer it gave for other fields. */
    @Test
    fun testProbesTheDraft() =
        runTest {
            val vm = vm()
            advanceUntilIdle()
            vm.editDraft { it.copy(turnPass = "new") }
            vm.test()
            advanceUntilIdle()
            assertEquals(listOf("stun:stun.example.org"), probed?.first)
            assertEquals("new", probed?.second?.pass)
            assertEquals(
                ProbeVerdict.AuthFailed,
                vm.state.value.result
                    ?.turn,
            )
            assertFalse(vm.state.value.testing)

            vm.editDraft { it.copy(turnPass = "newer") }
            assertNull(vm.state.value.result)
        }

    @Test
    fun aRefusedProbeLeavesNoResult() =
        runTest {
            probeAnswer = null
            val vm = vm()
            advanceUntilIdle()
            vm.test()
            advanceUntilIdle()
            assertNull(vm.state.value.result)
            assertFalse(vm.state.value.testing)
        }

    private companion object {
        val SAVED =
            OwnIceServers(
                stunOn = true,
                stunUrls = listOf("stun:stun.example.org"),
                turnOn = true,
                turnUrl = "turn:t.example.org",
                turnUser = "ada",
                turnPass = "pw",
            )
    }
}
