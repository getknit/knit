package app.getknit.knit.ui.profile

import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.mesh.FakeMeshController
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.crypto.VerifyPayload
import app.getknit.knit.ui.peer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the flow-derived state projection and the QR-scan verify logic. The identity fields
 * (`safetyNumber`/`myQrPayload`) are set on [Dispatchers.IO] in the VM's init and are covered separately by
 * `SafetyNumberTest`; these tests assert only the deterministic, flow-driven surface — presence/block/key
 * state and `onScanned` — none of which depends on that background load resolving.
 */
class ProfileDetailsViewModelTest {
    private val nodeId = "peer-1"
    private val peers = mockk<PeerRepository>(relaxed = true)
    private val mesh = FakeMeshController()
    private val settings = mockk<SettingsStore>(relaxed = true)
    private val identity = mockk<Identity>(relaxed = true)

    private val peersFlow = MutableStateFlow(emptyList<PeerEntity>())
    private val blockedFlow = MutableStateFlow(emptySet<String>())

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        coEvery { identity.nodeId() } returns "me"
        every { identity.publicKeyBundle() } returns "MYBUNDLE"
        every { peers.observePeers() } returns peersFlow
        every { settings.blockedNodeIds } returns blockedFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = ProfileDetailsViewModel(nodeId, peers, mesh, settings, identity)

    @Test
    fun stateReflectsProfilePresenceBlockAndKeyState() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, name = "Ada", pubKey = "PBUNDLE", verified = true))
            mesh.neighbors.value = setOf(Peer(nodeId))
            blockedFlow.value = setOf(nodeId)
            advanceUntilIdle()

            val s = vm.state.value
            assertEquals("Ada", s.displayName)
            assertTrue("in the neighbor set → online", s.online)
            assertTrue("id is in the blocked set", s.isBlocked)
            assertTrue("a pinned pubKey → hasKey", s.hasKey)
            assertTrue("peer.verified true → verified", s.verified)
        }

    @Test
    fun onScannedMatchingPinnedKeyMarksVerified() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "PBUNDLE"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MATCH, vm.scanResult.value)
            coVerify { peers.setVerified(nodeId, true) }
        }

    @Test
    fun onScannedWrongKeyReportsMismatchAndDoesNotVerify() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(peer(nodeId, pubKey = "PBUNDLE"))
            advanceUntilIdle()

            vm.onScanned(VerifyPayload.encode(nodeId, "SOMEONE-ELSES-KEY"))
            advanceUntilIdle()

            assertEquals(VerifyScanResult.MISMATCH, vm.scanResult.value)
            coVerify(exactly = 0) { peers.setVerified(nodeId, true) }
        }

    @Test
    fun acceptPersistsThePeersConversationSoTappingMessageClearsAnyRequest() =
        runTest {
            val vm = vm()

            vm.accept()
            advanceUntilIdle()

            // A DM's conversationId is the peer's node id, so accepting adds exactly that.
            coVerify { settings.accept(nodeId) }
        }

    @Test
    fun blockUsesTheCapturedDeviceTagSoItSticksAcrossAKeyReset() =
        runTest {
            val vm = vm()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            peersFlow.value = listOf(PeerEntity(nodeId = nodeId, deviceTag = "tag-1"))
            advanceUntilIdle()

            vm.block()
            advanceUntilIdle()

            coVerify { settings.block(nodeId, "tag-1") }
            assertFalse("scan result starts empty", vm.scanResult.value == VerifyScanResult.MATCH)
        }
}
